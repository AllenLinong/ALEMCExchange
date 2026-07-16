package com.alwarp;

import com.alwarp.command.ALwarpCommand;
import com.alwarp.command.CustomCommandManager;
import com.alwarp.gui.GUIManager;
import com.alwarp.gui.shape.ShapeMenuManager;
import com.alwarp.listener.InventoryListener;
import com.alwarp.listener.PlayerListener;
import com.alwarp.manager.*;
import com.alwarp.model.Landmark;
import com.alwarp.scheduler.FoliaScheduler;
import com.alwarp.storage.MySQLStorage;
import com.alwarp.storage.SQLiteStorage;
import com.alwarp.storage.StorageManager;
import com.alwarp.util.ConfigUtil;
import com.alwarp.util.ItemUtil;
import com.alwarp.util.MessageUtil;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ALwarp 主类。
 * Minecraft Folia 1.21.4+ 地标管理插件。
 */
public final class ALwarp extends JavaPlugin {

    private static final ZoneId DEFAULT_TIME_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String AUTHOR_NAME = "Allen_Linong";
    private static final String AUTHOR_QQ = "1422163791";

    private FoliaScheduler scheduler;
    private ConfigUtil configUtil;
    private MessageUtil messageUtil;
    private StorageManager storage;
    private Economy economy;
    private CustomCommandManager customCommandManager;

    // 管理器
    private LandmarkManager landmarkManager;
    private CategoryManager categoryManager;
    private RatingManager ratingManager;
    private TaxManager taxManager;
    private TransactionManager transactionManager;
    private ManagerManager managerManager;
    private BlacklistManager blacklistManager;
    private PinManager pinManager;
    private TeleportManager teleportManager;
    private FavoritesManager favoritesManager;
    private IncomeManager incomeManager;
    private RedisManager redisManager;

    // GUI
    private ShapeMenuManager shapeMenuManager;
    private GUIManager guiManager;

    // 配置文件
    private FileConfiguration messagesConfig;
    private FileConfiguration databaseConfig;
    private ZoneId timeZone = DEFAULT_TIME_ZONE;
    private String currentLanguage = "zh_CN";

    @Override
    public void onEnable() {
        // 1. 初始化工具类
        this.scheduler = new FoliaScheduler(this);
        this.configUtil = new ConfigUtil(this);

        // 2. 保存默认配置
        saveDefaultConfig();
        saveResource("messages/zh_CN.yml", false);
        saveResource("messages/en_US.yml", false);
        saveResource("database.yml", false);

        // 3. 加载配置
        reloadAllConfigs();
        logStartupHeader();

        // 4. 初始化存储
        initStorage();

        // 5. 初始化经济
        initEconomy();

        // 6. 初始化管理器
        initManagers();

        // 7. 初始化GUI
        initGUI();

        // 8. 注册命令
        registerCommands();

        // 9. 注册监听器
        registerListeners();
        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");

        // 10. 初始化Redis
        initRedis();

        // 11. 加载缓存数据
        loadCacheData();

        logInfo("核心功能已启动，业务缓存正在异步加载...",
                "Core services started; business caches are loading asynchronously...");
    }

