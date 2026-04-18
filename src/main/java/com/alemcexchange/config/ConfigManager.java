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
        plugin.getLogger().info("Config files loaded successfully!");
    }

    private YamlConfiguration loadConfigFile(String filename) {
        File file = new File(plugin.getDataFolder(), filename);
        if (!file.exists()) {
            plugin.saveResource(filename, false);
        }
        return YamlConfiguration.loadConfiguration(file);
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
}
