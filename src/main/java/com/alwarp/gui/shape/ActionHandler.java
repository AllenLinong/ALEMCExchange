package com.alwarp.gui.shape;

import com.alwarp.ALwarp;
import com.alwarp.gui.GUIManager;
import com.alwarp.model.Landmark;
import com.alwarp.model.LandmarkAdmin;
import com.alwarp.model.LandmarkBlacklist;
import com.alwarp.model.LandmarkPin;
import com.alwarp.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/**
 * Shape动作处理器。
 * 解析菜单项中的action字段，分发到具体处理逻辑。
 */
public class ActionHandler {

    private final ALwarp plugin;
    private final GUIManager guiManager;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    public ActionHandler(ALwarp plugin, GUIManager guiManager) {
        this.plugin = plugin;
        this.guiManager = guiManager;
    }

    /**
     * 处理动作字符串。
     * 格式: "action_type:param" 或 "action_type"
     */
    public void handleAction(Player player, String action) {
        if (action == null || action.isEmpty()) return;

        String[] parts = action.split(":", 2);
        String type = parts[0];
        String param = parts.length > 1 ? parts[1] : "";

        switch (type) {
            case "close":
                player.closeInventory();
                break;
            case "silent-close":
            case "force-close":
                guiManager.closeMenuSilently(player);
                break;
            case "open_gui":
            case "open":
            case "gui":
                handleOpenGUI(player, param);
                break;
            case "command":
            case "cmd":
            case "player":
            case "execute":
                player.performCommand(param);
                break;
            case "console":
                executeConsoleCommands(param);
                break;
            case "op":
            case "operator":
            case "ops":
                executeOpCommands(player, param);
                break;
            case "message":
            case "msg":
            case "tell":
                player.sendMessage(MessageUtil.colorize(param));
                break;
            case "sound":
            case "play-sound":
            case "playsound":
                playConfiguredSound(player, param);
                break;
            case "teleport":
                handleTeleport(player, param);
                break;
            case "search":
                handleSearch(player);
                break;
            case "clear_search":
                handleClearSearch(player);
                break;
            case "filter_toggle":
                handleFilterToggle(player);
                break;
            case "filter":
                handleFilter(player, param);
                break;
            case "prev_page":
                guiManager.prevPage(player);
                break;
            case "next_page":
                guiManager.nextPage(player);
                break;
            case "set_name":
                handleSetName(player);
                break;
            case "open_landmark_color":
                if (!guiManager.isLandmarkColorSystemEnabled()) {
                    Landmark editing = guiManager.getEditingLandmark(player.getUniqueId());
                    if (editing != null) {
                        guiManager.openLandmarkManage(player, editing.getId());
                    }
                    break;
                }
                guiManager.openLandmarkColorSelect(player, param);
                break;
            case "select_landmark_color":
                guiManager.selectLandmarkColor(player, param);
                break;
            case "buy_landmark_color":
                guiManager.buyLandmarkColor(player, param);
                break;
            case "buy_landmark_bold":
                guiManager.buyLandmarkBold(player);
                break;
            case "clear_landmark_color":
                guiManager.clearLandmarkColor(player);
                break;
            case "toggle_landmark_text_bold":
                guiManager.toggleLandmarkTextBold(player);
                break;
            case "set_icon":
                handleSetIcon(player);
                break;
            case "set_description":
                handleSetDescription(player);
                break;
            case "set_category":
                handleSetCategory(player);
                break;
            case "set_price":
                handleSetPrice(player);
                break;
            case "set_location":
                handleSetLocation(player);
                break;
            case "delete_landmark":
                handleDeleteLandmark(player);
                break;
            case "save_landmark":
                handleSaveLandmark(player);
                break;
            case "toggle_private":
                handleTogglePrivate(player);
                break;
            case "create_landmark_chat":
                handleCreateLandmark(player);
                break;
            case "claim_income":
                handleClaimIncome(player);
                break;
            case "toggle_favorite":
                handleToggleFavorite(player, param);
                break;
            case "rate_landmark":
                handleRateLandmark(player, param);
                break;
            case "rate_current_landmark":
                handleRateCurrentLandmark(player);
                break;
            case "view_reviews":
                handleViewReviews(player, param);
                break;
            case "delete_own_review":
                handleDeleteOwnReview(player);
                break;
            case "return_landmark_manage":
                handleReturnLandmarkManage(player);
                break;
            case "open_admin_interface":
                handleOpenAdminInterface(player);
                break;
            case "open_blacklist_interface":
                handleOpenBlacklistInterface(player);
                break;
            case "add_manager":
                handleAddManager(player);
                break;
            case "remove_manager":
                handleRemoveManager(player, param);
                break;
            case "add_blacklist":
                handleAddBlacklist(player);
                break;
            case "remove_blacklist":
                handleRemoveBlacklist(player, param);
                break;
            case "pin_slot":
                handlePinSlot(player, param);
                break;
            case "pin_slot_favorite":
                handlePinSlotFavorite(player, param);
                break;
            case "pin_slot_reviews":
                handlePinSlotReviews(player, param);
                break;
            case "open_pin_landmark_select":
                guiManager.openPinLandmarkSelect(player);
                break;
            case "select_pin_landmark":
                handleSelectPinLandmark(player, param);
                break;
            case "confirm_pin_purchase":
                handleConfirmPinPurchase(player);
                break;
            case "cancel_pin_purchase":
                handleCancelPinPurchase(player);
                break;
        }
    }

