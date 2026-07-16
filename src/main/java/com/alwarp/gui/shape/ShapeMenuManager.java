package com.alwarp.gui.shape;

import com.alwarp.ALwarp;
import com.alwarp.util.ItemUtil;
import com.alwarp.util.MessageUtil;
import com.alwarp.util.TextStyleUtil;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.*;

/**
 * Shape菜单管理器。
 * 从 menus/ 目录加载所有 .yml 文件，每个文件的顶层key即菜单名。
 * 字符→槽位映射：Shape每行9个字符，对应9个格子。
 */
public class ShapeMenuManager {

    private final ALwarp plugin;
    private final Map<String, MenuConfig> menus = new HashMap<>();
    private final Map<String, String> menuFileMap = new HashMap<>(); // 菜单名 → 文件名
    private final Map<String, Boolean> canonicalMenuKeys = new HashMap<>();

    private final String menuDir; // "menus" or "menus_en"

    public ShapeMenuManager(ALwarp plugin, String menuDir) {
        this.plugin = plugin;
        this.menuDir = menuDir;
    }

    /**
     * 从 menus/ 目录加载所有菜单配置。
     * 每个 .yml 文件可以包含一个或多个菜单（顶层key=菜单名）。
     */
    public void loadMenus() {
        menus.clear();
        menuFileMap.clear();
        canonicalMenuKeys.clear();

        File dir = new File(plugin.getDataFolder(), menuDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        String[] defaultFiles = {
                "main.yml", "my_landmarks.yml", "managed_landmarks.yml", "landmark_manage.yml",
                "landmark_admins.yml", "landmark_blacklist.yml", "category_select.yml", "landmark_reviews.yml",
                "pin_purchase.yml", "pin_landmark_select.yml", "my_favorites.yml", "admin_landmarks.yml",
                "landmark_color_select.yml"
        };
        for (String fileName : defaultFiles) {
            File file = new File(dir, fileName);
            File legacyFile = new File(dir, fileName.replace('_', '-'));
            if (file.exists() || legacyFile.exists()) continue;

            String resourcePath = menuDir + "/" + fileName;
            try {
                plugin.saveResource(resourcePath, false);
            } catch (IllegalArgumentException ignored) {
            }
        }

        File[] files = dir.listFiles((d, name) -> name.endsWith(".yml"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));

        for (File file : files) {
            FileConfiguration config = YamlConfiguration.loadConfiguration(file);
            for (String menuName : config.getKeys(false)) {
                String canonicalName = normalizeMenuName(menuName);
                if (canonicalName == null || canonicalName.isBlank()) {
                    continue;
                }
                boolean isCanonicalKey = canonicalName.equals(menuName);
                Boolean existingCanonical = canonicalMenuKeys.get(canonicalName);
                if (existingCanonical != null && (existingCanonical || !isCanonicalKey)) {
                    continue;
                }

                MenuConfig menuConfig = parseMenu(config, menuName);
                menus.put(canonicalName, menuConfig);
                menuFileMap.put(canonicalName, file.getName());
                canonicalMenuKeys.put(canonicalName, isCanonicalKey);
            }
        }

        validateRequiredMenuEntries();
    }

    private void validateRequiredMenuEntries() {
        List<String> missing = new ArrayList<>();

        for (String menuName : List.of(
                "main_menu",
                "my_landmarks",
                "managed_landmarks",
                "landmark_manage",
                "landmark_admins",
                "landmark_blacklist",
                "category_select",
                "landmark_reviews",
                "pin_purchase",
                "pin_landmark_select",
                "my_favorites",
                "admin_landmarks",
                "landmark_color_select"
        )) {
            requireMenu(missing, menuName);
        }

        MenuConfig mainMenu = menus.get("main_menu");
        if (mainMenu != null) {
            requireItemAction(missing, mainMenu, "main_menu", "A", "right_action", "toggle_favorite:");
            requireItemAction(missing, mainMenu, "main_menu", "A", "shift_left_action", "view_reviews:");
            for (int pinSlot = 1; pinSlot <= 7; pinSlot++) {
                requirePinSlot(missing, mainMenu, "main_menu", String.valueOf(pinSlot), pinSlot);
            }
        }

        MenuConfig myLandmarks = menus.get("my_landmarks");
        if (myLandmarks != null) {
            requireItemAction(missing, myLandmarks, "my_landmarks", "A", "right_action", "open_gui:landmark_manage");
            requireItemAction(missing, myLandmarks, "my_landmarks", "S", "action", "open_gui:managed_landmarks");
            requireItemAction(missing, myLandmarks, "my_landmarks", "Q", "action", "claim_income");
        }

        MenuConfig managedLandmarks = menus.get("managed_landmarks");
        if (managedLandmarks != null) {
            requireItemAction(missing, managedLandmarks, "managed_landmarks", "A", "right_action", "open_gui:landmark_manage");
            requireItemAction(missing, managedLandmarks, "managed_landmarks", "F", "action", "open_gui:my_landmarks");
        }

        MenuConfig landmarkManage = menus.get("landmark_manage");
        if (landmarkManage != null) {
            requireItemAction(missing, landmarkManage, "landmark_manage", "A", "right_action", "open_landmark_color:name");
            requireItemAction(missing, landmarkManage, "landmark_manage", "B", "right_action", "open_landmark_color:description");
            requireItemAction(missing, landmarkManage, "landmark_manage", "H", "action", "open_blacklist_interface");
        }

        MenuConfig landmarkColorSelect = menus.get("landmark_color_select");
        if (landmarkColorSelect != null) {
            requireItemAction(missing, landmarkColorSelect, "landmark_color_select", "C", "action", "toggle_landmark_text_bold");
            requireItemAction(missing, landmarkColorSelect, "landmark_color_select", "Q", "action", "clear_landmark_color");
            requireItemAction(missing, landmarkColorSelect, "landmark_color_select", "X", "action", "return_landmark_manage");
        }

        MenuConfig landmarkAdmins = menus.get("landmark_admins");
        if (landmarkAdmins != null) {
            requireItemAction(missing, landmarkAdmins, "landmark_admins", "A", "shift_left_action", "remove_manager:");
        }

        MenuConfig landmarkReviews = menus.get("landmark_reviews");
        if (landmarkReviews != null) {
            requireItemAction(missing, landmarkReviews, "landmark_reviews", "B", "action", "rate_current_landmark");
        }

        MenuConfig myFavorites = menus.get("my_favorites");
        if (myFavorites != null) {
            requireItemAction(missing, myFavorites, "my_favorites", "A", "right_action", "toggle_favorite:");
            requireItemAction(missing, myFavorites, "my_favorites", "A", "shift_left_action", "view_reviews:");
        }

        if (missing.isEmpty()) return;

        int limit = Math.min(missing.size(), 24);
        String summary = String.join(", ", missing.subList(0, limit));
        if (missing.size() > limit) {
            summary += ", ... +" + (missing.size() - limit);
        }
        plugin.logWarning(
                "菜单配置缺少新版功能入口，已保留已有菜单文件，不自动补全。缺少: "
                        + summary + "。删除对应菜单文件可重新生成默认配置，或手动补齐。",
                "Menu configs are missing newer entries; existing files were not auto-completed. Missing: "
                        + summary + ". Delete the related menu file to regenerate defaults, or add entries manually."
        );
    }

    private void requireMenu(List<String> missing, String menuName) {
        if (!menus.containsKey(menuName)) {
            missing.add(menuName);
        }
    }

    private void requirePinSlot(List<String> missing, MenuConfig menu, String menuName, String itemKey, int pinSlotId) {
        MenuItem item = menu.getItems() != null ? menu.getItems().get(itemKey) : null;
        if (item == null) {
            missing.add(menuName + ".items." + itemKey);
            return;
        }
        if (!Boolean.TRUE.equals(item.getPinSlot())) {
            missing.add(menuName + ".items." + itemKey + ".pin_slot");
        }
        if (!Objects.equals(item.getPinSlotId(), pinSlotId)) {
            missing.add(menuName + ".items." + itemKey + ".pin_slot_id");
        }
        if (!startsWith(item.getAction(), "pin_slot:")) {
            missing.add(menuName + ".items." + itemKey + ".action");
        }
    }

    private void requireItemAction(List<String> missing, MenuConfig menu, String menuName, String itemKey,
                                   String actionName, String expectedPrefix) {
        MenuItem item = menu.getItems() != null ? menu.getItems().get(itemKey) : null;
        if (item == null) {
            missing.add(menuName + ".items." + itemKey);
            return;
        }
        String action = switch (actionName) {
            case "right_action" -> item.getRightAction();
            case "shift_left_action" -> item.getShiftLeftAction();
            case "shift_right_action" -> item.getShiftRightAction();
            default -> item.getAction();
        };
        if (!startsWith(action, expectedPrefix)) {
            missing.add(menuName + ".items." + itemKey + "." + actionName);
        }
    }

    private boolean startsWith(String value, String prefix) {
        return value != null && value.startsWith(prefix);
    }

    private MenuConfig parseMenu(FileConfiguration config, String menuName) {
        MenuConfig menuConfig = new MenuConfig();
        menuConfig.setTitle(config.getString(menuName + ".title", "菜单"));
        menuConfig.setAdminTitle(config.getString(menuName + ".admin_title", null));
        menuConfig.setShape(config.getStringList(menuName + ".shape"));
        menuConfig.setAdminShape(config.getStringList(menuName + ".admin_shape"));
        menuConfig.setBoldPermission(config.getString(menuName + ".bold.permission", "alwarp.color.bold"));

        Map<String, MenuItem> items = new HashMap<>();
        String itemsPath = menuName + ".items";
        if (config.contains(itemsPath)) {
            for (String itemKey : config.getConfigurationSection(itemsPath).getKeys(false)) {
                MenuItem mi = new MenuItem();
                String p = itemsPath + "." + itemKey;
                String configuredType = config.getString(p + ".type", null);
                String pluginItem = readPluginItemReference(config, p, configuredType);
                mi.setType(resolveDisplayType(configuredType, pluginItem));
                if (config.contains(p + ".custom_model_data")) {
                    mi.setCustomModelData(config.getInt(p + ".custom_model_data"));
                }
                mi.setPluginItem(pluginItem);
                mi.setName(config.getString(p + ".name", ""));
                mi.setLore(config.getStringList(p + ".lore"));
                mi.setAction(config.getString(p + ".action", ""));
                mi.setRightAction(config.getString(p + ".right_action", null));
                mi.setShiftLeftAction(config.getString(p + ".shift_left_action", null));
                mi.setShiftRightAction(config.getString(p + ".shift_right_action", null));
                mi.setTriggers(readTriggers(config, p + ".triggers"));
                if (config.contains(p + ".pin_slot")) {
                    mi.setPinSlot(config.getBoolean(p + ".pin_slot"));
                }
                if (config.contains(p + ".pin_slot_id")) {
                    mi.setPinSlotId(config.getInt(p + ".pin_slot_id"));
                }
                if (config.contains(p + ".cost")) {
                    mi.setCost(config.getDouble(p + ".cost"));
                }
                if (config.contains(p + ".duration_days")) {
                    mi.setDurationDays(config.getInt(p + ".duration_days"));
                }
                if (config.contains(p + ".default")) {
                    mi.setIsDefault(config.getBoolean(p + ".default"));
                }
                items.put(itemKey, mi);
            }
        }
        menuConfig.setItems(items);
        menuConfig.setColors(parseColorOptions(config, menuName));
        return menuConfig;
    }

    private Map<String, List<String>> readTriggers(FileConfiguration config, String path) {
        Map<String, List<String>> triggers = new LinkedHashMap<>();
        if (!config.isConfigurationSection(path)) return triggers;

        for (String trigger : List.of("left", "right", "shift_left", "shift_right")) {
            List<String> actions = readStringList(config, path + "." + trigger);
            if (!actions.isEmpty()) {
                triggers.put(trigger, actions);
            }
        }
        return triggers;
    }

    private List<String> readStringList(FileConfiguration config, String path) {
        if (config.isList(path)) {
            return config.getStringList(path);
        }
        String value = config.getString(path, null);
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value);
    }

