package com.alemcexchange.placeholder;

import com.alemcexchange.config.ConfigManager;
import com.alemcexchange.database.DatabaseManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.UUID;

public class ALEMCExchangeExpansion extends PlaceholderExpansion {

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final DatabaseManager databaseManager;

    public ALEMCExchangeExpansion(JavaPlugin plugin, ConfigManager configManager, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.databaseManager = databaseManager;
    }

    @Override
    public String getIdentifier() {
        return "alemc";
    }

    @Override
    public String getAuthor() {
        return "ALEMCExchange Team";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public String onPlaceholderRequest(Player player, String identifier) {
        if (player == null) {
            return "";
        }
        return getPlaceholderValue(player.getUniqueId(), identifier);
    }

    @Override
    public String onRequest(OfflinePlayer offlinePlayer, String identifier) {
        if (offlinePlayer == null) {
            return "";
        }
        return getPlaceholderValue(offlinePlayer.getUniqueId(), identifier);
    }

    private String getPlaceholderValue(UUID uuid, String identifier) {
        try {
            switch (identifier) {
                case "balance":
                    double balance = databaseManager.getEMCBalance(uuid);
                    return String.format("%.2f", balance);
                case "balance_int":
                    double balanceInt = databaseManager.getEMCBalance(uuid);
                    return String.valueOf((int) balanceInt);
                case "autosell":
                    boolean autoSellEnabled = databaseManager.isAutoSellEnabled(uuid);
                    String enabledText = configManager.getLang().getString("placeholder.autosell.enabled", "开启");
                    String disabledText = configManager.getLang().getString("placeholder.autosell.disabled", "关闭");
                    return autoSellEnabled ? enabledText : disabledText;
                case "autosell_bool":
                    boolean autoSellBool = databaseManager.isAutoSellEnabled(uuid);
                    return String.valueOf(autoSellBool);
                case "daily_earned":
                    // 今日已获取EMC
                    if (configManager.isDailyLimitEnabled()) {
                        String effectiveDate = databaseManager.getEffectiveDate();
                        double earned = databaseManager.getDailyEMCEarned(uuid, effectiveDate);
                        return String.format("%.2f", earned);
                    }
                    return "0.00";
                case "daily_earned_int":
                    // 今日已获取EMC（整数）
                    if (configManager.isDailyLimitEnabled()) {
                        String effectiveDate = databaseManager.getEffectiveDate();
                        double earnedInt = databaseManager.getDailyEMCEarned(uuid, effectiveDate);
                        return String.valueOf((int) earnedInt);
                    }
                    return "0";
                case "daily_limit":
                    // 每日限额
                    if (configManager.isDailyLimitEnabled()) {
                        org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(uuid);
                        if (player != null) {
                            double limit = configManager.getDailyLimitForPlayer(player);
                            if (limit == -1) {
                                return configManager.getLang().getString("placeholder.daily_limit.unlimited", "无限");
                            }
                            return String.format("%.2f", limit);
                        }
                    }
                    return "0.00";
                case "daily_remaining":
                    // 今日剩余限额
                    if (configManager.isDailyLimitEnabled()) {
                        org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(uuid);
                        if (player != null) {
                            double limit = configManager.getDailyLimitForPlayer(player);
                            if (limit == -1) {
                                return configManager.getLang().getString("placeholder.daily_limit.unlimited", "无限");
                            }
                            String effectiveDate = databaseManager.getEffectiveDate();
                            double earned = databaseManager.getDailyEMCEarned(uuid, effectiveDate);
                            double remaining = Math.max(0, limit - earned);
                            return String.format("%.2f", remaining);
                        }
                    }
                    return "0.00";
                default:
                    return "";
            }
        } catch (SQLException | ClassNotFoundException e) {
            plugin.getLogger().severe("Error in placeholder expansion: " + e.getMessage());
            return "";
        }
    }

}
