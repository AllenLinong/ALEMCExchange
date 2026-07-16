package com.alwarp.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * 消息格式化工具。同时支持 & 传统颜色码和 MiniMessage 格式。
 *
 * MiniMessage 示例：
 *   <green>成功</green> <bold>粗体</bold>
 *   <hover:show_text:'<red>详情'>悬停查看</hover>
 *   <click:run_command:/help>点击执行命令</click>
 *
 * 传统格式示例：&a成功 &c&l错误
 */
public final class MessageUtil {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final FileConfiguration messages;
    private final String prefix;

    public MessageUtil(FileConfiguration messages) {
        this.messages = messages;
        this.prefix = colorize(messages.getString("prefix", "&8[&6玩家地标&8] &r"));
    }

    /** 获取格式化后的消息（用于 sendMessage）。 */
    public String get(String key) {
        String msg = messages.getString(key, "&cMissing message: " + key);
        return colorize(msg.replace("%prefix%", prefix));
    }

    /** 获取消息并替换占位符。 */
    public String get(String key, String... replacements) {
        String msg = get(key);
        for (int i = 0; i < replacements.length - 1; i += 2) {
            msg = msg.replace(replacements[i], replacements[i + 1]);
        }
        return msg;
    }

    /** 获取消息并替换一个占位符。 */
    public String get(String key, String placeholder, String value) {
        return get(key).replace(placeholder, value);
    }

    public String getPrefix() { return prefix; }

    public List<String> getList(String key) {
        return messages.getStringList(key).stream()
                .map(line -> colorize(line.replace("%prefix%", prefix)))
                .toList();
    }

    // ── 颜色处理 ──────────────────────────────────────────

    /**
     * 将文本转换为带颜色的 Legacy 字符串。
     * 支持 & 传统颜色码和 MiniMessage 两种格式，自动检测。
     * 前置 §r 关闭 MC 1.21+ 默认斜体。
     */
    public static String colorize(String text) {
        if (text == null) return "";
        // 检测是否包含 MiniMessage 标签
        if (isMiniMessage(text) && !hasLegacyColorCode(text)) {
            return mmToLegacy(text);
        }
        return "§r" + ChatColor.translateAlternateColorCodes('&', text);
    }

    /**
     * 解析 MiniMessage 文本为 Adventure Component（用于 sendMessage 等）。
     */
    public static Component deserialize(String mmText) {
        if (mmText == null || mmText.isEmpty()) return Component.empty();
        // 先把 & 码转为 MiniMessage 标签
        return MINI.deserialize("<!italic>" + legacyToMiniMessage(mmText));
    }

    /**
     * MiniMessage → Legacy § 码（用于 ItemMeta displayName/Lore）。
     */
    public static String mmToLegacy(String mmText) {
        if (mmText == null || mmText.isEmpty()) return "";
        return LEGACY.serialize(MINI.deserialize("<!italic>" + mmText));
    }

    /**
     * 向玩家发送 MiniMessage 格式的消息（无需经过 legacy 中转）。
     */
    public static void send(Player player, String mmText) {
        player.sendMessage(deserialize(mmText));
    }

    // ── 内部方法 ──────────────────────────────────────────

    /** 检测文本是否包含 MiniMessage 尖括号标签。 */
    private static boolean isMiniMessage(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.matches(".*<#[0-9a-f]{6}>.*")) return true;
        if (lower.contains("<hover:")
                || lower.contains("<click:")
                || lower.contains("<gradient:")
                || lower.contains("<rainbow")
                || lower.contains("<transition:")
                || lower.contains("<font:")
                || lower.contains("<keybind:")
                || lower.contains("<translatable:")
                || lower.contains("<selector:")
                || lower.contains("<score:")
                || lower.contains("<nbt:")) {
            return true;
        }

        String[] tags = {
                "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
                "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple",
                "yellow", "white", "bold", "strikethrough", "underlined", "italic",
                "obfuscated", "reset", "newline", "br"
        };
        for (String tag : tags) {
            if (lower.contains("<" + tag + ">") || lower.contains("</" + tag + ">")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLegacyColorCode(String text) {
        for (int i = 0; i < text.length() - 1; i++) {
            if (text.charAt(i) == '&'
                    && "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(text.charAt(i + 1)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将 & 传统颜色码转换为 MiniMessage 标签格式。
     * &a → <green>  &c → <red>  &l → <bold>  etc.
     */
    private static String legacyToMiniMessage(String text) {
        if (text == null) return "";
        return text
                .replace("&0", "<black>")
                .replace("&1", "<dark_blue>")
                .replace("&2", "<dark_green>")
                .replace("&3", "<dark_aqua>")
                .replace("&4", "<dark_red>")
                .replace("&5", "<dark_purple>")
                .replace("&6", "<gold>")
                .replace("&7", "<gray>")
                .replace("&8", "<dark_gray>")
                .replace("&9", "<blue>")
                .replace("&a", "<green>")
                .replace("&b", "<aqua>")
                .replace("&c", "<red>")
                .replace("&d", "<light_purple>")
                .replace("&e", "<yellow>")
                .replace("&f", "<white>")
                .replace("&k", "<obfuscated>")
                .replace("&l", "<bold>")
                .replace("&m", "<strikethrough>")
                .replace("&n", "<underlined>")
                .replace("&o", "<italic>")
                .replace("&r", "<reset>");
    }

    // ── 评分/数字 ──────────────────────────────────────────

    public static String formatRating(double avg) {
        double clamped = Math.max(0.0, Math.min(5.0, avg));
        int fullStars = (int) Math.floor(clamped);
        double fraction = clamped - fullStars;
        boolean halfStar = false;
        if (fraction >= 0.5) {
            fullStars++;
        } else if (fraction > 0.0) {
            halfStar = true;
        }
        if (fullStars > 5) fullStars = 5;
        if (fullStars == 5) halfStar = false;

        StringBuilder sb = new StringBuilder();
        sb.append("§6");
        for (int i = 0; i < fullStars; i++) sb.append("★");
        if (halfStar) sb.append("⯨");
        sb.append("§f(§d").append(formatRatingNumber(clamped)).append("§f)");
        return sb.toString();
    }

    private static String formatRatingNumber(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    public static String formatDouble(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) return String.format("%.0f", v);
        return String.format("%.2f", v);
    }
}
