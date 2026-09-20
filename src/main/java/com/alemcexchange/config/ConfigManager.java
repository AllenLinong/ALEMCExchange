package com.alemcexchange.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ConfigManager {

    private final JavaPlugin plugin;
    private YamlConfiguration config;
    private YamlConfiguration lang;
    private YamlConfiguration items;
    private YamlConfiguration menus;
    private YamlConfiguration database;
    // v4：每个菜单独立 yml，按 config.language 加载 menus/<语言>/ 目录
    private final Map<String, YamlConfiguration> menuConfigs = new LinkedHashMap<>();
    // 多语言文件：lang/ 目录下每个 yml 一个语言（zh_CN / en_US ...）
    private final Map<String, YamlConfiguration> langFiles = new LinkedHashMap<>();
    private Map<String, ItemConfig> itemCache = Collections.emptyMap();
    private List<String> itemMaterialNames = Collections.emptyList();
    private Map<String, String> itemSearchText = Collections.emptyMap();
    private long configStamp = 0L;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfig() {
        plugin.getDataFolder().mkdirs();
        config = loadConfigFile("config.yml");
        lang = loadConfigFile("lang.yml");
        items = loadConfigFile("items.yml");
        menus = loadConfigFile("menus.yml");
        database = loadDatabaseConfig();
        migrateAndLoadLangs();
        migrateMenusV2ToV3();
        migrateMenusToV4();

        // 补全缺失的配置项
        completeMissingConfig();
        rebuildItemCache();
        configStamp++;

        plugin.getLogger().info("Config files loaded successfully!");
    }

    /**
     * 加载 database.yml；文件不存在时自动从 config.yml 的 database 段迁移生成。
     */
    private YamlConfiguration loadDatabaseConfig() {
        File file = new File(plugin.getDataFolder(), "database.yml");
        if (file.exists()) {
            return YamlConfiguration.loadConfiguration(file);
        }
        if (!config.contains("database")) {
            // 全新安装：直接释放默认配置
            plugin.saveResource("database.yml", false);
            return YamlConfiguration.loadConfiguration(file);
        }

        // 旧版本升级：迁移 config.yml → database.yml
        YamlConfiguration db = new YamlConfiguration();
        db.set("storage.type", config.getString("database.type", "sqlite"));
        db.set("storage.table-prefix", config.getString("database.table_prefix", "alembc_"));
        db.set("storage.sqlite.file", config.getString("database.sqlite.file", "alembc.db"));
        db.set("storage.mysql.host", config.getString("database.mysql.host", "localhost"));
        db.set("storage.mysql.port", config.getInt("database.mysql.port", 3306));
        db.set("storage.mysql.database", config.getString("database.mysql.database", "minecraft"));
        db.set("storage.mysql.user", config.getString("database.mysql.user", "root"));
        db.set("storage.mysql.password", config.getString("database.mysql.password", ""));
        db.set("storage.mysql.connection-parameters", config.getString("database.mysql.connection-parameters",
                "?autoReconnect=true&useSSL=false&useUnicode=true&characterEncoding=UTF-8"));
        db.set("storage.mysql.pool-settings.max-pool-size", config.getInt("database.mysql.pool-settings.max-pool-size", 10));
        db.set("storage.mysql.pool-settings.min-idle", config.getInt("database.mysql.pool-settings.min-idle", 5));
        db.set("storage.mysql.pool-settings.max-lifetime", config.getLong("database.mysql.pool-settings.max-lifetime", 1800000L));
        db.set("storage.mysql.pool-settings.keep-alive-time", config.getLong("database.mysql.pool-settings.keep-alive-time", 60000L));
        db.set("storage.mysql.pool-settings.time-out", config.getLong("database.mysql.pool-settings.time-out", 30000L));
        db.set("sync.enabled", false);
        db.set("sync.server-id", "server-" + plugin.getServer().getPort());
        db.set("sync.poll-interval-ticks", 10);
        db.set("sync.event-retention-minutes", 10);
        try {
            db.save(file);
            plugin.getLogger().info("Migrated database settings from config.yml to database.yml (old values kept in config.yml)");
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save migrated database.yml: " + e.getMessage());
        }
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        // 自动补全新增的同步/Redis 配置键（升级迁移）
        boolean dbChanged = false;
        if (!loaded.contains("sync.mode")) {
            loaded.set("sync.mode", "polling");
            dbChanged = true;
        }
        String[] redisKeys = {"host", "port", "password", "username", "channel", "reconnect-seconds"};
        Object[] redisDefaults = {"127.0.0.1", 6379, "", "", "alemcexchange-sync", 5};
        for (int i = 0; i < redisKeys.length; i++) {
            if (!loaded.contains("sync.redis." + redisKeys[i])) {
                loaded.set("sync.redis." + redisKeys[i], redisDefaults[i]);
                dbChanged = true;
            }
        }
        if (dbChanged) {
            try {
                loaded.save(file);
                plugin.getLogger().info("database.yml: added new sync/redis options with defaults");
            } catch (IOException e) {
                plugin.getLogger().warning("Could not save database.yml additions: " + e.getMessage());
            }
        }
        return loaded;
    }

    /**
     * 配置加载戳：每次 loadConfig/reload 递增，菜单层据此失效缓存。
     */
    public long getConfigStamp() {
        return configStamp;
    }

    private YamlConfiguration loadConfigFile(String filename) {
        File file = new File(plugin.getDataFolder(), filename);
        if (!file.exists()) {
            plugin.saveResource(filename, false);
            return YamlConfiguration.loadConfiguration(file);
        }
        // menus.yml v1 → v2 自动升级：旧格式备份后释放新版
        if (filename.equals("menus.yml")) {
            YamlConfiguration existing = YamlConfiguration.loadConfiguration(file);
            if (existing.getInt("config-version", 1) < 2) {
                File backup = new File(plugin.getDataFolder(), "menus_old_v1.yml");
                if (!backup.exists()) {
                    try {
                        java.nio.file.Files.copy(file.toPath(), backup.toPath());
                        plugin.getLogger().info("Detected legacy menus.yml, backed up to menus_old_v1.yml");
                    } catch (IOException e) {
                        plugin.getLogger().warning("Could not back up menus.yml: " + e.getMessage());
                    }
                }
                plugin.saveResource("menus.yml", true);
                existing = YamlConfiguration.loadConfiguration(file);
                return existing;
            }
        }
        return YamlConfiguration.loadConfiguration(file);
    }
    
    private void completeMissingConfig() {
        boolean configChanged = false;
        boolean langChanged = false;

        // === 补全 config.yml 缺失项 ===
        // 每日限额
        if (!config.contains("daily_limit.enabled")) {
            config.set("daily_limit.enabled", false);
            configChanged = true;
        }
        if (!config.contains("daily_limit.reset_time")) {
            config.set("daily_limit.reset_time", "00:00");
            configChanged = true;
        }
        if (!config.contains("daily_limit.default_limit")) {
            config.set("daily_limit.default_limit", 10000);
            configChanged = true;
        }
        if (!config.contains("daily_limit.permission_prefix")) {
            config.set("daily_limit.permission_prefix", "alemcexchange.daily");
            configChanged = true;
        }
        if (!config.contains("daily_limit.limit_groups.base.limit")) {
            config.set("daily_limit.limit_groups.base.limit",
                    config.getDouble("daily_limit.default_limit", 10000));
            config.set("daily_limit.limit_groups.base.weight", 0);
            configChanged = true;
        }
        if (!config.contains("daily_limit.limit_groups.vip.limit")) {
            config.set("daily_limit.limit_groups.vip.limit",
                    50000);
            config.set("daily_limit.limit_groups.vip.weight", 10);
            configChanged = true;
        }
        if (!config.contains("daily_limit.limit_groups.premium.limit")) {
            config.set("daily_limit.limit_groups.premium.limit",
                    100000);
            config.set("daily_limit.limit_groups.premium.weight", 20);
            configChanged = true;
        }
        if (!config.contains("daily_limit.limit_groups.unlimited.limit")) {
            config.set("daily_limit.limit_groups.unlimited.limit",
                    -1);
            config.set("daily_limit.limit_groups.unlimited.weight", 100);
            configChanged = true;
        }
        // 自动出售消息
        if (!config.contains("autosell.send_message")) {
            config.set("autosell.send_message", true);
            configChanged = true;
        }
        if (!config.contains("autosell.message")) {
            config.set("autosell.message", "&a自动出售 %item% x%amount% ，获得 %emc% EMC");
            configChanged = true;
        }
        if (!config.contains("autosell.batch_threshold")) {
            config.set("autosell.batch_threshold", 64);
            configChanged = true;
        }
        if (!config.contains("features.drop_overflow_items")) {
            config.set("features.drop_overflow_items", true);
            configChanged = true;
        }
        if (!config.contains("language")) {
            config.set("language", "zh_CN");
            configChanged = true;
        }


        // === 补全 lang.yml 缺失项 ===
        // PlaceholderAPI
        if (!lang.contains("placeholder.autosell.enabled")) {
            lang.set("placeholder.autosell.enabled", "开启");
            langChanged = true;
        }
        if (!lang.contains("placeholder.autosell.disabled")) {
            lang.set("placeholder.autosell.disabled", "关闭");
            langChanged = true;
        }
        // 每日限额消息
        if (!lang.contains("daily_limit.reached")) {
            lang.set("daily_limit.reached", "&c你今日的EMC获取限额已用完！请明天再来。");
            langChanged = true;
        }
        if (!lang.contains("daily_limit.remaining")) {
            lang.set("daily_limit.remaining", "&e今日剩余限额: &6{remaining} &e/ &6{limit} EMC");
            langChanged = true;
        }
        if (!lang.contains("daily_limit.unlimited")) {
            lang.set("daily_limit.unlimited", "&a无限");
            langChanged = true;
        }
        // 命令
        if (!lang.contains("command.unlock.usage")) {
            lang.set("command.unlock.usage", "&c用法: /alex unlock <玩家> <物品ID>");
            langChanged = true;
        }
        if (!lang.contains("command.unlock.success")) {
            lang.set("command.unlock.success", "&a已为 &6{player} &a解锁物品: &6{item} &a！");
            langChanged = true;
        }
        if (!lang.contains("command.unlock.invalid-item")) {
            lang.set("command.unlock.invalid-item", "&c无效的物品ID！");
            langChanged = true;
        }

        // === 搜索功能 ===
        if (!lang.contains("search.prompt")) {
            lang.set("search.prompt", "&e请 在聊天栏 输入搜索关键词（支持中文名/物品ID/CE物品ID），输入 cancel 取消：");
            langChanged = true;
        }
        if (!lang.contains("search.applied")) {
            lang.set("search.applied", "&a搜索已应用：&f{keyword}");
            langChanged = true;
        }
        if (!lang.contains("search.cleared")) {
            lang.set("search.cleared", "&a搜索已清除，显示全部物品。");
            langChanged = true;
        }
        if (!lang.contains("search.cancelled")) {
            lang.set("search.cancelled", "&c已取消搜索。");
            langChanged = true;
        }
        if (!lang.contains("search.no-result")) {
            lang.set("search.no-result", "&c没有找到匹配 &f{keyword} &c的物品。");
            langChanged = true;
        }

        // 保存
        if (configChanged) {
            try {
                config.save(new File(plugin.getDataFolder(), "config.yml"));
            } catch (IOException e) {
                plugin.getLogger().warning("Could not save config.yml: " + e.getMessage());
            }
        }
        if (langChanged) {
            try {
                // 保存到实际生效的语言文件（而非旧的根目录 lang.yml）
                String tag = config.getString("language", "zh_CN");
                File langTarget = new File(plugin.getDataFolder(), "lang" + File.separator + tag + ".yml");
                if (!langTarget.exists()) {
                    langTarget = new File(plugin.getDataFolder(), "lang.yml");
                }
                lang.save(langTarget);
            } catch (IOException e) {
                plugin.getLogger().warning("Could not save lang.yml: " + e.getMessage());
            }
        }
    }

    public void reloadConfig() {
        loadConfig();
    }

    public YamlConfiguration getConfig() {
        return config;
    }

    public YamlConfiguration getItems() {
        return items;
    }

    public YamlConfiguration getMenus() {
        return menus;
    }

    /**
     * 语言文件加载与迁移：
     *  - 已有 lang 目录：加载其中全部 yml；
     *  - 旧版（根目录 lang.yml）：消息迁移到 lang/zh_CN.yml，gui 段补默认；
     *  - 全新安装：释放 lang/zh_CN.yml 与 lang/en_US.yml。
     */
    private void migrateAndLoadLangs() {
        File langDir = new File(plugin.getDataFolder(), "lang");
        File legacyLang = new File(plugin.getDataFolder(), "lang.yml");

        if (!langDir.exists() || langDir.listFiles() == null || langDir.listFiles().length == 0) {
            langDir.mkdirs();
            YamlConfiguration zh = new YamlConfiguration();
            if (legacyLang.exists()) {
                try {
                    zh.loadFromString(String.join("\n", java.nio.file.Files.readAllLines(legacyLang.toPath())));
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to read legacy lang.yml: " + e.getMessage());
                }
            }
            try (java.io.InputStream in = plugin.getResource("lang/zh_CN.yml");
                 java.io.BufferedReader reader = in == null ? null
                         : new java.io.BufferedReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
                if (reader != null) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                    YamlConfiguration def = new YamlConfiguration();
                    def.loadFromString(sb.toString());
                    for (String key : def.getKeys(true)) {
                        if (!zh.contains(key)) {
                            zh.set(key, def.get(key));
                        }
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load bundled zh_CN defaults: " + e.getMessage());
            }
            try {
                java.nio.file.Files.write(new File(langDir, "zh_CN.yml").toPath(),
                        zh.saveToString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to save lang/zh_CN.yml: " + e.getMessage());
            }
            if (!(new File(langDir, "en_US.yml").exists())) {
                plugin.saveResource("lang/en_US.yml", false);
            }
            plugin.getLogger().info("Lang files ready in plugins/ALEMCExchange/lang/");
        }

        File[] files = langDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".yml"));
        if (files != null) {
            for (File f : files) {
                String tag = f.getName().substring(0, f.getName().length() - 4);
                langFiles.put(tag, YamlConfiguration.loadConfiguration(f));
            }
        }
        lang = getLang();
    }

    /**
     * 旧 v2 menus.yml（含内联文本）迁移到 v3（纯布局）：
     * 把用户自定义的按钮文本抽取到 lang/zh_CN.yml 的 gui 段后替换为 v3 布局。
     */
    private void migrateMenusV2ToV3() {
        File menusFile = new File(plugin.getDataFolder(), "menus.yml");
        if (!menusFile.exists()) {
            return;
        }
        YamlConfiguration existing = YamlConfiguration.loadConfiguration(menusFile);
        if (existing.getInt("config-version", 1) >= 3) {
            return;
        }
        File zhFile = new File(plugin.getDataFolder(), "lang" + File.separator + "zh_CN.yml");
        YamlConfiguration zh = YamlConfiguration.loadConfiguration(zhFile);
        ConfigurationSection menusSec = existing.getConfigurationSection("menus");
        boolean changed = false;
        if (menusSec != null) {
            for (String menuName : menusSec.getKeys(false)) {
                ConfigurationSection menu = menusSec.getConfigurationSection(menuName);
                if (menu == null) continue;
                String title = menu.getString("title");
                if (title != null) {
                    zh.set("gui." + menuName + ".title", title);
                    changed = true;
                }
                ConfigurationSection itemsSec = menu.getConfigurationSection("items");
                if (itemsSec != null) {
                    for (String btnKey : itemsSec.getKeys(false)) {
                        if (btnKey.length() != 1) continue;
                        ConfigurationSection btn = itemsSec.getConfigurationSection(btnKey);
                        if (btn == null) continue;
                        String base = "gui." + menuName + "." + btnKey;
                        if (btn.contains("name")) {
                            zh.set(base + ".name", btn.getString("name"));
                            changed = true;
                        }
                        if (btn.contains("lore")) {
                            zh.set(base + ".lore", btn.getStringList("lore"));
                            changed = true;
                        }
                    }
                }
            }
        }
        if (changed) {
            try {
                zh.save(zhFile);
                langFiles.put("zh_CN", YamlConfiguration.loadConfiguration(zhFile));
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to save migrated gui texts: " + e.getMessage());
            }
        }
        File backup = new File(plugin.getDataFolder(), "menus_old_v2.yml");
        if (!backup.exists()) {
            try {
                java.nio.file.Files.copy(menusFile.toPath(), backup.toPath());
            } catch (IOException e) {
                plugin.getLogger().warning("Could not back up menus.yml: " + e.getMessage());
            }
        }
        plugin.saveResource("menus.yml", true);
        menus = YamlConfiguration.loadConfiguration(menusFile);
        plugin.getLogger().info("Migrated menus.yml to v3 (layout-only); texts moved to lang/zh_CN.yml");
    }

    /**
     * v4 菜单迁移：menus.yml（v3 纯布局）+ lang/<语言>.yml 的 gui 段
     * → menus/<语言>/<菜单>.yml（每菜单独立文件，含文本）。
     * 全新安装直接释放内置的 menus/zh_CN 与 menus/en_US。
     */
    private void migrateMenusToV4() {
        File menusDir = new File(plugin.getDataFolder(), "menus");
        File legacy = new File(plugin.getDataFolder(), "menus.yml");

        if (!menusDir.exists()) {
            menusDir.mkdirs();
            if (legacy.exists() && menus != null) {
                // v3 → v4：布局来自 menus.yml，文本来自各语言 gui 段
                ConfigurationSection root = menus.getConfigurationSection("menus");
                for (Map.Entry<String, YamlConfiguration> entry : langFiles.entrySet()) {
                    String tag = entry.getKey();
                    YamlConfiguration langFile = entry.getValue();
                    // 文本来源 = 内置 _texts.yml 兜底 + 用户 lang gui 段覆盖（v2 升级时的自定义文案）
                    YamlConfiguration gui = loadBundledMenuTexts(tag);
                    ConfigurationSection userGui = langFile.getConfigurationSection("gui");
                    if (userGui != null) {
                        for (String menuName : userGui.getKeys(false)) {
                            ConfigurationSection menuSec = userGui.getConfigurationSection(menuName);
                            if (menuSec == null) continue;
                            for (String btnKey : menuSec.getKeys(false)) {
                                Object value = menuSec.get(btnKey);
                                if (value instanceof String || value instanceof java.util.List) {
                                    gui.set(menuName + "." + btnKey, value);
                                } else if (value instanceof ConfigurationSection btn) {
                                    for (String field : btn.getKeys(false)) {
                                        gui.set(menuName + "." + btnKey + "." + field, btn.get(field));
                                    }
                                }
                            }
                        }
                    }
                    File langDir = new File(menusDir, tag);
                    langDir.mkdirs();
                    if (root != null) {
                        for (String menuName : root.getKeys(false)) {
                            ConfigurationSection menuSec = root.getConfigurationSection(menuName);
                            if (menuSec == null) continue;
                            YamlConfiguration out = new YamlConfiguration();
                            if (gui.contains(menuName + ".title")) {
                                out.set("title", gui.getString(menuName + ".title"));
                            }
                            out.set("shape", menuSec.getStringList("shape"));
                            out.set("glass.type", menuSec.getString("glass.type", "GRAY_STAINED_GLASS_PANE"));
                            ConfigurationSection itemsSec = menuSec.getConfigurationSection("items");
                            if (itemsSec != null) {
                                for (String btnKey : itemsSec.getKeys(false)) {
                                    if (btnKey.length() != 1) continue;
                                    ConfigurationSection btn = itemsSec.getConfigurationSection(btnKey);
                                    if (btn == null) continue;
                                    String to = "items." + btnKey + ".";
                                    for (String k : new String[]{"type", "craftengine", "plugin_item", "action", "right_action"}) {
                                        if (btn.contains(k)) out.set(to + k, btn.get(k));
                                    }
                                    if (gui.contains(menuName + "." + btnKey + ".name")) {
                                        out.set(to + "name", gui.getString(menuName + "." + btnKey + ".name"));
                                    }
                                    if (gui.contains(menuName + "." + btnKey + ".lore")) {
                                        out.set(to + "lore", gui.getStringList(menuName + "." + btnKey + ".lore"));
                                    }
                                }
                            }
                            try {
                                out.save(new File(langDir, menuName + ".yml"));
                            } catch (IOException e) {
                                plugin.getLogger().warning("Failed to save menus/" + tag + "/" + menuName + ".yml: " + e.getMessage());
                            }
                        }
                    }
                    // gui 段已并入菜单文件，从语言文件移除避免两处维护
                    {
                        langFile.set("gui", null);
                        try {
                            langFile.save(new File(plugin.getDataFolder(), "lang" + File.separator + tag + ".yml"));
                        } catch (IOException e) {
                            plugin.getLogger().warning("Failed to clean gui section from lang/" + tag + ".yml: " + e.getMessage());
                        }
                    }
                }
                File backup = new File(plugin.getDataFolder(), "menus_old_v3.yml");
                if (!backup.exists()) {
                    try {
                        java.nio.file.Files.copy(legacy.toPath(), backup.toPath());
                    } catch (IOException ignored) {
                    }
                }
                plugin.getLogger().info("Migrated menus.yml + lang gui texts to menus/<lang>/ per-menu files");
            } else {
                // 全新安装：释放内置菜单（与内置语言对应）
                for (String tag : new String[]{"zh_CN", "en_US"}) {
                    File langDir = new File(menusDir, tag);
                    langDir.mkdirs();
                    for (String menuName : new String[]{"main", "sell", "browse", "exchange"}) {
                        String res = "menus/" + tag + "/" + menuName + ".yml";
                        File target = new File(langDir, menuName + ".yml");
                        if (!target.exists()) {
                            try (java.io.InputStream in = plugin.getResource(res)) {
                                if (in != null) {
                                    java.nio.file.Files.copy(in, target.toPath());
                                }
                            } catch (IOException e) {
                                plugin.getLogger().warning("Failed to extract " + res + ": " + e.getMessage());
                            }
                        }
                    }
                }
            }
        }

        // 按 config.language 加载对应语言目录（无则取第一个可用目录）
        menuConfigs.clear();
        String lang = config != null ? config.getString("language", "zh_CN") : "zh_CN";
        File dir = new File(menusDir, lang);
        if (!dir.isDirectory()) {
            File[] dirs = menusDir.listFiles(File::isDirectory);
            if (dirs != null && dirs.length > 0) {
                dir = dirs[0];
                plugin.getLogger().warning("menus/" + lang + " not found, falling back to " + dir.getName());
            }
        }
        File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".yml"));
        if (files != null) {
            for (File f : files) {
                String name = f.getName().substring(0, f.getName().length() - 4);
                if (name.startsWith("_")) continue;
                menuConfigs.put(name, YamlConfiguration.loadConfiguration(f));
            }
        }
        if (menuConfigs.isEmpty()) {
            plugin.getLogger().severe("No menu files loaded from menus/ directory!");
        }
    }

    /** 读取 jar 内置的菜单文本兜底 menus/<tag>/_texts.yml */
    private YamlConfiguration loadBundledMenuTexts(String tag) {
        YamlConfiguration texts = new YamlConfiguration();
        try (java.io.InputStream in = plugin.getResource("menus/" + tag + "/_texts.yml");
             java.io.BufferedReader reader = in == null ? null
                     : new java.io.BufferedReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
            if (reader != null) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                texts.loadFromString(sb.toString());
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load bundled menu texts for " + tag + ": " + e.getMessage());
        }
        return texts;
    }

    /** 当前语言的菜单配置（菜单名 → 文件内容） */
    public Map<String, YamlConfiguration> getMenuConfigs() {
        return menuConfigs;
    }

    /** 默认语言文件（config.yml 的 language 设置，缺 zh_CN） */
    public YamlConfiguration getLang() {
        String tag = config != null ? config.getString("language", "zh_CN") : "zh_CN";
        YamlConfiguration file = langFiles.get(tag);
        return file != null ? file : langFiles.values().stream().findFirst().orElse(new YamlConfiguration());
    }

    public Map<String, YamlConfiguration> getLangFiles() {
        return Collections.unmodifiableMap(langFiles);
    }

    public YamlConfiguration getDatabase() {
        return database;
    }

    private void rebuildItemCache() {
        Map<String, ItemConfig> newCache = new LinkedHashMap<>();
        Map<String, String> newSearchText = new LinkedHashMap<>();
        ConfigurationSection itemSection = items.getConfigurationSection("items");
        if (itemSection != null) {
            // getKeys(false) 保持 items.yml 中的书写顺序（LinkedHashSet）
            for (String materialName : itemSection.getKeys(false)) {
                String path = "items." + materialName + ".";
                newCache.put(materialName, new ItemConfig(
                        items.getString(path + "name", materialName),
                        items.getDouble(path + "emc", 0),
                        items.getInt(path + "required_mine", 0),
                        items.getInt(path + "required_sell", 0)
                ));
                // 预计算搜索索引：中文名 + 配置键（含 CE 物品ID）
                String ceId = com.alemcexchange.util.CeItemUtil.extractCeId(materialName);
                String searchText = (materialName + "\n" + items.getString(path + "name", materialName) + "\n"
                        + (ceId != null ? ceId : "")).toLowerCase(java.util.Locale.ROOT);
                newSearchText.put(materialName, searchText);
            }
        }
        itemCache = Collections.unmodifiableMap(newCache);
        itemMaterialNames = Collections.unmodifiableList(new ArrayList<>(newCache.keySet()));
        itemSearchText = Collections.unmodifiableMap(newSearchText);
    }

    /**
     * 获取物品的预计算搜索文本（小写，多行拼接）。
     * CE 物品的运行时显示名在菜单层通过 CeItemUtil 追加匹配。
     */
    public String getSearchText(String materialName) {
        return itemSearchText.getOrDefault(materialName, materialName.toLowerCase(java.util.Locale.ROOT));
    }

    public boolean hasItem(String materialName) {
        return itemCache.containsKey(materialName);
    }

    public List<String> getItemMaterialNames() {
        return itemMaterialNames;
    }

    public ItemConfig getItemConfig(String materialName) {
        return itemCache.get(materialName);
    }

    public double getItemEMC(String materialName) {
        ItemConfig itemConfig = itemCache.get(materialName);
        return itemConfig != null ? itemConfig.getEmc() : 0;
    }

    public int getRequiredMine(String materialName) {
        ItemConfig itemConfig = itemCache.get(materialName);
        return itemConfig != null ? itemConfig.getRequiredMine() : 0;
    }

    public int getRequiredSell(String materialName) {
        ItemConfig itemConfig = itemCache.get(materialName);
        return itemConfig != null ? itemConfig.getRequiredSell() : 0;
    }

    public String getItemName(String materialName) {
        ItemConfig itemConfig = itemCache.get(materialName);
        return itemConfig != null ? itemConfig.getName() : materialName;
    }

    public double getTaxRate(String permission) {
        if (config.contains("tax_rates." + permission)) {
            return config.getDouble("tax_rates." + permission);
        }
        return config.getDouble("sell_tax", 0.05);
    }

    /**
     * 遍历玩家拥有的所有税率权限，取最低税率
     */
    public double getTaxRateForPlayer(org.bukkit.entity.Player player) {
        double defaultTax = config.getDouble("sell_tax", 0.05);
        double lowestRate = defaultTax;

        if (config.contains("tax_rates")) {
            for (String permission : config.getConfigurationSection("tax_rates").getKeys(false)) {
                if (player.hasPermission(permission)) {
                    double rate = config.getDouble("tax_rates." + permission, defaultTax);
                    if (rate < lowestRate) {
                        lowestRate = rate;
                    }
                }
            }
        }

        return lowestRate;
    }

    public boolean isAutoSellEnabled() {
        return config.getBoolean("features.auto_sell", true);
    }

    public boolean isDropOverflowItemsEnabled() {
        return config.getBoolean("features.drop_overflow_items", true);
    }

    public boolean isDebugEnabled() {
        return config.getBoolean("debug.enabled", false);
    }

    public String getDebugLevel() {
        return config.getString("debug.level", "info");
    }

    public boolean isAutoSellMessageEnabled() {
        return config.getBoolean("autosell.send_message", true);
    }

    public String getAutoSellMessage() {
        return config.getString("autosell.message", "&a自动出售 %item% x%amount% ，获得 %emc% EMC");
    }

    public boolean isDailyLimitEnabled() {
        return config.getBoolean("daily_limit.enabled", false);
    }

    public String getDailyLimitResetTime() {
        return config.getString("daily_limit.reset_time", "00:00");
    }

        /**
     * 获取玩家的每日EMC获取上限
     * @param player 玩家对象（用于权限检查）
     * @return 每日上限，-1 表示不限制
     */
    public double getDailyLimitForPlayer(org.bukkit.entity.Player player) {
        String permissionPrefix = config.getString("daily_limit.permission_prefix", "alemcexchange.daily");
        permissionPrefix = permissionPrefix == null ? "alemcexchange.daily" : permissionPrefix.trim();
        while (permissionPrefix.endsWith(".")) {
            permissionPrefix = permissionPrefix.substring(0, permissionPrefix.length() - 1);
        }
        ConfigurationSection groups = config.getConfigurationSection("daily_limit.limit_groups");
        if (groups != null) {
            int selectedWeight = Integer.MIN_VALUE;
            double selectedLimit = config.getDouble("daily_limit.default_limit", 10000);

            for (String groupName : groups.getKeys(false)) {
                ConfigurationSection group = groups.getConfigurationSection(groupName);
                if (group == null || !group.contains("limit")) {
                    continue;
                }

                boolean isBaseGroup = groupName.equalsIgnoreCase("base");
                String permission = permissionPrefix + "." + groupName;
                if (!isBaseGroup && !player.hasPermission(permission)) {
                    continue;
                }

                int weight = group.getInt("weight", 0);
                if (weight > selectedWeight) {
                    selectedWeight = weight;
                    selectedLimit = group.getDouble("limit", selectedLimit);
                }
            }

            return selectedLimit;
        }

        return config.getDouble("daily_limit.default_limit", 10000);
    }

    public static class ItemConfig {
        private final String name;
        private final double emc;
        private final int requiredMine;
        private final int requiredSell;

        public ItemConfig(String name, double emc, int requiredMine, int requiredSell) {
            this.name = name;
            this.emc = emc;
            this.requiredMine = requiredMine;
            this.requiredSell = requiredSell;
        }

        public String getName() {
            return name;
        }

        public double getEmc() {
            return emc;
        }

        public int getRequiredMine() {
            return requiredMine;
        }

        public int getRequiredSell() {
            return requiredSell;
        }
    }
}
