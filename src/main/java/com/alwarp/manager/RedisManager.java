package com.alwarp.manager;

import com.alwarp.ALwarp;
import com.alwarp.model.Landmark;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bukkit.World;
import org.bukkit.entity.Player;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis管理器。处理跨服通信：传送请求、地标同步、心跳检测。
 *
 * 线程安全设计：
 * - 通过FoliaScheduler异步订阅，Folia兼容
 * - volatile + AtomicBoolean 控制生命周期
 * - JedisPool 线程安全
 * - 消息回调中涉及Minecraft API的操作通过runTask回到主线程
 */
public class RedisManager {

    private final ALwarp plugin;
    private final String host;
    private final int port;
    private final String password;
    private final String channel;

    private JedisPool jedisPool;
    private volatile Jedis subscriptionJedis;
    private volatile JedisPubSub subscriber;
    private volatile boolean enabled;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Long> onlineServers = new ConcurrentHashMap<>();
    private final Map<String, String> serverDisplayNames = new ConcurrentHashMap<>();
    private final Map<UUID, PendingTeleport> pendingTeleports = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean shutdown = false;

    /**
     * 未启用Redis时使用此构造。
     */
    public RedisManager(ALwarp plugin) {
        this.plugin = plugin;
        this.host = null;
        this.port = 0;
        this.password = null;
        this.channel = null;
        this.enabled = false;
    }

    /**
     * 启用Redis时使用此构造，参数从database.yml注入。
     */
    public RedisManager(ALwarp plugin, String host, int port, String password, String channel) {
        this.plugin = plugin;
        this.host = host;
        this.port = port;
        this.password = password;
        this.channel = channel;
        this.enabled = true;
    }

    public void init() {
        if (!enabled) return;

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(8);
        poolConfig.setMaxIdle(4);
        poolConfig.setMinIdle(1);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestOnReturn(true);
        poolConfig.setTestWhileIdle(true);
        poolConfig.setMaxWait(Duration.ofMillis(2000));

        if (password != null && !password.isEmpty()) {
            jedisPool = new JedisPool(poolConfig, host, port, 2000, password);
        } else {
            jedisPool = new JedisPool(poolConfig, host, port, 2000);
        }

        // 验证连接
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.ping();
        } catch (JedisConnectionException e) {
            plugin.getLogger().severe(plugin.localize("Redis连接失败: ", "Redis connection failed: ") + e.getMessage());
            jedisPool.close();
            jedisPool = null;
            enabled = false;
            return;
        }