    /**
     * 顺序执行 triggers 下配置的多动作列表。
     * 支持 action/open/command/console/op/message/sound/close/silent-close/delay。
     */
    public void handleTriggerActions(Player player, List<String> actions) {
        if (actions == null || actions.isEmpty()) return;
        handleTriggerActions(player, actions, 0);
    }

    private void handleTriggerActions(Player player, List<String> actions, int startIndex) {
        for (int i = startIndex; i < actions.size(); i++) {
            String action = actions.get(i);
            DelayAction delay = parseDelayAction(action);
            if (delay != null) {
                int nextIndex = i + 1;
                if (delay.ticks() <= 0) {
                    continue;
                }
                plugin.getScheduler().runAtEntityLater(player, () -> handleTriggerActions(player, actions, nextIndex), delay.ticks());
                return;
            }
            handleTriggerAction(player, action);
        }
    }

    private void handleTriggerAction(Player player, String rawAction) {
        if (rawAction == null) return;
        String action = rawAction.trim();
        if (action.isEmpty()) return;

        String[] parts = action.split(":", 2);
        String type = parts[0].trim().toLowerCase(Locale.ROOT);
        String param = parts.length > 1 ? replaceTriggerPlaceholders(player, parts[1].trim()) : "";

        switch (type) {
            case "action" -> handleAction(player, param);
            case "open", "gui" -> handleOpenGUI(player, param);
            case "command", "cmd", "player", "execute" -> executePlayerCommands(player, param);
            case "console" -> executeConsoleCommands(param);
            case "op", "operator", "ops" -> executeOpCommands(player, param);
            case "message", "msg", "tell" -> player.sendMessage(MessageUtil.colorize(param));
            case "sound", "play-sound", "playsound" -> playConfiguredSound(player, param);
            case "delay", "wait" -> {
                // delay is handled by handleTriggerActions so following actions can resume later.
            }
            case "close" -> player.closeInventory();
            case "silent-close", "force-close" -> guiManager.closeMenuSilently(player);
            default -> handleAction(player, action);
        }
    }

    private DelayAction parseDelayAction(String rawAction) {
        if (rawAction == null) return null;
        String action = rawAction.trim();
        if (action.isEmpty()) return null;

        String[] parts = action.split(":", 2);
        String type = parts[0].trim().toLowerCase(Locale.ROOT);
        if (!"delay".equals(type) && !"wait".equals(type)) return null;

        String value = parts.length > 1 ? parts[1].trim() : "";
        return new DelayAction(parseDelayTicks(value));
    }

    private long parseDelayTicks(String value) {
        if (value == null || value.isBlank()) return 20L;

        String normalized = value.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        try {
            if (normalized.endsWith("ticks")) {
                return Math.max(0L, Long.parseLong(normalized.substring(0, normalized.length() - 5)));
            }
            if (normalized.endsWith("tick")) {
                return Math.max(0L, Long.parseLong(normalized.substring(0, normalized.length() - 4)));
            }
            if (normalized.endsWith("t")) {
                return Math.max(0L, Long.parseLong(normalized.substring(0, normalized.length() - 1)));
            }
            if (normalized.endsWith("ms")) {
                double millis = Double.parseDouble(normalized.substring(0, normalized.length() - 2));
                return Math.max(0L, (long) Math.ceil(millis / 50.0D));
            }
            if (normalized.endsWith("s")) {
                double seconds = Double.parseDouble(normalized.substring(0, normalized.length() - 1));
                return Math.max(0L, (long) Math.ceil(seconds * 20.0D));
            }
            double seconds = Double.parseDouble(normalized);
            return Math.max(0L, (long) Math.ceil(seconds * 20.0D));
        } catch (NumberFormatException e) {
            plugin.logWarning("菜单延迟配置无效: " + value,
                    "Invalid menu delay config: " + value);
            return 0L;
        }
    }

    private record DelayAction(long ticks) {
    }

    private void executePlayerCommands(Player player, String commands) {
        for (String command : splitCommands(commands)) {
            player.performCommand(stripLeadingSlash(command));
        }
    }

