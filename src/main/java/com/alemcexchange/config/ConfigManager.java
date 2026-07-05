package com.alemcexchange.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

public class ConfigManager {

    private final JavaPlugin plugin;
    private YamlConfiguration config;
    private YamlConfiguration lang;
    private YamlConfiguration items;
    private YamlConfiguration menus;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfig() {
        plugin.getDataFolder().mkdirs();
        config = loadConfigFile("config.yml");
        lang = loadConfigFile("lang.yml");
        items = loadConfigFile("items.yml");
        menus = loadConfigFile("menus.yml");
        
        // 补全缺失的配置项
        completeMissingConfig();
        
        plugin.getLogger().info("Config files loaded successfully!");
    }

    private YamlConfiguration loadConfigFile(String filename) {
        File file = new File(plugin.getDataFolder(), filename);
        if (!file.exists()) {
            plugin.saveResource(filename, false);
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
        if (!config.contains("daily_limit.permissions")) {
            config.set("daily_limit.permissions.alemcexchange.daily.vip", 50000);
            config.set("daily_limit.permissions.alemcexchange.daily.premium", 100000);
            config.set("daily_limit.permissions.alemcexchange.daily.unlimited", -1);
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
        if (!lang.contains("placeholder.daily_limit.unlimited")) {
            lang.set("placeholder.daily_limit.unlimited", "无限");
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
        if (!lang.contains("daily_limit.limit_info")) {
            lang.set("daily_limit.limit_info", "&e今日已获取: &6{earned} &e/ &6{limit} EMC");
            langChanged = true;
        }
        if (!lang.contains("daily_limit.unlimited")) {
            lang.set("daily_limit.unlimited", "&a无限");
            langChanged = true;
        }
        if (!lang.contains("daily_limit.disabled")) {
            lang.set("daily_limit.disabled", "&7未开启");
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
                lang.save(new File(plugin.getDataFolder(), "lang.yml"));
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

    public YamlConfiguration getLang() {
        return lang;
    }

    public YamlConfiguration getItems() {
        return items;
    }

    public YamlConfiguration getMenus() {
        return menus;
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
        double highestLimit = -1;
        boolean hasUnlimited = false;

        if (config.contains("daily_limit.permissions")) {
            // 遍历所有配置的权限节点，取最高限额
            for (String permission : config.getConfigurationSection("daily_limit.permissions").getKeys(false)) {
                if (player.hasPermission(permission)) {
                    double limit = config.getDouble("daily_limit.permissions." + permission, -1);
                    if (limit == -1) {
                        hasUnlimited = true;
                    } else if (limit > highestLimit) {
                        highestLimit = limit;
                    }
                }
            }
        }

        // 如果有无限权限，返回 -1
        if (hasUnlimited) {
            return -1;
        }

        // 如果有匹配的权限限额，返回最高值
        if (highestLimit >= 0) {
            return highestLimit;
        }

        // 返回默认限制
        return config.getDouble("daily_limit.default_limit", 10000);
    }
}