        shutdown = false;
        running.set(true);
        plugin.getScheduler().runTaskAsync(this::subscribeLoop);
        plugin.getScheduler().runTaskTimerAsync(this::sendPing, 20L, 100L);
        sendPing();
    }

    public void close() {
        shutdown = true;
        enabled = false;

        JedisPubSub activeSubscriber = subscriber;
        if (activeSubscriber != null) {
            try {
                activeSubscriber.unsubscribe();
            } catch (Exception ignored) {}
        }

        Jedis activeSubscription = subscriptionJedis;
        if (activeSubscription != null) {
            try {
                activeSubscription.disconnect();
            } catch (Exception ignored) {}
            try {
                activeSubscription.close();
            } catch (Exception ignored) {}
        }

        JedisPool pool = jedisPool;
        jedisPool = null;
        if (pool != null) {
            try {
                pool.close();
            } catch (Exception ignored) {}
        }

        long deadline = System.currentTimeMillis() + 2000L;
        while (running.get() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void subscribeLoop() {
        int reconnectDelay = 5;
        int maxReconnectDelay = 60;

        while (!shutdown) {
            try {
                JedisPool pool = jedisPool;
                if (pool == null || pool.isClosed()) break;
                try (Jedis jedis = pool.getResource()) {
                    subscriptionJedis = jedis;
                    JedisPubSub activeSubscriber = new JedisPubSub() {
                        @Override
                        public void onMessage(String ch, String message) {
                            if (shutdown) return;
                            plugin.getScheduler().runTask(() -> {
                                if (!shutdown) {
                                    handleMessage(message);
                                }
                            });
                        }
                        @Override
                        public void onUnsubscribe(String ch, int subscribedChannels) {
                            plugin.logInfo("Redis取消订阅: " + ch,
                                    "Redis unsubscribed: " + ch);
                        }
                    };
                    subscriber = activeSubscriber;
                    plugin.logInfo("Redis订阅已建立，频道: " + channel,
                            "Redis subscription established, channel: " + channel);
                    jedis.subscribe(activeSubscriber, channel);
                } catch (JedisConnectionException e) {
                    if (shutdown) break;
                    plugin.logWarning("Redis订阅连接断开，" + reconnectDelay + "秒后重连...",
                            "Redis subscription disconnected; reconnecting in " + reconnectDelay + "s...");
                } finally {
                    subscriber = null;
                    subscriptionJedis = null;
                }
                if (!shutdown) {
                    try { Thread.sleep(reconnectDelay * 1000L); } catch (InterruptedException ignored) { break; }
                    reconnectDelay = Math.min(reconnectDelay * 2, maxReconnectDelay);
                }
            } catch (Exception e) {
                if (shutdown) break;
                plugin.getLogger().severe(plugin.localize("Redis订阅异常: ", "Redis subscription error: ") + e.getMessage());
                try { Thread.sleep(reconnectDelay * 1000L); } catch (InterruptedException ignored) { break; }
            }
        }
        running.set(false);
        plugin.logInfo("Redis订阅线程已退出", "Redis subscription thread stopped");
    }

    public void sendTeleportRequest(UUID playerUuid, String playerName, Landmark landmark) {
        if (!enabled || jedisPool == null) return;
        try {
            Player player = plugin.getServer().getPlayer(playerUuid);
            Map<String, Object> msg = new HashMap<>();
            msg.put("type", "teleport_request");
            msg.put("player_uuid", playerUuid.toString());
            msg.put("player_name", playerName);
            msg.put("landmark_id", landmark.getId());
            msg.put("landmark_name", landmark.getName());
            msg.put("owner_uuid", landmark.getOwnerUuid().toString());
            msg.put("owner_name", landmark.getOwnerName());
            msg.put("category_id", landmark.getCategoryId());
            msg.put("price", landmark.getPrice());
            TaxManager.PaymentTerms payment = player != null
                    ? plugin.getTaxManager().getPaymentTerms(player, landmark)
                    : new TaxManager.PaymentTerms(landmark.isPaid(), landmark.getPrice(), 0.0, landmark.getPrice());
            msg.put("payment_charged", payment.charged());
            msg.put("payment_price", payment.price());
            msg.put("target_server", landmark.getServerName());
            msg.put("target_world", landmark.getWorld());
            msg.put("target_x", landmark.getX());
            msg.put("target_y", landmark.getY());
            msg.put("target_z", landmark.getZ());
            msg.put("timestamp", System.currentTimeMillis());
            sendMessage(objectMapper.writeValueAsString(msg));
        } catch (Exception e) {
            plugin.getLogger().severe("发送传送请求失败: " + e.getMessage());
        }
    }

    public void broadcastLandmarkUpdate(Landmark landmark) {
        if (!enabled || jedisPool == null) return;
        try {
            sendMessage(objectMapper.writeValueAsString(Map.of(
                    "type", "landmark_update", "landmark_id", landmark.getId())));
        } catch (Exception e) {
            plugin.getLogger().severe("广播地标更新失败: " + e.getMessage());
        }
    }

    public void broadcastLandmarkDelete(int landmarkId) {
        if (!enabled || jedisPool == null) return;
        try {
            sendMessage(objectMapper.writeValueAsString(Map.of(
                    "type", "landmark_delete", "landmark_id", landmarkId)));
        } catch (Exception e) {
            plugin.getLogger().severe("广播地标删除失败: " + e.getMessage());
        }
    }

    public void broadcastLandmarkRefresh() {
        if (!enabled || jedisPool == null) return;
        try {
            sendMessage(objectMapper.writeValueAsString(Map.of(
                    "type", "landmark_update", "landmark_id", 0)));
        } catch (Exception e) {
            plugin.getLogger().severe(plugin.localize("广播地标刷新失败: ", "Broadcast landmark refresh failed: ") + e.getMessage());
        }
    }

    public void broadcastIncomeRefresh() {
        if (!enabled || jedisPool == null) return;
        try {
            sendMessage(objectMapper.writeValueAsString(Map.of(
                    "type", "income_refresh", "timestamp", System.currentTimeMillis())));
        } catch (Exception e) {
            plugin.getLogger().severe(plugin.localize("广播收入刷新失败: ", "Broadcast income refresh failed: ") + e.getMessage());
        }
    }

    public void sendPing() {
        if (!enabled || jedisPool == null) return;
        try {
            String serverName = plugin.getServerId();
            sendMessage(objectMapper.writeValueAsString(Map.of(
                    "type", "server_ping", "server_name", serverName,
                    "display_name", plugin.getLocalServerDisplayName(),
                    "timestamp", System.currentTimeMillis())));
        } catch (Exception e) {
            plugin.getLogger().warning("发送心跳失败: " + e.getMessage());
        }
    }

    public boolean isServerOnline(String serverName) {
        if (serverName == null || serverName.isBlank()) return false;
        Long lastSeen = onlineServers.get(serverName);
        if (lastSeen == null) {
            for (Map.Entry<String, Long> entry : onlineServers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(serverName)) {
                    lastSeen = entry.getValue();
                    break;
                }
            }
        }
        return lastSeen != null && System.currentTimeMillis() - lastSeen < 30_000;
    }

    private void sendMessage(String message) {
        if (jedisPool == null || jedisPool.isClosed()) return;
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.publish(channel, message);
        } catch (JedisConnectionException e) {
            plugin.getLogger().warning("Redis发送消息失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void handleMessage(String json) {
        try {
            Map<String, Object> msg = objectMapper.readValue(json, Map.class);
            String type = (String) msg.get("type");
            switch (type) {
                case "teleport_request" -> handleTeleportRequest(msg);
                case "landmark_update"  -> refreshBusinessCaches();
                case "landmark_delete"  -> refreshBusinessCaches();
                case "income_refresh" -> plugin.getIncomeManager().loadAllAsync(() -> {});
                case "server_ping" -> {
                    String server = (String) msg.get("server_name");
                    if (server != null) {
                        onlineServers.put(server, System.currentTimeMillis());
                        Object displayName = msg.get("display_name");
                        if (displayName != null && !String.valueOf(displayName).isBlank()) {
                            serverDisplayNames.put(server, String.valueOf(displayName));
                        }
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("处理Redis消息失败: " + e.getMessage());
        }
    }

    private void handleTeleportRequest(Map<String, Object> msg) {
        String myName = plugin.getServerId();
        String target = (String) msg.get("target_server");
        if (target == null || !myName.equalsIgnoreCase(target)) return;

        try {
            UUID playerUuid = UUID.fromString((String) msg.get("player_uuid"));
            pendingTeleports.put(playerUuid, new PendingTeleport(
                    asInt(msg.get("landmark_id")),
                    (String) msg.get("landmark_name"),
                    UUID.fromString((String) msg.get("owner_uuid")),
                    (String) msg.get("owner_name"),
                    asInt(msg.get("category_id")),
                    asDouble(msg.get("price")),
                    asBoolean(msg.get("payment_charged")),
                    asDouble(msg.get("payment_price")),
                    target,
                    (String) msg.get("target_world"),
                    asInt(msg.get("target_x")),
                    asInt(msg.get("target_y")),
                    asInt(msg.get("target_z")),
                    System.currentTimeMillis()
            ));
            plugin.logInfo("收到跨服传送请求: " + msg.get("player_name")
                            + " -> 地标 " + msg.get("landmark_id"),
                    "Received cross-server teleport request: " + msg.get("player_name")
                            + " -> landmark " + msg.get("landmark_id"));
        } catch (Exception e) {
            plugin.getLogger().warning("跨服传送请求参数无效: " + e.getMessage());
        }
    }

    private void refreshBusinessCaches() {
        plugin.getScheduler().runTaskAsync(() -> {
            plugin.getCategoryManager().refreshCache();
            plugin.getLandmarkManager().refreshCache(() ->
                    plugin.getManagerManager().loadAllAsync(() ->
                            plugin.getBlacklistManager().loadAllAsync(() ->
                                    plugin.getPinManager().loadAllAsync(() ->
                                            plugin.getFavoritesManager().loadAllAsync(() ->
                                                    plugin.getRatingManager().loadStatsAsync(() -> {}))))));
        });
    }

    public boolean consumePendingTeleport(Player player) {
        PendingTeleport pending = pendingTeleports.remove(player.getUniqueId());
        if (pending == null) return false;
        if (System.currentTimeMillis() - pending.createdAt() > 60_000L) return false;
        if (plugin.getBlacklistManager().isBlacklisted(pending.landmarkId(), player.getUniqueId())) {
            player.sendMessage(plugin.getMessageUtil().get("landmark_blacklisted"));
            return true;
        }
        Landmark accessCheck = new Landmark();
        accessCheck.setServerName(pending.serverName());
        if (!plugin.canAccessLandmarkServer(player, accessCheck)) {
            player.sendMessage(plugin.getMessageUtil().get("server_permission_denied"));
            return true;
        }

        World world = plugin.getServer().getWorld(pending.world());
        if (world == null) {
            player.sendMessage(plugin.getMessageUtil().get("landmark_not_found"));
            return true;
        }

        plugin.getScheduler().runAtLocation(new org.bukkit.Location(world, pending.x(), pending.y(), pending.z()), () -> {
            org.bukkit.Location loc = new org.bukkit.Location(world, pending.x() + 0.5, pending.y(), pending.z() + 0.5);
            Landmark landmark = new Landmark();
            landmark.setId(pending.landmarkId());
            landmark.setName(pending.landmarkName());
            landmark.setOwnerUuid(pending.ownerUuid());
            landmark.setOwnerName(pending.ownerName());
            landmark.setServerName(pending.serverName());
            landmark.setWorld(pending.world());
            landmark.setX(pending.x());
            landmark.setY(pending.y());
            landmark.setZ(pending.z());
            landmark.setCategoryId(pending.categoryId());
            landmark.setPrice(pending.price());
            TaxManager.PaymentReceipt payment = plugin.getTaxManager().reservePayment(player,
                    new TaxManager.PaymentTerms(
                            pending.paymentCharged(),
                            pending.paymentPrice(),
                            0.0,
                            pending.paymentPrice()));
            if (payment == null) {
                player.sendMessage(plugin.getMessageUtil().get("no_money"));
                return;
            }
            player.teleportAsync(loc).thenAccept(success -> {
                plugin.runAtPlayer(player, () -> plugin.getTeleportManager().completeTeleport(player, landmark, payment, success));
            });
        });
        return true;
    }

    private int asInt(Object value) {
        if (value instanceof Number number) return number.intValue();
        return Integer.parseInt(String.valueOf(value));
    }

    private double asDouble(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        return Double.parseDouble(String.valueOf(value));
    }

    private boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) return bool;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    public boolean isEnabled() { return enabled && jedisPool != null; }
    public boolean isRunning() { return running.get(); }

    public String getServerDisplayName(String serverId) {
        if (serverId == null) return null;
        String exact = serverDisplayNames.get(serverId);
        if (exact != null) return exact;
        for (Map.Entry<String, String> entry : serverDisplayNames.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(serverId)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private record PendingTeleport(
            int landmarkId,
            String landmarkName,
            UUID ownerUuid,
            String ownerName,
            int categoryId,
            double price,
            boolean paymentCharged,
            double paymentPrice,
            String serverName,
            String world,
            int x,
            int y,
            int z,
            long createdAt) {}
}