    private Map<String, ColorOption> parseColorOptions(FileConfiguration config, String menuName) {
        Map<String, ColorOption> colors = new LinkedHashMap<>();
        String colorsPath = menuName + ".colors";
        if (!config.isConfigurationSection(colorsPath)) return colors;

        for (String colorKey : config.getConfigurationSection(colorsPath).getKeys(false)) {
            String p = colorsPath + "." + colorKey;
            ColorOption option = new ColorOption();
            option.setId(colorKey);
            String configuredType = config.getString(p + ".type", "PAPER");
            String pluginItem = readPluginItemReference(config, p, configuredType);
            option.setType(resolveDisplayType(configuredType, pluginItem));
            if (config.contains(p + ".custom_model_data")) {
                option.setCustomModelData(config.getInt(p + ".custom_model_data"));
            }
            option.setPluginItem(pluginItem);
            option.setName(config.getString(p + ".name", "%color_sample%"));
            option.setLore(config.getStringList(p + ".lore"));
            option.setPermission(config.getString(p + ".permission", ""));
            option.setSample(config.getString(p + ".sample", colorKey));
            if (config.contains(p + ".purchase.enabled")) {
                option.setPurchaseEnabled(config.getBoolean(p + ".purchase.enabled"));
            }
            option.setPurchaseCurrency(config.getString(p + ".purchase.currency", null));
            if (config.contains(p + ".purchase.price")) {
                option.setPurchasePrice(config.getDouble(p + ".purchase.price"));
            }
            option.setPurchaseCurrencyName(config.getString(p + ".purchase.currency_name", null));

            String style = config.getString(p + ".color", config.getString(p + ".style", null));
            if (style == null && config.isList(p + ".gradient")) {
                style = TextStyleUtil.gradientStyle(config.getStringList(p + ".gradient"));
            }
            option.setStyle(TextStyleUtil.normalizeStyle(style));
            if (option.getStyle() != null) {
                colors.put(colorKey, option);
            }
        }
        return colors;
    }

