package com.alemcexchange;

import com.alemcexchange.command.CommandManager;
import com.alemcexchange.config.ConfigManager;
import com.alemcexchange.database.DatabaseManager;
import com.alemcexchange.listener.BlockBreakListener;
import com.alemcexchange.listener.PlayerPickupItemListener;
import com.alemcexchange.placeholder.ALEMCExchangeExpansion;
import com.alemcexchange.util.MenuManager;
import com.alemcexchange.util.SchedulerUtil;
import org.bukkit.plugin.java.JavaPlugin;

public class PluginInitializer {

    private final JavaPlugin plugin;
    private DatabaseManager databaseManager;
    private SchedulerUtil schedulerUtil;

    public PluginInitializer(JavaPlugin plugin) {
        this.plugin = plugin;
        initialize();
    }

    private void initialize() {
        ConfigManager configManager = new ConfigManager(plugin);
        configManager.loadConfig();

        databaseManager = new DatabaseManager(plugin, configManager);
        databaseManager.initialize();

        schedulerUtil = new SchedulerUtil(plugin);

        MenuManager menuManager = new MenuManager(plugin, configManager, databaseManager);

        BlockBreakListener blockBreakListener = new BlockBreakListener(plugin, configManager, databaseManager);
        PlayerPickupItemListener playerPickupItemListener = new PlayerPickupItemListener(plugin, configManager, databaseManager);

        blockBreakListener.setMenuManager(menuManager);
        playerPickupItemListener.setMenuManager(menuManager);

        CommandManager commandManager = new CommandManager(plugin, configManager, databaseManager, menuManager, playerPickupItemListener);
        commandManager.registerCommands();

        plugin.getServer().getPluginManager().registerEvents(blockBreakListener, plugin);
        plugin.getServer().getPluginManager().registerEvents(playerPickupItemListener, plugin);

        if (plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            ALEMCExchangeExpansion expansion = new ALEMCExchangeExpansion(plugin, configManager, databaseManager);
            expansion.register();
            plugin.getLogger().info("PlaceholderAPI expansion registered!");
        }

        // 启动每日EMC记录清理任务（每30分钟检查一次）
        startDailyCleanupTask();

        plugin.getLogger().info("ALEMCExchange Plugin initialized successfully!");
    }

    /**
     * 启动每日EMC记录清理任务，定期清理过期记录
     */
    private void startDailyCleanupTask() {
        schedulerUtil.runTimerAsync(() -> {
            try {
                databaseManager.cleanupOldDailyEMCRecords();
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to cleanup daily EMC records: " + e.getMessage());
            }
        }, 30 * 60 * 20L, 30 * 60 * 20L); // 每30分钟执行一次
    }

    public void shutdown() {
        if (schedulerUtil != null) {
            schedulerUtil.cancelTasks();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

}
