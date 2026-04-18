package com.alemcexchange.command;

import com.alemcexchange.config.ConfigManager;
import com.alemcexchange.database.DatabaseManager;
import com.alemcexchange.listener.PlayerPickupItemListener;
import com.alemcexchange.util.ColorUtil;
import com.alemcexchange.util.MenuManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class CommandManager implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final DatabaseManager databaseManager;
    private final MenuManager menuManager;
    private final PlayerPickupItemListener playerPickupItemListener;

    public CommandManager(JavaPlugin plugin, ConfigManager configManager, DatabaseManager databaseManager, MenuManager menuManager, PlayerPickupItemListener playerPickupItemListener) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.databaseManager = databaseManager;
        this.menuManager = menuManager;
        this.playerPickupItemListener = playerPickupItemListener;
    }

    public void registerCommands() {
        plugin.getCommand("alemcexchange").setExecutor(this);
        plugin.getCommand("alemcexchange").setTabCompleter(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player)) {
                sendMessage(sender, "command.player-only");
                return true;
            }
            Player player = (Player) sender;
            menuManager.openMainMenu(player);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        // 管理员命令可以从后台执行
        if (subCommand.equals("reload") || subCommand.equals("give") || subCommand.equals("unlockall") || subCommand.equals("set") || subCommand.equals("unlock")) {
            if (!(sender instanceof Player)) {
                // 后台执行管理员命令
                switch (subCommand) {
                    case "reload":
                        if (sender.hasPermission("alemcexchange.admin")) {
                            configManager.reloadConfig();
                            sendMessage(sender, "command.reload");
                        } else {
                            sendMessage(sender, "command.no-permission");
                        }
                        break;
                    case "give":
                        if (sender.hasPermission("alemcexchange.admin")) {
                            handleGiveCommandForConsole(sender, args);
                        } else {
                            sendMessage(sender, "command.no-permission");
                        }
                        break;
                    case "unlockall":
                        if (sender.hasPermission("alemcexchange.admin")) {
                            handleUnlockAllCommandForConsole(sender, args);
                        } else {
                            sendMessage(sender, "command.no-permission");
                        }
                        break;
                    case "unlock":
                        if (sender.hasPermission("alemcexchange.admin")) {
                            handleUnlockCommandForConsole(sender, args);
                        } else {
                            sendMessage(sender, "command.no-permission");
                        }
                        break;
                    case "set":
                        if (sender.hasPermission("alemcexchange.admin")) {
                            handleSetCommandForConsole(sender, args);
                        } else {
                            sendMessage(sender, "command.no-permission");
                        }
                        break;
                }
                return true;
            }
        }

        // 其他命令需要玩家执行
        if (!(sender instanceof Player)) {
            sendMessage(sender, "command.player-only");
            return true;
        }

        Player player = (Player) sender;

        switch (subCommand) {
            case "help":
                showHelp(player);
                break;
            case "sell":
                handleMenuCommand(player, "alemcexchange.use", () -> menuManager.openSellMenu(player));
                break;
            case "exchange":
                handleMenuCommand(player, "alemcexchange.use", () -> menuManager.openExchangeMenu(player));
                break;
            case "browse":
                handleMenuCommand(player, "alemcexchange.use", () -> menuManager.openBrowseMenu(player));
                break;
            case "balance":
                handleBalanceCommand(player);
                break;
            case "autosell":
                handleAutoSellCommand(player);
                break;
            case "pay":
            case "transfer":
                handleTransferCommand(player, args);
                break;
            case "reload":
                handleAdminCommand(player, "alemcexchange.admin", () -> {
                    configManager.reloadConfig();
                    sendMessage(player, "command.reload");
                });
                break;
            case "give":
                handleGiveCommand(player, args);
                break;
            case "unlockall":
                handleUnlockAllCommand(player, args);
                break;
            case "unlock":
                handleUnlockCommand(player, args);
                break;
            case "set":
                handleSetCommand(player, args);
                break;
            default:
                sendMessage(player, "command.unknown");
                break;
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            String[] commands = {"help", "sell", "exchange", "browse", "balance", "autosell", "pay", "transfer", "reload", "give", "unlockall", "unlock", "set"};
            for (String cmd : commands) {
                if (cmd.startsWith(args[0].toLowerCase())) {
                    completions.add(cmd);
                }
            }
        } else if (args.length == 2) {
            String subCommand = args[0].toLowerCase();
            if (subCommand.equals("pay") || subCommand.equals("transfer") || subCommand.equals("give") || subCommand.equals("unlockall") || subCommand.equals("unlock") || subCommand.equals("set")) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getName().startsWith(args[1])) {
                        completions.add(player.getName());
                    }
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("unlock")) {
            // 为 unlock 命令添加物品ID补全
            if (configManager.getItems().contains("items")) {
                Set<String> materialNames = configManager.getItems().getConfigurationSection("items").getKeys(false);
                for (String material : materialNames) {
                    if (material.toLowerCase().startsWith(args[2].toLowerCase())) {
                        completions.add(material);
                    }
                }
            }
        }

        return completions;
    }

    private void showHelp(Player player) {
        player.sendMessage(ColorUtil.translateColorCodes(configManager.getLang().getString("help.title")));
        player.sendMessage(ColorUtil.translateColorCodes(configManager.getLang().getString("help.main")));
        player.sendMessage(ColorUtil.translateColorCodes(configManager.getLang().getString("help.help")));
        player.sendMessage(ColorUtil.translateColorCodes(configManager.getLang().getString("help.sell")));
        player.sendMessage(ColorUtil.translateColorCodes(configManager.getLang().getString("help.exchange")));
        player.sendMessage(ColorUtil.translateColorCodes(configManager.getLang().getString("help.browse")));
        player.sendMessage(ColorUtil.translateColorCodes(configManager.getLang().getString("help.balance")));
        player.sendMessage(ColorUtil.translateColorCodes(configManager.getLang().getString("help.autosell")));
        player.sendMessage(ColorUtil.translateColorCodes(configManager.getLang().getString("help.pay")));
        if (player.hasPermission("alemcexchange.admin")) {
            player.sendMessage(ColorUtil.translateColorCodes(configManager.getLang().getString("help.reload")));
            player.sendMessage(ColorUtil.translateColorCodes(configManager.getLang().getString("help.give")));
            player.sendMessage(ColorUtil.translateColorCodes(configManager.getLang().getString("help.set")));
            player.sendMessage(ColorUtil.translateColorCodes(configManager.getLang().getString("help.unlockall")));
        }
        player.sendMessage(ColorUtil.translateColorCodes(configManager.getLang().getString("help.footer")));
    }

    private void sendMessage(Player player, String key) {
        player.sendMessage(ColorUtil.translateColorCodes(configManager.getLang().getString("prefix") + configManager.getLang().getString(key)));
    }

    private void sendMessage(CommandSender sender, String key) {
        sender.sendMessage(ColorUtil.translateColorCodes(configManager.getLang().getString("prefix") + configManager.getLang().getString(key)));
    }

    private void sendMessage(CommandSender sender, String key, String... replacements) {
        String message = configManager.getLang().getString("prefix") + configManager.getLang().getString(key);
        for (int i = 0; i < replacements.length; i += 2) {
            message = message.replace("{" + replacements[i] + "}", replacements[i + 1]);
        }
        sender.sendMessage(ColorUtil.translateColorCodes(message));
    }

    private void sendMessage(Player player, String key, String... replacements) {
        String message = configManager.getLang().getString("prefix") + configManager.getLang().getString(key);
        for (int i = 0; i < replacements.length; i += 2) {
            message = message.replace("{" + replacements[i] + "}", replacements[i + 1]);
        }
        player.sendMessage(ColorUtil.translateColorCodes(message));
    }

    private boolean checkPermission(Player player, String permission) {
        if (!player.hasPermission(permission)) {
            sendMessage(player, "command.no-permission");
            return false;
        }
        return true;
    }

    private void handleMenuCommand(Player player, String permission, Runnable action) {
        if (checkPermission(player, permission)) {
            action.run();
        }
    }

    private void handleAdminCommand(Player player, String permission, Runnable action) {
        if (checkPermission(player, permission)) {
            action.run();
        }
    }

    private void handleBalanceCommand(Player player) {
        if (!checkPermission(player, "alemcexchange.use")) {
            return;
        }

        try {
            double balance = databaseManager.getEMCBalance(player.getUniqueId());
            sendMessage(player, "command.balance", "balance", String.format("%.2f", balance));
        } catch (SQLException | ClassNotFoundException e) {
            sendMessage(player, "command.balance-failed");
            plugin.getLogger().severe("Error getting balance: " + e.getMessage());
        }
    }

    private void handleAutoSellCommand(Player player) {
        if (!checkPermission(player, "alemcexchange.autosell")) {
            return;
        }

        try {
            boolean currentState = databaseManager.isAutoSellEnabled(player.getUniqueId());
            boolean newState = !currentState;
            databaseManager.setAutoSellEnabled(player.getUniqueId(), newState);
            playerPickupItemListener.refreshAutoSellCache();
            String message = configManager.getLang().getString("prefix") + 
                    configManager.getLang().getString("autosell." + (newState ? "enabled" : "disabled"));
            player.sendMessage(ColorUtil.translateColorCodes(message));
        } catch (SQLException | ClassNotFoundException e) {
            sendMessage(player, "command.operation-failed");
            plugin.getLogger().severe("Error toggling autosell: " + e.getMessage());
        }
    }

    private void handleTransferCommand(Player player, String[] args) {
        if (!checkPermission(player, "alemcexchange.use")) {
            return;
        }

        if (args.length < 3) {
            sendMessage(player, "command.transfer.usage");
            return;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sendMessage(player, "command.player-not-found");
            return;
        }

        if (target == player) {
            sendMessage(player, "command.transfer.self");
            return;
        }

        try {
            double amount = Double.parseDouble(args[2]);
            if (amount <= 0) {
                sendMessage(player, "command.invalid-amount");
                return;
            }

            if (!databaseManager.hasSufficientEMC(player.getUniqueId(), amount)) {
                sendMessage(player, "command.insufficient-funds");
                return;
            }

            double taxRate = configManager.getTaxRate("alemcexchange.notax");
            if (!player.hasPermission("alemcexchange.notax")) {
                if (player.hasPermission("alemcexchange.premium")) {
                    taxRate = configManager.getTaxRate("alemcexchange.premium");
                } else if (player.hasPermission("alemcexchange.vip")) {
                    taxRate = configManager.getTaxRate("alemcexchange.vip");
                } else {
                    taxRate = configManager.getConfig().getDouble("sell_tax", 0.05);
                }
            }

            double tax = amount * taxRate;
            double netAmount = amount - tax;

            databaseManager.addEMCBalance(player.getUniqueId(), -amount);
            databaseManager.addEMCBalance(target.getUniqueId(), netAmount);
            // 清理双方玩家的缓存
            menuManager.clearPlayerCache(player.getUniqueId());
            menuManager.clearPlayerCache(target.getUniqueId());

            sendMessage(player, "command.transfer.success", 
                    "amount", String.format("%.2f", amount),
                    "player", target.getName(),
                    "netAmount", String.format("%.2f", netAmount),
                    "tax", String.format("%.2f", tax));

            sendMessage(target, "command.transfer.received",
                    "player", player.getName(),
                    "amount", String.format("%.2f", netAmount));
        } catch (NumberFormatException e) {
            sendMessage(player, "command.invalid-amount");
        } catch (SQLException | ClassNotFoundException e) {
            sendMessage(player, "command.operation-failed");
            plugin.getLogger().severe("Error transferring EMC: " + e.getMessage());
        }
    }

    private void handleGiveCommand(Player player, String[] args) {
        if (!checkPermission(player, "alemcexchange.admin")) {
            return;
        }

        if (args.length < 3) {
            sendMessage(player, "command.give.usage");
            return;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sendMessage(player, "command.player-not-found");
            return;
        }

        try {
            double amount = Double.parseDouble(args[2]);
            if (amount <= 0) {
                sendMessage(player, "command.invalid-amount");
                return;
            }

            databaseManager.addEMCBalance(target.getUniqueId(), amount);
            // 清理目标玩家的缓存
            menuManager.clearPlayerCache(target.getUniqueId());
            sendMessage(player, "command.give.success", "player", target.getName(), "amount", String.format("%.2f", amount));
        } catch (NumberFormatException e) {
            sendMessage(player, "command.invalid-amount");
        } catch (SQLException | ClassNotFoundException e) {
            sendMessage(player, "command.operation-failed");
            plugin.getLogger().severe("Error giving EMC: " + e.getMessage());
        }
    }

    private void handleUnlockAllCommand(Player player, String[] args) {
        if (!checkPermission(player, "alemcexchange.admin")) {
            return;
        }

        if (args.length < 2) {
            sendMessage(player, "command.unlockall.usage");
            return;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sendMessage(player, "command.player-not-found");
            return;
        }

        // 立即发送消息，不等待数据库操作完成
        sendMessage(player, "command.unlockall.success", "player", target.getName());

        // 异步执行数据库操作
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                databaseManager.unlockAllItems(target.getUniqueId());
                // 清理目标玩家的缓存
                menuManager.clearPlayerCache(target.getUniqueId());
            } catch (SQLException | ClassNotFoundException e) {
                plugin.getLogger().severe("Error unlocking all items: " + e.getMessage());
            }
        });
    }

    private void handleSetCommand(Player player, String[] args) {
        if (!checkPermission(player, "alemcexchange.admin")) {
            return;
        }

        if (args.length < 3) {
            sendMessage(player, "command.set.usage");
            return;
        }

        Player setTarget = Bukkit.getPlayer(args[1]);
        if (setTarget == null) {
            sendMessage(player, "command.player-not-found");
            return;
        }

        try {
            double amount = Double.parseDouble(args[2]);
            if (amount < 0) {
                sendMessage(player, "command.invalid-amount");
                return;
            }

            databaseManager.setEMCBalance(setTarget.getUniqueId(), amount);
            // 清理目标玩家的缓存
            menuManager.clearPlayerCache(setTarget.getUniqueId());
            sendMessage(player, "command.set.success", "player", setTarget.getName(), "amount", String.format("%.2f", amount));
        } catch (NumberFormatException e) {
            sendMessage(player, "command.invalid-amount");
        } catch (SQLException | ClassNotFoundException e) {
            sendMessage(player, "command.operation-failed");
            plugin.getLogger().severe("Error setting EMC: " + e.getMessage());
        }
    }

    // 后台执行 give 命令
    private void handleGiveCommandForConsole(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sendMessage(sender, "command.give.usage");
            return;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sendMessage(sender, "command.player-not-found");
            return;
        }

        try {
            double amount = Double.parseDouble(args[2]);
            if (amount <= 0) {
                sendMessage(sender, "command.invalid-amount");
                return;
            }

            databaseManager.addEMCBalance(target.getUniqueId(), amount);
            // 清理目标玩家的缓存
            menuManager.clearPlayerCache(target.getUniqueId());
            sendMessage(sender, "command.give.success", "player", target.getName(), "amount", String.format("%.2f", amount));
        } catch (NumberFormatException e) {
            sendMessage(sender, "command.invalid-amount");
        } catch (SQLException | ClassNotFoundException e) {
            sendMessage(sender, "command.operation-failed");
            plugin.getLogger().severe("Error giving EMC: " + e.getMessage());
        }
    }

    // 后台执行 unlockall 命令
    private void handleUnlockAllCommandForConsole(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendMessage(sender, "command.unlockall.usage");
            return;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sendMessage(sender, "command.player-not-found");
            return;
        }

        // 立即发送消息，不等待数据库操作完成
        sendMessage(sender, "command.unlockall.success", "player", target.getName());

        // 异步执行数据库操作
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                databaseManager.unlockAllItems(target.getUniqueId());
                // 清理目标玩家的缓存
                menuManager.clearPlayerCache(target.getUniqueId());
            } catch (SQLException | ClassNotFoundException e) {
                plugin.getLogger().severe("Error unlocking all items: " + e.getMessage());
            }
        });
    }

    // 后台执行 set 命令
    private void handleSetCommandForConsole(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sendMessage(sender, "command.set.usage");
            return;
        }

        Player setTarget = Bukkit.getPlayer(args[1]);
        if (setTarget == null) {
            sendMessage(sender, "command.player-not-found");
            return;
        }

        try {
            double amount = Double.parseDouble(args[2]);
            if (amount < 0) {
                sendMessage(sender, "command.invalid-amount");
                return;
            }

            databaseManager.setEMCBalance(setTarget.getUniqueId(), amount);
            // 清理目标玩家的缓存
            menuManager.clearPlayerCache(setTarget.getUniqueId());
            sendMessage(sender, "command.set.success", "player", setTarget.getName(), "amount", String.format("%.2f", amount));
        } catch (NumberFormatException e) {
            sendMessage(sender, "command.invalid-amount");
        } catch (SQLException | ClassNotFoundException e) {
            sendMessage(sender, "command.operation-failed");
            plugin.getLogger().severe("Error setting EMC: " + e.getMessage());
        }
    }

    private void handleUnlockCommand(Player player, String[] args) {
        if (!checkPermission(player, "alemcexchange.admin")) {
            return;
        }

        if (args.length < 3) {
            sendMessage(player, "command.unlock.usage");
            return;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sendMessage(player, "command.player-not-found");
            return;
        }

        String material = args[2].toUpperCase();

        // 检查物品是否存在于配置中
        if (!configManager.getItems().contains("items." + material)) {
            sendMessage(player, "command.unlock.invalid-item");
            return;
        }

        // 异步执行数据库操作
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                databaseManager.unlockItem(target.getUniqueId(), material);
                // 清理目标玩家的缓存
                menuManager.clearPlayerCache(target.getUniqueId());
                sendMessage(player, "command.unlock.success", "player", target.getName(), "item", material);
            } catch (SQLException | ClassNotFoundException e) {
                sendMessage(player, "command.operation-failed");
                plugin.getLogger().severe("Error unlocking item: " + e.getMessage());
            }
        });
    }

    // 后台执行 unlock 命令
    private void handleUnlockCommandForConsole(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sendMessage(sender, "command.unlock.usage");
            return;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sendMessage(sender, "command.player-not-found");
            return;
        }

        String material = args[2].toUpperCase();

        // 检查物品是否存在于配置中
        if (!configManager.getItems().contains("items." + material)) {
            sendMessage(sender, "command.unlock.invalid-item");
            return;
        }

        // 异步执行数据库操作
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                databaseManager.unlockItem(target.getUniqueId(), material);
                // 清理目标玩家的缓存
                menuManager.clearPlayerCache(target.getUniqueId());
                sendMessage(sender, "command.unlock.success", "player", target.getName(), "item", material);
            } catch (SQLException | ClassNotFoundException e) {
                sendMessage(sender, "command.operation-failed");
                plugin.getLogger().severe("Error unlocking item: " + e.getMessage());
            }
        });
    }
}