    private static String resolveDisplayType(String configuredType, String pluginItem) {
        if (configuredType == null || configuredType.isBlank()) {
            return pluginItem != null ? "PAPER" : "STONE";
        }
        return normalizePluginItemReference(configuredType) != null ? "PAPER" : configuredType;
    }

    private static String readPluginItemReference(FileConfiguration config, String path, String configuredType) {
        String pluginItem = normalizePluginItemReference(config.getString(path + ".plugin_item", null));
        if (pluginItem != null) return pluginItem;

        pluginItem = directPluginItemReference("ce", config.getString(path + ".craftengine", null));
        if (pluginItem != null) return pluginItem;

        pluginItem = directPluginItemReference("ce", config.getString(path + ".ce", null));
        if (pluginItem != null) return pluginItem;

        pluginItem = directPluginItemReference("ia", config.getString(path + ".itemsadder", null));
        if (pluginItem != null) return pluginItem;

        pluginItem = directPluginItemReference("ia", config.getString(path + ".ia", null));
        if (pluginItem != null) return pluginItem;

        pluginItem = directPluginItemReference("oraxen", config.getString(path + ".oraxen", null));
        if (pluginItem != null) return pluginItem;

        return normalizePluginItemReference(configuredType);
    }

    private static String directPluginItemReference(String prefix, String value) {
        String normalized = normalizePluginItemReference(value);
        if (normalized != null) return normalized;

        String id = normalizePluginItemId(value);
        if (id == null || id.isBlank()) return null;
        return prefix + ":" + id;
    }

