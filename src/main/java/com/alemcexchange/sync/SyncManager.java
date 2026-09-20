package com.alemcexchange.sync;

import com.alemcexchange.config.ConfigManager;
import com.alemcexchange.database.DatabaseManager;
import com.alemcexchange.util.MenuManager;
import com.alemcexchange.util.SchedulerUtil;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨服实时同步（MySQL 轮询实现，无新增依赖）。
 *
 * 原理：
 *  1. 本服任何数据变动（余额/解锁/进度/限额）→ DatabaseManager 回调 publish(uuid)；
 *  2. 待发布事件按 UUID 合并去重（高频挖掘进度也只占用一条），随轮询周期批量写入 sync_events 表；
 *  3. 各服务器每 0.5s 轮询一次新事件，跳过自己发出的事件；
 *  4. 收到事件 → 清掉该玩家的本地缓存；若玩家正开着菜单，回到其线程自动重绘（数据全部重读数据库）。
 *
 * 约束：仅 MySQL 后端可用（SQLite 是单机文件库，不存在跨服场景）。
 */
public class SyncManager {

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final DatabaseManager databaseManager;
    private final MenuManager menuManager;
    private final SchedulerUtil schedulerUtil;

    private final boolean enabled;
    private final boolean redisMode;
    private final String serverId;
    private final long pollIntervalTicks;
    private final long retentionMillis;
    private RedisSyncClient redisClient;

    /** 待发布事件（按 UUID 去重合并） */
    private final Set<UUID> pendingPublish = ConcurrentHashMap.newKeySet();

    /** 轮询游标：已处理到的最大事件 ID（含自己发出的事件，避免重复扫描） */
    private volatile long cursorId = -1;

    private volatile boolean running = false;
    private long lastPruneAt = 0L;
    private int consecutiveErrors = 0;

    public SyncManager(JavaPlugin plugin, ConfigManager configManager, DatabaseManager databaseManager,
                       MenuManager menuManager, SchedulerUtil schedulerUtil) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.databaseManager = databaseManager;
        this.menuManager = menuManager;
        this.schedulerUtil = schedulerUtil;

        boolean syncEnabled = configManager.getDatabase().getBoolean("sync.enabled", false);
        this.enabled = syncEnabled && databaseManager.isMySQLBackend();
        this.redisMode = "redis".equalsIgnoreCase(configManager.getDatabase().getString("sync.mode", "polling"));
        String rawServerId = configManager.getDatabase().getString("sync.server-id", "server-1");
        this.serverId = rawServerId.replaceAll("[|,\\s]", "-");
        this.pollIntervalTicks = Math.max(2L, configManager.getDatabase().getLong("sync.poll-interval-ticks", 10L));
        this.retentionMillis = Math.max(1L, configManager.getDatabase().getLong("sync.event-retention-minutes", 10L)) * 60_000L;