    @Override
    public void onDisable() {
        if (customCommandManager != null) {
            customCommandManager.unregisterAll();
        }
        if (guiManager != null) {
            getServer().getOnlinePlayers().forEach(guiManager::clearPlayerState);
        }
        if (redisManager != null) {
            redisManager.close();
        }
        if (scheduler != null) {
            scheduler.cancelAll();
        }
        if (storage != null) {
            storage.close();
        }
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, "BungeeCord");
        logInfo("ALwarp 已关闭", "ALwarp has been disabled");
    }

    private void logStartupHeader() {
        String version = getDescription().getVersion();
        String platform = scheduler.isFolia() ? "Folia" : "Paper";
        getLogger().info("========================================");
        logInfo("ALwarp v" + version + " 正在启动",
                "ALwarp v" + version + " is starting");
        logInfo("作者: " + AUTHOR_NAME + " | QQ: " + AUTHOR_QQ,
                "Author: " + AUTHOR_NAME + " | QQ: " + AUTHOR_QQ);
        logInfo("运行平台: " + platform,
                "Platform: " + platform);
        logInfo("语言: " + currentLanguage + " | 时区: " + timeZone.getId(),
                "Language: " + currentLanguage + " | Timezone: " + timeZone.getId());
        logInfo("服务器: " + getServerId() + " | 显示名: " + getLocalServerDisplayName(),
                "Server: " + getServerId() + " | Display name: " + getLocalServerDisplayName());
        logInfo("菜单: 已加载 " + shapeMenuManager.getAllMenus().size() + " 个",
                "Menus: loaded " + shapeMenuManager.getAllMenus().size());
        getLogger().info("========================================");
    }

    public void logInfo(String zh, String en) {
        getLogger().info(localize(zh, en));
    }

    public void logWarning(String zh, String en) {
        getLogger().warning(localize(zh, en));
    }

    public String localize(String zh, String en) {
        return isEnglishLanguage() ? en : zh;
    }

    public boolean isEnglishLanguage() {
        return "en_US".equalsIgnoreCase(currentLanguage);
    }

    // ─── 配置加载 ───

    public void reloadAllConfigs() {
        // 合并 jar 默认值到 config.yml（新增 key 自动写入）
        configUtil.loadConfig("config.yml");
        reloadConfig();
        this.timeZone = resolveConfiguredTimeZone();

        // 根据 language 配置选择消息文件和菜单目录
        String lang = getConfig().getString("language", "zh_CN");
        boolean isEnglish = "en_US".equalsIgnoreCase(lang);
        this.currentLanguage = isEnglish ? "en_US" : "zh_CN";

        String messagesFile = "messages/" + (isEnglish ? "en_US.yml" : "zh_CN.yml");
        String menuDir = isEnglish ? "menus_en" : "menus";

        this.messagesConfig = configUtil.loadConfig(messagesFile);
        this.databaseConfig = configUtil.loadConfig("database.yml");
        this.messageUtil = new MessageUtil(messagesConfig);

        // 切换语言时重建 ShapeMenuManager
        this.shapeMenuManager = new ShapeMenuManager(this, menuDir);
        this.shapeMenuManager.loadMenus();

        // 重建 GUI（菜单变了需要新的 GUIManager 引用）
        if (this.guiManager != null) {
            this.guiManager = new GUIManager(this, shapeMenuManager);
        }
        if (this.customCommandManager != null) {
            this.customCommandManager.reload();
        }

        logInfo("配置已加载: language=" + currentLanguage + " | timezone=" + timeZone.getId(),
                "Config loaded: language=" + currentLanguage + " | timezone=" + timeZone.getId());
    }

    private ZoneId resolveConfiguredTimeZone() {
        String configured = getConfig().getString("time.timezone", getConfig().getString("timezone", DEFAULT_TIME_ZONE.getId()));
        if (configured == null || configured.isBlank()) {
            configured = DEFAULT_TIME_ZONE.getId();
        }
        try {
            return ZoneId.of(configured.trim());
        } catch (DateTimeException e) {
            logWarning("time.timezone 配置无效: " + configured + "，已使用默认北京时间: " + DEFAULT_TIME_ZONE.getId(),
                    "Invalid time.timezone: " + configured + "; using default timezone: " + DEFAULT_TIME_ZONE.getId());
            return DEFAULT_TIME_ZONE;
        }
    }

    // ─── 存储初始化 ───

    private void initStorage() {
        String dbType = databaseConfig.getString("database.type", "sqlite");
        String tablePrefix = resolveTablePrefix();

        if ("mysql".equalsIgnoreCase(dbType)) {
            String host = databaseConfig.getString("database.host", "localhost");
            int port = databaseConfig.getInt("database.port", 3306);
            String db = databaseConfig.getString("database.database", "alwarp");
            String user = databaseConfig.getString("database.username", "alwarp");
            String pass = databaseConfig.getString("database.password", "password");
            storage = new MySQLStorage(this, scheduler, tablePrefix, host, port, db, user, pass);
            logInfo("数据库: MySQL " + host + ":" + port + "/" + db + " | 表前缀: " + tablePrefix,
                    "Database: MySQL " + host + ":" + port + "/" + db + " | prefix: " + tablePrefix);
        } else {
            storage = new SQLiteStorage(this, scheduler, tablePrefix);
            logInfo("数据库: SQLite | 表前缀: " + tablePrefix,
                    "Database: SQLite | prefix: " + tablePrefix);
        }

        storage.init();
    }

    // ─── 经济初始化 ───

    private void initEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            logWarning("经济系统: 未检测到 Vault，经济功能将不可用",
                    "Economy: Vault not found; economy features are unavailable");
            return;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            logWarning("经济系统: 未找到经济服务提供商，经济功能将不可用",
                    "Economy: no economy provider found; economy features are unavailable");
            return;
        }
        economy = rsp.getProvider();
        logInfo("经济系统: 已连接 " + economy.getName(),
                "Economy: connected to " + economy.getName());
    }

    // ─── 管理器初始化 ───

    private void initManagers() {
        this.landmarkManager = new LandmarkManager(this, storage, scheduler);
        this.categoryManager = new CategoryManager(this, storage);
        this.ratingManager = new RatingManager(this, storage, scheduler);
        this.taxManager = new TaxManager(this);
        this.transactionManager = new TransactionManager(this, storage, scheduler);
        this.managerManager = new ManagerManager(this, storage, scheduler);
        this.blacklistManager = new BlacklistManager(this, storage, scheduler);
        this.pinManager = new PinManager(this, storage, scheduler);
        this.teleportManager = new TeleportManager(this);
        this.favoritesManager = new FavoritesManager(this, storage, scheduler);
        this.incomeManager = new IncomeManager(this, storage, scheduler);

        this.taxManager.loadConfig();
        this.ratingManager.loadConfig();
        this.pinManager.startCleanupTask();
    }

    // ─── GUI初始化 ───

    private void initGUI() {
        // shapeMenuManager 已在 reloadAllConfigs() 中初始化
        this.guiManager = new GUIManager(this, shapeMenuManager);
    }

    // ─── 命令注册 ───

    private void registerCommands() {
        var alwarpCmd = new ALwarpCommand(this);
        var alwarpExecutor = getCommand("alwarp");
        if (alwarpExecutor != null) {
            alwarpExecutor.setExecutor(alwarpCmd);
            alwarpExecutor.setTabCompleter(alwarpCmd);
        }
        this.customCommandManager = new CustomCommandManager(this, alwarpCmd);
        this.customCommandManager.reload();
    }

    // ─── 监听器注册 ───

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new InventoryListener(this), this);
    }

    // ─── 缓存加载 ───

    private void loadCacheData() {
        categoryManager.loadAll();
        scheduler.runTaskAsync(() -> {
            int loaded = 0;
            for (StorageManager.PlayerSkin skin : storage.getRecentPlayerSkins(ItemUtil.getPlayerSkinCacheMaxSize())) {
                ItemUtil.cachePlayerSkin(skin.playerUuid(), skin.textureValue(), skin.textureSignature());
                loaded++;
            }
            if (loaded > 0) {
                logInfo("皮肤缓存: 已加载 " + loaded + " 个玩家",
                        "Skin cache: loaded " + loaded + " players");
            }
        });
        landmarkManager.loadAllAsync(() -> {
            int landmarks = landmarkManager.getCachedLandmarks().size();
            int categories = categoryManager.getAllCategories().size();
            logInfo("缓存: 地标 " + landmarks + " 个 | 分类 " + categories + " 个",
                    "Cache: " + landmarks + " landmarks | " + categories + " categories");

            AtomicInteger remaining = new AtomicInteger(6);
            Runnable done = () -> {
                if (remaining.decrementAndGet() == 0) {
                    logInfo("缓存: 管理员 / 黑名单 / 置顶 / 收藏 / 评分 / 收入 已加载",
                            "Cache: managers / blacklists / pins / favorites / ratings / incomes loaded");
                    logInfo("ALwarp 启动完成", "ALwarp startup completed");
                    getLogger().info("========================================");
                }
            };
            managerManager.loadAllAsync(done);
            blacklistManager.loadAllAsync(done);
            pinManager.loadAllAsync(done);
            favoritesManager.loadAllAsync(done);
            ratingManager.loadStatsAsync(done);
            incomeManager.loadAllAsync(done);
        });
    }

    private String resolveTablePrefix() {
        String prefix = databaseConfig.getString("database.prefix", null);
        String legacyPrefix = databaseConfig.getString("database.table_prefix", null);
        if (legacyPrefix != null && !legacyPrefix.isBlank()
                && (prefix == null || prefix.isBlank()
                || ("alwarp_".equals(prefix) && !"alwarp_".equals(legacyPrefix)))) {
            return legacyPrefix;
        }
        if (prefix != null && !prefix.isBlank()) return prefix;
        return "alwarp_";
    }

    // ─── Redis初始化 ───

    private void initRedis() {
        boolean enabled = databaseConfig.getBoolean("redis.enabled", false);
        if (!enabled) {
            logInfo("Redis: 未启用，跨服功能关闭",
                    "Redis: disabled; cross-server features are off");
            redisManager = new RedisManager(this);
            return;
        }

        String host = databaseConfig.getString("redis.host", "localhost");
        int port = databaseConfig.getInt("redis.port", 6379);
        String password = databaseConfig.getString("redis.password", "");
        String channel = databaseConfig.getString("redis.channel", "alwarp_channel");

        redisManager = new RedisManager(this, host, port, password, channel);
        redisManager.init();
        if (redisManager.isEnabled()) {
            logInfo("Redis: 已连接 " + host + ":" + port + " | 频道: " + channel,
                    "Redis: connected to " + host + ":" + port + " | channel: " + channel);
            logInfo("跨服功能: 已启用", "Cross-server features: enabled");
        } else {
            logWarning("Redis: 连接失败，跨服功能不可用",
                    "Redis: connection failed; cross-server features are unavailable");
        }
    }

    // ─── Getter方法 ───

    public FoliaScheduler getScheduler() { return scheduler; }
    public ConfigUtil getConfigUtil() { return configUtil; }
    public MessageUtil getMessageUtil() { return messageUtil; }
    public StorageManager getStorage() { return storage; }
    public Economy getEconomy() { return economy; }
    public CustomCommandManager getCustomCommandManager() { return customCommandManager; }

    public LandmarkManager getLandmarkManager() { return landmarkManager; }
    public CategoryManager getCategoryManager() { return categoryManager; }
    public RatingManager getRatingManager() { return ratingManager; }
    public TaxManager getTaxManager() { return taxManager; }
    public TransactionManager getTransactionManager() { return transactionManager; }
    public ManagerManager getManagerManager() { return managerManager; }
    public BlacklistManager getBlacklistManager() { return blacklistManager; }
    public PinManager getPinManager() { return pinManager; }
    public TeleportManager getTeleportManager() { return teleportManager; }
    public FavoritesManager getFavoritesManager() { return favoritesManager; }
    public IncomeManager getIncomeManager() { return incomeManager; }
    public RedisManager getRedisManager() { return redisManager; }

    public ShapeMenuManager getShapeMenuManager() { return shapeMenuManager; }
    public GUIManager getGUIManager() { return guiManager; }

    public FileConfiguration getMessagesConfig() { return messagesConfig; }
    public FileConfiguration getDatabaseConfig() { return databaseConfig; }
    public ZoneId getTimeZone() { return timeZone; }

    public void runAtPlayer(org.bukkit.entity.Player player, Runnable task) {
        if (player == null) return;
        scheduler.runAtEntity(player, task);
    }

    public String getServerId() {
        return getConfig().getString("serverid", getConfig().getString("serverName", "server"));
    }

    public String getLocalServerDisplayName() {
        return getConfig().getString("serverName", getServerId());
    }

    public String getServerDisplayName(String serverId) {
        if (serverId == null || serverId.isBlank()) return getLocalServerDisplayName();
        if (serverId.equalsIgnoreCase(getServerId())) return getLocalServerDisplayName();
        if (redisManager != null) {
            String displayName = redisManager.getServerDisplayName(serverId);
            if (displayName != null && !displayName.isBlank()) return displayName;
        }
        return serverId;
    }

    public String getLandmarkServerPermission(String serverId) {
        if (!getConfig().getBoolean("server_permissions.enabled", false)) return null;
        if (serverId == null || serverId.isBlank()) return null;
        ConfigurationSection servers = getConfig().getConfigurationSection("server_permissions.servers");
        if (servers == null) return null;

        String permission = servers.getString(serverId);
        if (permission != null && !permission.isBlank()) return permission;

        for (String key : servers.getKeys(false)) {
            if (key.equalsIgnoreCase(serverId)) {
                permission = servers.getString(key);
                return permission != null && !permission.isBlank() ? permission : null;
            }
        }
        return null;
    }

    public boolean canAccessLandmarkServer(Player player, Landmark landmark) {
        if (player == null || landmark == null) return false;
        if (player.isOp() || player.hasPermission("alwarp.admin")) return true;
        String permission = getLandmarkServerPermission(landmark.getServerName());
        return permission == null || player.hasPermission(permission);
    }

    /**
     * 检查本服是否允许该玩家创建地标（多服部署时可用于禁用特定子服）。
     * alwarp.admin 权限不受此限制。
     */
    public boolean isCreateAllowedOnServer(org.bukkit.entity.Player player) {
        if (player.hasPermission("alwarp.admin")) return true;
        return getConfig().getBoolean("landmark.allow_create", true);
    }

    /**
     * 检查玩家是否可以在当前世界创建地标。
     * 受限世界仅alwarp.admin权限玩家可创建。
     */
    public boolean canCreateInWorld(org.bukkit.entity.Player player) {
        if (player.hasPermission("alwarp.admin")) return true;
        // isList 确保配置值是真正的列表；若用户设为 "" 等非列表值，视为无限制
        // 避免 getStringList 在非列表值上回退到 jar 内置默认值
        if (!getConfig().isList("landmark.restricted_worlds")) return true;
        java.util.List<String> restricted = getConfig().getStringList("landmark.restricted_worlds");
        return restricted == null || restricted.isEmpty() || !restricted.contains(player.getWorld().getName());
    }
}