    private static String normalizePluginItemReference(String value) {
        String normalized = normalizePluginItemId(value);
        if (normalized == null || normalized.isBlank()) return null;

        int colon = normalized.indexOf(':');
        if (colon <= 0 || colon >= normalized.length() - 1) return null;

        String prefix = normalized.substring(0, colon).toLowerCase(Locale.ROOT);
        String id = normalized.substring(colon + 1).trim();
        if (id.isBlank()) return null;

        return switch (prefix) {
            case "ce", "craftengine" -> "ce:" + id;
            case "ia", "itemsadder" -> "ia:" + id;
            case "oraxen" -> "oraxen:" + id;
            default -> null;
        };
    }

    private static String normalizePluginItemId(String value) {
        return value == null ? null : value.trim().replace('：', ':');
    }

    public static String normalizeMenuName(String menuName) {
        if (menuName == null) return null;
        return menuName.trim().replace('-', '_');
    }

    /**
     * 根据Shape配置创建Inventory。
     */
    public Inventory createInventory(String menuName) {
        menuName = normalizeMenuName(menuName);
        MenuConfig config = menus.get(menuName);
        if (config == null) {
            ShapeMenuHolder h = new ShapeMenuHolder("unknown");
            Inventory i = Bukkit.createInventory(h, 54, plugin.getMessageUtil().get("text_unknown_menu"));
            h.setInventory(i);
            return i;
        }

        List<String> shape = config.getShape();
        return createInventory(menuName, shape);
    }

