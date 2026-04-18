package com.alemcexchange.placeholder;

import com.alemcexchange.database.DatabaseManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;

public class ALEMCExchangeExpansion extends PlaceholderExpansion {

    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;

    public ALEMCExchangeExpansion(JavaPlugin plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
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

        try {
            switch (identifier) {
                case "balance":
                    double balance = databaseManager.getEMCBalance(player.getUniqueId());
                    return String.format("%.2f", balance);
                case "balance_int":
                    double balanceInt = databaseManager.getEMCBalance(player.getUniqueId());
                    return String.valueOf((int) balanceInt);
                case "autosell":
                    boolean autoSellEnabled = databaseManager.isAutoSellEnabled(player.getUniqueId());
                    return autoSellEnabled ? "开启" : "关闭";
                case "autosell_bool":
                    boolean autoSellBool = databaseManager.isAutoSellEnabled(player.getUniqueId());
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
