package com.alwarp.command;

import com.alwarp.ALwarp;
import com.alwarp.model.Category;
import com.alwarp.model.LandmarkAdmin;
import com.alwarp.model.LandmarkBlacklist;
import com.alwarp.model.Landmark;
import com.alwarp.model.LandmarkPin;
import com.alwarp.model.Rating;
import com.alwarp.model.Transaction;
import com.alwarp.storage.StorageManager;
import com.alwarp.util.MessageUtil;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 涓诲懡浠ゅ鐞嗗櫒 /alwarp銆? */
public class ALwarpCommand implements CommandExecutor, TabCompleter {

    private final ALwarp plugin;

    public ALwarpCommand(ALwarp plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!hasCommandPermission(sender, "alwarp.use")) {
            sender.sendMessage(plugin.getMessageUtil().get("no_permission"));
            return true;
        }

        if (args.length == 0) {
            Player player = requirePlayer(sender);
            if (player == null) return true;
            plugin.getGUIManager().openMenu(player, "main_menu");
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "confirmteleport":
                Player confirmPlayer = requirePlayer(sender);
                if (confirmPlayer == null) return true;
                handleConfirmTeleport(confirmPlayer, args);
                break;
            case "cancelteleport":
                Player cancelPlayer = requirePlayer(sender);
                if (cancelPlayer == null) return true;
                plugin.getGUIManager().getActionHandler().cancelTeleportConfirmation(cancelPlayer);
                break;
            case "help":
                sendHelp(sender, label);
                break;
            case "create":
                Player createPlayer = requirePlayer(sender);
                if (createPlayer == null) return true;
                handleCreate(createPlayer);
                break;
            case "admin":
                Player adminPlayer = requirePlayer(sender);
                if (adminPlayer == null) return true;
                handleAdminMenu(adminPlayer);
                break;
            case "reload":
                handleReload(sender);
                break;
            case "export":
                handleExport(sender, args);
                break;
            case "import":
                handleImport(sender, args);
                break;
            case "clearpin":
            case "pinclear":
                handleClearPin(sender, args, 1);
                break;
            case "pin":
                handlePinCommand(sender, args);
                break;
            case "purge":
                handlePurge(sender, args);
                break;
            default:
                Player player = requirePlayer(sender);
                if (player == null) return true;
                if (isInteger(sub)) {
                    player.sendMessage(withCommandLabel(plugin.getMessageUtil().get("teleport_menu_only"), label));
                } else {
                    plugin.getGUIManager().openMenu(player, "main_menu");
                }
                break;
        }
        return true;
    }

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage(plugin.getMessageUtil().get("player_only"));
        return null;
    }

    private void handleConfirmTeleport(Player player, String[] args) {
        if (args.length < 2 || !isInteger(args[1])) {
            player.sendMessage(plugin.getMessageUtil().get("teleport_confirm_expired"));
            return;
        }
        plugin.getGUIManager().getActionHandler().confirmTeleport(player, Integer.parseInt(args[1]));
    }

    private void sendHelp(CommandSender sender, String label) {
        for (String line : plugin.getMessageUtil().getList("help.user")) {
            sender.sendMessage(withCommandLabel(line, label));
        }
        if (hasCommandPermission(sender, "alwarp.admin")) {
            for (String line : plugin.getMessageUtil().getList("help.admin")) {
                sender.sendMessage(withCommandLabel(line, label));
            }
        }
        if (canClearPins(sender)) {
            sender.sendMessage(withCommandLabel(plugin.getMessageUtil().get("pin_clear_help"), label));
        }
    }

    private String withCommandLabel(String message, String label) {
        if (message == null) return null;
        String command = label == null || label.isBlank() ? "alwarp" : label;
        while (command.startsWith("/")) {
            command = command.substring(1);
        }
        return message.replace("/alwarp", "/" + command);
    }

    private void handleCreate(Player player) {
        if (!player.hasPermission("alwarp.create")) {
            player.sendMessage(plugin.getMessageUtil().get("no_permission"));
            return;
        }
        if (!plugin.isCreateAllowedOnServer(player)) {
            player.sendMessage(plugin.getMessageUtil().get("create_disabled_server"));
            return;
        }
        if (!plugin.canCreateInWorld(player)) {
            player.sendMessage(plugin.getMessageUtil().get("world_restricted"));
            return;
        }
        double cost = plugin.getConfig().getDouble("economy.create_cost", 1000.0);
        int maxLen = Math.max(1, plugin.getConfig().getInt("landmark.max_name_length", 8));
        player.sendMessage(plugin.getMessageUtil().get("create_name_prompt_limited",
                "%cost%", String.format("%.0f", cost),
                "%max%", String.valueOf(maxLen)));
        plugin.getGUIManager().setPendingInput(player.getUniqueId(), "create_name");
    }

    private void handleAdminMenu(Player player) {
        if (!player.hasPermission("alwarp.admin")) {
            player.sendMessage(plugin.getMessageUtil().get("no_permission"));
            return;
        }
        plugin.getGUIManager().openMenu(player, "admin_landmarks");
    }

    private void handleReload(CommandSender sender) {
        if (!hasCommandPermission(sender, "alwarp.admin")) {
            sender.sendMessage(plugin.getMessageUtil().get("no_permission"));
            return;
        }
        plugin.reloadAllConfigs();
        sender.sendMessage(plugin.getMessageUtil().get("reload_started"));
        plugin.getTaxManager().loadConfig();
        plugin.getRatingManager().loadConfig();
        plugin.getCategoryManager().loadAll();
        plugin.getLandmarkManager().refreshCache(() ->
                plugin.getManagerManager().loadAllAsync(() ->
                        plugin.getBlacklistManager().loadAllAsync(() ->
                                plugin.getPinManager().loadAllAsync(() ->
                                        plugin.getFavoritesManager().loadAllAsync(() ->
                                                plugin.getRatingManager().loadStatsAsync(() ->
                                                        sendResult(sender, plugin.getMessageUtil().get("reload_success")))))))
        );
    }

    private void handlePinCommand(CommandSender sender, String[] args) {
        if (!canClearPins(sender)) {
            sender.sendMessage(plugin.getMessageUtil().get("no_permission"));
            return;
        }
        if (args.length >= 2 && "clear".equalsIgnoreCase(args[1])) {
            handleClearPin(sender, args, 2);
            return;
        }
        sender.sendMessage(plugin.getMessageUtil().get("pin_clear_usage"));
    }

    private void handleClearPin(CommandSender sender, String[] args, int slotArgIndex) {
        if (!canClearPins(sender)) {
            sender.sendMessage(plugin.getMessageUtil().get("no_permission"));
            return;
        }
        if (args.length <= slotArgIndex || !isInteger(args[slotArgIndex])) {
            sender.sendMessage(plugin.getMessageUtil().get("pin_clear_usage"));
            return;
        }

        int slotIndex = Integer.parseInt(args[slotArgIndex]);
        if (slotIndex <= 0) {
            sender.sendMessage(plugin.getMessageUtil().get("invalid_number"));
            return;
        }

        plugin.getPinManager().removePinBySlot(slotIndex, result -> {
            boolean removed = Boolean.TRUE.equals(result);
            sendResult(sender, plugin.getMessageUtil().get(
                    removed ? "pin_clear_success" : "pin_clear_empty",
                    "%slot%", String.valueOf(slotIndex)));
        });
    }

    private void handleExport(CommandSender sender, String[] args) {
        if (!hasCommandPermission(sender, "alwarp.export")) {
            sender.sendMessage(plugin.getMessageUtil().get("no_permission"));
            return;
        }
        String fileName = args.length > 1 ? args[1].replace('\\', '/') : java.time.LocalDate.now().toString();
        if (!fileName.endsWith(".yml")) fileName += ".yml";
        if (!fileName.contains("/")) fileName = "exports/" + fileName;

        java.io.File file = new java.io.File(plugin.getDataFolder(), fileName);
        final String exportFileName = fileName;

        plugin.getScheduler().runTaskAsync(() -> {
            java.io.File exportDir = file.getParentFile();
            if (exportDir != null && !exportDir.exists()) exportDir.mkdirs();
            List<Landmark> all = plugin.getLandmarkManager().getCachedLandmarks();

            try {
                org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
                yaml.set("format_version", 5);
                yaml.set("export_date", java.time.LocalDateTime.now().toString());
                yaml.set("landmark_count", all.size());
                List<Map<String, Object>> dataCategories = new ArrayList<>();
                List<Map<String, Object>> dataLandmarks = new ArrayList<>();
                List<Map<String, Object>> dataFavorites = new ArrayList<>();
                List<Map<String, Object>> dataRatings = new ArrayList<>();
                List<Map<String, Object>> dataManagers = new ArrayList<>();
                List<Map<String, Object>> dataBlacklists = new ArrayList<>();
                List<Map<String, Object>> dataPins = new ArrayList<>();
                List<Map<String, Object>> dataTransactions = new ArrayList<>();
                List<Map<String, Object>> dataIncomes = new ArrayList<>();

                List<Category> categories = plugin.getCategoryManager().getAllCategories();
                yaml.set("category_count", categories.size());
                for (int i = 0; i < categories.size(); i++) {
                    Category category = categories.get(i);
                    String path = "categories." + i;
                    yaml.set(path + ".id", category.getId());
                    yaml.set(path + ".name", category.getName());
                    yaml.set(path + ".icon", category.getIcon());
                    yaml.set(path + ".icon_custom_model_data", category.getIconCustomModelData());
                    yaml.set(path + ".icon_plugin_item", category.getIconPluginItem());
                    yaml.set(path + ".color", category.getColor());
                    yaml.set(path + ".is_default", category.isDefault());
                    Map<String, Object> categoryRow = new java.util.LinkedHashMap<>();
                    categoryRow.put("id", category.getId());
                    categoryRow.put("name", category.getName());
                    categoryRow.put("icon", category.getIcon());
                    categoryRow.put("icon_custom_model_data", category.getIconCustomModelData());
                    categoryRow.put("icon_plugin_item", category.getIconPluginItem());
                    categoryRow.put("color", category.getColor());
                    categoryRow.put("is_default", category.isDefault());
                    dataCategories.add(categoryRow);
                }

                int favoriteCount = 0;
                int ratingCount = 0;
                int managerCount = 0;
                int blacklistCount = 0;
                int pinCount = 0;
                int transactionCount = 0;
                int incomeCount = 0;
                Map<UUID, List<Integer>> favorites = plugin.getStorage().getAllFavorites();
                for (Map.Entry<UUID, List<Integer>> entry : favorites.entrySet()) {
                    String playerPath = "favorites." + entry.getKey();
                    yaml.set(playerPath, entry.getValue());
                }
                for (StorageManager.FavoriteSnapshot favorite : plugin.getStorage().getAllFavoriteSnapshots()) {
                    Map<String, Object> favoriteRow = new java.util.LinkedHashMap<>();
                    favoriteRow.put("player_uuid", favorite.playerUuid().toString());
                    favoriteRow.put("landmark_id", favorite.landmarkId());
                    favoriteRow.put("created_at", timestampToString(favorite.createdAt()));
                    dataFavorites.add(favoriteRow);
                    favoriteCount++;
                }

                for (int i = 0; i < all.size(); i++) {
                    Landmark lm = all.get(i);
                    String path = "landmarks." + i;
                    yaml.set(path + ".id", lm.getId());
                    yaml.set(path + ".name", lm.getName());
                    yaml.set(path + ".description", lm.getDescription());
                    yaml.set(path + ".name_color", lm.getNameColor());
                    yaml.set(path + ".description_color", lm.getDescriptionColor());
                    yaml.set(path + ".name_bold", lm.isNameBold());
                    yaml.set(path + ".description_bold", lm.isDescriptionBold());
                    yaml.set(path + ".icon", lm.getIcon());
                    yaml.set(path + ".icon_custom_model_data", lm.getIconCustomModelData());
                    yaml.set(path + ".icon_plugin_item", lm.getIconPluginItem());
                    yaml.set(path + ".icon_data", lm.getIconData());
                    yaml.set(path + ".owner_uuid", lm.getOwnerUuid().toString());
                    yaml.set(path + ".owner_name", lm.getOwnerName());
                    yaml.set(path + ".server_name", lm.getServerName());
                    yaml.set(path + ".world", lm.getWorld());
                    yaml.set(path + ".x", lm.getX());
                    yaml.set(path + ".y", lm.getY());
                    yaml.set(path + ".z", lm.getZ());
                    yaml.set(path + ".category_id", lm.getCategoryId());
                    yaml.set(path + ".price", lm.getPrice());
                    yaml.set(path + ".visit_count", lm.getVisitCount());
                    yaml.set(path + ".weekly_visits", lm.getWeeklyVisits());
                    yaml.set(path + ".is_private", lm.isPrivate());
                    yaml.set(path + ".is_global", lm.isGlobal());
                    yaml.set(path + ".created_at", timestampToString(lm.getCreatedAt()));
                    yaml.set(path + ".updated_at", timestampToString(lm.getUpdatedAt()));
                    Map<String, Object> landmarkRow = new java.util.LinkedHashMap<>();
                    landmarkRow.put("id", lm.getId());
                    landmarkRow.put("name", lm.getName());
                    landmarkRow.put("description", lm.getDescription());
                    landmarkRow.put("name_color", lm.getNameColor());
                    landmarkRow.put("description_color", lm.getDescriptionColor());
                    landmarkRow.put("name_bold", lm.isNameBold());
                    landmarkRow.put("description_bold", lm.isDescriptionBold());
                    landmarkRow.put("icon", lm.getIcon());
                    landmarkRow.put("icon_custom_model_data", lm.getIconCustomModelData());
                    landmarkRow.put("icon_plugin_item", lm.getIconPluginItem());
                    landmarkRow.put("icon_data", lm.getIconData());
                    landmarkRow.put("owner_uuid", lm.getOwnerUuid().toString());
                    landmarkRow.put("owner_name", lm.getOwnerName());
                    landmarkRow.put("server_name", lm.getServerName());
                    landmarkRow.put("world", lm.getWorld());
                    landmarkRow.put("x", lm.getX());
                    landmarkRow.put("y", lm.getY());
                    landmarkRow.put("z", lm.getZ());
                    landmarkRow.put("category_id", lm.getCategoryId());
                    landmarkRow.put("price", lm.getPrice());
                    landmarkRow.put("visit_count", lm.getVisitCount());
                    landmarkRow.put("weekly_visits", lm.getWeeklyVisits());
                    landmarkRow.put("is_private", lm.isPrivate());
                    landmarkRow.put("is_global", lm.isGlobal());
                    landmarkRow.put("created_at", timestampToString(lm.getCreatedAt()));
                    landmarkRow.put("updated_at", timestampToString(lm.getUpdatedAt()));
                    dataLandmarks.add(landmarkRow);

                    List<Rating> ratings = plugin.getStorage().getRatingsByLandmark(lm.getId());
                    for (int j = 0; j < ratings.size(); j++) {
                        Rating rating = ratings.get(j);
                        String ratingPath = path + ".ratings." + j;
                        yaml.set(ratingPath + ".player_uuid", rating.getPlayerUuid().toString());
                        yaml.set(ratingPath + ".player_name", rating.getPlayerName());
                        yaml.set(ratingPath + ".score", rating.getScore());
                        yaml.set(ratingPath + ".comment", rating.getComment());
                        yaml.set(ratingPath + ".created_at", timestampToString(rating.getCreatedAt()));
                        Map<String, Object> ratingRow = new java.util.LinkedHashMap<>();
                        ratingRow.put("landmark_id", lm.getId());
                        ratingRow.put("player_uuid", rating.getPlayerUuid().toString());
                        ratingRow.put("player_name", rating.getPlayerName());
                        ratingRow.put("score", rating.getScore());
                        ratingRow.put("comment", rating.getComment());
                        ratingRow.put("created_at", timestampToString(rating.getCreatedAt()));
                        dataRatings.add(ratingRow);
                        ratingCount++;
                    }

                    List<LandmarkAdmin> managers = plugin.getStorage().getManagersByLandmark(lm.getId());
                    for (int j = 0; j < managers.size(); j++) {
                        LandmarkAdmin manager = managers.get(j);
                        String managerPath = path + ".managers." + j;
                        yaml.set(managerPath + ".player_uuid", manager.getPlayerUuid().toString());
                        yaml.set(managerPath + ".player_name", manager.getPlayerName());
                        yaml.set(managerPath + ".added_at", timestampToString(manager.getAddedAt()));
                        Map<String, Object> managerRow = new java.util.LinkedHashMap<>();
                        managerRow.put("landmark_id", lm.getId());
                        managerRow.put("player_uuid", manager.getPlayerUuid().toString());
                        managerRow.put("player_name", manager.getPlayerName());
                        managerRow.put("added_at", timestampToString(manager.getAddedAt()));
                        dataManagers.add(managerRow);
                        managerCount++;
                    }

                    List<LandmarkBlacklist> blacklists = plugin.getStorage().getBlacklistByLandmark(lm.getId());
                    for (int j = 0; j < blacklists.size(); j++) {
                        LandmarkBlacklist blacklist = blacklists.get(j);
                        String blacklistPath = path + ".blacklists." + j;
                        yaml.set(blacklistPath + ".player_uuid", blacklist.getPlayerUuid().toString());
                        yaml.set(blacklistPath + ".player_name", blacklist.getPlayerName());
                        yaml.set(blacklistPath + ".added_at", timestampToString(blacklist.getAddedAt()));
                        Map<String, Object> blacklistRow = new java.util.LinkedHashMap<>();
                        blacklistRow.put("landmark_id", lm.getId());
                        blacklistRow.put("player_uuid", blacklist.getPlayerUuid().toString());
                        blacklistRow.put("player_name", blacklist.getPlayerName());
                        blacklistRow.put("added_at", timestampToString(blacklist.getAddedAt()));
                        dataBlacklists.add(blacklistRow);
                        blacklistCount++;
                    }

                    List<Transaction> transactions = plugin.getStorage().getTransactionsByLandmark(lm.getId());
                    for (int j = 0; j < transactions.size(); j++) {
                        Transaction transaction = transactions.get(j);
                        String transactionPath = path + ".transactions." + j;
                        yaml.set(transactionPath + ".player_uuid", transaction.getPlayerUuid().toString());
                        yaml.set(transactionPath + ".player_name", transaction.getPlayerName());
                        yaml.set(transactionPath + ".amount", transaction.getAmount());
                        yaml.set(transactionPath + ".tax_amount", transaction.getTaxAmount());
                        yaml.set(transactionPath + ".type", transaction.getType().name());
                        yaml.set(transactionPath + ".created_at", timestampToString(transaction.getCreatedAt()));
                        Map<String, Object> transactionRow = new java.util.LinkedHashMap<>();
                        transactionRow.put("landmark_id", lm.getId());
                        transactionRow.put("player_uuid", transaction.getPlayerUuid().toString());
                        transactionRow.put("player_name", transaction.getPlayerName());
                        transactionRow.put("amount", transaction.getAmount());
                        transactionRow.put("tax_amount", transaction.getTaxAmount());
                        transactionRow.put("type", transaction.getType().name());
                        transactionRow.put("created_at", timestampToString(transaction.getCreatedAt()));
                        dataTransactions.add(transactionRow);
                        transactionCount++;
                    }
                }

                for (StorageManager.IncomeSnapshot income : plugin.getStorage().getAllIncomeSnapshots()) {
                    Map<String, Object> incomeRow = new java.util.LinkedHashMap<>();
                    incomeRow.put("owner_uuid", income.ownerUuid().toString());
                    incomeRow.put("owner_name", income.ownerName());
                    incomeRow.put("amount", income.amount());
                    incomeRow.put("updated_at", timestampToString(income.updatedAt()));
                    dataIncomes.add(incomeRow);
                    incomeCount++;
                }

                for (LandmarkPin pin : plugin.getStorage().getAllPinSnapshots()) {
                    Map<String, Object> pinRow = new java.util.LinkedHashMap<>();
                    pinRow.put("id", pin.getId());
                    pinRow.put("slot_index", pin.getSlotIndex());
                    pinRow.put("landmark_id", pin.getLandmarkId());
                    pinRow.put("buyer_uuid", pin.getBuyerUuid().toString());
                    pinRow.put("buyer_name", pin.getBuyerName());
                    pinRow.put("created_at", timestampToString(pin.getCreatedAt()));
                    pinRow.put("expires_at", timestampToString(pin.getExpiresAt()));
                    dataPins.add(pinRow);
                    pinCount++;
                }

                yaml.set("data.categories", dataCategories);
                yaml.set("data.landmarks", dataLandmarks);
                yaml.set("data.favorites", dataFavorites);
                yaml.set("data.ratings", dataRatings);
                yaml.set("data.managers", dataManagers);
                yaml.set("data.blacklists", dataBlacklists);
                yaml.set("data.pins", dataPins);
                yaml.set("data.transactions", dataTransactions);
                yaml.set("data.incomes", dataIncomes);
                yaml.set("favorite_count", favoriteCount);
                yaml.set("rating_count", ratingCount);
                yaml.set("manager_count", managerCount);
                yaml.set("blacklist_count", blacklistCount);
                yaml.set("pin_count", pinCount);
                yaml.set("transaction_count", transactionCount);
                yaml.set("income_count", incomeCount);

                yaml.save(file);
                sendResult(sender, plugin.getMessageUtil().get("export_success",
                        "%count%", String.valueOf(all.size()),
                        "%file%", exportFileName));
                plugin.getLogger().info(sender.getName() + " exported " + all.size() + " landmarks to " + exportFileName);
            } catch (Exception e) {
                sendResult(sender, plugin.getMessageUtil().get("export_failed"));
                plugin.getLogger().severe("Export failed: " + e.getMessage());
            }
        });
    }

    private void handleImport(CommandSender sender, String[] args) {
        if (!hasCommandPermission(sender, "alwarp.import")) {
            sender.sendMessage(plugin.getMessageUtil().get("no_permission"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(plugin.getMessageUtil().get("import_usage"));
            return;
        }
        String fileName = args[1].replace('\\', '/');
        if (!fileName.endsWith(".yml")) fileName += ".yml";

        java.io.File file = new java.io.File(plugin.getDataFolder(), fileName);
        if (!file.exists() && !fileName.contains("/")) {
            java.io.File exportedFile = new java.io.File(plugin.getDataFolder(), "exports/" + fileName);
            if (exportedFile.exists()) {
                file = exportedFile;
                fileName = "exports/" + fileName;
            }
        }
        if (!file.exists()) {
            sender.sendMessage(plugin.getMessageUtil().get("file_not_found", "%file%", fileName));
            return;
        }

        final java.io.File importFile = file;
        final String importFileName = fileName;

        plugin.getScheduler().runTaskAsync(() -> {
            try {
                org.bukkit.configuration.file.YamlConfiguration yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(importFile);
                int count = yaml.getInt("landmark_count", 0);

                String serverName = plugin.getServerId();
                String defaultIcon = plugin.getConfig().getString("landmark.default_icon", "COMPASS");
                int imported = 0;
                int favoritesImported = 0;
                int ratingsImported = 0;
                int managersImported = 0;
                int blacklistsImported = 0;
                int pinsImported = 0;
                int transactionsImported = 0;
                int incomesImported = 0;

                List<Map<?, ?>> dataCategoryRows = yaml.getMapList("data.categories");
                if (!dataCategoryRows.isEmpty()) {
                    for (Map<?, ?> row : dataCategoryRows) {
                        Category category = categoryFromRow(row);
                        upsertCategory(category);
                    }
                } else {
                for (String key : getSectionKeys(yaml, "categories")) {
                    String path = "categories." + key;
                    Category category = new Category(
                            yaml.getInt(path + ".id"),
                            yaml.getString(path + ".name", "Unnamed"),
                            yaml.getString(path + ".icon", "MAP"),
                            yaml.getString(path + ".color", "&7"),
                            yaml.getBoolean(path + ".is_default", false)
                    );
                    if (yaml.contains(path + ".icon_custom_model_data")) {
                        category.setIconCustomModelData(yaml.getInt(path + ".icon_custom_model_data"));
                    }
                    category.setIconPluginItem(yaml.getString(path + ".icon_plugin_item"));
                    Category existing = plugin.getStorage().getCategoryById(category.getId());
                    if (existing != null) {
                        plugin.getStorage().updateCategory(category);
                    } else {
                        plugin.getStorage().createCategory(category);
                    }
                }
                }
                plugin.getCategoryManager().refreshCache();
                plugin.getStorage().deleteAllPins();

                List<Map<?, ?>> dataLandmarkRows = yaml.getMapList("data.landmarks");
                if (!dataLandmarkRows.isEmpty()) {
                    for (Map<?, ?> row : dataLandmarkRows) {
                        Landmark lm = landmarkFromRow(row, serverName, defaultIcon);
                        Landmark result = importLandmarkAndClearAttached(lm);
                        if (result != null) imported++;
                    }
                } else {
                for (int i = 0; i < count; i++) {
                    String path = "landmarks." + i;
                    if (!yaml.contains(path + ".name")) continue;

                    Landmark lm = new Landmark(
                            yaml.getString(path + ".name"),
                            yaml.getString(path + ".description"),
                            java.util.UUID.fromString(yaml.getString(path + ".owner_uuid")),
                            yaml.getString(path + ".owner_name"),
                            yaml.getString(path + ".server_name", serverName),
                            yaml.getString(path + ".world"),
                            yaml.getInt(path + ".x"),
                            yaml.getInt(path + ".y"),
                            yaml.getInt(path + ".z"),
                            yaml.getInt(path + ".category_id", 1),
                            yaml.getDouble(path + ".price", 0.0),
                            yaml.getString(path + ".icon", defaultIcon)
                    );
                    lm.setId(yaml.getInt(path + ".id", 0));
                    lm.setNameColor(yaml.getString(path + ".name_color"));
                    lm.setDescriptionColor(yaml.getString(path + ".description_color"));
                    lm.setNameBold(yaml.getBoolean(path + ".name_bold", false));
                    lm.setDescriptionBold(yaml.getBoolean(path + ".description_bold", false));
                    if (yaml.contains(path + ".icon_custom_model_data")) {
                        lm.setIconCustomModelData(yaml.getInt(path + ".icon_custom_model_data"));
                    }
                    lm.setIconPluginItem(yaml.getString(path + ".icon_plugin_item"));
                    ConfigurationSection iconData = yaml.getConfigurationSection(path + ".icon_data");
                    if (iconData != null) lm.setIconData(iconData.getValues(false));
                    lm.setVisitCount(yaml.getInt(path + ".visit_count", 0));
                    lm.setWeeklyVisits(yaml.getInt(path + ".weekly_visits", 0));
                    lm.setPrivate(yaml.getBoolean(path + ".is_private", false));
                    lm.setGlobal(yaml.getBoolean(path + ".is_global", true));
                    lm.setCreatedAt(timestampFromString(yaml.getString(path + ".created_at")));
                    lm.setUpdatedAt(timestampFromString(yaml.getString(path + ".updated_at")));

                    Landmark result = plugin.getStorage().importLandmarkSnapshot(lm);
                    if (result == null) continue;
                    imported++;

                    plugin.getStorage().removeFavoritesByLandmark(result.getId());
                    plugin.getStorage().deleteRatingsByLandmark(result.getId());
                    plugin.getStorage().deleteAllManagers(result.getId());
                    plugin.getStorage().deleteAllBlacklists(result.getId());
                    plugin.getStorage().deletePinsByLandmark(result.getId());
                    plugin.getStorage().deleteTransactionsByLandmark(result.getId());

                }
                }

                List<Map<?, ?>> dataRatingRows = yaml.getMapList("data.ratings");
                if (!dataRatingRows.isEmpty()) {
                    for (Map<?, ?> row : dataRatingRows) {
                        if (uuidValue(row, "player_uuid") == null || intValue(row, "landmark_id", 0) <= 0) continue;
                        Rating rating = ratingFromRow(row);
                        if (plugin.getStorage().addRating(rating) != null) ratingsImported++;
                    }
                } else {
                for (int i = 0; i < count; i++) {
                    String path = "landmarks." + i;
                    for (String ratingKey : getSectionKeys(yaml, path + ".ratings")) {
                        String ratingPath = path + ".ratings." + ratingKey;
                        Rating rating = new Rating(
                                yaml.getInt(path + ".id", 0),
                                UUID.fromString(yaml.getString(ratingPath + ".player_uuid")),
                                yaml.getString(ratingPath + ".player_name", "Unknown"),
                                yaml.getInt(ratingPath + ".score", 1),
                                yaml.getString(ratingPath + ".comment")
                        );
                        rating.setCreatedAt(timestampFromString(yaml.getString(ratingPath + ".created_at")));
                        if (plugin.getStorage().addRating(rating) != null) ratingsImported++;
                    }
                }
                }

                List<Map<?, ?>> dataManagerRows = yaml.getMapList("data.managers");
                if (!dataManagerRows.isEmpty()) {
                    for (Map<?, ?> row : dataManagerRows) {
                        if (uuidValue(row, "player_uuid") == null || intValue(row, "landmark_id", 0) <= 0) continue;
                        LandmarkAdmin manager = managerFromRow(row);
                        if (plugin.getStorage().addManager(manager) != null) managersImported++;
                    }
                } else {
                for (int i = 0; i < count; i++) {
                    String path = "landmarks." + i;
                    for (String managerKey : getSectionKeys(yaml, path + ".managers")) {
                        String managerPath = path + ".managers." + managerKey;
                        LandmarkAdmin manager = new LandmarkAdmin(
                                yaml.getInt(path + ".id", 0),
                                UUID.fromString(yaml.getString(managerPath + ".player_uuid")),
                                yaml.getString(managerPath + ".player_name", "Unknown")
                        );
                        manager.setAddedAt(timestampFromString(yaml.getString(managerPath + ".added_at")));
                        if (plugin.getStorage().addManager(manager) != null) managersImported++;
                    }
                }
                }

                List<Map<?, ?>> dataBlacklistRows = yaml.getMapList("data.blacklists");
                if (!dataBlacklistRows.isEmpty()) {
                    for (Map<?, ?> row : dataBlacklistRows) {
                        if (uuidValue(row, "player_uuid") == null || intValue(row, "landmark_id", 0) <= 0) continue;
                        LandmarkBlacklist blacklist = blacklistFromRow(row);
                        if (plugin.getStorage().addBlacklist(blacklist) != null) {
                            plugin.getStorage().removeManager(blacklist.getLandmarkId(), blacklist.getPlayerUuid());
                            blacklistsImported++;
                        }
                    }
                } else {
                for (int i = 0; i < count; i++) {
                    String path = "landmarks." + i;
                    for (String blacklistKey : getSectionKeys(yaml, path + ".blacklists")) {
                        String blacklistPath = path + ".blacklists." + blacklistKey;
                        LandmarkBlacklist blacklist = new LandmarkBlacklist(
                                yaml.getInt(path + ".id", 0),
                                UUID.fromString(yaml.getString(blacklistPath + ".player_uuid")),
                                yaml.getString(blacklistPath + ".player_name", "Unknown")
                        );
                        blacklist.setAddedAt(timestampFromString(yaml.getString(blacklistPath + ".added_at")));
                        if (plugin.getStorage().addBlacklist(blacklist) != null) {
                            plugin.getStorage().removeManager(blacklist.getLandmarkId(), blacklist.getPlayerUuid());
                            blacklistsImported++;
                        }
                    }
                }
                }

                List<Map<?, ?>> dataTransactionRows = yaml.getMapList("data.transactions");
                if (!dataTransactionRows.isEmpty()) {
                    for (Map<?, ?> row : dataTransactionRows) {
                        if (uuidValue(row, "player_uuid") == null || intValue(row, "landmark_id", 0) <= 0) continue;
                        Transaction transaction = transactionFromRow(row);
                        if (plugin.getStorage().createTransaction(transaction) != null) transactionsImported++;
                    }
                } else {
                for (int i = 0; i < count; i++) {
                    String path = "landmarks." + i;
                    for (String transactionKey : getSectionKeys(yaml, path + ".transactions")) {
                        String transactionPath = path + ".transactions." + transactionKey;
                        Transaction transaction = new Transaction(
                                yaml.getInt(path + ".id", 0),
                                UUID.fromString(yaml.getString(transactionPath + ".player_uuid")),
                                yaml.getString(transactionPath + ".player_name", "Unknown"),
                                yaml.getDouble(transactionPath + ".amount", 0.0),
                                yaml.getDouble(transactionPath + ".tax_amount", 0.0),
                                Transaction.Type.valueOf(yaml.getString(transactionPath + ".type", "INCOME"))
                        );
                        transaction.setCreatedAt(timestampFromString(yaml.getString(transactionPath + ".created_at")));
                        if (plugin.getStorage().createTransaction(transaction) != null) transactionsImported++;
                    }
                }
                }

                List<Map<?, ?>> dataFavoriteRows = yaml.getMapList("data.favorites");
                if (!dataFavoriteRows.isEmpty()) {
                    for (Map<?, ?> row : dataFavoriteRows) {
                        UUID playerUuid = uuidValue(row, "player_uuid");
                        int landmarkId = intValue(row, "landmark_id", 0);
                        if (playerUuid != null && landmarkId > 0
                                && importFavoriteIfAllowed(playerUuid, landmarkId, timestampValue(row, "created_at"))) {
                            favoritesImported++;
                        }
                    }
                } else {
                    ConfigurationSection favorites = yaml.getConfigurationSection("favorites");
                    if (favorites != null) {
                    for (String uuidText : favorites.getKeys(false)) {
                        UUID playerUuid = UUID.fromString(uuidText);
                        for (Integer landmarkId : favorites.getIntegerList(uuidText)) {
                            if (importFavoriteIfAllowed(playerUuid, landmarkId, null)) favoritesImported++;
                        }
                    }
                    }
                }

                List<Map<?, ?>> dataIncomeRows = yaml.getMapList("data.incomes");
                for (Map<?, ?> row : dataIncomeRows) {
                    UUID ownerUuid = uuidValue(row, "owner_uuid");
                    double amount = doubleValue(row, "amount", 0.0);
                    if (ownerUuid != null && amount > 0 && plugin.getStorage().importIncomeSnapshot(
                            new StorageManager.IncomeSnapshot(
                                    ownerUuid,
                                    stringValue(row, "owner_name", "Unknown"),
                                    amount,
                                    timestampValue(row, "updated_at")))) {
                        incomesImported++;
                    }
                }

                List<Map<?, ?>> dataPinRows = yaml.getMapList("data.pins");
                for (Map<?, ?> row : dataPinRows) {
                    LandmarkPin pin = pinFromRow(row);
                    if (pin.getSlotIndex() > 0
                            && pin.getLandmarkId() > 0
                            && pin.getBuyerUuid() != null
                            && pin.getExpiresAt() != null
                            && plugin.getStorage().importPinSnapshot(pin) != null) {
                        pinsImported++;
                    }
                }

                int finalImported = imported;
                int finalFavoritesImported = favoritesImported;
                int finalRatingsImported = ratingsImported;
                int finalManagersImported = managersImported;
                int finalBlacklistsImported = blacklistsImported;
                int finalPinsImported = pinsImported;
                int finalTransactionsImported = transactionsImported;
                int finalIncomesImported = incomesImported;
                plugin.getLandmarkManager().refreshCache(() ->
                        plugin.getManagerManager().loadAllAsync(() ->
                                plugin.getBlacklistManager().loadAllAsync(() ->
                                        plugin.getPinManager().loadAllAsync(() ->
                                                plugin.getFavoritesManager().loadAllAsync(() ->
                                                        plugin.getRatingManager().loadStatsAsync(() ->
                                                                plugin.getIncomeManager().loadAllAsync(() -> {
                                            if (plugin.getRedisManager() != null) {
                                                plugin.getRedisManager().broadcastLandmarkRefresh();
                                                plugin.getRedisManager().broadcastIncomeRefresh();
                                            }
                                            sendResult(sender, plugin.getMessageUtil().get("import_success",
                                                    "%count%", String.valueOf(finalImported),
                                                    "%favorites%", String.valueOf(finalFavoritesImported),
                                                    "%ratings%", String.valueOf(finalRatingsImported),
                                                    "%managers%", String.valueOf(finalManagersImported),
                                                    "%blacklists%", String.valueOf(finalBlacklistsImported),
                                                    "%pins%", String.valueOf(finalPinsImported),
                                                    "%transactions%", String.valueOf(finalTransactionsImported),
                                                    "%incomes%", String.valueOf(finalIncomesImported),
                                                    "%file%", importFileName));
                                        })))))));
                plugin.getLogger().info(sender.getName() + " imported " + imported + " landmarks from " + importFileName);
            } catch (Exception e) {
                sendResult(sender, plugin.getMessageUtil().get("import_failed"));
                plugin.getLogger().severe("Import failed: " + e.getMessage());
            }
        });
    }

    private void handlePurge(CommandSender sender, String[] args) {
        if (!hasCommandPermission(sender, "alwarp.admin")) {
            sender.sendMessage(plugin.getMessageUtil().get("no_permission"));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(plugin.getMessageUtil().get("purge_usage"));
            return;
        }

        UUID targetUuid = resolveKnownPlayerUuid(sender, args[2]);
        if (targetUuid == null) return;

        switch (args[1].toLowerCase()) {
            case "landmarks" -> plugin.getLandmarkManager().deleteLandmarksByOwner(targetUuid, result ->
                    plugin.getFavoritesManager().loadAllAsync(() ->
                            plugin.getRatingManager().loadStatsAsync(() ->
                                    sendResult(sender, plugin.getMessageUtil().get("purge_landmarks_success",
                                            "%player%", args[2],
                                            "%count%", String.valueOf(result))))));
            case "records" -> plugin.getFavoritesManager().deleteFavoritesByPlayer(targetUuid, favoriteCount ->
                    plugin.getRatingManager().deleteRatingsByPlayer(targetUuid, ratingCount ->
                            sendResult(sender, plugin.getMessageUtil().get("purge_records_success",
                                    "%player%", args[2],
                                    "%favorites%", String.valueOf(favoriteCount),
                                    "%ratings%", String.valueOf(ratingCount)))));
            default -> sender.sendMessage(plugin.getMessageUtil().get("purge_usage"));
        }
    }

    private UUID resolveKnownPlayerUuid(CommandSender sender, String name) {
        Player online = plugin.getServer().getPlayerExact(name);
        if (online != null) return online.getUniqueId();

        StorageManager.PlayerRecord stored = plugin.getStorage().getPlayerRecordByName(name);
        if (stored != null) return stored.playerUuid();

        sender.sendMessage(plugin.getMessageUtil().get("player_record_not_found", "%player%", name));
        return null;
    }

    private List<String> getSectionKeys(org.bukkit.configuration.file.YamlConfiguration yaml, String path) {
        ConfigurationSection section = yaml.getConfigurationSection(path);
        return section == null ? List.of() : new ArrayList<>(section.getKeys(false));
    }

    private void upsertCategory(Category category) {
        Category existing = plugin.getStorage().getCategoryById(category.getId());
        if (existing != null) {
            plugin.getStorage().updateCategory(category);
        } else {
            plugin.getStorage().createCategory(category);
        }
    }

    private Landmark importLandmarkAndClearAttached(Landmark lm) {
        Landmark result = plugin.getStorage().importLandmarkSnapshot(lm);
        if (result == null) return null;
        plugin.getStorage().removeFavoritesByLandmark(result.getId());
        plugin.getStorage().deleteRatingsByLandmark(result.getId());
        plugin.getStorage().deleteAllManagers(result.getId());
        plugin.getStorage().deleteAllBlacklists(result.getId());
        plugin.getStorage().deletePinsByLandmark(result.getId());
        plugin.getStorage().deleteTransactionsByLandmark(result.getId());
        return result;
    }

    private boolean importFavoriteIfAllowed(UUID playerUuid, int landmarkId, Timestamp createdAt) {
        if (plugin.getStorage().isBlacklisted(landmarkId, playerUuid)) return false;
        return plugin.getStorage().importFavoriteSnapshot(
                new StorageManager.FavoriteSnapshot(playerUuid, landmarkId, createdAt));
    }

    private Category categoryFromRow(Map<?, ?> row) {
        Category category = new Category(
                intValue(row, "id", 0),
                stringValue(row, "name", "Unnamed"),
                stringValue(row, "icon", "MAP"),
                stringValue(row, "color", "&7"),
                booleanValue(row, "is_default", false)
        );
        Integer customModelData = optionalIntValue(row, "icon_custom_model_data");
        if (customModelData != null) category.setIconCustomModelData(customModelData);
        category.setIconPluginItem(optionalStringValue(row, "icon_plugin_item"));
        return category;
    }

    private Landmark landmarkFromRow(Map<?, ?> row, String serverName, String defaultIcon) {
        Landmark lm = new Landmark(
                stringValue(row, "name", "Unnamed"),
                optionalStringValue(row, "description"),
                uuidValue(row, "owner_uuid"),
                stringValue(row, "owner_name", "Unknown"),
                stringValue(row, "server_name", serverName),
                stringValue(row, "world", "world"),
                intValue(row, "x", 0),
                intValue(row, "y", 64),
                intValue(row, "z", 0),
                intValue(row, "category_id", 1),
                doubleValue(row, "price", 0.0),
                stringValue(row, "icon", defaultIcon)
        );
        lm.setId(intValue(row, "id", 0));
        lm.setNameColor(optionalStringValue(row, "name_color"));
        lm.setDescriptionColor(optionalStringValue(row, "description_color"));
        lm.setNameBold(booleanValue(row, "name_bold", false));
        lm.setDescriptionBold(booleanValue(row, "description_bold", false));
        Integer customModelData = optionalIntValue(row, "icon_custom_model_data");
        if (customModelData != null) lm.setIconCustomModelData(customModelData);
        lm.setIconPluginItem(optionalStringValue(row, "icon_plugin_item"));
        Object iconData = row.get("icon_data");
        if (iconData instanceof Map<?, ?> map) {
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) result.put(entry.getKey().toString(), entry.getValue());
            }
            lm.setIconData(result);
        }
        lm.setVisitCount(intValue(row, "visit_count", 0));
        lm.setWeeklyVisits(intValue(row, "weekly_visits", 0));
        lm.setPrivate(booleanValue(row, "is_private", false));
        lm.setGlobal(booleanValue(row, "is_global", true));
        lm.setCreatedAt(timestampValue(row, "created_at"));
        lm.setUpdatedAt(timestampValue(row, "updated_at"));
        return lm;
    }

    private Rating ratingFromRow(Map<?, ?> row) {
        Rating rating = new Rating(
                intValue(row, "landmark_id", 0),
                uuidValue(row, "player_uuid"),
                stringValue(row, "player_name", "Unknown"),
                intValue(row, "score", 1),
                optionalStringValue(row, "comment")
        );
        rating.setCreatedAt(timestampValue(row, "created_at"));
        return rating;
    }

    private LandmarkAdmin managerFromRow(Map<?, ?> row) {
        LandmarkAdmin manager = new LandmarkAdmin(
                intValue(row, "landmark_id", 0),
                uuidValue(row, "player_uuid"),
                stringValue(row, "player_name", "Unknown")
        );
        manager.setAddedAt(timestampValue(row, "added_at"));
        return manager;
    }

    private LandmarkBlacklist blacklistFromRow(Map<?, ?> row) {
        LandmarkBlacklist blacklist = new LandmarkBlacklist(
                intValue(row, "landmark_id", 0),
                uuidValue(row, "player_uuid"),
                stringValue(row, "player_name", "Unknown")
        );
        blacklist.setAddedAt(timestampValue(row, "added_at"));
        return blacklist;
    }

    private LandmarkPin pinFromRow(Map<?, ?> row) {
        LandmarkPin pin = new LandmarkPin();
        pin.setId(intValue(row, "id", 0));
        pin.setSlotIndex(intValue(row, "slot_index", 0));
        pin.setLandmarkId(intValue(row, "landmark_id", 0));
        pin.setBuyerUuid(uuidValue(row, "buyer_uuid"));
        pin.setBuyerName(stringValue(row, "buyer_name", "Unknown"));
        pin.setCreatedAt(timestampValue(row, "created_at"));
        pin.setExpiresAt(timestampValue(row, "expires_at"));
        return pin;
    }

    private Transaction transactionFromRow(Map<?, ?> row) {
        Transaction transaction = new Transaction(
                intValue(row, "landmark_id", 0),
                uuidValue(row, "player_uuid"),
                stringValue(row, "player_name", "Unknown"),
                doubleValue(row, "amount", 0.0),
                doubleValue(row, "tax_amount", 0.0),
                Transaction.Type.valueOf(stringValue(row, "type", "INCOME"))
        );
        transaction.setCreatedAt(timestampValue(row, "created_at"));
        return transaction;
    }

    private String stringValue(Map<?, ?> row, String key, String fallback) {
        String value = optionalStringValue(row, key);
        return value != null ? value : fallback;
    }

    private String optionalStringValue(Map<?, ?> row, String key) {
        Object value = row.get(key);
        return value != null ? value.toString() : null;
    }

    private UUID uuidValue(Map<?, ?> row, String key) {
        String value = optionalStringValue(row, key);
        return value != null && !value.isBlank() ? UUID.fromString(value) : null;
    }

    private int intValue(Map<?, ?> row, String key, int fallback) {
        Object value = row.get(key);
        if (value instanceof Number number) return number.intValue();
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private Integer optionalIntValue(Map<?, ?> row, String key) {
        Object value = row.get(key);
        if (value == null) return null;
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private double doubleValue(Map<?, ?> row, String key, double fallback) {
        Object value = row.get(key);
        if (value instanceof Number number) return number.doubleValue();
        if (value != null) {
            try {
                return Double.parseDouble(value.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private boolean booleanValue(Map<?, ?> row, String key, boolean fallback) {
        Object value = row.get(key);
        if (value instanceof Boolean bool) return bool;
        if (value != null) return Boolean.parseBoolean(value.toString());
        return fallback;
    }

    private Timestamp timestampValue(Map<?, ?> row, String key) {
        Object value = row.get(key);
        if (value instanceof Timestamp timestamp) return timestamp;
        return timestampFromString(value != null ? value.toString() : null);
    }

    private Timestamp timestampFromString(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Timestamp.valueOf(value);
        } catch (IllegalArgumentException ignored) {
        }
        try {
            return Timestamp.from(Instant.parse(value));
        } catch (IllegalArgumentException ignored) {
        }
        try {
            return Timestamp.valueOf(LocalDateTime.parse(value));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String timestampToString(Timestamp timestamp) {
        return timestamp != null ? timestamp.toString() : null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (!hasCommandPermission(sender, "alwarp.use")) return List.of();

        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("help"));
            if (sender instanceof Player) {
                subs.add("create");
            }
            if (hasCommandPermission(sender, "alwarp.admin")) {
                subs.addAll(List.of("reload", "export", "import", "purge"));
                if (sender instanceof Player) {
                    subs.add("admin");
                }
            }
            if (canClearPins(sender)) {
                subs.addAll(List.of("clearpin", "pin"));
            }
            return filterCompletions(subs, args[0]);
        }

        if (args.length == 2) {
            return switch (args[0].toLowerCase()) {
                case "import" -> List.of("[file]");
                case "export" -> List.of("[file]");
                case "clearpin", "pinclear" -> canClearPins(sender) ? completePinSlots(args[1]) : List.of();
                case "pin" -> canClearPins(sender) ? filterCompletions(List.of("clear"), args[1]) : List.of();
                case "purge" -> filterCompletions(List.of("landmarks", "records"), args[1]);
                default -> List.of();
            };
        }

        if (args.length == 3) {
            if (args[0].equalsIgnoreCase("pin") && args[1].equalsIgnoreCase("clear")) {
                return canClearPins(sender) ? completePinSlots(args[2]) : List.of();
            }
            if (args[0].equalsIgnoreCase("purge")) {
                return completePlayerNames(args[2]);
            }
        }

        return List.of();
    }

    private List<String> filterCompletions(List<String> options, String prefix) {
        return options.stream()
                .filter(s -> s.toLowerCase().startsWith(prefix.toLowerCase()))
                .collect(Collectors.toList());
    }

    private boolean hasCommandPermission(CommandSender sender, String permission) {
        return isConsole(sender) || sender.hasPermission(permission);
    }

    private boolean isConsole(CommandSender sender) {
        return sender instanceof ConsoleCommandSender || sender instanceof RemoteConsoleCommandSender;
    }

    private boolean canClearPins(CommandSender sender) {
        return isConsole(sender) || sender.isOp();
    }

    private void sendResult(CommandSender sender, String message) {
        if (sender instanceof Player player) {
            plugin.runAtPlayer(player, () -> player.sendMessage(message));
        } else {
            plugin.getScheduler().runTask(() -> sender.sendMessage(message));
        }
    }

    private List<String> completePinSlots(String prefix) {
        List<String> slots = plugin.getPinManager().getActivePins().stream()
                .map(pin -> String.valueOf(pin.getSlotIndex()))
                .distinct()
                .sorted((a, b) -> Integer.compare(Integer.parseInt(a), Integer.parseInt(b)))
                .collect(Collectors.toCollection(ArrayList::new));
        if (slots.isEmpty()) {
            for (int i = 1; i <= 7; i++) slots.add(String.valueOf(i));
        }
        return filterCompletions(slots, prefix);
    }

    private List<String> completePlayerNames(String prefix) {
        String lower = prefix.toLowerCase();
        List<String> names = new ArrayList<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getName().toLowerCase().startsWith(lower)) names.add(player.getName());
        }
        return names;
    }

    private boolean isInteger(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

}
