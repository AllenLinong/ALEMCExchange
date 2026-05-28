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
                default:
                    return "";
            }
        } catch (SQLException | ClassNotFoundException e) {
            plugin.getLogger().severe("Error in placeholder expansion: " + e.getMessage());
            return "";
        }
    }

}
