package com.alwarp.gui;

import com.alwarp.ALwarp;
import com.alwarp.gui.shape.ActionHandler;
import com.alwarp.gui.shape.ColorOption;
import com.alwarp.gui.shape.MenuConfig;
import com.alwarp.gui.shape.MenuItem;
import com.alwarp.gui.shape.ShapeMenuManager;
import com.alwarp.model.Category;
import com.alwarp.model.Landmark;
import com.alwarp.model.LandmarkAdmin;
import com.alwarp.model.LandmarkBlacklist;
import com.alwarp.model.LandmarkPin;
import com.alwarp.model.Rating;
import com.alwarp.util.ItemUtil;
import com.alwarp.util.MessageUtil;
import com.alwarp.util.TextStyleUtil;
import org.bukkit.World;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;

/**
 * GUI生命周期管理器。
 * 管理玩家的GUI状态、页面、过滤、编辑中的地标和待处理输入。
 */
public class GUIManager {

    private final ALwarp plugin;
    private final ShapeMenuManager shapeMenuManager;
    private final ActionHandler actionHandler;

    // 玩家GUI状态
    private final Map<UUID, Integer> playerPages = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playerFilters = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerSearches = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> favoriteFilters = new ConcurrentHashMap<>();
    private final Map<UUID, String> favoriteSearches = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> adminFilters = new ConcurrentHashMap<>();
    private final Map<UUID, String> adminSearches = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerCurrentMenu = new ConcurrentHashMap<>();

    // 编辑状态
    private final Map<UUID, Landmark> editingLandmarks = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> editingLandmarkIds = new ConcurrentHashMap<>();

    // 聊天输入状态
    private final Map<UUID, String> pendingInputs = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingCreateName = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> pendingScores = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingSearchReturnMenu = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> pendingTeleportConfirmations = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> pendingPinSlots = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> selectedPinLandmarkIds = new ConcurrentHashMap<>();
    private final Map<UUID, String> landmarkColorTargets = new ConcurrentHashMap<>();

    // 分类选择后返回的菜单（null=返回主菜单）
    private final Map<UUID, String> categoryReturnMenu = new ConcurrentHashMap<>();

    private static final int ITEMS_PER_PAGE = 21;
    private static final int DEFAULT_DESCRIPTION_WRAP_WIDTH = 10;
    private static final int DEFAULT_REVIEW_COMMENT_WRAP_WIDTH = 10;
    private static final String COLOR_ALL_PERMISSION = "alwarp.color.all";
    private static final DateTimeFormatter ZH_REVIEW_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy年M月d日");
    private static final DateTimeFormatter ZH_REVIEW_TIME_FORMAT = DateTimeFormatter.ofPattern("HH时mm分ss秒");
    private static final DateTimeFormatter EN_REVIEW_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter EN_REVIEW_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter PIN_EXPIRES_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public GUIManager(ALwarp plugin, ShapeMenuManager shapeMenuManager) {
        this.plugin = plugin;
        this.shapeMenuManager = shapeMenuManager;
        this.actionHandler = new ActionHandler(plugin, this);
    }

    public ActionHandler getActionHandler() {
        return actionHandler;
    }

    /** 标记正在切换菜单，防止 onClose 清除状态 */
    private final Set<UUID> switchingMenu = ConcurrentHashMap.newKeySet();
    /** 标记由 silent-close 触发的关闭，跳过一次关闭事件里的额外清理 */
    private final Set<UUID> silentClosingMenu = ConcurrentHashMap.newKeySet();

    /**
     * 打开指定菜单。
     */
    public void openMenu(Player player, String menuName) {
        menuName = normalizeMenuName(menuName, "main_menu");
        // 打开非编辑相关菜单时清除编辑副本
        if (!"landmark_manage".equals(menuName)
                && !"category_select".equals(menuName)
                && !"landmark_color_select".equals(menuName)) {
            UUID u = player.getUniqueId();
            editingLandmarks.remove(u);
            editingLandmarkIds.remove(u);
        }
        switchingMenu.add(player.getUniqueId());
        playerCurrentMenu.put(player.getUniqueId(), menuName);

        Inventory inv = shapeMenuManager.createInventory(menuName);

        MenuConfig config = shapeMenuManager.getMenu(menuName);
        if (config != null) {
            List<Landmark> menuLandmarks = getMenuLandmarks(player, menuName);
            int totalItems = getTotalItems(player, menuName, menuLandmarks);
            int aSlots = getASlotCount(menuName);
            int totalPages = getTotalPages(totalItems, aSlots);
            int page = clampPage(player, totalPages);

            // 替换静态占位符（如 %current_search%、%current_filter%）
            replaceStaticPlaceholders(player, inv, config, menuName, page, totalPages);
            fillDynamicArea(player, inv, config, menuName, menuLandmarks);
        }

        openInventorySafe(player, inv);
    }

    public void refreshOpenMenu(Player player, String menuName) {
        menuName = normalizeMenuName(menuName, "main_menu");
        if ("landmark_manage".equals(menuName)) return;

        playerCurrentMenu.put(player.getUniqueId(), menuName);
        Inventory inv = shapeMenuManager.createInventory(menuName);

        MenuConfig config = shapeMenuManager.getMenu(menuName);
        if (config != null) {
            List<Landmark> menuLandmarks = getMenuLandmarks(player, menuName);
            int totalItems = getTotalItems(player, menuName, menuLandmarks);
            int aSlots = getASlotCount(menuName);
            int totalPages = getTotalPages(totalItems, aSlots);
            int page = clampPage(player, totalPages);

            replaceStaticPlaceholders(player, inv, config, menuName, page, totalPages);
            fillDynamicArea(player, inv, config, menuName, menuLandmarks);
        }

        player.getOpenInventory().getTopInventory().setContents(inv.getContents());
    }

