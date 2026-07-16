package com.alwarp.listener;

import com.alwarp.ALwarp;
import com.alwarp.gui.GUIManager;
import com.alwarp.model.Landmark;
import com.alwarp.storage.StorageManager;
import com.alwarp.util.ItemUtil;
import com.alwarp.util.MessageUtil;
import com.alwarp.util.TextStyleUtil;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * 玩家事件监听器。
 * 处理聊天输入（地标创建、编辑）、玩家加入/退出。
 */
public class PlayerListener implements Listener {

    private final ALwarp plugin;

    public PlayerListener(ALwarp plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        String playerName = player.getName();
        ItemUtil.SkinTexture skin = ItemUtil.cachePlayerSkin(player);
        plugin.getScheduler().runTaskAsync(() ->
                plugin.getStorage().upsertPlayerRecord(playerUuid, playerName,
                        skin != null ? skin.value() : null,
                        skin != null ? skin.signature() : null));
        attemptPendingCrossServerTeleport(player, 0);
    }

    private void attemptPendingCrossServerTeleport(Player player, int attempt) {
        if (plugin.getRedisManager() == null || !plugin.getRedisManager().isEnabled()) return;
        plugin.getScheduler().runTaskLater(() -> {
            if (!player.isOnline()) return;
            plugin.runAtPlayer(player, () -> {
                boolean consumed = plugin.getRedisManager().consumePendingTeleport(player);
                if (!consumed && attempt < 4) {
                    attemptPendingCrossServerTeleport(player, attempt + 1);
                }
            });
        }, 10L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        plugin.getGUIManager().clearPlayerState(player);
        plugin.getTeleportManager().cancelTeleport(player);
        player.closeInventory();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (plugin.getTeleportManager().hasPendingTeleport(player.getUniqueId())) {
                plugin.getTeleportManager().cancelTeleport(player);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();
        GUIManager guiManager = plugin.getGUIManager();
        String pendingType = guiManager.getPendingInput(player.getUniqueId());

        if (pendingType == null) return;

        event.setCancelled(true);

        // 回到玩家实体线程处理，兼容 Folia
        plugin.runAtPlayer(player, () -> {
            switch (pendingType) {
                case "create_name":
                    handleCreateName(player, message);
                    break;
                case "create_confirm":
                    handleCreateConfirm(player, message);
                    break;
                case "set_name":
                    handleSetName(player, message);
                    break;
                case "set_description":
                    handleSetDescription(player, message);
                    break;
                case "set_price":
                    handleSetPrice(player, message);
                    break;
                case "set_icon":
                    handleSetIcon(player, message);
                    break;
                case "delete_landmark":
                    handleDeleteConfirm(player, message);
                    break;
                case "add_manager":
                    handleAddManager(player, message);
                    break;
                case "add_blacklist":
                    handleAddBlacklist(player, message);
                    break;
                case "remove_manager_confirm":
                    handleRemoveManagerConfirm(player, message);
                    break;
                case "search":
                    handleSearch(player, message);
                    break;
                case "rate_score":
                    handleRateScore(player, message);
                    break;
                case "rate_comment":
                    handleRateComment(player, message);
                    break;
            }
        });
    }

    private void handleCreateName(Player player, String name) {
        name = TextStyleUtil.stripUserFormatting(name);
        if (name.isBlank()) {
            player.sendMessage(plugin.getMessageUtil().get("input_empty"));
            plugin.getGUIManager().setPendingInput(player.getUniqueId(), "create_name");
            return;
        }
        int maxLen = getLandmarkNameMaxLength();
        if (name.length() > maxLen) {
            player.sendMessage(plugin.getMessageUtil().get("landmark_name_too_long", "%max%", String.valueOf(maxLen)));
            plugin.getGUIManager().setPendingInput(player.getUniqueId(), "create_name");
            return;
        }
        if (name.equalsIgnoreCase("Q") || name.equalsIgnoreCase("q")) {
            player.sendMessage(plugin.getMessageUtil().get("create_cancel"));
            return;
        }
        // 检查名称唯一性
        if (plugin.getConfig().getBoolean("landmark.name_unique", true)
                && !plugin.getLandmarkManager().isNameUnique(name)) {
            player.sendMessage(plugin.getMessageUtil().get("name_duplicate"));
            plugin.getGUIManager().setPendingInput(player.getUniqueId(), "create_name");
            return;
        }

        plugin.getGUIManager().setCreateName(player.getUniqueId(), name);
        double cost = plugin.getConfig().getDouble("economy.create_cost", 1000.0);
        player.sendMessage(plugin.getMessageUtil().get("create_confirm_second"));
        plugin.getGUIManager().setPendingInput(player.getUniqueId(), "create_confirm");
    }

    private void handleCreateConfirm(Player player, String confirm) {
        if (confirm.equalsIgnoreCase("Q")) {
            player.sendMessage(plugin.getMessageUtil().get("create_cancel"));
            plugin.getGUIManager().clearEditingState(player.getUniqueId());
            return;
        }
        if (!confirm.equals("确认创建")) {
            player.sendMessage(plugin.getMessageUtil().get("create_confirm_required"));
            plugin.getGUIManager().setPendingInput(player.getUniqueId(), "create_confirm");
            return;
        }

        // 检查本服是否允许创建
        if (!plugin.isCreateAllowedOnServer(player)) {
            player.sendMessage(plugin.getMessageUtil().get("create_disabled_server"));
            plugin.getGUIManager().clearEditingState(player.getUniqueId());
            return;
        }
        // 检查世界限制
        if (!plugin.canCreateInWorld(player)) {
            player.sendMessage(plugin.getMessageUtil().get("world_restricted"));
            plugin.getGUIManager().clearEditingState(player.getUniqueId());
            return;
        }

        String name = plugin.getGUIManager().getCreateName(player.getUniqueId());
        if (name == null) {
            player.sendMessage(plugin.getMessageUtil().get("create_failed_retry"));
            return;
        }

        // 检查数量限制
        int maxLandmarks = getMaxLandmarks(player);
        if (maxLandmarks > 0 && plugin.getLandmarkManager().getPlayerLandmarkCount(player.getUniqueId()) >= maxLandmarks) {
            player.sendMessage(plugin.getMessageUtil().get("landmark_limit_reached", "%max%", String.valueOf(maxLandmarks)));
            plugin.getGUIManager().clearEditingState(player.getUniqueId());
            return;
        }

        // 检查经济
        double cost = plugin.getConfig().getDouble("economy.create_cost", 1000.0);
        Economy economy = plugin.getEconomy();
        if (economy != null && cost > 0) {
            if (!economy.has(player, cost)) {
                player.sendMessage(plugin.getMessageUtil().get("no_money"));
                return;
            }
            economy.withdrawPlayer(player, cost);
        }

        // 创建地标，默认分类使用标记为 default: true 的分类
        String serverName = plugin.getServerId();
        String defaultIcon = plugin.getConfig().getString("landmark.default_icon", "COMPASS");
        var defaultCat = plugin.getCategoryManager().getDefaultCategory();
        int defaultCategoryId = defaultCat != null ? defaultCat.getId() : 1;

        Landmark landmark = new Landmark(
                name,
                null,
                player.getUniqueId(),
                player.getName(),
                serverName,
                player.getWorld().getName(),
                player.getLocation().getBlockX(),
                player.getLocation().getBlockY(),
                player.getLocation().getBlockZ(),
                defaultCategoryId,
                0.0,
                defaultIcon
        );

        plugin.getLandmarkManager().createLandmark(landmark, result -> {
            plugin.runAtPlayer(player, () -> {
                Landmark created = (Landmark) result;
                if (created != null) {
                    player.sendMessage(plugin.getMessageUtil().get("landmark_created", "%id%", String.valueOf(created.getId())));
                    player.sendMessage(plugin.getMessageUtil().get("create_manage_tip"));
                } else {
                    player.sendMessage(plugin.getMessageUtil().get("create_failed_admin"));
                }
            });
        });

        plugin.getGUIManager().clearEditingState(player.getUniqueId());
    }

    private void handleSetName(Player player, String name) {
        Landmark editing = plugin.getGUIManager().getEditingLandmark(player.getUniqueId());
        if (editing != null) {
            name = TextStyleUtil.stripUserFormatting(name);
            if (name.isBlank()) {
                player.sendMessage(plugin.getMessageUtil().get("input_empty"));
                plugin.getGUIManager().setPendingInput(player.getUniqueId(), "set_name");
                return;
            }
            int maxLen = getLandmarkNameMaxLength();
            if (name.length() > maxLen) {
                player.sendMessage(plugin.getMessageUtil().get("landmark_name_too_long", "%max%", String.valueOf(maxLen)));
                plugin.getGUIManager().setPendingInput(player.getUniqueId(), "set_name");
                return;
            }
            if (plugin.getConfig().getBoolean("landmark.name_unique", true)
                    && !name.equalsIgnoreCase(editing.getName())
                    && !plugin.getLandmarkManager().isNameUnique(name)) {
                player.sendMessage(plugin.getMessageUtil().get("name_duplicate"));
                plugin.getGUIManager().setPendingInput(player.getUniqueId(), "set_name");
                return;
            }
            editing.setName(name);
            player.sendMessage(plugin.getMessageUtil().get("set_name_success", "%name%", name));
            plugin.getGUIManager().openLandmarkManage(player, editing.getId());
        }
    }

    private void handleSetDescription(Player player, String desc) {
        desc = TextStyleUtil.stripUserFormatting(desc);
        int maxLen = getLandmarkDescriptionMaxLength();
        if (desc.length() > maxLen) {
            player.sendMessage(plugin.getMessageUtil().get("set_desc_too_long", "%max%", String.valueOf(maxLen)));
            plugin.getGUIManager().setPendingInput(player.getUniqueId(), "set_description");
            return;
        }
        Landmark editing = plugin.getGUIManager().getEditingLandmark(player.getUniqueId());
        if (editing != null) {
            editing.setDescription(desc);
            player.sendMessage(plugin.getMessageUtil().get("set_desc_success"));
            plugin.getGUIManager().openLandmarkManage(player, editing.getId());
        }
    }

    private void handleSetPrice(Player player, String priceStr) {
        try {
            double price = Double.parseDouble(priceStr);
            double min = plugin.getConfig().getDouble("economy.minimum_price", 0);
            double max = plugin.getConfig().getDouble("economy.maximum_price", 10000);
            if (price < min || price > max) {
                player.sendMessage(plugin.getMessageUtil().get("set_price_range",
                        "%min%", MessageUtil.formatDouble(min),
                        "%max%", MessageUtil.formatDouble(max)));
                return;
            }
            Landmark editing = plugin.getGUIManager().getEditingLandmark(player.getUniqueId());
            if (editing != null) {
                editing.setPrice(price);
                player.sendMessage(plugin.getMessageUtil().get("set_price_success", "%price%", MessageUtil.formatDouble(price)));
                plugin.getGUIManager().openLandmarkManage(player, editing.getId());
            }
        } catch (NumberFormatException e) {
            player.sendMessage(plugin.getMessageUtil().get("invalid_number"));
        }
    }

    private void handleSetIcon(Player player, String confirm) {
        if (!confirm.equals("确认")) {
            player.sendMessage(plugin.getMessageUtil().get("set_icon_cancel"));
            return;
        }
        Landmark editing = plugin.getGUIManager().getEditingLandmark(player.getUniqueId());
        if (editing != null) {
            org.bukkit.inventory.ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand.getType().isAir()) {
                player.sendMessage(plugin.getMessageUtil().get("set_icon_no_item"));
                return;
            }
            org.bukkit.inventory.ItemStack iconSource = ItemUtil.resolveIconSource(hand);
            editing.setIcon(iconSource.getType().name());
            var iconMeta = iconSource.getItemMeta();
            if (iconMeta != null && iconMeta.hasCustomModelData()) {
                editing.setIconCustomModelData(iconMeta.getCustomModelData());
            } else {
                editing.setIconCustomModelData(null);
            }
            // CE/IA/Oraxen 自定义物品：存完整序列化数据
            String pluginItem = ItemUtil.detectPluginItemId(iconSource);
            editing.setIconPluginItem(pluginItem);
            if (pluginItem != null || iconSource != hand) {
                editing.setIconData(ItemUtil.serializeIconOnly(iconSource));
            } else {
                editing.setIconData(null);
            }
            player.sendMessage(plugin.getMessageUtil().get("set_icon_success",
                    "%type%", pluginItem != null ? plugin.getMessageUtil().get("custom_item_suffix") : ""));
            plugin.getGUIManager().openLandmarkManage(player, editing.getId());
        }
    }

    private void handleDeleteConfirm(Player player, String confirm) {
        if (!confirm.equals("确认删除")) {
            player.sendMessage(plugin.getMessageUtil().get("delete_cancel"));
            return;
        }
        Landmark editing = plugin.getGUIManager().getEditingLandmark(player.getUniqueId());
        if (editing != null) {
            if (!plugin.getGUIManager().canManageLandmarkManagers(player, editing)) {
                player.sendMessage(plugin.getMessageUtil().get("no_permission"));
                return;
            }
            plugin.getLandmarkManager().deleteLandmark(editing.getId(), result -> {
                plugin.runAtPlayer(player, () -> {
                    if ((boolean) result) {
                        player.sendMessage(plugin.getMessageUtil().get("landmark_deleted"));
                    }
                });
            });
        }
        plugin.getGUIManager().clearEditingState(player.getUniqueId());
    }

    private void handleAddManager(Player player, String targetName) {
        Landmark editing = plugin.getGUIManager().getEditingLandmark(player.getUniqueId());
        if (editing == null) return;
        if (!plugin.getGUIManager().canManageLandmarkManagers(player, editing)) {
            player.sendMessage(plugin.getMessageUtil().get("no_permission"));
            return;
        }

        PlayerIdentity target = resolveKnownPlayer(targetName);
        if (target == null) {
            player.sendMessage(plugin.getMessageUtil().get("manager_not_found", "%player%", targetName));
            return;
        }

        plugin.getManagerManager().addManager(editing.getId(), target.uuid(), target.name(), result -> {
            plugin.runAtPlayer(player, () -> {
                if (result != null) {
                    player.sendMessage(plugin.getMessageUtil().get("manager_added", "%player%", target.name()));
                } else {
                    player.sendMessage(plugin.getMessageUtil().get("manager_already_exists", "%player%", target.name()));
                }
                plugin.getGUIManager().openAdminList(player, editing.getId());
            });
        });
    }

    private void handleAddBlacklist(Player player, String targetName) {
        Integer landmarkId = plugin.getGUIManager().getEditingLandmarkId(player.getUniqueId());
        Landmark editing = plugin.getGUIManager().getEditingLandmark(player.getUniqueId());
        if (editing == null && landmarkId != null) {
            editing = plugin.getLandmarkManager().getLandmark(landmarkId);
        }
        if (editing == null) return;
        if (!plugin.getGUIManager().canManageLandmarkManagers(player, editing)) {
            player.sendMessage(plugin.getMessageUtil().get("no_permission"));
            return;
        }

        PlayerIdentity target = resolveKnownPlayer(targetName);
        if (target == null) {
            player.sendMessage(plugin.getMessageUtil().get("player_record_not_found", "%player%", targetName));
            plugin.getGUIManager().openBlacklistList(player, editing.getId());
            return;
        }
        if (editing.getOwnerUuid().equals(target.uuid())) {
            player.sendMessage(plugin.getMessageUtil().get("blacklist_owner_blocked"));
            plugin.getGUIManager().openBlacklistList(player, editing.getId());
            return;
        }

        final Landmark targetLandmark = editing;
        plugin.getBlacklistManager().addBlacklist(targetLandmark.getId(), target.uuid(), target.name(), result -> {
            plugin.runAtPlayer(player, () -> {
                if (result != null) {
                    player.sendMessage(plugin.getMessageUtil().get("blacklist_added", "%player%", target.name()));
                } else {
                    player.sendMessage(plugin.getMessageUtil().get("blacklist_already_exists", "%player%", target.name()));
                }
                plugin.getGUIManager().openBlacklistList(player, targetLandmark.getId());
            });
        });
    }

    private PlayerIdentity resolveKnownPlayer(String name) {
        Player online = plugin.getServer().getPlayerExact(name);
        if (online != null) {
            return new PlayerIdentity(online.getUniqueId(), online.getName());
        }

        StorageManager.PlayerRecord stored = plugin.getStorage().getPlayerRecordByName(name);
        if (stored != null) {
            return new PlayerIdentity(stored.playerUuid(), stored.playerName());
        }

        return null;
    }

    private record PlayerIdentity(UUID uuid, String name) {}

    private void handleRemoveManagerConfirm(Player player, String confirm) {
        if (!confirm.equals("确认")) {
            player.sendMessage(plugin.getMessageUtil().get("cancelled"));
            return;
        }
        // Remove manager logic is handled via the action handler with UUID parameter
    }

    private void handleSearch(Player player, String query) {
        GUIManager guiManager = plugin.getGUIManager();
        String returnMenu = guiManager.getSearchReturnMenu(player.getUniqueId());
        if (returnMenu == null) returnMenu = "main_menu";
        guiManager.setSearch(player, returnMenu, query);
        guiManager.openMenu(player, returnMenu);
    }

    private void handleRateScore(Player player, String scoreStr) {
        Integer landmarkId = plugin.getGUIManager().getEditingLandmarkId(player.getUniqueId());
        if (landmarkId == null) {
            player.sendMessage(plugin.getMessageUtil().get("rating_expired"));
            return;
        }
        if (!validateRatingSession(player, landmarkId)) {
            return;
        }
        try {
            int score = Integer.parseInt(scoreStr.trim());
            int minScore = plugin.getRatingManager().getMinScore();
            int maxScore = plugin.getRatingManager().getMaxScore();
            if (score < minScore || score > maxScore) {
                player.sendMessage(plugin.getMessageUtil().get("rate_invalid_score",
                        "%min%", String.valueOf(minScore),
                        "%max%", String.valueOf(maxScore)));
                plugin.getGUIManager().setPendingInput(player.getUniqueId(), "rate_score");
                return;
            }
            plugin.getRatingManager().hasVisitedLandmark(player.getUniqueId(), landmarkId, visited ->
                    plugin.runAtPlayer(player, () -> {
                        if (!(boolean) visited) {
                            player.sendMessage(plugin.getMessageUtil().get("rating_requires_visit"));
                            plugin.getGUIManager().clearEditingState(player.getUniqueId());
                            return;
                        }
                        plugin.getGUIManager().setPendingScore(player.getUniqueId(), score);
                        plugin.getGUIManager().setPendingInput(player.getUniqueId(), "rate_comment");
                        sendRateCommentPrompt(player);
                    }));
        } catch (NumberFormatException e) {
            player.sendMessage(plugin.getMessageUtil().get("rate_invalid_score",
                    "%min%", String.valueOf(plugin.getRatingManager().getMinScore()),
                    "%max%", String.valueOf(plugin.getRatingManager().getMaxScore())));
            plugin.getGUIManager().setPendingInput(player.getUniqueId(), "rate_score");
        }
    }

    private void handleRateComment(Player player, String comment) {
        int minLength = getRatingCommentMinLength();
        int maxLength = getRatingCommentMaxLength();
        if (comment.length() < minLength) {
            player.sendMessage(plugin.getMessageUtil().get("rate_comment_short",
                    "%min%", String.valueOf(minLength)));
            plugin.getGUIManager().setPendingInput(player.getUniqueId(), "rate_comment");
            return;
        }
        if (maxLength > 0 && comment.length() > maxLength) {
            player.sendMessage(plugin.getMessageUtil().get("rate_comment_too_long",
                    "%max%", String.valueOf(maxLength)));
            plugin.getGUIManager().setPendingInput(player.getUniqueId(), "rate_comment");
            return;
        }

        Integer landmarkId = plugin.getGUIManager().getEditingLandmarkId(player.getUniqueId());
        Integer score = plugin.getGUIManager().getPendingScore(player.getUniqueId());
        if (landmarkId == null || score == null) {
            player.sendMessage(plugin.getMessageUtil().get("rating_expired"));
            return;
        }
        if (!validateRatingSession(player, landmarkId)) {
            return;
        }

        plugin.getRatingManager().hasVisitedLandmark(player.getUniqueId(), landmarkId, visited ->
                plugin.runAtPlayer(player, () -> {
                    if (!(boolean) visited) {
                        player.sendMessage(plugin.getMessageUtil().get("rating_requires_visit"));
                        plugin.getGUIManager().clearEditingState(player.getUniqueId());
                        return;
                    }
                    plugin.getRatingManager().addOrUpdateRating(landmarkId, player.getUniqueId(),
                            player.getName(), score, comment, result ->
                                    plugin.runAtPlayer(player, () -> {
                                        if (result != null) {
                                            player.sendMessage(plugin.getMessageUtil().get("rating_added"));
                                        } else {
                                            player.sendMessage(plugin.getMessageUtil().get("rating_requires_visit"));
                                        }
                                    }));
                    plugin.getGUIManager().clearEditingState(player.getUniqueId());
                }));
    }

    private boolean validateRatingSession(Player player, int landmarkId) {
        if (!plugin.getRatingManager().isEnabled()) {
            player.sendMessage(plugin.getMessageUtil().get("rating_disabled"));
            plugin.getGUIManager().clearEditingState(player.getUniqueId());
            return false;
        }
        Landmark lm = plugin.getLandmarkManager().getLandmark(landmarkId);
        if (lm == null) {
            player.sendMessage(plugin.getMessageUtil().get("landmark_not_found"));
            plugin.getGUIManager().clearEditingState(player.getUniqueId());
            return false;
        }
        if (!plugin.canAccessLandmarkServer(player, lm)) {
            player.sendMessage(plugin.getMessageUtil().get("server_permission_denied"));
            plugin.getGUIManager().clearEditingState(player.getUniqueId());
            return false;
        }
        if (!plugin.getGUIManager().canViewLandmark(player, lm)) {
            player.sendMessage(plugin.getMessageUtil().get("landmark_private_no_access"));
            plugin.getGUIManager().clearEditingState(player.getUniqueId());
            return false;
        }
        if (plugin.getBlacklistManager().isBlacklisted(landmarkId, player.getUniqueId())) {
            player.sendMessage(plugin.getMessageUtil().get("landmark_blacklisted"));
            plugin.getGUIManager().clearEditingState(player.getUniqueId());
            return false;
        }
        return true;
    }

    private void sendRateCommentPrompt(Player player) {
        int minLength = getRatingCommentMinLength();
        int maxLength = getRatingCommentMaxLength();
        if (maxLength > 0) {
            player.sendMessage(plugin.getMessageUtil().get("rate_comment_prompt_limited",
                    "%min%", String.valueOf(minLength),
                    "%max%", String.valueOf(maxLength)));
        } else {
            player.sendMessage(plugin.getMessageUtil().get("rate_comment_prompt",
                    "%min%", String.valueOf(minLength)));
        }
    }

    private int getRatingCommentMinLength() {
        return Math.max(0, plugin.getConfig().getInt("rating.min_comment_length", 3));
    }

    private int getRatingCommentMaxLength() {
        return plugin.getConfig().getInt("rating.max_comment_length", 50);
    }

    private int getLandmarkNameMaxLength() {
        return Math.max(1, plugin.getConfig().getInt("landmark.max_name_length", 8));
    }

    private int getLandmarkDescriptionMaxLength() {
        return Math.max(0, plugin.getConfig().getInt("landmark.max_description_length", 50));
    }

    /**
     * 根据权限获取玩家可创建的地标数量上限。
     * 优先级：alwarp.bypass.limit / alwarp.limit.unlimited > alwarp.limit.<N> > 默认值
     */
    private int getMaxLandmarks(Player player) {
        if (player.hasPermission("alwarp.bypass.limit")
                || player.hasPermission("alwarp.limit.unlimited")) {
            return -1; // 不限
        }
        // 遍历 alwarp.limit.<N> 权限，取最大值
        int max = -1;
        for (org.bukkit.permissions.PermissionAttachmentInfo perm : player.getEffectivePermissions()) {
            String node = perm.getPermission();
            if (node.startsWith("alwarp.limit.")) {
                String numStr = node.substring("alwarp.limit.".length());
                try {
                    int n = Integer.parseInt(numStr);
                    if (n > max) max = n;
                } catch (NumberFormatException ignored) {}
            }
        }
        if (max > 0) return max;
        // 使用默认值
        return plugin.getConfig().getInt("landmark.default_limit", 3);
    }
}
