package com.alemcexchange;

import com.alemcexchange.command.CommandManager;
import com.alemcexchange.config.ConfigManager;
import com.alemcexchange.database.DatabaseManager;
import com.alemcexchange.listener.BlockBreakListener;
import com.alemcexchange.listener.PlayerPickupItemListener;
import com.alemcexchange.placeholder.ALEMCExchangeExpansion;
import com.alemcexchange.util.MenuManager;
import org.bukkit.plugin.java.JavaPlugin;

public class PluginInitializer {

    private final JavaPlugin plugin;

    public PluginInitializer(JavaPlugin plugin) {
        this.plugin = plugin;
        initialize();
    }

    private void initialize() {
        // 初始化配置管理
        ConfigManager configManager = new ConfigManager(plugin);
        configManager.loadConfig();

        // 初始化数据库
        DatabaseManager databaseManager = new DatabaseManager(plugin, configManager);
        databaseManager.initialize();

        // 初始化菜单管理
        MenuManager menuManager = new MenuManager(plugin, configManager, databaseManager);

        // 初始化监听器
        BlockBreakListener blockBreakListener = new BlockBreakListener(plugin, configManager, databaseManager);
        PlayerPickupItemListener playerPickupItemListener = new PlayerPickupItemListener(plugin, configManager, databaseManager);

        // 设置 MenuManager 引用（用于缓存清理）
        blockBreakListener.setMenuManager(menuManager);
        playerPickupItemListener.setMenuManager(menuManager);

        // 初始化命令管理
        CommandManager commandManager = new CommandManager(plugin, configManager, databaseManager, menuManager, playerPickupItemListener);
        commandManager.registerCommands();

        plugin.getServer().getPluginManager().registerEvents(blockBreakListener, plugin);
        plugin.getServer().getPluginManager().registerEvents(playerPickupItemListener, plugin);

        // 初始化PlaceholderAPI扩展
        if (plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            ALEMCExchangeExpansion expansion = new ALEMCExchangeExpansion(plugin, databaseManager);
            expansion.register();
            plugin.getLogger().info("PlaceholderAPI expansion registered!");
        }

        plugin.getLogger().info("ALEMCExchange Plugin initialized successfully!");
    }

}