    /** 替换所有非A区域物品中的静态占位符 */
    private void replaceStaticPlaceholders(Player player, Inventory inv, MenuConfig config, String menuName,
                                           int page, int totalPages) {
        String search = getSearch(player.getUniqueId(), menuName);
        double cost = plugin.getConfig().getDouble("economy.create_cost", 1000.0);
        String pendingIncome = "0";
        String incomeTaxRate = "0";
        String incomeTax = "0";
        String claimableIncome = "0";
        Integer pinSlot = pendingPinSlots.get(player.getUniqueId());
        Landmark selectedPinLandmark = null;
        Integer selectedPinLandmarkId = selectedPinLandmarkIds.get(player.getUniqueId());
        if (selectedPinLandmarkId != null) {
            selectedPinLandmark = plugin.getLandmarkManager().getLandmark(selectedPinLandmarkId);
        }
        if ("my_landmarks".equals(menuName)) {
            double grossIncome = plugin.getIncomeManager().getCachedPendingIncome(player.getUniqueId());
            var claim = plugin.getTaxManager().previewClaim(player, grossIncome);
            pendingIncome = MessageUtil.formatDouble(claim.gross());
            incomeTaxRate = MessageUtil.formatDouble(claim.rate() * 100.0);
            incomeTax = MessageUtil.formatDouble(claim.tax());
            claimableIncome = MessageUtil.formatDouble(claim.net());
        }

        var shape = config.getShape();
        for (int row = 0; row < shape.size(); row++) {
            String line = shape.get(row);
            for (int col = 0; col < Math.min(line.length(), 9); col++) {
                char c = line.charAt(col);
                if (c == ' ') {
                    inv.setItem(row * 9 + col, null);
                    continue;
                }
                if (c == 'A') continue; // A区域由fillDynamicArea处理
                int slot = row * 9 + col;
                ItemStack item = inv.getItem(slot);
                if (item == null) continue;

                item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%current_search%", search != null ? search : "无");
                item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%current_filter%",
                        getCurrentFilterName(getFilter(player.getUniqueId(), menuName)));
                item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%create_cost%", String.format("%.0f", cost));
                item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%pending_income%", pendingIncome);
                item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%income_tax_rate%", incomeTaxRate);
                item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%income_tax%", incomeTax);
                item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%claimable_income%", claimableIncome);
                item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%current_page%", String.valueOf(page));
                item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%total_pages%", String.valueOf(totalPages));
                if (pinSlot != null) {
                    item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%pin_slot%", String.valueOf(pinSlot));
                    item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%pin_cost%", MessageUtil.formatDouble(getPinCost(pinSlot)));
                    item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%pin_days%", String.valueOf(getPinDurationDays(pinSlot)));
                    item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%pin_selected_landmark%",
                            selectedPinLandmark != null ? selectedPinLandmark.getName() : plugin.getMessageUtil().get("pin_selected_none"));
                }
                if ("landmark_color_select".equals(menuName)) {
                    item = replaceColorMenuPlaceholders(player, item);
                }
                inv.setItem(slot, item);
            }
        }
    }

    /**
     * 填充A区域（动态地标列表）或 category_select 的动态分类按钮。
     */
    private void fillDynamicArea(Player player, Inventory inv, MenuConfig config, String menuName, List<Landmark> menuLandmarks) {
        if ("category_select".equals(menuName)) {
            fillCategorySelect(inv, config);
            return;
        }
        if ("landmark_color_select".equals(menuName)) {
            fillLandmarkColorSelect(player, inv, config);
            return;
        }
        fillLandmarkArea(player, inv, config, menuName, menuLandmarks);
        if ("main_menu".equals(menuName)) {
            fillPinSlots(player, inv, config);
        }
    }

    /**
     * 动态填充分类选择界面——从数据库缓存读取所有分类。
     */
    private void fillCategorySelect(Inventory inv, MenuConfig config) {
        List<Category> categories = plugin.getCategoryManager().getAllCategories();
        List<String> shape = config.getShape();
        // 收集所有非#非空的字符位置作为分类按钮位
        List<Integer> slots = new ArrayList<>();
        List<Character> chars = new ArrayList<>();
        for (int row = 0; row < shape.size(); row++) {
            String line = shape.get(row);
            for (int col = 0; col < Math.min(line.length(), 9); col++) {
                char c = line.charAt(col);
                MenuItem template = config.getItems().get(String.valueOf(c));
                if (isCategorySlot(template)) {
                    slots.add(row * 9 + col);
                    chars.add(c);
                }
            }
        }

        for (int i = 0; i < Math.min(categories.size(), slots.size()); i++) {
            Category cat = categories.get(i);
            int slot = slots.get(i);
            MenuItem template = config.getItems().get(String.valueOf(chars.get(i)));

            if (template != null) {
                ItemStack item = ItemUtil.buildItem(
                        cat.getIcon(),
                        cat.getIconCustomModelData(),
                        cat.getIconPluginItem(),
                        template.getName(),
                        template.getLore()
                );
                // 替换分类占位符
                item = ItemUtil.replacePlaceholders(item, "%category_name%", cat.getName());
                item = ItemUtil.replacePlaceholders(item, "%category_color%", cat.getColor());
                // 确保action正确
                setCategoryId(item, cat.getId());
                inv.setItem(slot, item);
            }
        }
    }

    private boolean isCategorySlot(MenuItem item) {
        if (item == null) return false;
        if (startsWithAction(item.getAction(), "filter:")) return true;
        if (Boolean.TRUE.equals(item.getIsDefault())) return true;
        for (String trigger : List.of("left", "right", "shift_left", "shift_right")) {
            for (String action : item.getTriggerActions(trigger)) {
                if (startsWithAction(action, "filter:")
                        || startsWithAction(action, "action:filter:")
                        || startsWithAction(action, "action: filter:")) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean startsWithAction(String action, String prefix) {
        return action != null && action.trim().toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT));
    }

    private void fillLandmarkColorSelect(Player player, Inventory inv, MenuConfig config) {
        List<Integer> aSlots = getSlotsForChar(config, 'A');
        for (int slot : aSlots) {
            inv.setItem(slot, null);
        }
        if (aSlots.isEmpty()) return;

        MenuItem template = config.getItems().get("A");
        List<ColorOption> options = getColorOptions(config);
        if (options.isEmpty()) return;

        int page = playerPages.getOrDefault(player.getUniqueId(), 1);
        int startIndex = (page - 1) * aSlots.size();
        int endIndex = Math.min(startIndex + aSlots.size(), options.size());
        int slotIndex = 0;
        for (int i = startIndex; i < endIndex; i++) {
            ColorOption option = options.get(i);

            ItemStack item = ItemUtil.buildItem(
                    option.getType() != null ? option.getType() : template != null ? template.getType() : "PAPER",
                    option.getCustomModelData() != null ? option.getCustomModelData() : template != null ? template.getCustomModelData() : null,
                    option.getPluginItem() != null ? option.getPluginItem() : template != null ? template.getPluginItem() : null,
                    option.getName() != null ? option.getName() : template != null ? template.getName() : "%color_sample%",
                    option.getLore() != null && !option.getLore().isEmpty()
                            ? option.getLore()
                            : template != null ? template.getLore() : List.of("&a点击选择")
            );
            item = replaceColorOptionPlaceholders(player, item, option);
            setColorOptionId(item, option.getId());
            inv.setItem(aSlots.get(slotIndex), item);
            slotIndex++;
        }
    }

    private List<ColorOption> getColorOptions(MenuConfig config) {
        if (config == null || config.getColors() == null || config.getColors().isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(config.getColors().values());
    }

    private boolean canUseColorOption(Player player, ColorOption option) {
        if (player.hasPermission(COLOR_ALL_PERMISSION)) return true;
        String permission = option.getPermission();
        return permission == null || permission.isBlank() || player.hasPermission(permission);
    }

    private boolean canUseBold(Player player) {
        String permission = getBoldPermission();
        return permission == null || permission.isBlank() || player.hasPermission(permission);
    }

    private ItemStack replaceColorMenuPlaceholders(Player player, ItemStack item) {
        String target = getLandmarkColorTarget(player.getUniqueId());
        item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%color_target%", getColorTargetLabel(target));
        item = ItemUtil.replacePlaceholders(item, "%color_current_value%", getCurrentColorPreview(player));
        item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%color_bold_status%",
                isCurrentColorBold(player) ? plugin.localize("&l已加粗", "&lBold") : plugin.localize("普通", "Normal"));
        MenuConfig config = shapeMenuManager.getMenu("landmark_color_select");
        String boldPermission = getBoldPermission();
        item = ItemUtil.replacePlaceholders(item, "%color_bold_permission%",
                formatPermissionStatus(canUseBold(player)));
        item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%color_bold_permission_node%", boldPermission);
        UnlockCost boldCost = getBoldUnlockCost();
        if (canUseBold(player) || !boldCost.enabled()) {
            item = removeLoreLineContaining(item, "%color_bold_purchase_price%");
            item = removeLoreLineContaining(item, "%color_bold_purchase_hint%");
        } else {
            item = ItemUtil.replacePlaceholders(item, "%color_bold_purchase_price%", formatUnlockPriceLine(boldCost));
            item = ItemUtil.replacePlaceholders(item, "%color_bold_purchase_hint%", plugin.localize("&eShift+右键购买", "&eShift+right click to buy"));
        }
        return item;
    }

    private ItemStack replaceColorOptionPlaceholders(Player player, ItemStack item, ColorOption option) {
        item = replaceColorMenuPlaceholders(player, item);
        item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%color_id%", option.getId());
        item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%color_name%", option.getName() != null ? option.getName() : option.getId());
        item = ItemUtil.replacePlaceholders(item, "%color_sample%",
                TextStyleUtil.render(option.getSample(), option.getStyle(), isCurrentColorBold(player)));
        item = ItemUtil.replacePlaceholders(item, "%color_permission%",
                formatPermissionStatus(canUseColorOption(player, option)));
        item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%color_permission_node%",
                option.getPermission() != null && !option.getPermission().isBlank()
                        ? option.getPermission()
                        : plugin.localize("无", "None"));
        UnlockCost colorCost = getColorUnlockCost(option);
        if (canUseColorOption(player, option) || !colorCost.enabled()) {
            item = removeLoreLineContaining(item, "%color_purchase_price%");
            item = removeLoreLineContaining(item, "%color_purchase_hint%");
        } else {
            item = ItemUtil.replacePlaceholders(item, "%color_purchase_price%", formatUnlockPriceLine(colorCost));
            item = ItemUtil.replacePlaceholders(item, "%color_purchase_hint%", plugin.localize("&eShift+右键购买", "&eShift+right click to buy"));
        }
        return item;
    }

    private String formatPermissionStatus(Player player, String permission) {
        boolean allowed = permission == null || permission.isBlank() || player.hasPermission(permission);
        return formatPermissionStatus(allowed);
    }

    private String formatPermissionStatus(boolean allowed) {
        return allowed
                ? plugin.localize("&a有", "&aYes")
                : plugin.localize("&c无", "&cNo");
    }

    private UnlockCost getColorUnlockCost(ColorOption option) {
        boolean enabled = plugin.getConfig().getBoolean("landmark.color_system.unlock.enabled", true)
                && plugin.getConfig().getBoolean("landmark.color_system.unlock.color.enabled", true);
        if (option != null && option.getPurchaseEnabled() != null) {
            enabled = option.getPurchaseEnabled();
        }
        String currency = firstNonBlank(
                option != null ? option.getPurchaseCurrency() : null,
                plugin.getConfig().getString("landmark.color_system.unlock.color.currency", "playerpoints")
        );
        double price = option != null && option.getPurchasePrice() != null
                ? option.getPurchasePrice()
                : plugin.getConfig().getDouble("landmark.color_system.unlock.color.price", 500.0);
        String currencyName = firstNonBlank(
                option != null ? option.getPurchaseCurrencyName() : null,
                getCurrencyDisplayName(currency)
        );
        return new UnlockCost(enabled, normalizeCurrency(currency), Math.max(0.0, price), currencyName);
    }

    private UnlockCost getBoldUnlockCost() {
        boolean enabled = plugin.getConfig().getBoolean("landmark.color_system.unlock.enabled", true)
                && plugin.getConfig().getBoolean("landmark.color_system.unlock.bold.enabled", true);
        String currency = plugin.getConfig().getString("landmark.color_system.unlock.bold.currency", "playerpoints");
        double price = plugin.getConfig().getDouble("landmark.color_system.unlock.bold.price", 500.0);
        return new UnlockCost(enabled, normalizeCurrency(currency), Math.max(0.0, price), getCurrencyDisplayName(currency));
    }

    private String formatUnlockPriceLine(UnlockCost cost) {
        return plugin.localize("&f购买价格：&e", "&fPrice: &e")
                + formatUnlockAmount(cost)
                + " &6"
                + cost.currencyName();
    }

    private String formatUnlockAmount(UnlockCost cost) {
        if ("playerpoints".equals(cost.currency())) {
            return String.valueOf((int) Math.ceil(cost.price()));
        }
        return MessageUtil.formatDouble(cost.price());
    }

    private String getCurrencyDisplayName(String currency) {
        String normalized = normalizeCurrency(currency);
        String fallback = "playerpoints".equals(normalized)
                ? plugin.localize("点券", "points")
                : plugin.localize("金币", "coins");
        return plugin.getConfig().getString("landmark.color_system.unlock.currencies." + normalized + ".name", fallback);
    }

    private String normalizeCurrency(String currency) {
        if (currency == null) return "vault";
        String normalized = currency.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("points")
                || normalized.equals("point")
                || normalized.equals("playerpoint")
                || normalized.equals("playerpoints")
                || normalized.equals("pp")
                || normalized.equals("点券")) {
            return "playerpoints";
        }
        return "vault";
    }

    private String firstNonBlank(String first, String fallback) {
        return first != null && !first.isBlank() ? first : fallback;
    }

    private String getBoldPermission() {
        MenuConfig config = shapeMenuManager.getMenu("landmark_color_select");
        String permission = config != null ? config.getBoldPermission() : null;
        return permission != null && !permission.isBlank() ? permission : "alwarp.color.bold";
    }

    private record UnlockCost(boolean enabled, String currency, double price, String currencyName) {}

    /**
     * 填充A区域（动态地标列表）。
     */
    private void fillLandmarkArea(Player player, Inventory inv, MenuConfig config, String menuName, List<Landmark> landmarks) {
        // 查找A字符在Shape中的位置
        List<String> shape = config.getShape();
        List<Integer> aSlots = new ArrayList<>();
        for (int row = 0; row < shape.size(); row++) {
            String line = shape.get(row);
            for (int col = 0; col < Math.min(line.length(), 9); col++) {
                if (line.charAt(col) == 'A') {
                    aSlots.add(row * 9 + col);
                }
            }
        }
        if (aSlots.isEmpty()) return;

        // 先清空所有A槽位
        for (int slot : aSlots) inv.setItem(slot, null);

        if (landmarks == null) landmarks = getMenuLandmarks(player, menuName);

        if (landmarks.isEmpty()) return;

        int page = playerPages.getOrDefault(player.getUniqueId(), 1);
        int startIndex = (page - 1) * aSlots.size();
        int endIndex = Math.min(startIndex + aSlots.size(), landmarks.size());

        MenuItem templateItem = config.getItems().get("A");
        if (templateItem == null) return;

        for (int i = startIndex; i < endIndex; i++) {
            int slotIndex = i - startIndex;
            if (slotIndex >= aSlots.size()) break;

            Landmark lm = landmarks.get(i);
            int slot = aSlots.get(slotIndex);

            ItemStack item = buildLandmarkItem(player, templateItem, lm);
            // 把地标ID存入PDC，点击时读取
            setLandmarkId(item, lm.getId());
            inv.setItem(slot, item);
        }
    }

    private void fillPinSlots(Player player, Inventory inv, MenuConfig config) {
        for (PinSlot pinSlot : getConfiguredPinSlots(config)) {
            MenuItem slotItem = config.getItems().get(pinSlot.key());
            if (slotItem == null) continue;

            LandmarkPin pin = plugin.getPinManager().getActivePin(pinSlot.slotIndex());
            ItemStack item;
            if (pin != null) {
                Landmark lm = plugin.getLandmarkManager().getLandmark(pin.getLandmarkId());
                if (lm != null && plugin.canAccessLandmarkServer(player, lm)) {
                    MenuItem landmarkTemplate = config.getItems().get("A");
                    item = buildLandmarkItem(player, landmarkTemplate != null ? landmarkTemplate : slotItem, lm);
                    item = replacePinPlaceholders(item, pinSlot.slotIndex(), slotItem, pin);
                    item = appendPinExpiresLore(item, pinSlot.slotIndex(), pin);
                    setLandmarkId(item, lm.getId());
                } else {
                    item = buildPinSlotItem(pinSlot.slotIndex(), slotItem, pin);
                }
            } else {
                item = buildPinSlotItem(pinSlot.slotIndex(), slotItem, null);
            }
            setPinSlot(item, pinSlot.slotIndex());
            inv.setItem(pinSlot.inventorySlot(), item);
        }
    }

    private ItemStack buildPinSlotItem(int slotIndex, MenuItem slotItem, LandmarkPin pin) {
        ItemStack item = ItemUtil.buildItem(
                slotItem.getType(),
                slotItem.getCustomModelData(),
                slotItem.getPluginItem(),
                slotItem.getName(),
                slotItem.getLore()
        );
        return replacePinPlaceholders(item, slotIndex, slotItem, pin);
    }

    private ItemStack replacePinPlaceholders(ItemStack item, int slotIndex, MenuItem slotItem, LandmarkPin pin) {
        if (pin == null) {
            item = removeLoreLineContaining(item, "%pin_expires%");
        }
        item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%pin_slot%", String.valueOf(slotIndex));
        item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%pin_cost%", MessageUtil.formatDouble(getPinCost(slotIndex)));
        item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%pin_days%", String.valueOf(getPinDurationDays(slotIndex)));
        item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%pin_expires%",
                pin != null ? formatPinExpires(pin.getExpiresAt()) : plugin.getMessageUtil().get("text_none"));
        item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%pin_status%",
                pin != null ? plugin.getMessageUtil().get("pin_status_occupied") : plugin.getMessageUtil().get("pin_status_empty"));
        return item;
    }

    private ItemStack removeLoreLineContaining(ItemStack item, String needle) {
        if (item == null || needle == null || needle.isEmpty()) return item;
        var meta = item.getItemMeta();
        if (meta == null || !meta.hasLore() || meta.getLore() == null) return item;

        List<String> lore = new ArrayList<>();
        for (String line : meta.getLore()) {
            if (line == null || !line.contains(needle)) {
                lore.add(line);
            }
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack appendPinExpiresLore(ItemStack item, int slotIndex, LandmarkPin pin) {
        if (item == null || pin == null) return item;
        var meta = item.getItemMeta();
        if (meta == null) return item;

        List<String> lore = meta.hasLore() && meta.getLore() != null
                ? new ArrayList<>(meta.getLore())
                : new ArrayList<>();
        lore.add(plugin.getMessageUtil().get("pin_expires_lore",
                "%slot%", String.valueOf(slotIndex),
                "%pin_expires%", formatPinExpires(pin.getExpiresAt())));
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private List<PinSlot> getConfiguredPinSlots(MenuConfig config) {
        List<PinSlot> slots = new ArrayList<>();
        List<String> shape = config.getShape();
        for (int row = 0; row < shape.size(); row++) {
            String line = shape.get(row);
            for (int col = 0; col < Math.min(line.length(), 9); col++) {
                char c = line.charAt(col);
                if (c == ' ') continue;
                String key = String.valueOf(c);
                MenuItem item = config.getItems().get(key);
                if (item == null) continue;

                Integer slotIndex = null;
                Boolean pinSlotFlag = item.getPinSlot();
                if (Boolean.TRUE.equals(pinSlotFlag)) {
                    slotIndex = item.getPinSlotId();
                    if ((slotIndex == null || slotIndex <= 0) && Character.isDigit(c) && c != '0') {
                        slotIndex = Character.digit(c, 10);
                    }
                } else if (pinSlotFlag == null && Character.isDigit(c) && c != '0' && isPinSlotAction(item)) {
                    slotIndex = Character.digit(c, 10);
                }

                if (slotIndex != null && slotIndex > 0) {
                    slots.add(new PinSlot(slotIndex, row * 9 + col, key));
                }
            }
        }
        return slots;
    }

    public boolean isConfiguredPinSlot(int slotIndex) {
        MenuConfig config = shapeMenuManager.getMenu("main_menu");
        if (config == null) return false;
        return getConfiguredPinSlots(config).stream().anyMatch(slot -> slot.slotIndex() == slotIndex);
    }

    public double getPinCost(int slotIndex) {
        MenuConfig config = shapeMenuManager.getMenu("main_menu");
        MenuItem item = getPinSlotItem(config, slotIndex);
        if (item != null && item.getCost() != null) {
            return Math.max(0.0, item.getCost());
        }
        return 0.0;
    }

    public int getPinDurationDays(int slotIndex) {
        MenuConfig config = shapeMenuManager.getMenu("main_menu");
        MenuItem item = getPinSlotItem(config, slotIndex);
        if (item != null && item.getDurationDays() != null) {
            return Math.max(1, item.getDurationDays());
        }
        return 1;
    }

    private MenuItem getPinSlotItem(MenuConfig config, int slotIndex) {
        if (config == null) return null;

        for (Map.Entry<String, MenuItem> entry : config.getItems().entrySet()) {
            MenuItem item = entry.getValue();
            if (!Boolean.TRUE.equals(item.getPinSlot())) continue;
            Integer configuredSlot = item.getPinSlotId();
            if (configuredSlot != null && configuredSlot == slotIndex) {
                return item;
            }
            if ((configuredSlot == null || configuredSlot <= 0)
                    && entry.getKey().equals(String.valueOf(slotIndex))) {
                return item;
            }
        }

        MenuItem fallback = config.getItems().get(String.valueOf(slotIndex));
        return fallback != null && fallback.getPinSlot() == null && isPinSlotAction(fallback) ? fallback : null;
    }

    private boolean isPinSlotAction(MenuItem item) {
        return item != null && (
                startsWithAction(item.getAction(), "pin_slot:")
                        || startsWithAction(item.getRightAction(), "pin_slot")
                        || startsWithAction(item.getShiftLeftAction(), "pin_slot")
                        || startsWithAction(item.getShiftRightAction(), "pin_slot"));
    }

    private record PinSlot(int slotIndex, int inventorySlot, String key) {}

    private void setLandmarkId(ItemStack item, int id) {
        var meta = item.getItemMeta();
        var key = new NamespacedKey(plugin, "landmark_id");
        meta.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, id);
        item.setItemMeta(meta);
    }

    private void setCategoryId(ItemStack item, int id) {
        var meta = item.getItemMeta();
        var key = new NamespacedKey(plugin, "category_id");
        meta.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, id);
        item.setItemMeta(meta);
    }

    private void setColorOptionId(ItemStack item, String id) {
        var meta = item.getItemMeta();
        var key = new NamespacedKey(plugin, "color_option_id");
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
    }

    private void setPinSlot(ItemStack item, int slotIndex) {
        var meta = item.getItemMeta();
        var key = new NamespacedKey(plugin, "pin_slot");
        meta.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, slotIndex);
        item.setItemMeta(meta);
    }

    private List<Landmark> getFavoriteLandmarks(Player player) {
        List<Integer> favIds = plugin.getFavoritesManager().getFavorites(player.getUniqueId());
        List<Landmark> landmarks = new ArrayList<>();
        for (int fid : favIds) {
            Landmark lm = plugin.getLandmarkManager().getLandmark(fid);
            if (lm != null && !lm.isPrivate()) landmarks.add(lm);
        }
        return landmarks;
    }

    private List<Landmark> getManageableLandmarks(Player player) {
        UUID uuid = player.getUniqueId();
        Map<Integer, Landmark> landmarks = new LinkedHashMap<>();

        for (Landmark lm : plugin.getLandmarkManager().getLandmarksByOwner(uuid)) {
            landmarks.put(lm.getId(), lm);
        }

        for (Landmark lm : plugin.getLandmarkManager().getAllLandmarks()) {
            if (!landmarks.containsKey(lm.getId())
                    && plugin.getManagerManager().isManagerCached(lm.getId(), uuid)) {
                landmarks.put(lm.getId(), lm);
            }
        }

        List<Landmark> result = new ArrayList<>(landmarks.values());
        result.sort(this::compareByCreatedAt);
        return result;
    }

    private List<Landmark> getManagedLandmarks(Player player) {
        UUID uuid = player.getUniqueId();
        List<Landmark> result = new ArrayList<>();
        for (Landmark lm : plugin.getLandmarkManager().getAllLandmarks()) {
            if (!uuid.equals(lm.getOwnerUuid())
                    && plugin.getManagerManager().isManagerCached(lm.getId(), uuid)) {
                result.add(lm);
            }
        }
        result.sort(this::compareByCreatedAt);
        return result;
    }

    private int compareByCreatedAt(Landmark a, Landmark b) {
        if (a.getCreatedAt() == null && b.getCreatedAt() == null) return Integer.compare(a.getId(), b.getId());
        if (a.getCreatedAt() == null) return 1;
        if (b.getCreatedAt() == null) return -1;
        int date = b.getCreatedAt().compareTo(a.getCreatedAt());
        return date != 0 ? date : Integer.compare(a.getId(), b.getId());
    }

    private List<Landmark> applyLandmarkFilters(List<Landmark> landmarks, int filter, String search) {
        if (filter <= 0 && search == null) return landmarks;

        String lowerSearch = search != null ? search.toLowerCase(Locale.ROOT) : null;
        List<Landmark> filtered = new ArrayList<>();
        for (Landmark lm : landmarks) {
            if (filter > 0 && lm.getCategoryId() != filter) continue;
            if (lowerSearch != null && !matchesSearch(lm, lowerSearch)) continue;
            filtered.add(lm);
        }
        return filtered;
    }

    private boolean matchesSearch(Landmark lm, String lowerSearch) {
        return containsIgnoreCase(lm.getName(), lowerSearch)
                || containsIgnoreCase(lm.getOwnerName(), lowerSearch);
    }

    private boolean containsIgnoreCase(String value, String lowerSearch) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowerSearch);
    }

    private List<Landmark> getLandmarksForFilter(int filter, String search) {
        if (filter > 0) {
            return applyLandmarkFilters(plugin.getLandmarkManager().getLandmarksByCategory(filter), 0, search);
        }
        if (search != null) return plugin.getLandmarkManager().searchLandmarks(search);
        return plugin.getLandmarkManager().getAllLandmarks();
    }

    private List<Landmark> filterVisibleLandmarks(Player player, List<Landmark> landmarks) {
        List<Landmark> visible = new ArrayList<>();
        for (Landmark lm : landmarks) {
            if (canViewLandmark(player, lm)) visible.add(lm);
        }
        return visible;
    }

    public boolean canViewLandmark(Player player, Landmark lm) {
        if (lm == null) return false;
        if (!plugin.canAccessLandmarkServer(player, lm)) return false;
        if (!lm.isPrivate()) return true;
        UUID uuid = player.getUniqueId();
        if (uuid.equals(lm.getOwnerUuid())) return true;
        if (player.isOp() || player.hasPermission("alwarp.admin")) return true;
        return plugin.getManagerManager().isManagerCached(lm.getId(), uuid);
    }

    private List<Landmark> getMenuLandmarks(Player player, String menuName) {
        if ("category_select".equals(menuName)
                || "landmark_admins".equals(menuName)
                || "landmark_blacklist".equals(menuName)
                || "landmark_reviews".equals(menuName)
                || "landmark_manage".equals(menuName)
                || "landmark_color_select".equals(menuName)
                || "pin_purchase".equals(menuName)) {
            return List.of();
        }
        if ("pin_landmark_select".equals(menuName)) {
            List<Landmark> owned = plugin.getLandmarkManager().getLandmarksByOwner(player.getUniqueId());
            owned.sort(this::compareByCreatedAt);
            return owned;
        }
        if ("my_landmarks".equals(menuName)) {
            List<Landmark> owned = plugin.getLandmarkManager().getLandmarksByOwner(player.getUniqueId());
            owned.sort(this::compareByCreatedAt);
            return owned;
        }
        if ("managed_landmarks".equals(menuName)) {
            return getManagedLandmarks(player);
        }

        int filter = getFilter(player.getUniqueId(), menuName);
        String search = getSearch(player.getUniqueId(), menuName);
        if ("my_favorites".equals(menuName)) {
            return applyLandmarkFilters(filterVisibleLandmarks(player, getFavoriteLandmarks(player)), filter, search);
        }
        if ("admin_landmarks".equals(menuName)) {
            return getLandmarksForFilter(filter, search);
        }
        return filterVisibleLandmarks(player, getLandmarksForFilter(filter, search));
    }

    private int getTotalItems(Player player, String menuName, List<Landmark> menuLandmarks) {
        if ("landmark_reviews".equals(menuName)) {
            Integer id = editingLandmarkIds.get(player.getUniqueId());
            if (id != null) return plugin.getRatingManager().getRatingCount(id);
            return 0;
        }
        if ("landmark_color_select".equals(menuName)) {
            return getColorOptions(shapeMenuManager.getMenu(menuName)).size();
        }
        return menuLandmarks != null ? menuLandmarks.size() : getMenuLandmarks(player, menuName).size();
    }

    /** 从物品PDC读取地标ID */
    public static int getLandmarkId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return -1;
        var key = new NamespacedKey("alwarp", "landmark_id");
        var data = item.getItemMeta().getPersistentDataContainer();
        return data.getOrDefault(key, PersistentDataType.INTEGER, -1);
    }

    public static int getCategoryId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return -1;
        var key = new NamespacedKey("alwarp", "category_id");
        var data = item.getItemMeta().getPersistentDataContainer();
        return data.getOrDefault(key, PersistentDataType.INTEGER, -1);
    }

    public static String getColorOptionId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        var key = new NamespacedKey("alwarp", "color_option_id");
        var data = item.getItemMeta().getPersistentDataContainer();
        return data.get(key, PersistentDataType.STRING);
    }

    public static int getPinSlot(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return -1;
        var key = new NamespacedKey("alwarp", "pin_slot");
        var data = item.getItemMeta().getPersistentDataContainer();
        return data.getOrDefault(key, PersistentDataType.INTEGER, -1);
    }

    private void setRatingId(ItemStack item, int id) {
        var meta = item.getItemMeta();
        var key = new NamespacedKey(plugin, "rating_id");
        meta.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, id);
        item.setItemMeta(meta);
    }

    public static int getRatingId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return -1;
        var key = new NamespacedKey("alwarp", "rating_id");
        var data = item.getItemMeta().getPersistentDataContainer();
        return data.getOrDefault(key, PersistentDataType.INTEGER, -1);
    }

    private void setAdminUuid(ItemStack item, UUID uuid) {
        var meta = item.getItemMeta();
        var key = new NamespacedKey(plugin, "admin_uuid");
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, uuid.toString());
        item.setItemMeta(meta);
    }

    public static UUID getAdminUuid(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        var key = new NamespacedKey("alwarp", "admin_uuid");
        var data = item.getItemMeta().getPersistentDataContainer();
        String uuid = data.get(key, PersistentDataType.STRING);
        if (uuid == null || uuid.isBlank()) return null;
        try {
            return UUID.fromString(uuid);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void setBlacklistUuid(ItemStack item, UUID uuid) {
        var meta = item.getItemMeta();
        var key = new NamespacedKey(plugin, "blacklist_uuid");
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, uuid.toString());
        item.setItemMeta(meta);
    }

    public static UUID getBlacklistUuid(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        var key = new NamespacedKey("alwarp", "blacklist_uuid");
        var data = item.getItemMeta().getPersistentDataContainer();
        String uuid = data.get(key, PersistentDataType.STRING);
        if (uuid == null || uuid.isBlank()) return null;
        try {
            return UUID.fromString(uuid);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * 根据地标数据构建展示物品。
     */
    private ItemStack buildLandmarkItem(Player player, MenuItem template, Landmark lm) {
        // 使用地标的图标设置
        ItemStack base = buildLandmarkIcon(lm, template.getType(), template.getName(), template.getLore());

        // 替换占位符
        base = ItemUtil.replacePlaceholdersDefaultWhite(base, "%landmark_name%", formatLandmarkName(lm));
        base = ItemUtil.replacePlaceholdersDefaultWhite(base, "%landmark_description%", formatLandmarkDescription(lm));
        base = ItemUtil.replacePlaceholders(base, "%landmark_category%", getCategoryName(lm.getCategoryId()));
        base = ItemUtil.replacePlaceholdersDefaultWhite(base, "%landmark_visits%", String.valueOf(lm.getVisitCount()));
        base = ItemUtil.replacePlaceholdersDefaultWhite(base, "%landmark_owner%", lm.getOwnerName());
        base = ItemUtil.replacePlaceholdersDefaultWhite(base, "%landmark_date%",
                formatLandmarkDate(lm.getCreatedAt()));
        base = ItemUtil.replacePlaceholders(base, "%landmark_rating%",
                MessageUtil.formatRating(plugin.getRatingManager().getAverageRating(lm.getId())));
        base = ItemUtil.replacePlaceholdersDefaultWhite(base, "%landmark_weekly%", String.valueOf(lm.getWeeklyVisits()));
        boolean favorite = plugin.getFavoritesManager().isFavorite(player.getUniqueId(), lm.getId());
        String favoriteAction = lm.isPrivate()
                ? rawMessage("favorite_action_unavailable", "不可收藏")
                : rawMessage(favorite ? "favorite_action_remove" : "favorite_action_add",
                        favorite ? "取消收藏" : "收藏");
        base = ItemUtil.replacePlaceholdersDefaultWhite(base, "%favorite_action%",
                favoriteAction);
        base = ItemUtil.replacePlaceholdersDefaultWhite(base, "%favorite_action_en%",
                lm.isPrivate() ? "Unavailable" : favorite ? "Unfavorite" : "Favorite");
        base = ItemUtil.replacePlaceholdersDefaultWhite(base, "%landmark_price%", formatPrice(lm.getPrice()));
        base = ItemUtil.replacePlaceholdersDefaultWhite(base, "%landmark_server%",
                plugin.getServerDisplayName(lm.getServerName()));
        base = replaceLocationPlaceholders(base, lm);
        base = ItemUtil.replacePlaceholders(base, "%landmark_private%", getVisibilityText(lm));
        base = ItemUtil.replacePlaceholders(base, "%landmark_visibility%", getVisibilityText(lm));
        base = ItemUtil.replacePlaceholders(base, "%landmark_id%", String.valueOf(lm.getId()));
        base = ItemUtil.replacePlaceholdersDefaultWhite(base, "%create_cost%",
                String.format("%.0f", plugin.getConfig().getDouble("economy.create_cost", 1000.0)));
        base = ItemUtil.replacePlaceholdersDefaultWhite(base, "%current_filter%",
                MessageUtil.colorize(getCurrentFilterName(getFilter(
                        player.getUniqueId(),
                        playerCurrentMenu.get(player.getUniqueId())))));

        return base;
    }

    private ItemStack buildLandmarkIcon(Landmark lm, String fallbackType, String name, List<String> lore) {
        String icon = lm.getIcon() != null ? lm.getIcon() : fallbackType;
        if ("PLAYER_HEAD".equalsIgnoreCase(icon)
                && lm.getIconCustomModelData() == null
                && lm.getIconPluginItem() == null
                && lm.getIconData() == null) {
            return ItemUtil.buildPlayerHead(name, lm.getOwnerUuid(), lm.getOwnerName(), lore);
        }
        ItemStack item = ItemUtil.buildItem(
                icon,
                lm.getIconCustomModelData(),
                lm.getIconPluginItem(),
                lm.getIconData(),
                name,
                lore,
                Map.of("player.name", lm.getOwnerName() != null ? lm.getOwnerName() : "")
        );
        if (shouldApplyLandmarkOwnerToHeadTemplate(lm, icon, item)) {
            if (isCraftEngineItemReference(icon) || isCraftEngineItemReference(lm.getIconPluginItem())) {
                item = ItemUtil.freezeCraftEngineClientDisplay(
                        item,
                        Map.of("player.name", lm.getOwnerName() != null ? lm.getOwnerName() : "")
                );
            }
            ItemUtil.applyPlayerHeadOwner(item, lm.getOwnerUuid(), lm.getOwnerName());
        }
        return item;
    }

    private boolean shouldApplyLandmarkOwnerToHeadTemplate(Landmark lm, String icon, ItemStack item) {
        if (item == null || item.getType() != Material.PLAYER_HEAD) return false;
        if (lm.getIconData() != null && !lm.getIconData().isEmpty()) return false;
        return isPluginItemReference(icon) || isPluginItemReference(lm.getIconPluginItem());
    }

    private boolean isPluginItemReference(String value) {
        if (value == null) return false;
        String normalized = value.trim().replace('：', ':').toLowerCase(Locale.ROOT);
        return normalized.startsWith("ce:")
                || normalized.startsWith("craftengine:")
                || normalized.startsWith("ia:")
                || normalized.startsWith("itemsadder:")
                || normalized.startsWith("oraxen:");
    }

    private boolean isCraftEngineItemReference(String value) {
        if (value == null) return false;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("ce:") || normalized.startsWith("craftengine:");
    }

    private boolean shouldUseDynamicManageIcon(MenuItem item) {
        if (item == null) return true;
        if (item.getPluginItem() != null && !item.getPluginItem().isBlank()) return false;
        if (isPluginItemReference(item.getType())) return false;
        Integer customModelData = item.getCustomModelData();
        if (customModelData != null && customModelData > 0) return false;

        String type = item.getType();
        if (type == null || type.isBlank()) return true;
        try {
            Material.valueOf(type.trim().toUpperCase(Locale.ROOT));
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private String getCategoryName(int categoryId) {
        Category c = plugin.getCategoryManager().getCategory(categoryId);
        return c != null ? MessageUtil.colorize(c.getDisplayName()) : plugin.getMessageUtil().get("text_unknown");
    }

    private String formatLandmarkName(Landmark lm) {
        if (lm == null) return "";
        String text = TextStyleUtil.stripUserFormatting(lm.getName());
        if (!isLandmarkColorSystemEnabled()) return text;
        return TextStyleUtil.render(text, lm.getNameColor(), lm.isNameBold());
    }

    private String formatLandmarkDescription(Landmark lm) {
        if (lm == null) return "";
        String text = lm.getDescription() != null && !lm.getDescription().isEmpty()
                ? TextStyleUtil.stripUserFormatting(lm.getDescription())
                : rawMessage("text_no_description", "&a暂无描述");
        if (!isLandmarkColorSystemEnabled()) {
            return wrapDescription(text);
        }
        String style = TextStyleUtil.normalizeStyle(lm.getDescriptionColor());
        if (style != null) {
            return TextStyleUtil.render(wrapDescriptionPlain(TextStyleUtil.stripUserFormatting(text)),
                    style,
                    lm.isDescriptionBold());
        }
        if (lm.isDescriptionBold()) {
            return wrapDescriptionWithPrefix(TextStyleUtil.stripUserFormatting(text), "&a&l");
        }
        return wrapDescription(text);
    }

    private String getCurrentFilterName(int filterId) {
        if (filterId == 0) return plugin.getMessageUtil().get("text_all");
        Category c = plugin.getCategoryManager().getCategory(filterId);
        return c != null ? MessageUtil.colorize(c.getDisplayName()) : plugin.getMessageUtil().get("text_all");
    }

    /**
     * 打开地标管理界面（编辑模式）。
     */
    public void openLandmarkManage(Player player, int landmarkId) {
        // 如果已有编辑副本则复用（从分类选择等子菜单返回时保留修改）
        Landmark editing = editingLandmarks.get(player.getUniqueId());
        if (editing == null || editing.getId() != landmarkId) {
            Landmark lm = plugin.getLandmarkManager().getLandmark(landmarkId);
            if (lm == null) {
                player.sendMessage(plugin.getMessageUtil().get("landmark_not_found"));
                return;
            }
            if (!canManageLandmark(player, lm)) {
                player.sendMessage(plugin.getMessageUtil().get("no_permission"));
                return;
            }
            editing = lm.copy();
            editingLandmarks.put(player.getUniqueId(), editing);
        }
        editingLandmarkIds.put(player.getUniqueId(), landmarkId);

        playerCurrentMenu.put(player.getUniqueId(), "landmark_manage");
        Inventory inv;

        // 替换占位符
        MenuConfig config = shapeMenuManager.getMenu("landmark_manage");
        if (config == null) return;
        List<String> manageShape = getLandmarkManageShape(player, editing, config);
        inv = shapeMenuManager.createInventory("landmark_manage", manageShape, getLandmarkManageTitle(config, manageShape));

        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (item != null) {
                inv.setItem(i, replaceLandmarkPlaceholders(item, editing));
            }
        }

        // S按钮替换为玩家头颅（创建者或管理员）
        if (canManageLandmarkManagers(player, editing)) {
            List<Integer> sSlots = getSlotsForChar(manageShape, 'S');
            if (!sSlots.isEmpty()) {
                MenuItem sItem = config.getItems().get("S");
                if (shouldUseDynamicManageIcon(sItem)) {
                    ItemStack head = ItemUtil.buildPlayerHead(
                            sItem != null ? sItem.getName() : plugin.getMessageUtil().get("manager_list_button"),
                            player.getUniqueId(),
                            player.getName(),
                            sItem != null ? sItem.getLore() : List.of(plugin.getMessageUtil().get("manager_list_lore")));
                    setItemInSlots(inv, sSlots, head);
                }
            }
        }

        // W按钮替换为当前地标的图标
        List<Integer> wSlots = getSlotsForChar(manageShape, 'W');
        if (!wSlots.isEmpty()) {
            MenuItem wItem = config.getItems().get("W");
            if (shouldUseDynamicManageIcon(wItem)) {
                ItemStack icon = buildLandmarkIcon(
                    editing,
                    "PLAYER_HEAD",
                    wItem != null ? wItem.getName() : "&a设置地标图标",
                    wItem != null ? wItem.getLore() : List.of("&a手持物品后点击设置")
            );
                setItemInSlots(inv, wSlots, icon);
            }
        }

        openInventorySafe(player, inv);
    }

    /**
     * 打开管理员列表界面。
     */
    public void openPinPurchase(Player player, int slotIndex) {
        if (!isConfiguredPinSlot(slotIndex)) {
            player.sendMessage(plugin.getMessageUtil().get("pin_invalid_slot"));
            return;
        }
        pendingPinSlots.put(player.getUniqueId(), slotIndex);
        playerCurrentMenu.put(player.getUniqueId(), "pin_purchase");

        Inventory inv = shapeMenuManager.createInventory("pin_purchase");
        MenuConfig config = shapeMenuManager.getMenu("pin_purchase");
        if (config == null) return;

        replaceStaticPlaceholders(player, inv, config, "pin_purchase", 1, 1);
        fillPinPurchaseSelection(player, inv, config, slotIndex);
        openInventorySafe(player, inv);
    }

    private void fillPinPurchaseSelection(Player player, Inventory inv, MenuConfig config, int slotIndex) {
        List<Integer> aSlots = getSlotsForChar(config, 'A');
        if (aSlots.isEmpty()) return;
        MenuItem aItem = config.getItems().get("A");
        if (aItem == null) return;

        Integer selectedId = selectedPinLandmarkIds.get(player.getUniqueId());
        Landmark selected = selectedId != null ? plugin.getLandmarkManager().getLandmark(selectedId) : null;
        ItemStack item;
        if (selected != null && player.getUniqueId().equals(selected.getOwnerUuid())) {
            item = buildLandmarkItem(player, aItem, selected);
            setLandmarkId(item, selected.getId());
        } else {
            selectedPinLandmarkIds.remove(player.getUniqueId());
            item = ItemUtil.buildItem(aItem.getType(), aItem.getCustomModelData(), aItem.getPluginItem(),
                    aItem.getName(), aItem.getLore());
        }
        item = replacePinPurchasePlaceholders(item, slotIndex, selected);
        setItemInSlots(inv, aSlots, item);
    }

    private ItemStack replacePinPurchasePlaceholders(ItemStack item, int slotIndex, Landmark selected) {
        item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%pin_slot%", String.valueOf(slotIndex));
        item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%pin_cost%", MessageUtil.formatDouble(getPinCost(slotIndex)));
        item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%pin_days%", String.valueOf(getPinDurationDays(slotIndex)));
        item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%pin_selected_landmark%",
                selected != null ? selected.getName() : plugin.getMessageUtil().get("pin_selected_none"));
        return item;
    }

    public void openPinLandmarkSelect(Player player) {
        if (!pendingPinSlots.containsKey(player.getUniqueId())) {
            player.sendMessage(plugin.getMessageUtil().get("pin_invalid_slot"));
            openMenu(player, "main_menu");
            return;
        }
        openMenu(player, "pin_landmark_select");
    }

    public void selectPinLandmark(Player player, int landmarkId) {
        Landmark lm = plugin.getLandmarkManager().getLandmark(landmarkId);
        Integer slotIndex = pendingPinSlots.get(player.getUniqueId());
        if (slotIndex == null || !isConfiguredPinSlot(slotIndex)) {
            player.sendMessage(plugin.getMessageUtil().get("pin_invalid_slot"));
            clearPinState(player.getUniqueId());
            openMenu(player, "main_menu");
            return;
        }
        if (lm == null || !player.getUniqueId().equals(lm.getOwnerUuid())) {
            player.sendMessage(plugin.getMessageUtil().get("pin_landmark_not_owned"));
            openPinLandmarkSelect(player);
            return;
        }
        selectedPinLandmarkIds.put(player.getUniqueId(), landmarkId);
        openPinPurchase(player, slotIndex);
    }

    public Integer getPendingPinSlot(UUID uuid) {
        return pendingPinSlots.get(uuid);
    }

    public Integer getSelectedPinLandmarkId(UUID uuid) {
        return selectedPinLandmarkIds.get(uuid);
    }

    public void clearPinState(UUID uuid) {
        pendingPinSlots.remove(uuid);
        selectedPinLandmarkIds.remove(uuid);
    }

    public void openAdminList(Player player, int landmarkId) {
        Landmark lm = plugin.getLandmarkManager().getLandmark(landmarkId);
        if (lm == null) return;
        if (!canManageLandmarkManagers(player, lm)) {
            player.sendMessage(plugin.getMessageUtil().get("no_permission"));
            return;
        }

        editingLandmarkIds.put(player.getUniqueId(), landmarkId);
        playerCurrentMenu.put(player.getUniqueId(), "landmark_admins");

        Inventory inv = shapeMenuManager.createInventory("landmark_admins");
        MenuConfig config = shapeMenuManager.getMenu("landmark_admins");
        if (config == null) return;

        // 填充管理员列表
        plugin.getManagerManager().getManagers(landmarkId, result -> {
            plugin.runAtPlayer(player, () -> {
                @SuppressWarnings("unchecked")
                List<LandmarkAdmin> admins = (List<LandmarkAdmin>) result;
                List<Integer> aSlots = getSlotsForChar(config, 'A');
                int totalPages = getTotalPages(admins.size(), aSlots.size());
                int page = clampPage(player, totalPages);
                replaceStaticPlaceholders(player, inv, config, "landmark_admins", page, totalPages);

                // 清空所有A槽位
                for (int slot : aSlots) inv.setItem(slot, null);

                int start = (page - 1) * aSlots.size();
                int end = Math.min(start + aSlots.size(), admins.size());

                for (int i = start; i < end; i++) {
                    int slotIdx = i - start;
                    if (slotIdx >= aSlots.size()) break;

                    LandmarkAdmin admin = admins.get(i);
                    UUID adminUuid = admin.getPlayerUuid();
                    boolean isOwner = lm.getOwnerUuid().equals(adminUuid);
                    String role = isOwner
                            ? plugin.getMessageUtil().get("manager_role_owner")
                            : plugin.getMessageUtil().get("manager_role_manager");
                    String color = isOwner ? "&6" : "&a";

                    List<String> lore = new ArrayList<>();
                    lore.add("&7(" + role + ")");
                    if (!isOwner) lore.add(plugin.getMessageUtil().get("manager_remove_hint"));

                    ItemStack head = ItemUtil.buildPlayerHead(
                            color + admin.getPlayerName(),
                            adminUuid,
                            admin.getPlayerName(),
                            lore
                    );
                    setAdminUuid(head, adminUuid);
                    inv.setItem(aSlots.get(slotIdx), head);
                }

                openInventorySafe(player, inv);
            });
        });
    }

    public void openBlacklistList(Player player, int landmarkId) {
        Landmark lm = plugin.getLandmarkManager().getLandmark(landmarkId);
        if (lm == null) return;
        if (!canManageLandmarkManagers(player, lm)) {
            player.sendMessage(plugin.getMessageUtil().get("no_permission"));
            return;
        }

        editingLandmarkIds.put(player.getUniqueId(), landmarkId);
        playerCurrentMenu.put(player.getUniqueId(), "landmark_blacklist");

        Inventory inv = shapeMenuManager.createInventory("landmark_blacklist");
        MenuConfig config = shapeMenuManager.getMenu("landmark_blacklist");
        if (config == null) return;

        plugin.getBlacklistManager().getBlacklist(landmarkId, result -> {
            plugin.runAtPlayer(player, () -> {
                @SuppressWarnings("unchecked")
                List<LandmarkBlacklist> blacklists = (List<LandmarkBlacklist>) result;
                List<Integer> aSlots = getSlotsForChar(config, 'A');
                int totalPages = getTotalPages(blacklists.size(), aSlots.size());
                int page = clampPage(player, totalPages);
                replaceStaticPlaceholders(player, inv, config, "landmark_blacklist", page, totalPages);

                for (int slot : aSlots) inv.setItem(slot, null);

                int start = (page - 1) * aSlots.size();
                int end = Math.min(start + aSlots.size(), blacklists.size());

                for (int i = start; i < end; i++) {
                    int slotIdx = i - start;
                    if (slotIdx >= aSlots.size()) break;

                    LandmarkBlacklist blacklist = blacklists.get(i);
                    UUID targetUuid = blacklist.getPlayerUuid();
                    List<String> lore = new ArrayList<>();
                    lore.add(plugin.getMessageUtil().get("blacklist_remove_hint"));

                    ItemStack head = ItemUtil.buildPlayerHead(
                            "&c" + blacklist.getPlayerName(),
                            targetUuid,
                            blacklist.getPlayerName(),
                            lore
                    );
                    setBlacklistUuid(head, targetUuid);
                    inv.setItem(aSlots.get(slotIdx), head);
                }

                openInventorySafe(player, inv);
            });
        });
    }

    private List<Integer> getSlotsForChar(MenuConfig config, char c) {
        return getSlotsForChar(config.getShape(), c);
    }

    private List<Integer> getSlotsForChar(List<String> shape, char c) {
        List<Integer> slots = new ArrayList<>();
        for (int row = 0; row < shape.size(); row++) {
            String line = shape.get(row);
            for (int col = 0; col < Math.min(line.length(), 9); col++) {
                if (line.charAt(col) == c) {
                    slots.add(row * 9 + col);
                }
            }
        }
        return slots;
    }

    private void setItemInSlots(Inventory inv, List<Integer> slots, ItemStack item) {
        for (int slot : slots) {
            inv.setItem(slot, item != null ? item.clone() : null);
        }
    }

    private ItemStack replaceLandmarkPlaceholders(ItemStack item, Landmark lm) {
        item = hideLandmarkColorLoreIfDisabled(item);
        item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%landmark_name%", formatLandmarkName(lm));
        item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%landmark_description%", formatLandmarkDescription(lm));
        item = ItemUtil.replacePlaceholders(item, "%landmark_category%", getCategoryName(lm.getCategoryId()));
        item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%landmark_price%", formatPrice(lm.getPrice()));
        item = replaceLocationPlaceholders(item, lm);
        item = ItemUtil.replacePlaceholders(item, "%landmark_private%", getVisibilityText(lm));
        item = ItemUtil.replacePlaceholders(item, "%landmark_visibility%", getVisibilityText(lm));
        return item;
    }

    private ItemStack hideLandmarkColorLoreIfDisabled(ItemStack item) {
        if (isLandmarkColorSystemEnabled()) return item;
        item = removeLoreLineContaining(item, "设置名称颜色");
        item = removeLoreLineContaining(item, "设置描述颜色");
        item = removeLoreLineContaining(item, "Set name color");
        item = removeLoreLineContaining(item, "Set description color");
        return item;
    }

    public boolean isLandmarkColorSystemEnabled() {
        return plugin.getConfig().getBoolean("landmark.color_system.enabled", true);
    }

    private boolean canManageLandmark(Player player, Landmark lm) {
        UUID uuid = player.getUniqueId();
        return lm.getOwnerUuid().equals(uuid)
                || player.hasPermission("alwarp.admin")
                || plugin.getManagerManager().isManagerCached(lm.getId(), uuid);
    }

    public boolean isLandmarkOwner(Player player, Landmark lm) {
        return lm != null && lm.getOwnerUuid().equals(player.getUniqueId());
    }

    public boolean canManageLandmarkManagers(Player player, Landmark lm) {
        return lm != null
                && (lm.getOwnerUuid().equals(player.getUniqueId())
                || player.isOp()
                || player.hasPermission("alwarp.admin"));
    }

    private List<String> getLandmarkManageShape(Player player, Landmark lm, MenuConfig config) {
        if (canManageLandmarkManagers(player, lm)) return config.getShape();
        List<String> adminShape = config.getAdminShape();
        return adminShape != null && !adminShape.isEmpty() ? adminShape : config.getShape();
    }

    private String getLandmarkManageTitle(MenuConfig config, List<String> shape) {
        List<String> adminShape = config.getAdminShape();
        if (adminShape != null && !adminShape.isEmpty() && adminShape.equals(shape)) {
            String adminTitle = config.getAdminTitle();
            if (adminTitle != null && !adminTitle.isBlank()) return adminTitle;
        }
        return config.getTitle();
    }

    private ItemStack replaceLocationPlaceholders(ItemStack item, Landmark lm) {
        item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%landmark_server%", plugin.getServerDisplayName(lm.getServerName()));
        item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%landmark_world%", getWorldDisplayName(lm.getWorld()));
        item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%landmark_location%", formatLocation(lm));
        item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%landmark_x%", String.valueOf(lm.getX()));
        item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%landmark_y%", String.valueOf(lm.getY()));
        item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%landmark_z%", String.valueOf(lm.getZ()));
        return item;
    }

    private String formatLocation(Landmark lm) {
        return lm.getX() + ", " + lm.getY() + ", " + lm.getZ();
    }

    private String getWorldDisplayName(String worldName) {
        if (worldName == null || worldName.isBlank()) return plugin.getMessageUtil().get("text_unknown");
        World world = plugin.getServer().getWorld(worldName);
        if (world != null) {
            return switch (world.getEnvironment()) {
                case NORMAL -> rawMessage("world_normal", "主世界");
                case NETHER -> rawMessage("world_nether", "下界");
                case THE_END -> rawMessage("world_the_end", "末地");
                default -> worldName;
            };
        }

        String lower = worldName.toLowerCase(Locale.ROOT);
        if (lower.contains("nether")) return rawMessage("world_nether", "下界");
        if (lower.contains("end")) return rawMessage("world_the_end", "末地");
        return worldName;
    }

    private String getVisibilityText(Landmark lm) {
        return plugin.getMessageUtil().get(lm.isPrivate() ? "text_visibility_private" : "text_visibility_public");
    }

    private String formatPrice(double price) {
        if (price <= 0.0) return plugin.localize("&a免费", "&aFree");
        return "&e" + MessageUtil.formatDouble(price) + plugin.localize(" &6金币", " &6coins");
    }

    private String rawMessage(String key, String fallback) {
        return plugin.getMessagesConfig().getString(key, fallback);
    }

    // ─── 分页 ───

    public void nextPage(Player player) {
        String menu = playerCurrentMenu.get(player.getUniqueId());
        if (menu == null) return;
        if ("landmark_admins".equals(menu) || "landmark_blacklist".equals(menu)) {
            int page = playerPages.getOrDefault(player.getUniqueId(), 1);
            playerPages.put(player.getUniqueId(), page + 1);
            reopenCurrentPagedMenu(player, menu);
            return;
        }
        int total = getTotalItems(player, menu);
        int page = playerPages.getOrDefault(player.getUniqueId(), 1);
        int slots = getASlotCount(menu);
        if (slots == 0) return;
        if (page * slots >= total) return; // 没有下一页
        playerPages.put(player.getUniqueId(), page + 1);
        reopenCurrentPagedMenu(player, menu);
    }

    public void prevPage(Player player) {
        String menu = playerCurrentMenu.get(player.getUniqueId());
        int page = playerPages.getOrDefault(player.getUniqueId(), 1);
        if (page <= 1) return; // 没有上一页
        playerPages.put(player.getUniqueId(), page - 1);
        if (menu != null) reopenCurrentPagedMenu(player, menu);
    }

    private void reopenCurrentPagedMenu(Player player, String menu) {
        Integer id = editingLandmarkIds.get(player.getUniqueId());
        if ("landmark_admins".equals(menu) && id != null) {
            openAdminList(player, id);
            return;
        }
        if ("landmark_blacklist".equals(menu) && id != null) {
            openBlacklistList(player, id);
            return;
        }
        if ("landmark_reviews".equals(menu) && id != null) {
            openLandmarkReviews(player, id);
            return;
        }
        if ("pin_landmark_select".equals(menu)) {
            openPinLandmarkSelect(player);
            return;
        }
        openMenu(player, menu);
    }

    private int getTotalItems(Player player, String menuName) {
        return getTotalItems(player, menuName, getMenuLandmarks(player, menuName));
    }

    private int getTotalPages(int totalItems, int slots) {
        return slots > 0 ? Math.max(1, (totalItems + slots - 1) / slots) : 1;
    }

    private int clampPage(Player player, int totalPages) {
        int page = playerPages.getOrDefault(player.getUniqueId(), 1);
        int clamped = Math.max(1, Math.min(page, totalPages));
        if (clamped != page) playerPages.put(player.getUniqueId(), clamped);
        return clamped;
    }

    private int getASlotCount(String menuName) {
        MenuConfig config = shapeMenuManager.getMenu(menuName);
        if (config == null) return 0;
        int count = 0;
        for (String line : config.getShape()) {
            for (char c : line.toCharArray()) {
                if (c == 'A') count++;
            }
        }
        return count;
    }

    // ─── 过滤 ───

    public void setFilter(Player player, int categoryId) {
        setFilter(player.getUniqueId(), "main_menu", categoryId);
    }

    public void setFilter(Player player, String menuName, int categoryId) {
        setFilter(player.getUniqueId(), menuName, categoryId);
    }

    private void setFilter(UUID uuid, String menuName, int categoryId) {
        String listMenu = normalizeListMenu(menuName);
        if ("my_favorites".equals(listMenu)) {
            favoriteFilters.put(uuid, categoryId);
        } else if ("admin_landmarks".equals(listMenu)) {
            adminFilters.put(uuid, categoryId);
        } else {
            playerFilters.put(uuid, categoryId);
        }
        playerPages.put(uuid, 1);
    }

    public void toggleFilter(Player player) {
        UUID uuid = player.getUniqueId();
        String menuName = playerCurrentMenu.getOrDefault(uuid, "main_menu");
        int current = getFilter(uuid, menuName);
        List<Category> categories = plugin.getCategoryManager().getAllCategories();
        // 找到下一个分类
        int nextIdx = 0;
        if (current == 0) {
            nextIdx = categories.isEmpty() ? 0 : categories.get(0).getId();
        } else {
            boolean found = false;
            for (Category c : categories) {
                if (found) { nextIdx = c.getId(); break; }
                if (c.getId() == current) found = true;
            }
            if (found && nextIdx == 0) nextIdx = 0; // 回到"全部"
        }
        setFilter(uuid, menuName, nextIdx);
        openMenu(player, menuName);
    }

    private int getFilter(UUID uuid, String menuName) {
        String listMenu = normalizeListMenu(menuName);
        if ("my_favorites".equals(listMenu)) {
            return favoriteFilters.getOrDefault(uuid, 0);
        }
        if ("admin_landmarks".equals(listMenu)) {
            return adminFilters.getOrDefault(uuid, 0);
        }
        return playerFilters.getOrDefault(uuid, 0);
    }

    private String getSearch(UUID uuid, String menuName) {
        String search;
        String listMenu = normalizeListMenu(menuName);
        if ("my_favorites".equals(listMenu)) {
            search = favoriteSearches.get(uuid);
        } else if ("admin_landmarks".equals(listMenu)) {
            search = adminSearches.get(uuid);
        } else {
            search = playerSearches.get(uuid);
        }
        return search != null && !search.isBlank() ? search : null;
    }

    private String normalizeListMenu(String menuName) {
        menuName = normalizeMenuName(menuName, "main_menu");
        if ("my_favorites".equals(menuName)) return "my_favorites";
        if ("admin_landmarks".equals(menuName)) return "admin_landmarks";
        return "main_menu";
    }

    private String normalizeMenuName(String menuName, String fallback) {
        String normalized = ShapeMenuManager.normalizeMenuName(menuName);
        return normalized != null && !normalized.isBlank() ? normalized : fallback;
    }

    public void setSearch(Player player, String menuName, String query) {
        UUID uuid = player.getUniqueId();
        String listMenu = normalizeListMenu(menuName);
        if ("my_favorites".equals(listMenu)) {
            favoriteSearches.put(uuid, query);
        } else if ("admin_landmarks".equals(listMenu)) {
            adminSearches.put(uuid, query);
        } else {
            playerSearches.put(uuid, query);
        }
        playerPages.put(uuid, 1);
    }

    public void clearSearch(Player player, String menuName) {
        UUID uuid = player.getUniqueId();
        String listMenu = normalizeListMenu(menuName);
        if ("my_favorites".equals(listMenu)) {
            favoriteSearches.remove(uuid);
        } else if ("admin_landmarks".equals(listMenu)) {
            adminSearches.remove(uuid);
        } else {
            playerSearches.remove(uuid);
        }
        playerPages.put(uuid, 1);
    }

    public void setSearchReturnMenu(UUID uuid, String menuName) {
        pendingSearchReturnMenu.put(uuid, normalizeListMenu(menuName));
    }

    public String getSearchReturnMenu(UUID uuid) {
        return pendingSearchReturnMenu.remove(uuid);
    }

    // ─── 聊天输入管理 ───

    public void setPendingInput(UUID uuid, String type) {
        pendingInputs.put(uuid, type);
    }

    public String getPendingInput(UUID uuid) {
        return pendingInputs.remove(uuid);
    }

    public void setCreateName(UUID uuid, String name) {
        pendingCreateName.put(uuid, name);
    }

    public String getCreateName(UUID uuid) {
        return pendingCreateName.remove(uuid);
    }

    // ─── 编辑状态 ───

    public Landmark getEditingLandmark(UUID uuid) {
        return editingLandmarks.get(uuid);
    }

    public Integer getEditingLandmarkId(UUID uuid) {
        return editingLandmarkIds.get(uuid);
    }

    public void clearEditingState(UUID uuid) {
        editingLandmarks.remove(uuid);
        editingLandmarkIds.remove(uuid);
        pendingInputs.remove(uuid);
        pendingCreateName.remove(uuid);
        pendingScores.remove(uuid);
        landmarkColorTargets.remove(uuid);
    }

    public void setPendingScore(UUID uuid, int score) { pendingScores.put(uuid, score); }
    public Integer getPendingScore(UUID uuid) { return pendingScores.remove(uuid); }

    public void setPendingTeleportConfirmation(UUID uuid, int landmarkId) {
        pendingTeleportConfirmations.put(uuid, landmarkId);
    }

    public Integer getPendingTeleportConfirmation(UUID uuid) {
        return pendingTeleportConfirmations.get(uuid);
    }

    public void clearPendingTeleportConfirmation(UUID uuid) {
        pendingTeleportConfirmations.remove(uuid);
    }

    public void setEditingLandmarkId(UUID uuid, int id) {
        editingLandmarkIds.put(uuid, id);
    }

    public void openLandmarkColorSelect(Player player, String target) {
        if (!"description".equalsIgnoreCase(target)) {
            target = "name";
        }
        Landmark editing = getEditingLandmark(player.getUniqueId());
        if (editing == null) {
            player.sendMessage(plugin.getMessageUtil().get("landmark_not_found"));
            return;
        }
        if (!isLandmarkColorSystemEnabled()) {
            openLandmarkManage(player, editing.getId());
            return;
        }
        landmarkColorTargets.put(player.getUniqueId(), target.toLowerCase(Locale.ROOT));
        playerPages.put(player.getUniqueId(), 1);
        openMenu(player, "landmark_color_select");
    }

    public void selectLandmarkColor(Player player, String colorId) {
        Landmark editing = getEditingLandmark(player.getUniqueId());
        if (editing == null) {
            player.sendMessage(plugin.getMessageUtil().get("landmark_not_found"));
            return;
        }
        if (!isLandmarkColorSystemEnabled()) {
            openLandmarkManage(player, editing.getId());
            return;
        }
        ColorOption option = getColorOption(colorId);
        if (option == null) return;
        if (!canUseColorOption(player, option)) {
            player.sendMessage(plugin.getMessageUtil().get("color_permission_denied"));
            return;
        }
        setCurrentTextStyle(player.getUniqueId(), editing, option.getStyle());
        openLandmarkManage(player, editing.getId());
    }

    public void buyLandmarkColor(Player player, String colorId) {
        Landmark editing = getEditingLandmark(player.getUniqueId());
        if (editing == null) {
            player.sendMessage(plugin.getMessageUtil().get("landmark_not_found"));
            return;
        }
        if (!isLandmarkColorSystemEnabled()) {
            openLandmarkManage(player, editing.getId());
            return;
        }
        ColorOption option = getColorOption(colorId);
        if (option == null) return;
        if (canUseColorOption(player, option)) {
            player.sendMessage(plugin.getMessageUtil().get("color_unlock_already"));
            openMenu(player, "landmark_color_select");
            return;
        }
        UnlockCost cost = getColorUnlockCost(option);
        buyUnlock(player,
                option.getPermission(),
                option.getName() != null ? option.getName() : option.getId(),
                "color",
                option.getId(),
                cost);
    }

    public void buyLandmarkBold(Player player) {
        Landmark editing = getEditingLandmark(player.getUniqueId());
        if (editing == null) {
            player.sendMessage(plugin.getMessageUtil().get("landmark_not_found"));
            return;
        }
        if (!isLandmarkColorSystemEnabled()) {
            openLandmarkManage(player, editing.getId());
            return;
        }
        if (canUseBold(player)) {
            player.sendMessage(plugin.getMessageUtil().get("color_unlock_already"));
            openMenu(player, "landmark_color_select");
            return;
        }
        buyUnlock(player, getBoldPermission(), plugin.localize("加粗字体", "Bold text"), "bold", "", getBoldUnlockCost());
    }

    private void buyUnlock(Player player, String permission, String unlockName, String unlockType, String colorId, UnlockCost cost) {
        if (!cost.enabled()) {
            player.sendMessage(plugin.getMessageUtil().get("color_unlock_not_available"));
            return;
        }
        if (permission == null || permission.isBlank()) {
            player.sendMessage(plugin.getMessageUtil().get("color_unlock_not_available"));
            return;
        }
        if (player.hasPermission(COLOR_ALL_PERMISSION) || player.hasPermission(permission)) {
            player.sendMessage(plugin.getMessageUtil().get("color_unlock_already"));
            openMenu(player, "landmark_color_select");
            return;
        }
        if (!withdrawUnlockCost(player, cost)) {
            return;
        }
        grantUnlockPermission(player, permission, unlockName, unlockType, colorId, success ->
                plugin.runAtPlayer(player, () -> {
                    if (!success) {
                        refundUnlockCost(player, cost);
                        player.sendMessage(plugin.getMessageUtil().get("payment_failed"));
                        return;
                    }
                    player.sendMessage(plugin.getMessageUtil().get("color_unlock_purchased",
                            "%unlock%", MessageUtil.colorize(unlockName),
                            "%price%", formatUnlockAmount(cost),
                            "%currency%", cost.currencyName()));
                    openMenu(player, "landmark_color_select");
                }));
    }

    private void grantUnlockPermission(Player player, String permission, String unlockName, String unlockType,
                                       String colorId, java.util.function.Consumer<Boolean> callback) {
        List<String> commands = plugin.getConfig().getStringList("landmark.color_system.unlock.permission_grant.commands");
        if (commands.isEmpty()) {
            commands = List.of("lp user %player% permission set %permission% true");
        }

        List<String> commandTemplates = new ArrayList<>(commands);
        plugin.getScheduler().runTask(() -> {
            boolean ran = false;
            boolean success = true;
            for (String template : commandTemplates) {
                if (template == null || template.isBlank()) continue;
                String command = template
                        .replace("%player%", player.getName())
                        .replace("%uuid%", player.getUniqueId().toString())
                        .replace("%permission%", permission)
                        .replace("%unlock_name%", Objects.toString(org.bukkit.ChatColor.stripColor(MessageUtil.colorize(unlockName)), ""))
                        .replace("%unlock_type%", unlockType != null ? unlockType : "")
                        .replace("%color_id%", colorId != null ? colorId : "");
                ran = true;
                if (!plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), command)) {
                    success = false;
                }
            }
            boolean finalSuccess = ran && success;
            plugin.runAtPlayer(player, () -> {
                if (finalSuccess) {
                    player.recalculatePermissions();
                }
                callback.accept(finalSuccess);
            });
        });
    }

    private boolean withdrawUnlockCost(Player player, UnlockCost cost) {
        if (cost.price() <= 0.0) return true;
        if ("playerpoints".equals(cost.currency())) {
            int amount = (int) Math.ceil(cost.price());
            Object api = getPlayerPointsApi();
            if (api == null) {
                player.sendMessage(plugin.getMessageUtil().get("color_unlock_currency_unavailable"));
                return false;
            }
            Integer balance = callPlayerPointsInt(api, "look", player.getUniqueId());
            if (balance == null || balance < amount) {
                player.sendMessage(plugin.getMessageUtil().get("color_unlock_no_money"));
                return false;
            }
            Boolean success = callPlayerPointsBoolean(api, "take", player.getUniqueId(), amount);
            if (!Boolean.TRUE.equals(success)) {
                player.sendMessage(plugin.getMessageUtil().get("payment_failed"));
                return false;
            }
            return true;
        }

        if (plugin.getEconomy() == null) {
            player.sendMessage(plugin.getMessageUtil().get("color_unlock_currency_unavailable"));
            return false;
        }
        if (!plugin.getEconomy().has(player, cost.price())) {
            player.sendMessage(plugin.getMessageUtil().get("color_unlock_no_money"));
            return false;
        }
        var response = plugin.getEconomy().withdrawPlayer(player, cost.price());
        if (!response.transactionSuccess()) {
            player.sendMessage(plugin.getMessageUtil().get("payment_failed"));
            return false;
        }
        return true;
    }

    private void refundUnlockCost(Player player, UnlockCost cost) {
        if (cost.price() <= 0.0) return;
        if ("playerpoints".equals(cost.currency())) {
            Object api = getPlayerPointsApi();
            if (api != null) {
                callPlayerPointsBoolean(api, "give", player.getUniqueId(), (int) Math.ceil(cost.price()));
            }
            return;
        }
        if (plugin.getEconomy() != null) {
            plugin.getEconomy().depositPlayer(player, cost.price());
        }
    }

    private Object getPlayerPointsApi() {
        org.bukkit.plugin.Plugin points = plugin.getServer().getPluginManager().getPlugin("PlayerPoints");
        if (points == null || !points.isEnabled()) return null;
        try {
            return points.getClass().getMethod("getAPI").invoke(points);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private Integer callPlayerPointsInt(Object api, String method, UUID uuid) {
        try {
            Object result = api.getClass().getMethod(method, UUID.class).invoke(api, uuid);
            return result instanceof Number number ? number.intValue() : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private Boolean callPlayerPointsBoolean(Object api, String method, UUID uuid, int amount) {
        try {
            Object result = api.getClass().getMethod(method, UUID.class, int.class).invoke(api, uuid, amount);
            return result instanceof Boolean value ? value : false;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    public void clearLandmarkColor(Player player) {
        Landmark editing = getEditingLandmark(player.getUniqueId());
        if (editing == null) {
            player.sendMessage(plugin.getMessageUtil().get("landmark_not_found"));
            return;
        }
        if (!isLandmarkColorSystemEnabled()) {
            openLandmarkManage(player, editing.getId());
            return;
        }
        String target = getLandmarkColorTarget(player.getUniqueId());
        if ("description".equals(target)) {
            editing.setDescriptionColor(null);
            editing.setDescriptionBold(false);
        } else {
            editing.setNameColor(null);
            editing.setNameBold(false);
        }
        openLandmarkManage(player, editing.getId());
    }

    public void toggleLandmarkTextBold(Player player) {
        Landmark editing = getEditingLandmark(player.getUniqueId());
        if (editing == null) {
            player.sendMessage(plugin.getMessageUtil().get("landmark_not_found"));
            return;
        }
        if (!isLandmarkColorSystemEnabled()) {
            openLandmarkManage(player, editing.getId());
            return;
        }
        if (!canUseBold(player)) {
            setCurrentTextBold(player.getUniqueId(), editing, false);
            player.sendMessage(plugin.getMessageUtil().get("bold_permission_denied"));
            openMenu(player, "landmark_color_select");
            return;
        }
        setCurrentTextBold(player.getUniqueId(), editing, !isCurrentColorBold(player));
        openMenu(player, "landmark_color_select");
    }

    private ColorOption getColorOption(String colorId) {
        MenuConfig config = shapeMenuManager.getMenu("landmark_color_select");
        if (config == null || config.getColors() == null) return null;
        return config.getColors().get(colorId);
    }

    private String getLandmarkColorTarget(UUID uuid) {
        return landmarkColorTargets.getOrDefault(uuid, "name");
    }

    private String getColorTargetLabel(String target) {
        return "description".equals(target)
                ? plugin.localize("地标描述", "Landmark description")
                : plugin.localize("地标名称", "Landmark name");
    }

    private void setCurrentTextStyle(UUID uuid, Landmark landmark, String style) {
        if ("description".equals(getLandmarkColorTarget(uuid))) {
            landmark.setDescriptionColor(style);
        } else {
            landmark.setNameColor(style);
        }
    }

    private void setCurrentTextBold(UUID uuid, Landmark landmark, boolean bold) {
        if ("description".equals(getLandmarkColorTarget(uuid))) {
            landmark.setDescriptionBold(bold);
        } else {
            landmark.setNameBold(bold);
        }
    }

    private boolean isCurrentColorBold(Player player) {
        Landmark editing = getEditingLandmark(player.getUniqueId());
        if (editing == null) return false;
        return "description".equals(getLandmarkColorTarget(player.getUniqueId()))
                ? editing.isDescriptionBold()
                : editing.isNameBold();
    }

    private String getCurrentColorPreview(Player player) {
        Landmark editing = getEditingLandmark(player.getUniqueId());
        if (editing == null) return "";
        if ("description".equals(getLandmarkColorTarget(player.getUniqueId()))) {
            return formatLandmarkDescription(editing);
        }
        return formatLandmarkName(editing);
    }

    public void setCategoryReturnMenu(UUID uuid, String menu) { categoryReturnMenu.put(uuid, menu); }
    public String getCategoryReturnMenu(UUID uuid) { return categoryReturnMenu.remove(uuid); }

    public Map<UUID, Integer> getPlayerPages() { return playerPages; }

    /**
     * 打开地标评价查看界面。W=地标摘要, A=评价列表。
     */
    public void openLandmarkReviews(Player player, int landmarkId) {
        Landmark lm = plugin.getLandmarkManager().getLandmark(landmarkId);
        if (lm == null) return;
        if (!plugin.getRatingManager().isEnabled()) {
            player.sendMessage(plugin.getMessageUtil().get("rating_disabled"));
            return;
        }

        editingLandmarkIds.put(player.getUniqueId(), landmarkId);
        playerCurrentMenu.put(player.getUniqueId(), "landmark_reviews");

        Inventory inv = shapeMenuManager.createInventory("landmark_reviews");
        MenuConfig config = shapeMenuManager.getMenu("landmark_reviews");
        if (config == null) return;

        // W 位置 — 地标摘要信息
        List<Integer> wSlots = getSlotsForChar(config, 'W');
        if (!wSlots.isEmpty()) {
            MenuItem wItem = config.getItems().get("W");
            if (wItem != null) {
                double avg = plugin.getRatingManager().getAverageRating(landmarkId);
                int count = plugin.getRatingManager().getRatingCount(landmarkId);
                ItemStack info = ItemUtil.buildItem(
                        wItem.getType(), wItem.getCustomModelData(), wItem.getPluginItem(),
                        wItem.getName(), wItem.getLore()
                );
                info = ItemUtil.replacePlaceholdersDefaultWhite(info, "%landmark_name%", formatLandmarkName(lm));
                info = ItemUtil.replacePlaceholdersDefaultWhite(info, "%landmark_description%", formatLandmarkDescription(lm));
                info = ItemUtil.replacePlaceholdersDefaultWhite(info, "%landmark_owner%", lm.getOwnerName());
                info = ItemUtil.replacePlaceholders(info, "%landmark_rating%",
                        MessageUtil.formatRating(avg));
                info = ItemUtil.replacePlaceholdersDefaultWhite(info, "%rating_count%", String.valueOf(count));
                info = ItemUtil.replacePlaceholders(info, "%landmark_category%", getCategoryName(lm.getCategoryId()));
                setItemInSlots(inv, wSlots, info);
            }
        }

        // A 位置 — 评价列表（异步加载）
        List<Integer> aSlots = getSlotsForChar(config, 'A');
        if (aSlots.isEmpty()) { openInventorySafe(player, inv); return; }

        // 清空所有A槽位
        for (int slot : aSlots) inv.setItem(slot, null);

        plugin.getRatingManager().getRatings(landmarkId, result -> {
            plugin.runAtPlayer(player, () -> {
                @SuppressWarnings("unchecked")
                List<Rating> ratings = (List<Rating>) result;
                int perPage = aSlots.size();
                int totalPages = getTotalPages(ratings.size(), perPage);
                int page = clampPage(player, totalPages);
                replaceStaticPlaceholders(player, inv, config, "landmark_reviews", page, totalPages);
                fillOwnReviewItem(player, inv, config, findPlayerRating(ratings, player.getUniqueId()));
                int start = (page - 1) * perPage;
                int end = Math.min(start + perPage, ratings.size());
                MenuItem reviewItem = config.getItems().get("A");

                for (int i = start; i < end; i++) {
                    int slotIdx = i - start;
                    if (slotIdx >= aSlots.size()) break;
                    Rating r = ratings.get(i);

                    ItemStack head = ItemUtil.buildPlayerHead(
                            reviewItem != null ? reviewItem.getName() : "&e%review_player%",
                            r.getPlayerUuid(),
                            r.getPlayerName(),
                            reviewItem != null ? normalizeReviewLore(reviewItem.getLore(), false) : defaultReviewLore());
                    head = replaceReviewPlaceholders(head, r);
                    setRatingId(head, r.getId());
                    inv.setItem(aSlots.get(slotIdx), head);
                }
                openInventorySafe(player, inv);
            });
        });
    }

    private Rating findPlayerRating(List<Rating> ratings, UUID playerUuid) {
        for (Rating rating : ratings) {
            if (playerUuid.equals(rating.getPlayerUuid())) return rating;
        }
        return null;
    }

    private void fillOwnReviewItem(Player player, Inventory inv, MenuConfig config, Rating ownRating) {
        List<Integer> sSlots = getSlotsForChar(config, 'S');
        if (sSlots.isEmpty()) return;
        MenuItem sItem = config.getItems().get("S");
        if (sItem == null) return;

        ItemStack item = ItemUtil.buildItem(
                sItem.getType(), sItem.getCustomModelData(), sItem.getPluginItem(),
                sItem.getName(), normalizeReviewLore(sItem.getLore(), true)
        );

        String score = ownRating != null ? MessageUtil.formatRating(ownRating.getScore()) : plugin.getMessageUtil().get("text_not_rated");
        String comment = ownRating != null && ownRating.getComment() != null && !ownRating.getComment().isEmpty()
                ? wrapReviewComment(ownRating.getComment())
                : plugin.getMessageUtil().get("text_no_comment");
        String date = ownRating != null ? formatReviewDate(ownRating.getCreatedAt()) : plugin.getMessageUtil().get("text_none");
        String time = ownRating != null ? formatReviewTime(ownRating.getCreatedAt()) : plugin.getMessageUtil().get("text_none");
        String dateTime = ownRating != null ? formatReviewDateTime(ownRating.getCreatedAt()) : plugin.getMessageUtil().get("text_none");
        String deleteHint = ownRating != null
                ? plugin.getMessageUtil().get("own_review_delete_hint")
                : plugin.getMessageUtil().get("own_review_empty_hint");

        item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%own_review_score%", score);
        item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%own_review_comment%", comment);
        item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%own_review_date%", date);
        item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%own_review_time%", time);
        item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%own_review_datetime%", dateTime);
        item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%own_review_delete_hint%", deleteHint);
        item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%own_review_delete_note%",
                ownRating != null ? plugin.getMessageUtil().get("own_review_delete_note") : "");
        setItemInSlots(inv, sSlots, item);
    }

    private ItemStack replaceReviewPlaceholders(ItemStack item, Rating rating) {
        if (!plugin.getRatingManager().isReportsEnabled()) {
            item = removeLoreLineContaining(item, "%review_report_hint%");
        }
        String comment = rating.getComment() != null && !rating.getComment().isEmpty()
                ? wrapReviewComment(rating.getComment())
                : plugin.getMessageUtil().get("text_no_comment");
        String date = formatReviewDate(rating.getCreatedAt());
        String time = formatReviewTime(rating.getCreatedAt());

        item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%review_player%", rating.getPlayerName());
        item = ItemUtil.replacePlaceholders(item, "%review_score%", MessageUtil.formatRating(rating.getScore()));
        item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%review_comment%", comment);
        item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%review_date%", date);
        item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%review_time%", time);
        item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%review_datetime%", formatReviewDateTime(rating.getCreatedAt()));
        item = ItemUtil.replacePlaceholdersDefaultWhite(item, "%review_report_hint%",
                plugin.getMessageUtil().get("review_report_hint",
                        "%threshold%", String.valueOf(plugin.getRatingManager().getReportDeleteThreshold())));
        return item;
    }

    private List<String> defaultReviewLore() {
        return isEnglishLanguage()
                ? List.of("&fScore: %review_score%", "&fComment:", "%review_comment%", "",
                "&fDate: %review_date%", "&fTime: %review_time%", "%review_report_hint%")
                : List.of("&f评分：%review_score%", "&f评价：", "%review_comment%", "",
                "&f日期：%review_date%", "&f时间：%review_time%", "%review_report_hint%");
    }

    private List<String> normalizeReviewLore(List<String> lore, boolean ownReview) {
        if (lore == null || lore.isEmpty()) return defaultReviewLore();

        String timePlaceholder = ownReview ? "%own_review_time%" : "%review_time%";
        String datePlaceholder = ownReview ? "%own_review_date%" : "%review_date%";
        boolean hasTimePlaceholder = lore.stream().anyMatch(line -> line.contains(timePlaceholder));
        boolean hasReportPlaceholder = ownReview || lore.stream().anyMatch(line -> line.contains("%review_report_hint%"));
        List<String> normalized = new ArrayList<>();
        boolean changed = false;
        for (String line : lore) {
            if (!ownReview && line.contains("%review_comment%") && !line.trim().equals("%review_comment%")) {
                normalized.add(isEnglishLanguage() ? "&fComment:" : "&f评价：");
                normalized.add("%review_comment%");
                normalized.add("");
                changed = true;
            } else if (!hasTimePlaceholder && line.contains(datePlaceholder)) {
                if (isEnglishLanguage()) {
                    normalized.add("&fDate: " + datePlaceholder);
                    normalized.add("&fTime: " + timePlaceholder);
                } else {
                    normalized.add("&f日期：" + datePlaceholder);
                    normalized.add("&f时间：" + timePlaceholder);
                }
                changed = true;
            } else {
                normalized.add(line);
            }
        }
        if (!hasReportPlaceholder) {
            normalized.add("%review_report_hint%");
            changed = true;
        }
        return changed ? normalized : lore;
    }

    private String wrapReviewComment(String text) {
        int width = Math.max(0, plugin.getConfig().getInt("rating.review_wrap_width", DEFAULT_REVIEW_COMMENT_WRAP_WIDTH));
        if (width <= 0) return "&a" + text;
        return wrapText(text, width);
    }

    private String wrapDescription(String text) {
        int width = Math.max(0, plugin.getConfig().getInt("landmark.description_wrap_width", DEFAULT_DESCRIPTION_WRAP_WIDTH));
        if (width <= 0) return "&a" + text;
        return wrapText(text, width);
    }

    private String wrapDescriptionPlain(String text) {
        int width = Math.max(0, plugin.getConfig().getInt("landmark.description_wrap_width", DEFAULT_DESCRIPTION_WRAP_WIDTH));
        if (width <= 0) return text != null ? text : "";
        return wrapTextPlain(text, width);
    }

    private String wrapDescriptionWithPrefix(String text, String prefix) {
        int width = Math.max(0, plugin.getConfig().getInt("landmark.description_wrap_width", DEFAULT_DESCRIPTION_WRAP_WIDTH));
        if (width <= 0) return prefix + (text != null ? text : "");
        return wrapTextWithPrefix(text, width, prefix);
    }

    private String formatLandmarkDate(Timestamp timestamp) {
        if (timestamp == null) return "—";
        return DateTimeFormatter.ISO_LOCAL_DATE.format(timestamp.toLocalDateTime());
    }

    private String formatPinExpires(Timestamp timestamp) {
        if (timestamp == null) return plugin.getMessageUtil().get("text_none");
        return PIN_EXPIRES_FORMAT.format(timestamp.toLocalDateTime());
    }

    private String formatReviewDate(Timestamp timestamp) {
        if (timestamp == null) return plugin.getMessageUtil().get("text_none");
        DateTimeFormatter formatter = isEnglishLanguage() ? EN_REVIEW_DATE_FORMAT : ZH_REVIEW_DATE_FORMAT;
        return formatter.format(timestamp.toLocalDateTime());
    }

    private String formatReviewTime(Timestamp timestamp) {
        if (timestamp == null) return plugin.getMessageUtil().get("text_none");
        DateTimeFormatter formatter = isEnglishLanguage() ? EN_REVIEW_TIME_FORMAT : ZH_REVIEW_TIME_FORMAT;
        return formatter.format(timestamp.toLocalDateTime());
    }

    private String formatReviewDateTime(Timestamp timestamp) {
        if (timestamp == null) return plugin.getMessageUtil().get("text_none");
        return formatReviewDate(timestamp) + " " + formatReviewTime(timestamp);
    }

    private boolean isEnglishLanguage() {
        return "en_US".equalsIgnoreCase(plugin.getConfig().getString("language", "zh_CN"));
    }

    /**
     * 按指定宽度自动换行，每行前加颜色码保持地标文本颜色。
     */
    private String wrapText(String text, int width) {
        return wrapTextWithPrefix(text, width, "&a");
    }

    private String wrapTextWithPrefix(String text, int width, String prefix) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(prefix);
        for (int i = 0; i < text.length(); i++) {
            if (i > 0 && i % width == 0) sb.append('\n').append(prefix);
            sb.append(text.charAt(i));
        }
        return sb.toString();
    }

    private String wrapTextPlain(String text, int width) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (i > 0 && i % width == 0) sb.append('\n');
            sb.append(text.charAt(i));
        }
        return sb.toString();
    }

    public void clearPlayerState(Player player) {
        UUID uuid = player.getUniqueId();
        playerPages.remove(uuid);
        playerFilters.remove(uuid);
        playerSearches.remove(uuid);
        favoriteFilters.remove(uuid);
        favoriteSearches.remove(uuid);
        adminFilters.remove(uuid);
        adminSearches.remove(uuid);
        pendingSearchReturnMenu.remove(uuid);
        pendingTeleportConfirmations.remove(uuid);
        clearPinState(uuid);
        playerCurrentMenu.remove(uuid);
        clearEditingState(uuid);
    }

    /**
     * 获取玩家当前打开的菜单名称。
     */
    public String getPlayerCurrentMenu(UUID uuid) {
        return playerCurrentMenu.get(uuid);
    }

    /**
     * 清除玩家当前菜单状态（保留编辑状态）。
     */
    public void clearPlayerMenuState(UUID uuid) {
        playerCurrentMenu.remove(uuid);
    }

    public void closeMenuSilently(Player player) {
        UUID uuid = player.getUniqueId();
        silentClosingMenu.add(uuid);
        playerCurrentMenu.remove(uuid);
        player.closeInventory();
    }

    /** 仅在非菜单切换时清除状态，防止切换菜单的瞬间物品可被操作 */
    public void onMenuClosed(UUID uuid) {
        if (switchingMenu.contains(uuid)) return;
        if (silentClosingMenu.remove(uuid)) return;
        String menu = playerCurrentMenu.get(uuid);
        if ("pin_purchase".equals(menu) || "pin_landmark_select".equals(menu)) {
            clearPinState(uuid);
        }
        playerCurrentMenu.remove(uuid);
    }

    private void openInventorySafe(Player player, Inventory inv) {
        switchingMenu.add(player.getUniqueId());
        player.openInventory(inv);
        switchingMenu.remove(player.getUniqueId());
    }

    /**
     * 获取玩家搜索表（供PlayerListener使用）。
     */
    public Map<UUID, String> getPlayerSearches() {
        return playerSearches;
    }
}