        if (syncEnabled && !databaseManager.isMySQLBackend()) {
            plugin.getLogger().warning("Cross-server sync is enabled but storage type is not MySQL; sync disabled.");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 由 DatabaseManager 的数据变动回调触发（线程任意） */
    public void publish(UUID uuid) {
        if (enabled && uuid != null) {
            pendingPublish.add(uuid);
        }
    }

    public void start() {
        if (!enabled || running) return;
        running = true;

        if (redisMode) {
            var db = configManager.getDatabase();
            this.redisClient = new RedisSyncClient(
                    db.getString("sync.redis.host", "127.0.0.1"),
                    db.getInt("sync.redis.port", 6379),
                    db.getString("sync.redis.username", ""),
                    db.getString("sync.redis.password", ""),
                    db.getString("sync.redis.channel", "alemcexchange-sync"),
                    db.getInt("sync.redis.reconnect-seconds", 5),
                    this::onRedisMessage);
            this.redisClient.start();
            plugin.getLogger().info("Cross-server sync enabled via Redis (server-id=" + serverId
                    + ", channel=" + db.getString("sync.redis.channel", "alemcexchange-sync") + ")");
        } else {
            try {
                databaseManager.createSyncTableIfAbsent();
                cursorId = databaseManager.maxSyncEventId();
                plugin.getLogger().info("Cross-server sync enabled via MySQL polling (server-id=" + serverId + ", cursor=" + cursorId + ")");
            } catch (SQLException | ClassNotFoundException e) {
                running = false;
                plugin.getLogger().severe("Failed to init sync table, cross-server sync disabled: " + e.getMessage());
                return;
            }
        }
        // 两种通道共用一个定时器：轮询模式=冲刷+拉取+清理；Redis 模式=冲刷（合并发布）
        schedulerUtil.runTimerAsync(this::tick, pollIntervalTicks, pollIntervalTicks);
    }

    /** Redis 订阅回调（Redis 订阅线程）：payload = serverId|uuid,uuid,... */
    private void onRedisMessage(String payload) {
        if (!running || payload == null) return;
        int sep = payload.indexOf('|');
        if (sep <= 0) return;
        String from = payload.substring(0, sep);
        if (serverId.equals(from)) return;
        for (String part : payload.substring(sep + 1).split(",")) {
            try {
                menuManager.handleRemoteInvalidate(UUID.fromString(part));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    /** 单次定时任务：轮询模式=冲刷+拉取+清理；Redis 模式=合并发布 */
    private void tick() {
        if (!running) return;
        if (redisMode) {
            try {
                flushPendingRedis();
            } catch (Exception ignored) {
            }
            return;
        }
        try {
            flushPending();
            poll();
            pruneIfNeeded();
            consecutiveErrors = 0;
        } catch (Exception e) {
            consecutiveErrors++;
            if (consecutiveErrors == 1 || consecutiveErrors % 20 == 0) {
                plugin.getLogger().warning("Sync poll error (consecutive=" + consecutiveErrors + "): " + e.getMessage());
            }
            // 连续失败过多时重置游标到最新，避免故障恢复后风暴
            if (consecutiveErrors >= 60) {
                try {
                    cursorId = databaseManager.maxSyncEventId();
                    consecutiveErrors = 0;
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** Redis 模式：合并待发布事件为一条消息发布（serverId|uuid,uuid,...） */
    private void flushPendingRedis() {
        if (pendingPublish.isEmpty() || redisClient == null) return;
        StringBuilder sb = new StringBuilder(serverId).append('|');
        int n = 0;
        for (UUID uuid : pendingPublish) {
            pendingPublish.remove(uuid);
            if (n++ > 0) sb.append(',');
            sb.append(uuid);
            if (n >= 200) break;
        }
        redisClient.publish(sb.toString());
    }

    private void flushPending() throws Exception {
        if (pendingPublish.isEmpty()) return;
        Set<UUID> batch = new HashSet<>();
        for (UUID uuid : pendingPublish) {
            batch.add(uuid);
            pendingPublish.remove(uuid);
            if (batch.size() >= 200) break;   // 单批上限，防止极端堆积
        }
        databaseManager.insertSyncEvents(serverId, batch);
    }

    private void poll() throws Exception {
        if (cursorId < 0) {
            cursorId = databaseManager.maxSyncEventId();
            return;
        }
        List<String[]> events = databaseManager.pollSyncEvents(cursorId, 500);
        Set<UUID> toInvalidate = new HashSet<>();
        for (String[] event : events) {
            cursorId = Math.max(cursorId, Long.parseLong(event[0]));
            if (serverId.equals(event[1])) continue;   // 跳过自己发出的事件
            try {
                toInvalidate.add(UUID.fromString(event[2]));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (!toInvalidate.isEmpty()) {
            for (UUID uuid : toInvalidate) {
                // 缓存清理线程安全；菜单重绘需回主线程/实体线程
                menuManager.handleRemoteInvalidate(uuid);
            }
        }
    }

    private void pruneIfNeeded() throws Exception {
        long now = System.currentTimeMillis();
        if (now - lastPruneAt < 10 * 60 * 1000L) return;
        lastPruneAt = now;
        databaseManager.pruneSyncEvents(now - retentionMillis);
    }

    public void shutdown() {
        running = false;
        if (redisClient != null) {
            redisClient.stop();
        }
        // 关服前把剩余事件冲刷出去（尽力而为）
        if (enabled && !pendingPublish.isEmpty()) {
            try {
                flushPending();
            } catch (Exception ignored) {
            }
        }
    }
}
