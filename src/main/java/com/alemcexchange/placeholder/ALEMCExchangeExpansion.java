package com.alemcexchange.placeholder;

import com.alemcexchange.config.ConfigManager;
import com.alemcexchange.database.DatabaseManager;
import com.alemcexchange.util.SchedulerUtil;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

public class ALEMCExchangeExpansion extends PlaceholderExpansion {

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final DatabaseManager databaseManager;
    private final SchedulerUtil scheduler;
    private final ConcurrentHashMap<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final Set<UUID> refreshing = ConcurrentHashMap.newKeySet();
    private final Semaphore refreshSlots = new Semaphore(4);

    public ALEMCExchangeExpansion(JavaPlugin plugin, ConfigManager configManager, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.databaseManager = databaseManager;
        this.scheduler = new SchedulerUtil(plugin);
        scheduler.runTimerAsync(() -> {
            long cutoff = System.currentTimeMillis() - 60_000L;
            snapshots.entrySet().removeIf(entry -> entry.getValue().expiresAt() < cutoff);
        }, 1200L, 1200L);
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
        switch (identifier) {
            case "balance", "balance_int", "autosell", "autosell_bool", "daily_earned", "daily_earned_int",
                 "daily_limit", "daily_remaining" -> { }
            default -> { return ""; }
        }

        boolean dailyEnabled = configManager.isDailyLimitEnabled();
        if (!dailyEnabled && identifier.startsWith("daily_")) {
            return identifier.equals("daily_earned_int") ? "0" : "0.00";
        }
        Snapshot cached = snapshots.get(uuid);
        long now = System.currentTimeMillis();
        if (!identifier.equals("daily_limit") && (cached == null || cached.expiresAt() <= now
                || (dailyEnabled && !cached.includesDaily()))) {
            refresh(uuid, dailyEnabled);
        }
        double earned = cached == null ? 0 : cached.earned();
        double balance = cached == null ? 0 : cached.balance();
        boolean autoSell = cached != null && cached.autoSell();
        return switch (identifier) {
            case "balance" -> String.format("%.2f", balance);
            case "balance_int" -> String.valueOf((int) balance);
            case "autosell" -> autoSell
                    ? configManager.getLang().getString("placeholder.autosell.enabled", "开启")
                    : configManager.getLang().getString("placeholder.autosell.disabled", "关闭");
            case "autosell_bool" -> String.valueOf(autoSell);
            case "daily_earned" -> String.format("%.2f", earned);
            case "daily_earned_int" -> String.valueOf((int) earned);
            case "daily_limit", "daily_remaining" -> {
                Player player = org.bukkit.Bukkit.getPlayer(uuid);
                if (player == null) yield "0.00";
                double limit = configManager.getDailyLimitForPlayer(player);
                if (limit == -1) yield configManager.getLang().getString("placeholder.daily_limit.unlimited", "无限");
                yield String.format("%.2f", identifier.equals("daily_limit") ? limit : Math.max(0, limit - earned));
            }
            default -> "";
        };
    }

    private void refresh(UUID uuid, boolean dailyEnabled) {
        if (!refreshSlots.tryAcquire()) return;
        if (!refreshing.add(uuid)) {
            refreshSlots.release();
            return;
        }
        boolean scheduled = false;
        try {
            scheduled = scheduler.runAsync(() -> {
                try {
                    String date = dailyEnabled ? databaseManager.getEffectiveDate() : null;
                    var data = databaseManager.getPlayerDisplayData(uuid, date);
                    snapshots.put(uuid, new Snapshot(data.balance(), data.autoSell(), data.earned(), dailyEnabled,
                            System.currentTimeMillis() + 2000L));
                } catch (SQLException | ClassNotFoundException | RuntimeException e) {
                    snapshots.compute(uuid, (key, old) -> new Snapshot(old == null ? 0 : old.balance(),
                            old != null && old.autoSell(), old == null ? 0 : old.earned(),
                            dailyEnabled, System.currentTimeMillis() + 5000L));
                    plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not refresh EMC placeholders", e);
                } finally {
                    refreshing.remove(uuid);
                    refreshSlots.release();
                }
            });
        } finally {
            if (!scheduled) {
                refreshing.remove(uuid);
                refreshSlots.release();
            }
        }
    }

    private record Snapshot(double balance, boolean autoSell, double earned, boolean includesDaily, long expiresAt) {}
}
