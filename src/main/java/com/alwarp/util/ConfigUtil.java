package com.alwarp.util;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * 配置文件加载工具。
 * 自动合并 jar 默认值到用户文件，新增键自动写入，已有修改不覆盖。
 */
public final class ConfigUtil {

    private final Plugin plugin;

    public ConfigUtil(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 加载配置文件，自动合并 jar 中的新键到用户文件。
     * 用户已有的自定义值保持不变。
     */
    public FileConfiguration loadConfig(String fileName) {
        File file = new File(plugin.getDataFolder(), fileName);
        file.getParentFile().mkdirs();

        InputStream jarStream = plugin.getResource(fileName);
        if (jarStream == null) {
            // jar 中没有此文件，直接加载用户文件
            return YamlConfiguration.loadConfiguration(file);
        }

        FileConfiguration jarDefaults = YamlConfiguration.loadConfiguration(
                new InputStreamReader(jarStream, StandardCharsets.UTF_8));

        if (!file.exists()) {
            // 用户文件不存在，直接从 jar 复制
            try {
                saveRawResource(fileName, file);
            } catch (IOException e) {
                plugin.getLogger().severe("无法创建默认配置: " + fileName);
            }
            return jarDefaults;
        }

        // 用户文件存在 → 合并新键
        FileConfiguration userConfig = YamlConfiguration.loadConfiguration(file);
        int added = mergeDefaults(userConfig, jarDefaults, "");
        if (added > 0) {
            try {
                userConfig.save(file);
                plugin.getLogger().info(fileName + " 已自动更新，新增 " + added + " 个配置项");
            } catch (IOException e) {
                plugin.getLogger().warning("无法保存合并后的配置: " + fileName);
            }
        }

        userConfig.setDefaults(jarDefaults);
        return userConfig;
    }

    /**
     * 递归合并默认值到用户配置（用户已有 key 不覆盖）。
     * @return 新增的 key 数量
     */
    private int mergeDefaults(FileConfiguration user, FileConfiguration defaults, String path) {
        ConfigurationSection defaultSection = path.isEmpty() ? defaults : defaults.getConfigurationSection(path);
        if (defaultSection == null) {
            return 0;
        }

        int added = 0;
        for (String key : defaultSection.getKeys(false)) {
            String fullKey = path.isEmpty() ? key : path + "." + key;
            Object defaultValue = defaults.get(fullKey);
            if (defaultValue instanceof ConfigurationSection) {
                if (!user.contains(fullKey)) {
                    user.createSection(fullKey);
                    added++;
                } else if (!user.isConfigurationSection(fullKey)) {
                    continue;
                }
                added += mergeDefaults(user, defaults, fullKey);
            } else if (defaultValue != null && !user.contains(fullKey)) {
                user.set(fullKey, defaultValue);
                added++;
            }
        }
        return added;
    }

    /**
     * 保存配置到文件。
     */
    private void saveRawResource(String fileName, File file) throws IOException {
        file.getParentFile().mkdirs();
        try (InputStream in = plugin.getResource(fileName)) {
            if (in == null) {
                throw new FileNotFoundException(fileName);
            }
            try (OutputStream out = new FileOutputStream(file)) {
                in.transferTo(out);
            }
        }
    }

    public void saveConfig(FileConfiguration config, String fileName) {
        File file = new File(plugin.getDataFolder(), fileName);
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("无法保存配置文件: " + fileName + " - " + e.getMessage());
        }
    }

    public File getFile(String fileName) {
        return new File(plugin.getDataFolder(), fileName);
    }
}