    public Inventory createInventory(String menuName, List<String> shape) {
        return createInventory(menuName, shape, null);
    }

    public Inventory createInventory(String menuName, List<String> shape, String title) {
        menuName = normalizeMenuName(menuName);
        MenuConfig config = menus.get(menuName);
        if (config == null) {
            ShapeMenuHolder h = new ShapeMenuHolder("unknown");
            Inventory i = Bukkit.createInventory(h, 54, plugin.getMessageUtil().get("text_unknown_menu"));
            h.setInventory(i);
            return i;
        }
        if (shape == null || shape.isEmpty()) {
            shape = config.getShape();
        }
        int rows = shape.size();
        int size = rows * 9;
        ShapeMenuHolder holder = new ShapeMenuHolder(menuName, List.copyOf(shape));
        String inventoryTitle = title != null && !title.isBlank() ? title : config.getTitle();
        Inventory inv = Bukkit.createInventory(holder, size, MessageUtil.colorize(inventoryTitle));
        holder.setInventory(inv);

        for (int row = 0; row < rows; row++) {
            String line = shape.get(row);
            for (int col = 0; col < Math.min(line.length(), 9); col++) {
                char c = line.charAt(col);
                if (c == ' ') {
                    continue;
                }
                String charStr = String.valueOf(c);
                MenuItem menuItem = config.getItems().get(charStr);

                if (menuItem != null) {
                    ItemStack item = ItemUtil.buildItem(
                            menuItem.getType(),
                            menuItem.getCustomModelData(),
                            menuItem.getPluginItem(),
                            menuItem.getName(),
                            menuItem.getLore()
                    );
                    int slot = row * 9 + col;
                    if (slot < size) {
                        inv.setItem(slot, item);
                    }
                }
            }
        }
        return inv;
    }

    public MenuConfig getMenu(String name) { return menus.get(normalizeMenuName(name)); }
    public Map<String, MenuConfig> getAllMenus() { return Map.copyOf(menus); }
    public String getMenuFile(String name) { return menuFileMap.get(normalizeMenuName(name)); }
    public String getMenuDir() { return menuDir; }
    public boolean hasMenu(String name) { return menus.containsKey(normalizeMenuName(name)); }

    public void reloadMenus() { loadMenus(); }
}
