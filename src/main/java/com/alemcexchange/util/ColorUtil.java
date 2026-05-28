package com.alemcexchange.util;

import org.bukkit.ChatColor;

public class ColorUtil {

    /**
     * 自动识别并转换颜色代码，支持所有MC颜色格式
     * @param text 包含颜色代码的文本
     * @return 转换后的文本
     */
    public static String translateColorCodes(String text) {
        if (text == null) {
            return null;
        }
        
        // 处理 & 前缀的颜色代码（Bukkit/Spigot常用格式）
        text = ChatColor.translateAlternateColorCodes('&', text);
        
        // 处理 § 前缀的颜色代码（Minecraft原始格式）
        text = ChatColor.translateAlternateColorCodes('§', text);
        
        // 处理 &# 格式的颜色代码（HEX颜色）
        text = translateHexColorCodes(text);
        
        return text;
    }
    
    /**
     * 转换HEX颜色代码（&#RRGGBB格式）
     * @param text 包含HEX颜色代码的文本
     * @return 转换后的文本
     */
    private static String translateHexColorCodes(String text) {
        if (text == null) {
            return null;
        }
        
        // 检查是否支持HEX颜色（Minecraft 1.16+）
        try {
            Class.forName("org.bukkit.ChatColor");
            // 简单的HEX颜色转换实现
            // 注意：完整的HEX颜色支持需要更复杂的实现
            // 这里我们只处理基本的 &#RRGGBB 格式
            text = text.replaceAll("&#([0-9a-fA-F]{6})", "§x§$1".replaceAll("(..)", "§$1"));
        } catch (ClassNotFoundException e) {
            // 忽略，不支持HEX颜色
        }
        
        return text;
    }
    
    /**
     * 清理颜色代码，返回纯文本
     * @param text 包含颜色代码的文本
     * @return 清理后的纯文本
     */
    public static String stripColorCodes(String text) {
        if (text == null) {
            return null;
        }
        return ChatColor.stripColor(text);
    }
}