    private void executeConsoleCommands(String commands) {
        for (String command : splitCommands(commands)) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), stripLeadingSlash(command));
        }
    }

    private void executeOpCommands(Player player, String commands) {
        boolean wasOp = player.isOp();
        try {
            if (!wasOp) {
                player.setOp(true);
            }
            executePlayerCommands(player, commands);
        } finally {
            if (!wasOp && player.isOnline()) {
                player.setOp(false);
            }
        }
    }

    private List<String> splitCommands(String commands) {
        if (commands == null || commands.isBlank()) return List.of();
        return java.util.Arrays.stream(commands.split(";"))
                .map(String::trim)
                .filter(command -> !command.isEmpty())
                .toList();
    }

    private String stripLeadingSlash(String command) {
        String result = command != null ? command.trim() : "";
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        return result;
    }

    private String replaceTriggerPlaceholders(Player player, String text) {
        if (text == null) return "";
        return text
                .replace("%player%", player.getName())
                .replace("%player_name%", player.getName())
                .replace("%player_uuid%", player.getUniqueId().toString())
                .replace("%uuid%", player.getUniqueId().toString())
                .replace("%world%", player.getWorld().getName());
    }

    private void playConfiguredSound(Player player, String config) {
        if (config == null || config.isBlank()) return;

        String[] parts = config.split("-");
        String soundName = parts[0].trim().toUpperCase(Locale.ROOT);
        float volume = parseFloat(parts, 1, 1.0F);
        float pitch = parseFloat(parts, 2, 1.0F);

        try {
            Sound sound = Sound.valueOf(soundName);
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (IllegalArgumentException e) {
            plugin.logWarning("菜单音效配置无效: " + config,
                    "Invalid menu sound config: " + config);
        }
    }

    private float parseFloat(String[] parts, int index, float fallback) {
        if (parts.length <= index) return fallback;
        try {
            return Float.parseFloat(parts[index].trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void handleOpenGUI(Player player, String guiName) {
        // 对于动态菜单（如landmark_manage:123），解析ID参数
        if (guiName.contains(":")) {
            String[] parts = guiName.split(":", 2);
            String name = parts[0];
            String param = parts[1];
            try {
                int id = Integer.parseInt(param.replace("%landmark_id%", ""));
                // landmark_manage:123 → 打开指定地标的管理界面
                guiManager.openLandmarkManage(player, id);
            } catch (NumberFormatException e) {
                guiManager.openMenu(player, name);
            }
        } else {
            guiManager.openMenu(player, guiName);
        }
    }

    private void handleTeleport(Player player, String param) {
        try {
            int id = Integer.parseInt(param.replace("%landmark_id%", ""));
            Landmark lm = plugin.getLandmarkManager().getLandmark(id);
            if (!validateTeleportRequest(player, lm)) return;
            if (requiresTeleportConfirmation(player, lm)) {
                sendTeleportConfirmation(player, lm);
                return;
            }
            executeTeleport(player, lm);
        } catch (NumberFormatException e) {
            player.sendMessage(plugin.getMessageUtil().get("invalid_landmark_id"));
        }
    }

    public void confirmTeleport(Player player, int landmarkId) {
        Integer pending = guiManager.getPendingTeleportConfirmation(player.getUniqueId());
        if (pending == null || pending != landmarkId) {
            player.sendMessage(plugin.getMessageUtil().get("teleport_confirm_expired"));
            return;
        }
        Landmark lm = plugin.getLandmarkManager().getLandmark(landmarkId);
        if (!validateTeleportRequest(player, lm)) {
            guiManager.clearPendingTeleportConfirmation(player.getUniqueId());
            return;
        }
        guiManager.clearPendingTeleportConfirmation(player.getUniqueId());
        executeTeleport(player, lm);
    }

    public void cancelTeleportConfirmation(Player player) {
        guiManager.clearPendingTeleportConfirmation(player.getUniqueId());
        player.sendMessage(plugin.getMessageUtil().get("teleport_confirm_cancelled"));
    }

    private boolean validateTeleportRequest(Player player, Landmark lm) {
        if (lm == null) {
            player.sendMessage(plugin.getMessageUtil().get("landmark_not_found"));
            return false;
        }
        if (!plugin.canAccessLandmarkServer(player, lm)) {
            player.sendMessage(plugin.getMessageUtil().get("server_permission_denied"));
            return false;
        }
        if (!guiManager.canViewLandmark(player, lm)) {
            player.sendMessage(plugin.getMessageUtil().get("landmark_private_no_access"));
            return false;
        }
        if (plugin.getBlacklistManager().isBlacklisted(lm.getId(), player.getUniqueId())) {
            player.sendMessage(plugin.getMessageUtil().get("landmark_blacklisted"));
            return false;
        }
        if (!plugin.getTeleportManager().canReachLandmark(lm)) {
            player.sendMessage(plugin.getMessageUtil().get("server_offline"));
            return false;
        }
        return true;
    }

    private boolean requiresTeleportConfirmation(Player player, Landmark lm) {
        if (!plugin.getTaxManager().shouldCharge(player, lm)) return false;
        double threshold = plugin.getConfig().getDouble("economy.teleport_confirm_threshold", 0.0);
        return threshold > 0.0 && lm.getPrice() >= threshold;
    }

    private void sendTeleportConfirmation(Player player, Landmark lm) {
        guiManager.setPendingTeleportConfirmation(player.getUniqueId(), lm.getId());
        player.closeInventory();
        player.sendMessage(plugin.getMessageUtil().get("teleport_confirm_required",
                "%landmark%", lm.getName(),
                "%price%", MessageUtil.formatDouble(lm.getPrice())));
        player.sendMessage(Component.empty()
                .append(LEGACY.deserialize(plugin.getMessageUtil().get("teleport_confirm_button"))
                        .clickEvent(ClickEvent.runCommand("/alwarp confirmteleport " + lm.getId()))
                        .hoverEvent(HoverEvent.showText(LEGACY.deserialize(
                                plugin.getMessageUtil().get("teleport_confirm_button_hover")))))
                .append(Component.text(" "))
                .append(LEGACY.deserialize(plugin.getMessageUtil().get("teleport_cancel_button"))
                        .clickEvent(ClickEvent.runCommand("/alwarp cancelteleport"))
                        .hoverEvent(HoverEvent.showText(LEGACY.deserialize(
                                plugin.getMessageUtil().get("teleport_cancel_button_hover"))))));
    }

    private void executeTeleport(Player player, Landmark lm) {
        if (plugin.getTaxManager().shouldCharge(player, lm)) {
            if (plugin.getEconomy() != null && !plugin.getEconomy().has(player, lm.getPrice())) {
                player.sendMessage(plugin.getMessageUtil().get("no_money"));
                return;
            }
        }
        player.closeInventory();
        plugin.getTeleportManager().requestTeleport(player, lm);
    }

    private void handleSearch(Player player) {
        String currentMenu = guiManager.getPlayerCurrentMenu(player.getUniqueId());
        guiManager.setSearchReturnMenu(player.getUniqueId(), currentMenu != null ? currentMenu : "main_menu");
        player.closeInventory();
        player.sendMessage(plugin.getMessageUtil().get("search_prompt"));
        plugin.getGUIManager().setPendingInput(player.getUniqueId(), "search");
    }

    private void handleClearSearch(Player player) {
        String currentMenu = guiManager.getPlayerCurrentMenu(player.getUniqueId());
        String menuName = currentMenu != null ? currentMenu : "main_menu";
        guiManager.clearSearch(player, menuName);
        guiManager.openMenu(player, menuName);
    }

    private void handleFilterToggle(Player player) {
        guiManager.toggleFilter(player);
    }

    private void handleFilter(Player player, String param) {
        try {
            int categoryId = Integer.parseInt(param);
            Landmark editing = plugin.getGUIManager().getEditingLandmark(player.getUniqueId());
            if (editing != null) {
                editing.setCategoryId(categoryId);
            }
            String returnMenu = guiManager.getCategoryReturnMenu(player.getUniqueId());
            guiManager.setFilter(player, returnMenu != null ? returnMenu : "main_menu", categoryId);
            if ("landmark_manage".equals(returnMenu) && editing != null) {
                guiManager.openLandmarkManage(player, editing.getId());
            } else {
                guiManager.openMenu(player, returnMenu != null ? returnMenu : "main_menu");
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private void handleSetName(Player player) {
        player.closeInventory();
        int maxLen = getLandmarkNameMaxLength();
        player.sendMessage(plugin.getMessageUtil().get("set_name_prompt_limited", "%max%", String.valueOf(maxLen)));
        plugin.getGUIManager().setPendingInput(player.getUniqueId(), "set_name");
    }

    private void handleSetIcon(Player player) {
        player.closeInventory();
        player.sendMessage(plugin.getMessageUtil().get("set_icon_prompt"));
        plugin.getGUIManager().setPendingInput(player.getUniqueId(), "set_icon");
    }

    private void handleSetDescription(Player player) {
        player.closeInventory();
        int maxLen = getLandmarkDescriptionMaxLength();
        player.sendMessage(plugin.getMessageUtil().get("set_desc_prompt_limited", "%max%", String.valueOf(maxLen)));
        plugin.getGUIManager().setPendingInput(player.getUniqueId(), "set_description");
    }

    private void handleSetCategory(Player player) {
        guiManager.setCategoryReturnMenu(player.getUniqueId(), "landmark_manage");
        guiManager.openMenu(player, "category_select");
    }

    private void handleSetPrice(Player player) {
        player.closeInventory();
        player.sendMessage(plugin.getMessageUtil().get("set_price_prompt"));
        plugin.getGUIManager().setPendingInput(player.getUniqueId(), "set_price");
    }

    private void handleSetLocation(Player player) {
        Landmark editing = plugin.getGUIManager().getEditingLandmark(player.getUniqueId());
        if (editing == null) {
            player.sendMessage(plugin.getMessageUtil().get("landmark_not_found"));
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

        Location location = player.getLocation();
        editing.setServerName(plugin.getServerId());
        editing.setWorld(player.getWorld().getName());
        editing.setX(location.getBlockX());
        editing.setY(location.getBlockY());
        editing.setZ(location.getBlockZ());

        guiManager.openLandmarkManage(player, editing.getId());
    }

    private void handleDeleteLandmark(Player player) {
        Landmark editing = plugin.getGUIManager().getEditingLandmark(player.getUniqueId());
        if (!guiManager.canManageLandmarkManagers(player, editing)) {
            player.sendMessage(plugin.getMessageUtil().get("no_permission"));
            return;
        }
        player.closeInventory();
        player.sendMessage(plugin.getMessageUtil().get("delete_confirm"));
        plugin.getGUIManager().setPendingInput(player.getUniqueId(), "delete_landmark");
    }

    private void handleSaveLandmark(Player player) {
        Landmark editing = plugin.getGUIManager().getEditingLandmark(player.getUniqueId());
        if (editing != null) {
            plugin.getLandmarkManager().updateLandmark(editing, result -> {
                plugin.runAtPlayer(player, () -> {
                    if (result != null) {
                        if (editing.isPrivate()) {
                            plugin.getFavoritesManager().removeFavoritesByLandmark(editing.getId(), ignored -> {});
                        }
                        player.sendMessage(plugin.getMessageUtil().get("landmark_updated"));
                    }
                });
            });
            plugin.getGUIManager().clearEditingState(player.getUniqueId());
        }
        player.closeInventory();
    }

    private void handleCreateLandmark(Player player) {
        player.closeInventory();
        if (!plugin.isCreateAllowedOnServer(player)) {
            player.sendMessage(plugin.getMessageUtil().get("create_disabled_server"));
            return;
        }
        if (!plugin.canCreateInWorld(player)) {
            player.sendMessage(plugin.getMessageUtil().get("world_restricted"));
            return;
        }
        double cost = plugin.getConfig().getDouble("economy.create_cost", 1000.0);
        player.sendMessage(plugin.getMessageUtil().get("create_name_prompt_limited",
                "%cost%", String.format("%.0f", cost),
                "%max%", String.valueOf(getLandmarkNameMaxLength())));
        plugin.getGUIManager().setPendingInput(player.getUniqueId(), "create_name");
    }

    private void handleClaimIncome(Player player) {
        player.closeInventory();
        if (plugin.getEconomy() == null) {
            player.sendMessage(plugin.getMessageUtil().get("income_claim_failed"));
            return;
        }
        UUID playerUuid = player.getUniqueId();
        String playerName = player.getName();
        plugin.getScheduler().runTaskAsync(() -> {
            double amount = plugin.getStorage().claimPendingIncome(playerUuid);
            plugin.runAtPlayer(player, () -> {
                if (amount <= 0) {
                    plugin.getIncomeManager().refreshAsync(playerUuid, () -> {});
                    player.sendMessage(plugin.getMessageUtil().get("income_none"));
                    return;
                }
                var claim = plugin.getTaxManager().previewClaim(player, amount);
                var response = plugin.getEconomy().depositPlayer(player, claim.net());
                if (response.transactionSuccess()) {
                    plugin.getIncomeManager().refreshAsync(playerUuid, () -> {});
                    if (plugin.getRedisManager() != null) {
                        plugin.getRedisManager().broadcastIncomeRefresh();
                    }
                    player.sendMessage(plugin.getMessageUtil().get("income_claimed",
                            "%amount%", com.alwarp.util.MessageUtil.formatDouble(claim.net()),
                            "%gross%", com.alwarp.util.MessageUtil.formatDouble(claim.gross()),
                            "%tax%", com.alwarp.util.MessageUtil.formatDouble(claim.tax()),
                            "%rate%", com.alwarp.util.MessageUtil.formatDouble(claim.rate() * 100.0)));
                } else {
                    plugin.getScheduler().runTaskAsync(() -> {
                        if (plugin.getStorage().addPendingIncome(playerUuid, playerName, amount)) {
                            plugin.getIncomeManager().setCachedPendingIncome(playerUuid, amount);
                            if (plugin.getRedisManager() != null) {
                                plugin.getRedisManager().broadcastIncomeRefresh();
                            }
                        }
                    });
                    player.sendMessage(plugin.getMessageUtil().get("income_claim_failed"));
                }
            });
        });
    }

    private int getLandmarkNameMaxLength() {
        return Math.max(1, plugin.getConfig().getInt("landmark.max_name_length", 8));
    }

    private int getLandmarkDescriptionMaxLength() {
        return Math.max(0, plugin.getConfig().getInt("landmark.max_description_length", 50));
    }

    private void handleTogglePrivate(Player player) {
        Landmark editing = plugin.getGUIManager().getEditingLandmark(player.getUniqueId());
        if (editing == null) {
            player.sendMessage(plugin.getMessageUtil().get("landmark_not_found"));
            return;
        }
        editing.setPrivate(!editing.isPrivate());
        guiManager.openLandmarkManage(player, editing.getId());
    }

    private void handleToggleFavorite(Player player, String param) {
        try {
            int id = Integer.parseInt(param.replace("%landmark_id%", ""));
            Landmark lm = plugin.getLandmarkManager().getLandmark(id);
            if (lm == null) return;
            if (!plugin.canAccessLandmarkServer(player, lm)) {
                player.sendMessage(plugin.getMessageUtil().get("server_permission_denied"));
                return;
            }
            if (!guiManager.canViewLandmark(player, lm)) {
                player.sendMessage(plugin.getMessageUtil().get("landmark_private_no_access"));
                return;
            }
            if (lm.isPrivate()) {
                player.sendMessage(plugin.getMessageUtil().get("favorite_private_blocked"));
                return;
            }
            if (plugin.getBlacklistManager().isBlacklisted(id, player.getUniqueId())) {
                player.sendMessage(plugin.getMessageUtil().get("landmark_blacklisted"));
                return;
            }

            boolean fav = plugin.getFavoritesManager().toggle(player.getUniqueId(), id);
            String menu = guiManager.getPlayerCurrentMenu(player.getUniqueId());
            if (menu != null) guiManager.refreshOpenMenu(player, menu);
            player.sendMessage(plugin.getMessageUtil().get(fav ? "favorite_added" : "favorite_removed"));
        } catch (NumberFormatException ignored) {
        }
    }

    private void handleRateLandmark(Player player, String param) {
        try {
            int id = Integer.parseInt(param.replace("%landmark_id%", ""));
            if (!plugin.getRatingManager().isEnabled()) {
                player.sendMessage(plugin.getMessageUtil().get("rating_disabled"));
                return;
            }
            Landmark lm = plugin.getLandmarkManager().getLandmark(id);
            if (lm == null) return;
            if (!plugin.canAccessLandmarkServer(player, lm)) {
                player.sendMessage(plugin.getMessageUtil().get("server_permission_denied"));
                return;
            }
            if (!guiManager.canViewLandmark(player, lm)) {
                player.sendMessage(plugin.getMessageUtil().get("landmark_private_no_access"));
                return;
            }
            if (plugin.getBlacklistManager().isBlacklisted(id, player.getUniqueId())) {
                player.sendMessage(plugin.getMessageUtil().get("landmark_blacklisted"));
                return;
            }

            long remainingSeconds = plugin.getRatingManager().getRemainingCooldownSeconds(player.getUniqueId());
            if (remainingSeconds > 0) {
                player.sendMessage(plugin.getMessageUtil().get("rating_cooldown_remaining",
                        "%seconds%", String.valueOf(remainingSeconds)));
                return;
            }

            plugin.getRatingManager().hasVisitedLandmark(player.getUniqueId(), id, visitedResult ->
                    plugin.runAtPlayer(player, () -> {
                        if (!(boolean) visitedResult) {
                            player.sendMessage(plugin.getMessageUtil().get("rating_requires_visit"));
                            return;
                        }
                        player.closeInventory();
                        plugin.getGUIManager().setEditingLandmarkId(player.getUniqueId(), id);
                        plugin.getGUIManager().setPendingInput(player.getUniqueId(), "rate_score");
                        player.sendMessage(plugin.getMessageUtil().get("rate_prompt",
                                "%min%", String.valueOf(plugin.getRatingManager().getMinScore()),
                                "%max%", String.valueOf(plugin.getRatingManager().getMaxScore())));
                    }));
        } catch (NumberFormatException ignored) {}
    }

    private void handleRateCurrentLandmark(Player player) {
        Integer landmarkId = plugin.getGUIManager().getEditingLandmarkId(player.getUniqueId());
        if (landmarkId == null) {
            player.sendMessage(plugin.getMessageUtil().get("rating_expired"));
            return;
        }
        handleRateLandmark(player, String.valueOf(landmarkId));
    }

    private void handleViewReviews(Player player, String param) {
        try {
            int id = Integer.parseInt(param.replace("%landmark_id%", ""));
            if (!plugin.getRatingManager().isEnabled()) {
                player.sendMessage(plugin.getMessageUtil().get("rating_disabled"));
                return;
            }
            Landmark lm = plugin.getLandmarkManager().getLandmark(id);
            if (lm == null) return;
            if (!plugin.canAccessLandmarkServer(player, lm)) {
                player.sendMessage(plugin.getMessageUtil().get("server_permission_denied"));
                return;
            }
            if (!guiManager.canViewLandmark(player, lm)) {
                player.sendMessage(plugin.getMessageUtil().get("landmark_private_no_access"));
                return;
            }
            guiManager.openLandmarkReviews(player, id);
        } catch (NumberFormatException ignored) {}
    }

    private void handleDeleteOwnReview(Player player) {
        Integer landmarkId = plugin.getGUIManager().getEditingLandmarkId(player.getUniqueId());
        if (landmarkId == null) {
            player.sendMessage(plugin.getMessageUtil().get("rating_expired"));
            return;
        }
        plugin.getRatingManager().deletePlayerRating(landmarkId, player.getUniqueId(), result ->
                plugin.runAtPlayer(player, () -> {
                    if ((boolean) result) {
                        player.sendMessage(plugin.getMessageUtil().get("own_review_deleted"));
                    } else {
                        player.sendMessage(plugin.getMessageUtil().get("own_review_not_found"));
                    }
                    guiManager.openLandmarkReviews(player, landmarkId);
                }));
    }

    private void handleOpenAdminInterface(Player player) {
        Landmark editing = plugin.getGUIManager().getEditingLandmark(player.getUniqueId());
        if (editing != null) {
            guiManager.openAdminList(player, editing.getId());
        }
    }

    private void handleOpenBlacklistInterface(Player player) {
        Landmark editing = plugin.getGUIManager().getEditingLandmark(player.getUniqueId());
        if (editing != null) {
            guiManager.openBlacklistList(player, editing.getId());
        }
    }

    private void handleReturnLandmarkManage(Player player) {
        Integer id = plugin.getGUIManager().getEditingLandmarkId(player.getUniqueId());
        if (id != null) {
            guiManager.openLandmarkManage(player, id);
        }
    }

    private void handleAddManager(Player player) {
        Landmark editing = plugin.getGUIManager().getEditingLandmark(player.getUniqueId());
        if (!guiManager.canManageLandmarkManagers(player, editing)) {
            player.sendMessage(plugin.getMessageUtil().get("no_permission"));
            return;
        }
        player.closeInventory();
        player.sendMessage(plugin.getMessageUtil().get("add_manager_prompt"));
        plugin.getGUIManager().setPendingInput(player.getUniqueId(), "add_manager");
    }

    private void handleAddBlacklist(Player player) {
        Landmark editing = getCurrentEditingLandmark(player);
        if (!guiManager.canManageLandmarkManagers(player, editing)) {
            player.sendMessage(plugin.getMessageUtil().get("no_permission"));
            return;
        }
        player.closeInventory();
        player.sendMessage(plugin.getMessageUtil().get("add_blacklist_prompt"));
        plugin.getGUIManager().setPendingInput(player.getUniqueId(), "add_blacklist");
    }

    private void handleRemoveManager(Player player, String param) {
        try {
            UUID targetUuid = UUID.fromString(param.replace("%admin_uuid%", ""));
            Landmark editing = plugin.getGUIManager().getEditingLandmark(player.getUniqueId());
            if (editing != null && guiManager.canManageLandmarkManagers(player, editing)) {
                plugin.getManagerManager().getManagers(editing.getId(), managersResult -> {
                    @SuppressWarnings("unchecked")
                    List<LandmarkAdmin> managers = (List<LandmarkAdmin>) managersResult;
                    String targetName = findManagerName(managers, targetUuid);
                    plugin.getManagerManager().removeManager(editing.getId(), targetUuid, result -> {
                        plugin.runAtPlayer(player, () -> {
                            String displayName = targetName != null ? targetName : targetUuid.toString();
                            if ((boolean) result) {
                                player.sendMessage(plugin.getMessageUtil().get("manager_removed", "%player%", displayName));
                            } else {
                                player.sendMessage(plugin.getMessageUtil().get("manager_not_found", "%player%", displayName));
                            }
                            guiManager.openAdminList(player, editing.getId());
                        });
                    });
                });
            } else {
                player.sendMessage(plugin.getMessageUtil().get("no_permission"));
            }
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void handleRemoveBlacklist(Player player, String param) {
        try {
            UUID targetUuid = UUID.fromString(param.replace("%blacklist_uuid%", ""));
            Landmark editing = getCurrentEditingLandmark(player);
            if (editing != null && guiManager.canManageLandmarkManagers(player, editing)) {
                plugin.getBlacklistManager().getBlacklist(editing.getId(), blacklistsResult -> {
                    @SuppressWarnings("unchecked")
                    List<LandmarkBlacklist> blacklists = (List<LandmarkBlacklist>) blacklistsResult;
                    String targetName = findBlacklistName(blacklists, targetUuid);
                    plugin.getBlacklistManager().removeBlacklist(editing.getId(), targetUuid, result -> {
                        plugin.runAtPlayer(player, () -> {
                            String displayName = targetName != null ? targetName : targetUuid.toString();
                            if ((boolean) result) {
                                player.sendMessage(plugin.getMessageUtil().get("blacklist_removed", "%player%", displayName));
                            } else {
                                player.sendMessage(plugin.getMessageUtil().get("blacklist_not_found", "%player%", displayName));
                            }
                            guiManager.openBlacklistList(player, editing.getId());
                        });
                    });
                });
            } else {
                player.sendMessage(plugin.getMessageUtil().get("no_permission"));
            }
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void handlePinSlot(Player player, String param) {
        int slotIndex = parseSlotIndex(param);
        if (slotIndex <= 0 || !guiManager.isConfiguredPinSlot(slotIndex)) {
            player.sendMessage(plugin.getMessageUtil().get("pin_invalid_slot"));
            return;
        }
        LandmarkPin pin = plugin.getPinManager().getActivePin(slotIndex);
        if (pin == null) {
            guiManager.openPinPurchase(player, slotIndex);
            return;
        }
        handleTeleport(player, String.valueOf(pin.getLandmarkId()));
    }

    private void handlePinSlotFavorite(Player player, String param) {
        LandmarkPin pin = getActivePinFromParam(player, param);
        if (pin != null) handleToggleFavorite(player, String.valueOf(pin.getLandmarkId()));
    }

    private void handlePinSlotReviews(Player player, String param) {
        LandmarkPin pin = getActivePinFromParam(player, param);
        if (pin != null) handleViewReviews(player, String.valueOf(pin.getLandmarkId()));
    }

    private LandmarkPin getActivePinFromParam(Player player, String param) {
        int slotIndex = parseSlotIndex(param);
        if (slotIndex <= 0 || !guiManager.isConfiguredPinSlot(slotIndex)) {
            player.sendMessage(plugin.getMessageUtil().get("pin_invalid_slot"));
            return null;
        }
        LandmarkPin pin = plugin.getPinManager().getActivePin(slotIndex);
        if (pin == null) {
            player.sendMessage(plugin.getMessageUtil().get("pin_slot_available"));
            guiManager.openPinPurchase(player, slotIndex);
            return null;
        }
        return pin;
    }

    private void handleSelectPinLandmark(Player player, String param) {
        try {
            int id = Integer.parseInt(param.replace("%landmark_id%", ""));
            guiManager.selectPinLandmark(player, id);
        } catch (NumberFormatException ignored) {
            player.sendMessage(plugin.getMessageUtil().get("invalid_landmark_id"));
        }
    }

    private void handleConfirmPinPurchase(Player player) {
        Integer slotIndex = guiManager.getPendingPinSlot(player.getUniqueId());
        Integer landmarkId = guiManager.getSelectedPinLandmarkId(player.getUniqueId());
        if (slotIndex == null || !guiManager.isConfiguredPinSlot(slotIndex)) {
            player.sendMessage(plugin.getMessageUtil().get("pin_invalid_slot"));
            guiManager.clearPinState(player.getUniqueId());
            guiManager.openMenu(player, "main_menu");
            return;
        }
        if (landmarkId == null) {
            player.sendMessage(plugin.getMessageUtil().get("pin_select_landmark_first"));
            guiManager.openPinPurchase(player, slotIndex);
            return;
        }
        if (plugin.getPinManager().getActivePin(slotIndex) != null) {
            player.sendMessage(plugin.getMessageUtil().get("pin_slot_taken"));
            guiManager.clearPinState(player.getUniqueId());
            guiManager.openMenu(player, "main_menu");
            return;
        }

        Landmark lm = plugin.getLandmarkManager().getLandmark(landmarkId);
        if (lm == null || !player.getUniqueId().equals(lm.getOwnerUuid())) {
            player.sendMessage(plugin.getMessageUtil().get("pin_landmark_not_owned"));
            guiManager.openPinLandmarkSelect(player);
            return;
        }

        double cost = guiManager.getPinCost(slotIndex);
        int days = guiManager.getPinDurationDays(slotIndex);
        if (cost > 0.0) {
            if (plugin.getEconomy() == null) {
                player.sendMessage(plugin.getMessageUtil().get("pin_no_economy"));
                return;
            }
            if (!plugin.getEconomy().has(player, cost)) {
                player.sendMessage(plugin.getMessageUtil().get("pin_no_money"));
                return;
            }
            var response = plugin.getEconomy().withdrawPlayer(player, cost);
            if (!response.transactionSuccess()) {
                player.sendMessage(plugin.getMessageUtil().get("payment_failed"));
                return;
            }
        }

        long durationSeconds = Math.max(1L, days) * 24L * 60L * 60L;
        plugin.getPinManager().pinLandmark(slotIndex, landmarkId, player.getUniqueId(), player.getName(), durationSeconds, result -> {
            plugin.runAtPlayer(player, () -> {
                if (result == null) {
                    if (cost > 0.0 && plugin.getEconomy() != null) {
                        plugin.getEconomy().depositPlayer(player, cost);
                    }
                    player.sendMessage(plugin.getMessageUtil().get("pin_slot_taken"));
                    guiManager.clearPinState(player.getUniqueId());
                    guiManager.openMenu(player, "main_menu");
                    return;
                }
                guiManager.clearPinState(player.getUniqueId());
                player.sendMessage(plugin.getMessageUtil().get("pin_purchased",
                        "%slot%", String.valueOf(slotIndex),
                        "%landmark%", lm.getName(),
                        "%cost%", MessageUtil.formatDouble(cost),
                        "%days%", String.valueOf(days)));
                guiManager.openMenu(player, "main_menu");
            });
        });
    }

    private void handleCancelPinPurchase(Player player) {
        guiManager.clearPinState(player.getUniqueId());
        player.sendMessage(plugin.getMessageUtil().get("pin_cancelled"));
        guiManager.openMenu(player, "main_menu");
    }

    private int parseSlotIndex(String param) {
        try {
            return Integer.parseInt(param.replace("%pin_slot%", ""));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private Landmark getCurrentEditingLandmark(Player player) {
        Landmark editing = plugin.getGUIManager().getEditingLandmark(player.getUniqueId());
        if (editing != null) return editing;
        Integer id = plugin.getGUIManager().getEditingLandmarkId(player.getUniqueId());
        return id != null ? plugin.getLandmarkManager().getLandmark(id) : null;
    }

    private String findManagerName(List<LandmarkAdmin> managers, UUID targetUuid) {
        if (managers == null || targetUuid == null) return null;
        for (LandmarkAdmin manager : managers) {
            if (targetUuid.equals(manager.getPlayerUuid())) {
                return manager.getPlayerName();
            }
        }
        return null;
    }

    private String findBlacklistName(List<LandmarkBlacklist> blacklists, UUID targetUuid) {
        if (blacklists == null || targetUuid == null) return null;
        for (LandmarkBlacklist blacklist : blacklists) {
            if (targetUuid.equals(blacklist.getPlayerUuid())) {
                return blacklist.getPlayerName();
            }
        }
        return null;
    }
}
