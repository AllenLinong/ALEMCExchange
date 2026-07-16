package com.alwarp.listener;

import com.alwarp.ALwarp;
import com.alwarp.gui.GUIManager;
import com.alwarp.gui.shape.ActionHandler;
import com.alwarp.gui.shape.MenuConfig;
import com.alwarp.gui.shape.ShapeMenuHolder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物品栏事件监听器。
 * 所有Shape菜单禁止取下/放入物品。按点击类型分发动作。
 */
public class InventoryListener implements Listener {

    private static final String BUTTON_CLICK_COOLDOWN_PATH = "gui.button_click_cooldown_seconds";
    private static final double DEFAULT_BUTTON_CLICK_COOLDOWN_SECONDS = 0.5D;
    private static final long BUTTON_CLICK_COOLDOWN_CACHE_MILLIS = 1000L;

    private final ALwarp plugin;
    private final Map<UUID, Long> lastButtonClickMillis = new ConcurrentHashMap<>();
    private volatile long cachedButtonClickCooldownMillis = -1L;
    private volatile long buttonClickCooldownCacheExpiresAt = 0L;

    public InventoryListener(ALwarp plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory topInventory = event.getView().getTopInventory();
        String currentMenu = resolveOpenMenuName(player, topInventory);
        if (currentMenu == null) return;

        // 先取消所有点击，防止任何物品移动
        event.setCancelled(true);

        int slot = event.getRawSlot();
        if (slot >= topInventory.getSize()) return;

        long clickTime = System.currentTimeMillis();
        long cooldownMillis = getButtonClickCooldownMillis(clickTime);
        if (isButtonClickCoolingDown(player, cooldownMillis, clickTime)) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        MenuConfig config = plugin.getShapeMenuManager().getMenu(currentMenu);
        if (config == null) return;

        int row = slot / 9;
        int col = slot % 9;
        List<String> shape = getInventoryShape(topInventory, config);
        String charStr = getCharAt(shape, row, col);
        if (charStr == null || charStr.equals("#") || charStr.equals(" ")) return;

        boolean reviewDelete = currentMenu.equals("landmark_reviews") && "A".equals(charStr) && event.isShiftClick()
                && !event.isRightClick() && player.hasPermission("alwarp.admin");
        boolean reviewReport = currentMenu.equals("landmark_reviews") && "A".equals(charStr) && event.isShiftClick()
                && event.isRightClick();
        var menuItem = config.getItems().get(charStr);
        if (menuItem == null && !reviewDelete && !reviewReport) return;

        markButtonClick(player, cooldownMillis, clickTime);

        // 评价界面管理员 Shift+左键 删除评价
        if (reviewDelete) {
            deleteReview(player, clicked);
            return;
        }

        if (reviewReport) {
            reportReview(player, clicked);
            return;
        }

        // 从PDC读取实际地标ID，替换action中的占位符
        int landmarkId = GUIManager.getLandmarkId(clicked);
        String action = resolveAction(menuItem.getAction(), landmarkId);
        String rightAction = resolveAction(menuItem.getRightAction(), landmarkId);
        String shiftLeftAction = resolveAction(menuItem.getShiftLeftAction(), landmarkId);
        String shiftRightAction = resolveAction(menuItem.getShiftRightAction(), landmarkId);
        List<String> leftTriggers = resolveActions(menuItem.getTriggerActions("left"), landmarkId);
        List<String> rightTriggers = resolveActions(menuItem.getTriggerActions("right"), landmarkId);
        List<String> shiftLeftTriggers = resolveActions(menuItem.getTriggerActions("shift_left"), landmarkId);
        List<String> shiftRightTriggers = resolveActions(menuItem.getTriggerActions("shift_right"), landmarkId);
        if ("category_select".equals(currentMenu)) {
            int categoryId = GUIManager.getCategoryId(clicked);
            if (categoryId > 0) {
                action = "filter:" + categoryId;
                leftTriggers = resolveCategoryActions(leftTriggers, categoryId);
                rightTriggers = resolveCategoryActions(rightTriggers, categoryId);
                shiftLeftTriggers = resolveCategoryActions(shiftLeftTriggers, categoryId);
                shiftRightTriggers = resolveCategoryActions(shiftRightTriggers, categoryId);
            }
        }
        if ("landmark_color_select".equals(currentMenu) && "A".equals(charStr)) {
            String colorOptionId = GUIManager.getColorOptionId(clicked);
            if (colorOptionId != null && !colorOptionId.isBlank()) {
                action = "select_landmark_color:" + colorOptionId;
                shiftRightAction = "buy_landmark_color:" + colorOptionId;
                leftTriggers = resolveColorActions(leftTriggers, colorOptionId);
                rightTriggers = resolveColorActions(rightTriggers, colorOptionId);
                shiftLeftTriggers = resolveColorActions(shiftLeftTriggers, colorOptionId);
                shiftRightTriggers = resolveColorActions(shiftRightTriggers, colorOptionId);
            }
        }
        int pinSlot = GUIManager.getPinSlot(clicked);
        action = resolvePinAction(action, pinSlot);
        rightAction = resolvePinAction(rightAction, pinSlot);
        shiftLeftAction = resolvePinAction(shiftLeftAction, pinSlot);
        shiftRightAction = resolvePinAction(shiftRightAction, pinSlot);
        leftTriggers = resolvePinActions(leftTriggers, pinSlot);
        rightTriggers = resolvePinActions(rightTriggers, pinSlot);
        shiftLeftTriggers = resolvePinActions(shiftLeftTriggers, pinSlot);
        shiftRightTriggers = resolvePinActions(shiftRightTriggers, pinSlot);
        UUID adminUuid = GUIManager.getAdminUuid(clicked);
        if (adminUuid != null) {
            action = resolveAdminAction(action, adminUuid);
            rightAction = resolveAdminAction(rightAction, adminUuid);
            shiftLeftAction = resolveAdminAction(shiftLeftAction, adminUuid);
            shiftRightAction = resolveAdminAction(shiftRightAction, adminUuid);
            leftTriggers = resolveAdminActions(leftTriggers, adminUuid);
            rightTriggers = resolveAdminActions(rightTriggers, adminUuid);
            shiftLeftTriggers = resolveAdminActions(shiftLeftTriggers, adminUuid);
            shiftRightTriggers = resolveAdminActions(shiftRightTriggers, adminUuid);
        }
        UUID blacklistUuid = GUIManager.getBlacklistUuid(clicked);
        if (blacklistUuid != null) {
            action = resolveBlacklistAction(action, blacklistUuid);
            rightAction = resolveBlacklistAction(rightAction, blacklistUuid);
            shiftLeftAction = resolveBlacklistAction(shiftLeftAction, blacklistUuid);
            shiftRightAction = resolveBlacklistAction(shiftRightAction, blacklistUuid);
            leftTriggers = resolveBlacklistActions(leftTriggers, blacklistUuid);
            rightTriggers = resolveBlacklistActions(rightTriggers, blacklistUuid);
            shiftLeftTriggers = resolveBlacklistActions(shiftLeftTriggers, blacklistUuid);
            shiftRightTriggers = resolveBlacklistActions(shiftRightTriggers, blacklistUuid);
        }

        ActionHandler handler = plugin.getGUIManager().getActionHandler();
        boolean isRight = event.isRightClick();
        boolean isShift = event.isShiftClick();

        if (isShift && !isRight && !shiftLeftTriggers.isEmpty()) {
            handler.handleTriggerActions(player, shiftLeftTriggers);
        } else if (isShift && !isRight && shiftLeftAction != null) {
            handler.handleAction(player, shiftLeftAction);
        } else if (isShift && isRight && !shiftRightTriggers.isEmpty()) {
            handler.handleTriggerActions(player, shiftRightTriggers);
        } else if (isShift && isRight && shiftRightAction != null) {
            handler.handleAction(player, shiftRightAction);
        } else if (!isShift && isRight && !rightTriggers.isEmpty()) {
            handler.handleTriggerActions(player, rightTriggers);
        } else if (!isShift && isRight && rightAction != null) {
            handler.handleAction(player, rightAction);
        } else if (!isShift && !isRight && !leftTriggers.isEmpty()) {
            handler.handleTriggerActions(player, leftTriggers);
        } else if (!isShift && !isRight && action != null) {
            handler.handleAction(player, action);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (resolveOpenMenuName(player, event.getView().getTopInventory()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            plugin.getGUIManager().onMenuClosed(player.getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastButtonClickMillis.remove(event.getPlayer().getUniqueId());
    }

    private List<String> getInventoryShape(Inventory inventory, MenuConfig config) {
        if (inventory.getHolder() instanceof ShapeMenuHolder holder
                && holder.getShape() != null
                && !holder.getShape().isEmpty()) {
            return holder.getShape();
        }
        return config.getShape();
    }

    private String resolveOpenMenuName(Player player, Inventory topInventory) {
        if (topInventory != null && topInventory.getHolder() instanceof ShapeMenuHolder holder) {
            String menuName = holder.getMenuName();
            if (menuName != null && !menuName.isBlank()) {
                return menuName;
            }
        }
        return plugin.getGUIManager().getPlayerCurrentMenu(player.getUniqueId());
    }

    private String getCharAt(List<String> shape, int row, int col) {
        if (row < shape.size()) {
            String line = shape.get(row);
            if (col < line.length()) return String.valueOf(line.charAt(col));
        }
        return null;
    }

    private boolean isButtonClickCoolingDown(Player player, long cooldownMillis, long now) {
        if (cooldownMillis <= 0L) return false;

        Long lastClick = lastButtonClickMillis.get(player.getUniqueId());
        return lastClick != null && now - lastClick < cooldownMillis;
    }

    private void markButtonClick(Player player, long cooldownMillis, long now) {
        if (cooldownMillis > 0L) {
            lastButtonClickMillis.put(player.getUniqueId(), now);
        }
    }

    private long getButtonClickCooldownMillis(long now) {
        long cached = cachedButtonClickCooldownMillis;
        if (cached >= 0L && now < buttonClickCooldownCacheExpiresAt) {
            return cached;
        }

        double seconds = plugin.getConfig().getDouble(BUTTON_CLICK_COOLDOWN_PATH,
                DEFAULT_BUTTON_CLICK_COOLDOWN_SECONDS);
        long cooldownMillis = Math.max(0L, (long) Math.ceil(seconds * 1000.0D));
        cachedButtonClickCooldownMillis = cooldownMillis;
        buttonClickCooldownCacheExpiresAt = now + BUTTON_CLICK_COOLDOWN_CACHE_MILLIS;
        return cooldownMillis;
    }

    /** 将action中的%landmark_id%占位符替换为实际ID */
    private String resolveAction(String action, int landmarkId) {
        if (action == null) return null;
        if (landmarkId > 0) return action.replace("%landmark_id%", String.valueOf(landmarkId));
        return action;
    }

    private List<String> resolveActions(List<String> actions, int landmarkId) {
        if (actions == null || actions.isEmpty()) return List.of();
        return actions.stream()
                .map(action -> resolveAction(action, landmarkId))
                .toList();
    }

    private String resolveAdminAction(String action, UUID adminUuid) {
        if (action == null) return null;
        return action.replace("%admin_uuid%", adminUuid.toString());
    }

    private List<String> resolveAdminActions(List<String> actions, UUID adminUuid) {
        if (actions == null || actions.isEmpty()) return List.of();
        return actions.stream()
                .map(action -> resolveAdminAction(action, adminUuid))
                .toList();
    }

    private String resolveBlacklistAction(String action, UUID blacklistUuid) {
        if (action == null) return null;
        return action.replace("%blacklist_uuid%", blacklistUuid.toString());
    }

    private List<String> resolveBlacklistActions(List<String> actions, UUID blacklistUuid) {
        if (actions == null || actions.isEmpty()) return List.of();
        return actions.stream()
                .map(action -> resolveBlacklistAction(action, blacklistUuid))
                .toList();
    }

    private String resolvePinAction(String action, int pinSlot) {
        if (action == null) return null;
        if (pinSlot > 0) return action.replace("%pin_slot%", String.valueOf(pinSlot));
        return action;
    }

    private List<String> resolvePinActions(List<String> actions, int pinSlot) {
        if (actions == null || actions.isEmpty()) return List.of();
        return actions.stream()
                .map(action -> resolvePinAction(action, pinSlot))
                .toList();
    }

    private List<String> resolveCategoryActions(List<String> actions, int categoryId) {
        if (actions == null || actions.isEmpty()) return List.of();
        return actions.stream()
                .map(action -> action != null ? action.replace("%category_id%", String.valueOf(categoryId)) : null)
                .toList();
    }

    private List<String> resolveColorActions(List<String> actions, String colorOptionId) {
        if (actions == null || actions.isEmpty()) return List.of();
        return actions.stream()
                .map(action -> action != null ? action.replace("%color_id%", colorOptionId) : null)
                .toList();
    }

    /** Shift+左键删除评价（管理员） */
    private void deleteReview(Player player, ItemStack clicked) {
        Integer landmarkId = plugin.getGUIManager().getEditingLandmarkId(player.getUniqueId());
        if (landmarkId == null) return;

        int ratingId = GUIManager.getRatingId(clicked);
        if (ratingId <= 0) return;

        plugin.getRatingManager().deleteRating(landmarkId, ratingId, r2 ->
                plugin.runAtPlayer(player, () -> plugin.getGUIManager().openLandmarkReviews(player, landmarkId)));
    }

    private void reportReview(Player player, ItemStack clicked) {
        Integer landmarkId = plugin.getGUIManager().getEditingLandmarkId(player.getUniqueId());
        if (landmarkId == null) {
            player.sendMessage(plugin.getMessageUtil().get("rating_expired"));
            return;
        }

        int ratingId = GUIManager.getRatingId(clicked);
        if (ratingId <= 0) {
            player.sendMessage(plugin.getMessageUtil().get("review_report_not_found"));
            return;
        }

        plugin.getRatingManager().reportRating(landmarkId, ratingId, player.getUniqueId(), result ->
                plugin.runAtPlayer(player, () -> {
                    var report = (com.alwarp.manager.RatingManager.ReportResult) result;
                    switch (report.status()) {
                        case DISABLED -> player.sendMessage(plugin.getMessageUtil().get("review_report_disabled"));
                        case NOT_FOUND -> player.sendMessage(plugin.getMessageUtil().get("review_report_not_found"));
                        case OWN_REVIEW -> player.sendMessage(plugin.getMessageUtil().get("review_report_own"));
                        case ALREADY_REPORTED -> player.sendMessage(plugin.getMessageUtil().get("review_report_duplicate",
                                "%count%", String.valueOf(report.count()),
                                "%threshold%", String.valueOf(report.threshold())));
                        case REPORTED -> player.sendMessage(plugin.getMessageUtil().get("review_reported",
                                "%count%", String.valueOf(report.count()),
                                "%threshold%", String.valueOf(report.threshold())));
                        case DELETED -> {
                            player.sendMessage(plugin.getMessageUtil().get("review_report_deleted"));
                            plugin.getGUIManager().openLandmarkReviews(player, landmarkId);
                        }
                    }
                }));
    }
}
