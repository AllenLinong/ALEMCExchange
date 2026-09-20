package com.alemcexchange.util;

import com.alemcexchange.config.ConfigManager;
import com.alemcexchange.database.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Locale;

/**
 * 菜单管理器（v2，参照 ALwarp 的 shape 布局 + 聊天搜索）。
 *
 * 性能设计：
 *  - menus.yml 在加载/重载时一次性解析成 MenuLayout，静态按钮预构建为 ItemStack；
 *  - 物品基础图标（材质/CE 物品）按配置戳缓存，翻页/重开不再重复 Material 解析与 CE 反射；
 *  - 玩家数据（余额/解锁/进度）按玩家缓存，30 分钟未活跃自动清理；
 *  - 搜索索引在 ConfigManager 预计算，搜索时仅做 lowercase contains。
 */
public class MenuManager implements Listener {

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final DatabaseManager databaseManager;
    private final SchedulerUtil schedulerUtil;

    private final NamespacedKey keyAction;
    private final NamespacedKey keyRightAction;
    private final NamespacedKey keyItem;

    // ── 菜单布局缓存 ──
    private volatile long parsedStamp = -1;
    private final Map<String, MenuLayout> layouts = new HashMap<>();
    private final Map<String, ItemStack> baseIconCache = new ConcurrentHashMap<>();

    // ── 按钮点击冷却（按玩家全局，防连点器；gui.button-click-cooldown-ms 可调） ──
    private final Map<UUID, Long> lastButtonClickMillis = new ConcurrentHashMap<>();

    // ── 玩家运行状态 ──
    private final Map<UUID, String> browseSearches = new ConcurrentHashMap<>();
    private final Map<UUID, String> exchangeSearches = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingSearchType = new ConcurrentHashMap<>();

    // ── 玩家数据缓存 ──
    private final ConcurrentHashMap<UUID, Double> playerBalanceCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Map<String, Boolean>> playerUnlockedCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Map<String, Integer>> playerMineProgressCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Map<String, Integer>> playerSellProgressCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> playerCacheTimestamps = new ConcurrentHashMap<>();

    public MenuManager(JavaPlugin plugin, ConfigManager configManager, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.databaseManager = databaseManager;
        this.schedulerUtil = new SchedulerUtil(plugin);
        this.keyAction = new NamespacedKey(plugin, "action");
        this.keyRightAction = new NamespacedKey(plugin, "raction");
        this.keyItem = new NamespacedKey(plugin, "ikey");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        ensureLayouts();
        startCacheCleanupTask();
    }

    // ══════════════════════════ 布局解析 ══════════════════════════

    private void ensureLayouts() {
        long stamp = configManager.getConfigStamp();
        if (stamp == parsedStamp && !layouts.isEmpty()) return;
        synchronized (layouts) {
            if (stamp == parsedStamp && !layouts.isEmpty()) return;
            Map<String, MenuLayout> parsed = new HashMap<>();
            for (Map.Entry<String, org.bukkit.configuration.file.YamlConfiguration> entry
                    : configManager.getMenuConfigs().entrySet()) {
                try {
                    parsed.put(entry.getKey(), parseLayout(entry.getKey(), entry.getValue()));
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to parse menu '" + entry.getKey() + "': " + e.getMessage());
                }
            }
            layouts.clear();
            layouts.putAll(parsed);
            parsedStamp = stamp;
            baseIconCache.clear();
            CeItemUtil.clearCache();
        }
    }

    /** 解析 ALwarp 风格 triggers（left/right/shift_left/shift_right，单条或列表） */
    private Map<String, List<String>> parseTriggers(ConfigurationSection trig) {
        if (trig == null) return Collections.emptyMap();
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String key : trig.getKeys(false)) {
            List<String> list = new ArrayList<>();
            Object value = trig.get(key);
            if (value instanceof List<?> listVal) {
                for (Object o : listVal) {
                    if (o != null && !String.valueOf(o).isBlank()) list.add(String.valueOf(o));
                }
            } else if (value instanceof String strVal && !strVal.isBlank()) {
                list.add(strVal);
            }
            if (!list.isEmpty()) result.put(key.toLowerCase(java.util.Locale.ROOT), list);
        }
        return result;
    }

    private MenuLayout parseLayout(String name, org.bukkit.configuration.file.YamlConfiguration sec) {
        List<String> shape = sec.getStringList("shape");
        if (shape.isEmpty() || shape.size() > 6) {
            throw new IllegalArgumentException("invalid shape (need 1-6 rows of 9 chars)");
        }
        MenuLayout layout = new MenuLayout(name, shape, sec.getString("title", ""));

        String glassType = sec.getString("glass.type", "GRAY_STAINED_GLASS_PANE");
        try {
            layout.glass = new ItemStack(Material.valueOf(glassType));
            ItemMeta gm = layout.glass.getItemMeta();
            if (gm != null) {
                gm.setDisplayName(" ");
                layout.glass.setItemMeta(gm);
            }
        } catch (IllegalArgumentException e) {
            layout.glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        }

        // 兼容旧 glass: 段 —— items 未定义 '#' 时自动注入玻璃按钮
        if (!sec.contains("items.#") && sec.contains("glass.type")) {
            ButtonDef glassDef = new ButtonDef();
            try {
                glassDef.template = new ItemStack(Material.valueOf(sec.getString("glass.type", "GRAY_STAINED_GLASS_PANE")));
                ItemMeta gm = glassDef.template.getItemMeta();
                if (gm != null) {
                    gm.setDisplayName(" ");
                    glassDef.template.setItemMeta(gm);
                }
            } catch (IllegalArgumentException e) {
                glassDef.template = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            }
            layout.buttons.put('#', glassDef);
        }

        ConfigurationSection itemsSec = sec.getConfigurationSection("items");
        if (itemsSec != null) {
            for (String btnKey : itemsSec.getKeys(false)) {
                if (btnKey.length() != 1) continue;
                char ch = btnKey.charAt(0);
                ConfigurationSection btn = itemsSec.getConfigurationSection(btnKey);
                if (btn == null) continue;
                ButtonDef def = new ButtonDef();
                def.name = btn.getString("name");
                def.lore = btn.getStringList("lore");
                def.action = btn.getString("action");
                def.rightAction = btn.getString("right_action");
                def.pluginItem = btn.contains("craftengine") ? btn.getString("craftengine")
                        : (btn.contains("plugin_item") ? btn.getString("plugin_item") : null);
                String type = btn.getString("type");
                if (ch != 'A' && type != null && type.equalsIgnoreCase("AIR")) {
                    def.template = null;   // AIR = 该槽位留空
                } else if (ch != 'A') {
                    def.template = buildStaticIcon(type, def.pluginItem);
                }
                def.triggers = parseTriggers(btn.getConfigurationSection("triggers"));
                layout.buttons.put(ch, def);
            }
        }
        return layout;
    }

    /** 静态按钮图标：原版材质或 CE 物品（craftengine: "ns:id"） */
    private ItemStack buildStaticIcon(String type, String pluginItem) {
        ItemStack item = null;
        if (pluginItem != null && !pluginItem.isEmpty()) {
            String ref = pluginItem.trim().replace('：', ':');
            if (ref.toLowerCase(Locale.ROOT).startsWith("ce:") || ref.toLowerCase(Locale.ROOT).startsWith("craftengine:")) {
                String ceId = CeItemUtil.extractCeId(ref.toLowerCase(Locale.ROOT).startsWith("ce:") ? "ce:" + ref.substring(3) : "craftengine:" + ref.substring("craftengine:".length()));
                item = CeItemUtil.buildCeItem(ceId);
            }
        }
        if (item == null && type != null && !type.isEmpty()) {
            if (type.contains(":") && type.toLowerCase(Locale.ROOT).startsWith("ce:")) {
                item = CeItemUtil.buildCeItem(CeItemUtil.extractCeId(type));
            } else {
                try {
                    item = new ItemStack(Material.valueOf(type));
                } catch (IllegalArgumentException e) {
                    item = new ItemStack(Material.BARRIER);
                }
            }
        }
        if (item == null) item = new ItemStack(Material.BARRIER);
        return item;
    }

    // ══════════════════════════ 菜单打开 ══════════════════════════

    public void openMainMenu(Player player) {
        ensureLayouts();
        MenuLayout layout = layouts.get("main");
        if (layout == null) {
            plugin.getLogger().severe("Menu 'main' missing from menus.yml");
            return;
        }
        clearPlayerCache(player.getUniqueId());

        double balance = getCachedBalance(player.getUniqueId());
        String dailyLimit = getDailyLimitDisplayText(player);

        Map<String, String> ph = new HashMap<>();
        ph.put("%balance%", String.format("%.2f", balance));
        ph.put("%daily_limit%", dailyLimit);
        ph.put("%tax%", String.format("%.1f", configManager.getTaxRateForPlayer(player) * 100));

        MenuHolder holder = new MenuHolder("main", 1, null, layout.aSlots, layout);
        Inventory inv = Bukkit.createInventory(holder, layout.size, ColorUtil.translateColorCodes(layout.title));
        holder.setInventory(inv);
        fillStaticButtons(inv, layout, ph, 1, 1, 0);
        player.openInventory(inv);
    }

    public void openSellMenu(Player player) {
        ensureLayouts();
        MenuLayout layout = layouts.get("sell");
        if (layout == null) {
            plugin.getLogger().severe("Menu 'sell' missing from menus.yml");
            return;
        }
        clearPlayerCache(player.getUniqueId());

        Map<String, String> ph = new HashMap<>();
        ph.put("%balance%", String.format("%.2f", getCachedBalance(player.getUniqueId())));
        ph.put("%tax%", String.format("%.1f", configManager.getTaxRateForPlayer(player) * 100));

        MenuHolder holder = new MenuHolder("sell", 1, null, layout.aSlots, layout);
        Inventory inv = Bukkit.createInventory(holder, layout.size, ColorUtil.translateColorCodes(layout.title));
        holder.setInventory(inv);
        fillStaticButtons(inv, layout, ph, 1, 1, 0);
        player.openInventory(inv);
    }

    public void openBrowseMenu(Player player) {
        openBrowseMenu(player, 1);
    }

    public void openBrowseMenu(Player player, int page) {
        ensureLayouts();
        MenuLayout layout = layouts.get("browse");
        if (layout == null) {
            plugin.getLogger().severe("Menu 'browse' missing from menus.yml");
            return;
        }
        clearPlayerCache(player.getUniqueId());

        String search = browseSearches.get(player.getUniqueId());
        List<String> materials = applySearch(configManager.getItemMaterialNames(), search);

        int totalItems = materials.size();
        int itemsPerPage = Math.max(1, layout.aSlots.size());
        int totalPages = (totalItems + itemsPerPage - 1) / itemsPerPage;
        if (totalPages == 0) totalPages = 1;
        if (page > totalPages) page = totalPages;
        if (page < 1) page = 1;

        int startIndex = (page - 1) * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, totalItems);
        List<String> pageMaterials = materials.subList(startIndex, endIndex);

        Map<String, Boolean> unlocked = getCachedUnlockedStatuses(player.getUniqueId(), pageMaterials);
        Map<String, Integer> mineProg = getCachedMineProgresses(player.getUniqueId(), pageMaterials);
        Map<String, Integer> sellProg = getCachedSellProgresses(player.getUniqueId(), pageMaterials);

        Map<String, String> ph = pagePlaceholders(page, totalPages, totalItems, search);

        MenuHolder holder = new MenuHolder("browse", page, search, layout.aSlots, layout);
        Inventory inv = Bukkit.createInventory(holder, layout.size,
                ColorUtil.translateColorCodes(applyPlaceholders(layout.title, ph)));
        holder.setInventory(inv);
        fillStaticButtons(inv, layout, ph, page, totalPages, totalItems);

        ButtonDef template = layout.buttons.get('A');
        for (int i = 0; i < pageMaterials.size(); i++) {
            String materialName = pageMaterials.get(i);
            ItemStack icon = buildItemIcon(materialName, template, false,
                    unlocked.getOrDefault(materialName, false),
                    mineProg.getOrDefault(materialName, 0),
                    sellProg.getOrDefault(materialName, 0));
            if (icon != null) {
                inv.setItem(layout.aSlots.get(i), icon);
            }
        }
        player.openInventory(inv);
    }

    public void openExchangeMenu(Player player) {
        openExchangeMenu(player, 1);
    }

    public void openExchangeMenu(Player player, int page) {
        ensureLayouts();
        MenuLayout layout = layouts.get("exchange");
        if (layout == null) {
            plugin.getLogger().severe("Menu 'exchange' missing from menus.yml");
            return;
        }
        clearPlayerCache(player.getUniqueId());

        String search = exchangeSearches.get(player.getUniqueId());
        List<String> allMaterials = configManager.getItemMaterialNames();
        Map<String, Boolean> unlockedAll = getCachedUnlockedStatuses(player.getUniqueId(), allMaterials);
        List<String> unlockedMaterials = new ArrayList<>(allMaterials.size());
        for (String materialName : allMaterials) {
            if (unlockedAll.getOrDefault(materialName, false)) {
                unlockedMaterials.add(materialName);
            }
        }
        List<String> materials = applySearch(unlockedMaterials, search);

        int totalItems = materials.size();
        int itemsPerPage = Math.max(1, layout.aSlots.size());
        int totalPages = (totalItems + itemsPerPage - 1) / itemsPerPage;
        if (totalPages == 0) totalPages = 1;
        if (page > totalPages) page = totalPages;
        if (page < 1) page = 1;

        int startIndex = (page - 1) * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, totalItems);
        List<String> pageMaterials = materials.subList(startIndex, endIndex);

        double balance;
        try {
            balance = databaseManager.getEMCBalance(player.getUniqueId());
        } catch (SQLException | ClassNotFoundException e) {
            plugin.getLogger().severe("Error getting balance: " + e.getMessage());
            balance = 0;
        }

        Map<String, String> ph = pagePlaceholders(page, totalPages, totalItems, search);
        ph.put("%balance%", String.format("%.2f", balance));

        MenuHolder holder = new MenuHolder("exchange", page, search, layout.aSlots, layout);
        Inventory inv = Bukkit.createInventory(holder, layout.size,
                ColorUtil.translateColorCodes(applyPlaceholders(layout.title, ph)));
        holder.setInventory(inv);
        fillStaticButtons(inv, layout, ph, page, totalPages, totalItems);

        ButtonDef template = layout.buttons.get('A');
        for (int i = 0; i < pageMaterials.size(); i++) {
            String materialName = pageMaterials.get(i);
            ItemStack icon = buildItemIcon(materialName, template, true, true, 0, 0);
            if (icon != null) {
                inv.setItem(layout.aSlots.get(i), icon);
            }
        }
        player.openInventory(inv);
    }

    /** 按钮的主左键动作：triggers.left 优先，action 快捷方式兜底 */
    private String primaryLeftAction(ButtonDef def) {
        List<String> left = def.triggers.get("left");
        if (left != null && !left.isEmpty()) return left.get(0);
        return def.action;
    }

    /**
     * 逐槽填充：shape 字符在 items 中有定义且有图标才放置；
     * 未定义字符 / type: AIR / 首页的上一页 / 末页的下一页 = 空槽。
     */
    private void fillStaticButtons(Inventory inv, MenuLayout layout,
                                   Map<String, String> ph, int page, int totalPages, int totalItems) {
        boolean isSell = "sell".equals(layout.name);
        for (int row = 0; row < layout.shape.size(); row++) {
            String line = layout.shape.get(row);
            for (int col = 0; col < 9 && col < line.length(); col++) {
                int slot = row * 9 + col;
                if (isSell && line.charAt(col) == 'A') continue;
                ButtonDef def = layout.buttons.get(line.charAt(col));
                if (def == null || def.template == null) continue;
                String primary = primaryLeftAction(def);
                if ("prev_page".equals(primary) && page <= 1) continue;
                if ("next_page".equals(primary) && page >= totalPages) continue;
                inv.setItem(slot, buildActionButton(def, ph));
            }
        }
    }

    private ItemStack buildActionButton(ButtonDef def, Map<String, String> ph) {
        ItemStack item = def.template.clone();
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (def.name != null) {
                meta.setDisplayName(ColorUtil.translateColorCodes(applyPlaceholders(def.name, ph)));
            }
            List<String> loreTemplate = def.lore;
            if (!loreTemplate.isEmpty()) {
                List<String> lore = new ArrayList<>(loreTemplate.size());
                for (String line : loreTemplate) {
                    lore.add(ColorUtil.translateColorCodes(applyPlaceholders(line, ph)));
                }
                meta.setLore(lore);
            }
            if (def.action != null) {
                meta.getPersistentDataContainer().set(keyAction, PersistentDataType.STRING, def.action);
            }
            if (def.rightAction != null) {
                meta.getPersistentDataContainer().set(keyRightAction, PersistentDataType.STRING, def.rightAction);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /** 构建物品展示图标（基础图标缓存 + 按进度替换占位符，文本来自菜单文件模板） */
    private ItemStack buildItemIcon(String materialName, ButtonDef template, boolean isExchange,
                                    boolean unlocked, int mineProgress, int sellProgress) {
        ItemStack base = baseIconCache.get(materialName);
        if (base == null) {
            if (CeItemUtil.isCeKey(materialName)) {
                base = CeItemUtil.buildCeItem(CeItemUtil.extractCeId(materialName));
            } else {
                try {
                    base = new ItemStack(Material.valueOf(materialName));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Invalid material: " + materialName);
                    return null;
                }
            }
            if (base == null) return null;
            baseIconCache.put(materialName, base.clone());
        }
        ItemStack item = base.clone();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        String displayName = configManager.getItemName(materialName);
        double emc = configManager.getItemEMC(materialName);
        int requiredMine = configManager.getRequiredMine(materialName);
        int requiredSell = configManager.getRequiredSell(materialName);

        Map<String, String> ph = new HashMap<>();
        ph.put("%item_name%", displayName);
        ph.put("%item_id%", CeItemUtil.isCeKey(materialName) ? CeItemUtil.extractCeId(materialName) : materialName);
        ph.put("%emc%", formatEmc(emc));
        org.bukkit.configuration.file.YamlConfiguration lang = configManager.getLang();
        String noneText = lang.getString("gui-misc.none", "—");
        ph.put("%mine_progress%", requiredMine > 0 ? mineProgress + "/" + requiredMine : noneText);
        ph.put("%sell_progress%", requiredSell > 0 ? sellProgress + "/" + requiredSell : noneText);
        ph.put("%unlock_status%", unlocked ? lang.getString("gui-misc.unlocked", "&a已解锁")
                : lang.getString("gui-misc.locked", "&c未解锁"));

        if (template != null && template.name != null) {
            meta.setDisplayName(ColorUtil.translateColorCodes(applyPlaceholders(template.name, ph)));
        } else {
            meta.setDisplayName(ColorUtil.translateColorCodes((unlocked ? "&a" : "&c") + displayName));
        }
        List<String> loreTemplate = template != null ? template.lore : java.util.Collections.emptyList();
        List<String> lore;
        if (!loreTemplate.isEmpty()) {
            lore = new ArrayList<>(loreTemplate.size());
            for (String line : loreTemplate) {
                lore.add(ColorUtil.translateColorCodes(applyPlaceholders(line, ph)));
            }
        } else {
            lore = new ArrayList<>();
            lore.add(ColorUtil.translateColorCodes("&7EMC: &6" + formatEmc(emc)));
            if (!isExchange) {
                lore.add(ColorUtil.translateColorCodes(ph.get("%unlock_status%")));
            }
        }
        meta.setLore(lore);
        // 浏览/兑换菜单的物品均可购买，写入物品键供点击识别
        meta.getPersistentDataContainer().set(keyItem, PersistentDataType.STRING, materialName);
        item.setItemMeta(meta);
        return item;
    }

    // ══════════════════════════ 搜索 ══════════════════════════

    private List<String> applySearch(List<String> materials, String search) {
        if (search == null || search.isBlank()) return materials;
        String query = search.toLowerCase(Locale.ROOT);
        List<String> filtered = new ArrayList<>();
        for (String materialName : materials) {
            if (matchesSearch(materialName, query)) {
                filtered.add(materialName);
            }
        }
        return filtered;
    }

    /** 匹配：中文名 / 物品ID / CE物品ID / CE运行时显示名 */
    private boolean matchesSearch(String materialName, String lowerQuery) {
        if (configManager.getSearchText(materialName).contains(lowerQuery)) {
            return true;
        }
        String ceId = CeItemUtil.extractCeId(materialName);
        if (ceId != null) {
            String display = CeItemUtil.getCeDisplayName(ceId);
            if (display != null) {
                String stripped = ChatColor.stripColor(display);
                if (stripped != null && stripped.toLowerCase(Locale.ROOT).contains(lowerQuery)) {
                    return true;
                }
            }
        }
        return false;
    }

    public void startSearch(Player player, String menuType) {
        pendingSearchType.put(player.getUniqueId(), menuType);
        player.closeInventory();
        org.bukkit.configuration.file.YamlConfiguration lang = configManager.getLang();
        String prompt = lang.getString("search.prompt", "&e请在聊天栏输入搜索关键词：");
        player.sendMessage(ColorUtil.translateColorCodes(lang.getString("prefix", "") + prompt));
    }

    public void clearSearch(Player player, String menuType) {
        UUID uuid = player.getUniqueId();
        if ("exchange".equals(menuType)) {
            exchangeSearches.remove(uuid);
            openExchangeMenu(player, 1);
        } else {
            browseSearches.remove(uuid);
            openBrowseMenu(player, 1);
        }
        org.bukkit.configuration.file.YamlConfiguration lang = configManager.getLang();
        String msg = lang.getString("search.cleared", "&a搜索已清除。");
        player.sendMessage(ColorUtil.translateColorCodes(lang.getString("prefix", "") + msg));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String pending = pendingSearchType.get(player.getUniqueId());
        if (pending == null) return;

        event.setCancelled(true);
        String message = event.getMessage().trim();

        // 回到玩家实体线程处理，兼容 Folia
        schedulerUtil.runAtEntity(player, () -> handleSearchInput(player, pending, message), null);
    }

    private void handleSearchInput(Player player, String menuType, String message) {
        pendingSearchType.remove(player.getUniqueId());
        org.bukkit.configuration.file.YamlConfiguration lang = configManager.getLang();
        String prefix = lang.getString("prefix", "");

        if (message.equalsIgnoreCase("cancel") || message.equalsIgnoreCase("取消")) {
            player.sendMessage(ColorUtil.translateColorCodes(prefix + lang.getString("search.cancelled", "&c已取消搜索。")));
            return;
        }
        if (message.isEmpty()) {
            pendingSearchType.put(player.getUniqueId(), menuType);
            player.sendMessage(ColorUtil.translateColorCodes(prefix + lang.getString("search.prompt")));
            return;
        }
        if (message.length() > 32) {
            message = message.substring(0, 32);
        }
        // 安全过滤：去除颜色代码前缀字符，防止向菜单文本注入颜色
        message = message.replace("&", "").replace("§", "").trim();
        if (message.isEmpty()) {
            player.sendMessage(ColorUtil.translateColorCodes(prefix + lang.getString("search.prompt")));
            return;
        }

        UUID uuid = player.getUniqueId();
        List<String> source = configManager.getItemMaterialNames();
        List<String> visible;
        if ("exchange".equals(menuType)) {
            try {
                Map<String, Boolean> unlocked = databaseManager.getUnlockedStatuses(uuid, source);
                List<String> unlockedList = new ArrayList<>();
                for (String m : source) {
                    if (unlocked.getOrDefault(m, false)) unlockedList.add(m);
                }
                visible = unlockedList;
            } catch (SQLException | ClassNotFoundException e) {
                visible = source;
            }
        } else {
            visible = source;
        }
        int matches = applySearch(visible, message).size();

        if (matches == 0) {
            String msg = lang.getString("search.no-result", "&c没有匹配的物品。")
                    .replace("{keyword}", message);
            player.sendMessage(ColorUtil.translateColorCodes(prefix + msg));
            return;
        }

        if ("exchange".equals(menuType)) {
            exchangeSearches.put(uuid, message);
        } else {
            browseSearches.put(uuid, message);
        }
        String applied = lang.getString("search.applied", "&a搜索已应用：{keyword}")
                .replace("{keyword}", message);
        player.sendMessage(ColorUtil.translateColorCodes(prefix + applied));

        if ("exchange".equals(menuType)) {
            openExchangeMenu(player, 1);
        } else {
            openBrowseMenu(player, 1);
        }
    }

    // ══════════════════════════ 点击处理 ══════════════════════════

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MenuHolder holder)) return;

        Inventory clickedInv = event.getClickedInventory();
        boolean inTop = clickedInv != null && clickedInv == top;

        // 点击的是玩家自己的背包：出售菜单放行（拿取/放入物品），其他菜单一律取消
        if (!inTop) {
            if (!"sell".equals(holder.menuType)) {
                event.setCancelled(true);
            }
            return;
        }

        int slot = event.getRawSlot();

        // 出售菜单：A 槽允许自由放入/取出
        if ("sell".equals(holder.menuType)) {
            if (holder.layoutASlots().contains(slot)) return;
            event.setCancelled(true);
            schedulerUtil.runAtEntity(player, () -> handleSellClick(player), null);
            return;
        }

        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        String clickKind = switch (event.getClick()) {
            case RIGHT -> "right";
            case SHIFT_RIGHT -> "shift_right";
            case SHIFT_LEFT -> "shift_left";
            default -> "left";
        };

        // 动作优先从布局配置按槽位字符解析；triggers 优先于 action 快捷方式
        String itemKey = null;
        if (clicked.hasItemMeta() && clicked.getItemMeta() != null) {
            itemKey = clicked.getItemMeta().getPersistentDataContainer()
                    .get(keyItem, PersistentDataType.STRING);
        }
        List<String> actions = new ArrayList<>();
        MenuLayout layout = holder.layout();
        if (layout != null && slot >= 0 && slot < layout.size) {
            char ch = layout.shape.get(slot / 9).charAt(slot % 9);
            ButtonDef def = layout.buttons.get(ch);
            if (def != null) {
                List<String> triggered = def.triggers.get(clickKind);
                if (triggered == null && clickKind.startsWith("shift_")) {
                    triggered = def.triggers.get(clickKind.substring("shift_".length()));
                }
                if (triggered != null) {
                    actions.addAll(triggered);
                } else {
                    String single = clickKind.endsWith("right") && def.rightAction != null
                            ? def.rightAction : def.action;
                    if (single != null) actions.add(single);
                }
            }
        }
        // 兜底：物品 PDC，供兑换物品等动态图标使用
        if (actions.isEmpty() && clicked.hasItemMeta() && clicked.getItemMeta() != null) {
            var pdc = clicked.getItemMeta().getPersistentDataContainer();
            String pdcAction = pdc.get(keyAction, PersistentDataType.STRING);
            if (pdcAction != null) actions.add(pdcAction);
            itemKey = pdc.get(keyItem, PersistentDataType.STRING);
        }
        if (actions.isEmpty() && itemKey == null) return;

        // 全局防连点：同一玩家任意菜单按钮 CD 内只响应一次（可配置）
        long cooldownMs = (long) (Math.max(0, configManager.getConfig().getDouble("gui.button-click-cooldown-seconds", 0.15)) * 1000);
        if (cooldownMs > 0) {
            long now = System.currentTimeMillis();
            Long last = lastButtonClickMillis.put(player.getUniqueId(), now);
            if (last != null && now - last < cooldownMs) return;
        }

        final List<String> finalActions = actions;
        final String finalItemKey = itemKey;
        final String menuType = holder.menuType();
        final int page = holder.page();
        // 延迟到下一 tick 执行（避免在点击事件内关闭/打开界面的边界问题）
        schedulerUtil.runAtEntity(player, () -> {
            runTriggerList(player, finalActions, finalItemKey, menuType, page, 0);
        }, null);
    }

    /** ALwarp 风格动作列表执行：支持 delay:Nt/Ns/Nms、close、sound:名-音量-音调 */
    private void runTriggerList(Player player, List<String> actions, String itemKey, String menuType, int page, int startIndex) {
        if (actions == null) return;
        for (int i = startIndex; i < actions.size(); i++) {
            String raw = actions.get(i) == null ? "" : actions.get(i).trim();
            if (raw.isEmpty()) continue;

            // delay: 2t / 1s / 500ms（无单位默认秒）
            if (raw.toLowerCase(java.util.Locale.ROOT).startsWith("delay:")) {
                long ticks = parseDelayTicks(raw.substring(6).trim());
                if (ticks > 0) {
                    int next = i + 1;
                    schedulerUtil.runAtEntityLater(player,
                            () -> runTriggerList(player, actions, itemKey, menuType, page, next), ticks);
                    return;
                }
                continue;
            }
            if (raw.equalsIgnoreCase("close")) {
                player.closeInventory();
                continue;
            }
            String lower = raw.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("sound:")) {
                playActionSound(player, raw.substring(6).trim());
                continue;
            }
            executeAction(player, raw, itemKey, menuType, page, false);
        }
    }

    private long parseDelayTicks(String spec) {
        try {
            spec = spec.trim().toLowerCase(java.util.Locale.ROOT);
            if (spec.endsWith("t")) return (long) Double.parseDouble(spec.substring(0, spec.length() - 1));
            if (spec.endsWith("ms")) return Math.round(Double.parseDouble(spec.substring(0, spec.length() - 2)) / 50.0);
            if (spec.endsWith("s")) return (long) (Double.parseDouble(spec.substring(0, spec.length() - 1)) * 20);
            return (long) (Double.parseDouble(spec) * 20);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void playActionSound(Player player, String spec) {
        try {
            String[] parts = spec.split("-");
            org.bukkit.Sound sound = org.bukkit.Sound.valueOf(parts[0].trim().toUpperCase(java.util.Locale.ROOT));
            float volume = parts.length > 1 ? Float.parseFloat(parts[1].trim()) : 1.0f;
            float pitch = parts.length > 2 ? Float.parseFloat(parts[2].trim()) : 1.0f;
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (Exception e) {
            plugin.getLogger().warning("Invalid sound spec: " + spec);
        }
    }

    private void executeAction(Player player, String action, String itemKey, String menuType, int page, boolean rightClick) {
        if (action != null) {
            switch (action) {
                case "open_sell" -> openSellMenu(player);
                case "open_exchange" -> openExchangeMenu(player);
                case "open_browse" -> openBrowseMenu(player);
                case "close" -> player.closeInventory();
                case "back" -> openMainMenu(player);
                case "prev_page" -> {
                    if (page > 1) {
                        if ("exchange".equals(menuType)) openExchangeMenu(player, page - 1);
                        else openBrowseMenu(player, page - 1);
                    }
                }
                case "next_page" -> {
                    if ("exchange".equals(menuType)) openExchangeMenu(player, page + 1);
                    else openBrowseMenu(player, page + 1);
                }
                case "search" -> startSearch(player, menuType);
                case "clear_search" -> clearSearch(player, menuType);
                case "sell" -> handleSellClick(player);
                case "exchange_buy_1" -> handleExchangeBuy(player, itemKey, false);
                case "exchange_buy_64" -> handleExchangeBuy(player, itemKey, true);
                default -> handleCustomAction(player, action);
            }
        }
    }

    /**
     * 自定义按钮动作（menus/<语言>/<菜单>.yml 中可用）：
     *   open:<菜单名>            打开 menus 目录下的任意菜单（如 open:sell、open:my_help）
     *   command:<命令>           以玩家身份执行命令（不含开头的 /）
     *   console_command:<命令>   以控制台身份执行，支持 %player% 占位符
     *   message:<文本>           给玩家发送一条彩色消息
     */
    private void handleCustomAction(Player player, String action) {
        if (action.startsWith("open:")) {
            openMenuByName(player, action.substring("open:".length()).trim());
        } else if (action.startsWith("open_gui:")) {
            // ALwarp 兼容别名
            openMenuByName(player, action.substring("open_gui:".length()).trim());
        } else if (action.startsWith("command:")) {
            String cmd = action.substring("command:".length()).trim();
            if (!cmd.isEmpty()) {
                player.closeInventory();
                player.performCommand(cmd);
            }
        } else if (action.startsWith("console_command:")) {
            String cmd = action.substring("console_command:".length()).trim()
                    .replace("%player%", player.getName());
            if (!cmd.isEmpty()) {
                schedulerUtil.runTask(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
            }
        } else if (action.startsWith("message:")) {
            player.sendMessage(ColorUtil.translateColorCodes(action.substring("message:".length()).trim()));
        }
    }

    /** 按菜单名打开：四个内置菜单走专属渲染，其余走通用静态渲染 */
    public void openMenuByName(Player player, String menuName) {
        switch (menuName) {
            case "main" -> openMainMenu(player);
            case "sell" -> openSellMenu(player);
            case "exchange" -> openExchangeMenu(player);
            case "browse" -> openBrowseMenu(player);
            default -> openCustomMenu(player, menuName);
        }
    }

    /** 通用菜单渲染：任意自定义菜单文件（仅静态按钮，A 槽留空） */
    private void openCustomMenu(Player player, String menuName) {
        ensureLayouts();
        MenuLayout layout = layouts.get(menuName);
        if (layout == null) {
            player.sendMessage(ColorUtil.translateColorCodes(
                    configManager.getLang().getString("prefix", "") + "&c菜单不存在: " + menuName));
            return;
        }
        clearPlayerCache(player.getUniqueId());

        double balance = getCachedBalance(player.getUniqueId());
        Map<String, String> ph = new HashMap<>();
        ph.put("%balance%", String.format("%.2f", balance));
        ph.put("%daily_limit%", getDailyLimitDisplayText(player));
        ph.put("%tax%", String.format("%.1f", configManager.getTaxRateForPlayer(player) * 100));
        ph.put("%player%", player.getName());

        MenuHolder holder = new MenuHolder(menuName, 1, null, layout.aSlots, layout);
        Inventory inv = Bukkit.createInventory(holder, layout.size, ColorUtil.translateColorCodes(layout.title));
        holder.setInventory(inv);
        fillStaticButtons(inv, layout, ph, 1, 1, 0);
        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MenuHolder holder)) return;
        if (!"sell".equals(holder.menuType)) {
            event.setCancelled(true);
            return;
        }
        // 出售菜单仅允许拖入 A 槽
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < top.getSize() && !holder.layoutASlots().contains(rawSlot)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastButtonClickMillis.remove(uuid);
        browseSearches.remove(uuid);
        exchangeSearches.remove(uuid);
        pendingSearchType.remove(uuid);
        clearPlayerCache(uuid);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof MenuHolder holder)) return;

        if ("sell".equals(holder.menuType)) {
            Inventory inventory = event.getInventory();
            for (int slot : holder.layoutASlots()) {
                ItemStack item = inventory.getItem(slot);
                if (item != null && item.getType() != Material.AIR) {
                    returnItemToPlayer(player, item);
                    inventory.setItem(slot, null);
                }
            }
        }
    }

    // ══════════════════════════ 出售逻辑 ══════════════════════════

    private void handleSellClick(Player player) {
        MenuLayout layout = layouts.get("sell");
        if (layout == null) return;
        List<Integer> aSlots = layout.aSlots;

        final double[] totalEMC = {0};
        final List<ItemStack> returnItems = new ArrayList<>();
        final List<ItemStack> itemsToRemove = new ArrayList<>();
        final List<String> materialNames = new ArrayList<>();
        final List<Integer> amounts = new ArrayList<>();
        final List<Double> emcPerItemList = new ArrayList<>();

        Inventory inv = player.getOpenInventory().getTopInventory();
        for (int slot : aSlots) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType() == Material.AIR) continue;
            // CE 物品映射为 "ce:ns:id"，原版物品映射为材质名
            String saleKey = CeItemUtil.resolveSaleKey(item);
            if (configManager.hasItem(saleKey)) {
                double emcPerItem = configManager.getItemEMC(saleKey);
                int amount = item.getAmount();
                totalEMC[0] += emcPerItem * amount;
                materialNames.add(saleKey);
                amounts.add(amount);
                emcPerItemList.add(emcPerItem);
                itemsToRemove.add(item.clone());
            } else {
                returnItems.add(item.clone());
            }
            inv.setItem(slot, null);
        }

        if (totalEMC[0] > 0) {
            final double finalTotalEMC = totalEMC[0];
            final List<String> finalMaterialNames = materialNames;
            final List<Integer> finalAmounts = amounts;
            final List<Double> finalEmcPerItemList = emcPerItemList;

            schedulerUtil.runAsync(() -> {
                double taxRate = configManager.getTaxRateForPlayer(player);
                String consumedDailyDate = null;
                double consumedDailyEMC = 0;
                boolean balanceCredited = false;

                try {
                    double allowedEMC = finalTotalEMC;
                    List<Integer> actualAmounts = new ArrayList<>(finalAmounts);
                    List<ItemStack> limitReturnItems = new ArrayList<>();
                    if (configManager.isDailyLimitEnabled()) {
                        double dailyLimit = configManager.getDailyLimitForPlayer(player);
                        if (dailyLimit != -1) {
                            String effectiveDate = databaseManager.getEffectiveDate();
                            consumedDailyDate = effectiveDate;
                            double remaining = databaseManager.consumeDailyEMCLimit(player.getUniqueId(), effectiveDate, finalTotalEMC, dailyLimit);
                            consumedDailyEMC = remaining;

                            if (remaining <= 0) {
                                allowedEMC = 0;
                            } else if (remaining < finalTotalEMC) {
                                allowedEMC = 0;
                                actualAmounts.clear();
                                double remainingLimit = remaining;

                                for (int i = 0; i < finalMaterialNames.size(); i++) {
                                    double itemTotalEMC = finalEmcPerItemList.get(i) * finalAmounts.get(i);
                                    if (itemTotalEMC <= remainingLimit) {
                                        allowedEMC += itemTotalEMC;
                                        actualAmounts.add(finalAmounts.get(i));
                                        remainingLimit -= itemTotalEMC;
                                    } else if (remainingLimit > 0) {
                                        int sellableAmount = (int) (remainingLimit / finalEmcPerItemList.get(i));
                                        if (sellableAmount > 0) {
                                            double sellableEMC = sellableAmount * finalEmcPerItemList.get(i);
                                            allowedEMC += sellableEMC;
                                            actualAmounts.add(sellableAmount);
                                            remainingLimit -= sellableEMC;

                                            final int unsoldAmount = finalAmounts.get(i) - sellableAmount;
                                            if (unsoldAmount > 0) {
                                                ItemStack unsoldItem = itemsToRemove.get(i).clone();
                                                unsoldItem.setAmount(unsoldAmount);
                                                limitReturnItems.add(unsoldItem);
                                            }
                                        } else {
                                            actualAmounts.add(0);
                                            limitReturnItems.add(itemsToRemove.get(i).clone());
                                        }
                                    } else {
                                        actualAmounts.add(0);
                                        limitReturnItems.add(itemsToRemove.get(i).clone());
                                    }
                                }
                            }
                        }
                    }

                    if (consumedDailyDate != null && consumedDailyEMC > allowedEMC) {
                        databaseManager.addDailyEMCEarned(player.getUniqueId(), consumedDailyDate, -(consumedDailyEMC - allowedEMC));
                        consumedDailyEMC = allowedEMC;
                    }

                    if (allowedEMC <= 0) {
                        final List<ItemStack> itemsToReturn = limitReturnItems.isEmpty()
                                ? cloneItemList(itemsToRemove)
                                : cloneItemList(limitReturnItems);
                        schedulerUtil.runTask(() -> {
                            String message = configManager.getLang().getString("prefix") +
                                    configManager.getLang().getString("daily_limit.reached");
                            player.sendMessage(ColorUtil.translateColorCodes(message));
                            returnItemsToPlayer(player, itemsToReturn);
                        });
                        return;
                    }

                    double tax = allowedEMC * taxRate;
                    final double netEMC = allowedEMC - tax;

                    databaseManager.addEMCBalance(player.getUniqueId(), netEMC);
                    balanceCredited = true;

                    try {
                        for (int i = 0; i < finalMaterialNames.size(); i++) {
                            String materialName = finalMaterialNames.get(i);
                            int amount = actualAmounts.get(i);

                            if (amount <= 0) continue;

                            if (!databaseManager.isUnlocked(player.getUniqueId(), materialName)) {
                                int requiredSell = configManager.getRequiredSell(materialName);
                                if (requiredSell > 0) {
                                    int currentProgress = databaseManager.getSellProgress(player.getUniqueId(), materialName);
                                    int addAmount = Math.min(amount, requiredSell - currentProgress);
                                    if (addAmount > 0) {
                                        databaseManager.addSellProgress(player.getUniqueId(), materialName, addAmount);
                                    }
                                }
                                checkUnlock(player, materialName);
                            }
                        }
                    } catch (SQLException | ClassNotFoundException progressException) {
                        plugin.getLogger().severe("Error updating sell progress: " + progressException.getMessage());
                    }

                    final List<ItemStack> finalLimitReturnItems = cloneItemList(limitReturnItems);
                    schedulerUtil.runTask(() -> {
                        returnItemsToPlayer(player, finalLimitReturnItems);
                        String message = configManager.getLang().getString("prefix") +
                                configManager.getLang().getString("menu.sell.success").replace("{amount}", String.format("%.2f", netEMC));
                        player.sendMessage(ColorUtil.translateColorCodes(message));
                    });
                } catch (SQLException | ClassNotFoundException e) {
                    if (!balanceCredited && consumedDailyDate != null && consumedDailyEMC > 0) {
                        try {
                            databaseManager.addDailyEMCEarned(player.getUniqueId(), consumedDailyDate, -consumedDailyEMC);
                        } catch (SQLException | ClassNotFoundException refundException) {
                            plugin.getLogger().severe("Error refunding daily EMC limit: " + refundException.getMessage());
                        }
                    }
                    final boolean shouldReturnItems = !balanceCredited;
                    schedulerUtil.runTask(() -> {
                        player.sendMessage(ColorUtil.translateColorCodes(configManager.getLang().getString("prefix") + configManager.getLang().getString("menu.sell.failed")));
                        if (shouldReturnItems) {
                            for (ItemStack item : itemsToRemove) {
                                returnItemToPlayer(player, item.clone());
                            }
                        }
                    });
                    plugin.getLogger().severe("Error handling sell: " + e.getMessage());
                }
            });
        }

        for (ItemStack item : returnItems) {
            returnItemToPlayer(player, item.clone());
        }
        schedulerUtil.runTask(() -> openSellMenu(player));
    }

    // ══════════════════════════ 兑换购买 ══════════════════════════

    private void handleExchangeBuy(Player player, String saleKey, boolean isRightClick) {
        if (saleKey == null || saleKey.isEmpty()) return;
        try {
            // 浏览菜单也能购买：未解锁的物品一律拦截
            if (!databaseManager.isUnlocked(player.getUniqueId(), saleKey)) {
                player.sendMessage(ColorUtil.translateColorCodes(configManager.getLang().getString("prefix") +
                        configManager.getLang().getString("menu.exchange.item-locked", "&c该物品尚未解锁！")));
                return;
            }
            double emcPerItem = configManager.getItemEMC(saleKey);
            if (emcPerItem <= 0) return;
            int amount = isRightClick ? 64 : 1;

            ItemStack itemToAdd;
            if (CeItemUtil.isCeKey(saleKey)) {
                itemToAdd = CeItemUtil.buildCeItem(CeItemUtil.extractCeId(saleKey));
                if (itemToAdd == null) {
                    player.sendMessage(ColorUtil.translateColorCodes(configManager.getLang().getString("prefix") + "&cFailed to build this item (CraftEngine not loaded?)"));
                    return;
                }
                itemToAdd.setAmount(amount);
            } else {
                Material material = Material.valueOf(saleKey);
                itemToAdd = new ItemStack(material, amount);
            }

            int maxStackSize = itemToAdd.getMaxStackSize();
            int emptySlots = 0;
            int existingPartialSlotSpace = 0;

            ItemStack[] contents = player.getInventory().getContents();
            for (int i = 0; i < 36; i++) {
                ItemStack invItem = contents[i];
                if (invItem == null) {
                    emptySlots++;
                } else if (invItem.isSimilar(itemToAdd)) {
                    int currentAmount = invItem.getAmount();
                    if (currentAmount < maxStackSize) {
                        existingPartialSlotSpace += (maxStackSize - currentAmount);
                    }
                }
            }

            int canAdd = (emptySlots * maxStackSize) + existingPartialSlotSpace;

            if (configManager.isDebugEnabled()) {
                plugin.getLogger().info("[DEBUG] purchase - player: " + player.getName() + ", item: " + saleKey + ", req: " + amount + ", canAdd: " + canAdd);
            }

            if (canAdd <= 0) {
                player.sendMessage(ColorUtil.translateColorCodes(configManager.getLang().getString("prefix") + configManager.getLang().getString("menu.exchange.inventory-full")));
                return;
            }
            // 背包不足且未开启掉落：部分购买（买放得下的量）而非整笔拒绝
            boolean partial = false;
            if (!configManager.isDropOverflowItemsEnabled() && canAdd < amount) {
                amount = canAdd;
                partial = true;
                itemToAdd.setAmount(amount);
            }
            double totalEMC = emcPerItem * amount;

            if (!databaseManager.tryWithdrawEMC(player.getUniqueId(), totalEMC)) {
                player.sendMessage(ColorUtil.translateColorCodes(configManager.getLang().getString("prefix") + configManager.getLang().getString("command.insufficient-funds")));
                return;
            }
            clearPlayerCache(player.getUniqueId());

            Map<Integer, ItemStack> leftover = player.getInventory().addItem(itemToAdd);
            if (!leftover.isEmpty()) {
                int lost = 0;
                for (ItemStack leftoverItem : leftover.values()) {
                    lost += leftoverItem.getAmount();
                }
                if (configManager.isDropOverflowItemsEnabled()) {
                    for (ItemStack leftoverItem : leftover.values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), leftoverItem);
                    }
                    player.sendMessage(ColorUtil.translateColorCodes(configManager.getLang().getString("prefix") +
                            configManager.getLang().getString("menu.exchange.dropped", "&e背包已满，&6{amount} &e个物品掉落在你脚边")
                                    .replace("{amount}", String.valueOf(lost))));
                } else {
                    // 竞态导致放不下（理论上不应发生）：按未交付数量退还 EMC
                    double refund = lost * emcPerItem;
                    databaseManager.addEMCBalance(player.getUniqueId(), refund);
                    clearPlayerCache(player.getUniqueId());
                    player.sendMessage(ColorUtil.translateColorCodes(configManager.getLang().getString("prefix") +
                            configManager.getLang().getString("menu.exchange.refunded", "&c背包空间异常，&6{amount} &c个未能放入，已退还 &6{emc} EMC")
                                    .replace("{amount}", String.valueOf(lost))
                                    .replace("{emc}", String.format("%.2f", refund))));
                }
            }

            String messageKey = partial ? "menu.exchange.partial-success" : "menu.exchange.purchase-success";
            String defaultMessage = partial ? "&e背包空间不足，已购买 &6{count} &e个（消耗 &6{amount} EMC&e）"
                    : configManager.getLang().getString("menu.exchange.purchase-success");
            String message = configManager.getLang().getString("prefix") +
                    configManager.getLang().getString(messageKey, defaultMessage)
                            .replace("{amount}", String.format("%.2f", totalEMC))
                            .replace("{count}", String.valueOf(amount));
            player.sendMessage(ColorUtil.translateColorCodes(message));
        } catch (SQLException | ClassNotFoundException e) {
            plugin.getLogger().warning("Error processing purchase: " + e.getMessage());
        }
    }

    // ══════════════════════════ 通用工具 ══════════════════════════

    private Map<String, String> pagePlaceholders(int page, int totalPages, int totalItems, String search) {
        Map<String, String> ph = new HashMap<>();
        ph.put("%current_page%", String.valueOf(page));
        ph.put("%total_pages%", String.valueOf(totalPages));
        ph.put("%total_items%", String.valueOf(totalItems));
        ph.put("%current_search%", search != null ? search
                : configManager.getLang().getString("gui-misc.no-search", "无"));
        ph.put("%page_display%", "(" + page + "/" + totalPages + ")");
        return ph;
    }

    private String applyPlaceholders(String text, Map<String, String> ph) {
        if (text == null) return null;
        for (Map.Entry<String, String> entry : ph.entrySet()) {
            if (text.contains(entry.getKey())) {
                text = text.replace(entry.getKey(), entry.getValue());
            }
        }
        return text;
    }

    private String formatEmc(double emc) {
        if (emc == Math.floor(emc)) {
            return String.valueOf((long) emc);
        }
        return String.format("%.2f", emc);
    }

    private List<ItemStack> cloneItemList(List<ItemStack> items) {
        List<ItemStack> clones = new ArrayList<>(items.size());
        for (ItemStack item : items) {
            if (item != null) {
                clones.add(item.clone());
            }
        }
        return clones;
    }

    private void returnItemsToPlayer(Player player, List<ItemStack> items) {
        for (ItemStack item : items) {
            returnItemToPlayer(player, item);
        }
    }

    private void returnItemToPlayer(Player player, ItemStack item) {
        if (item == null || item.getType() == Material.AIR || item.getAmount() <= 0) {
            return;
        }
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack leftoverItem : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftoverItem);
        }
    }

    private String getDailyLimitDisplayText(Player player) {
        if (!configManager.isDailyLimitEnabled()) {
            return configManager.getLang().getString("daily_limit.unlimited", "&a无限");
        }
        double dailyLimit = configManager.getDailyLimitForPlayer(player);
        if (dailyLimit == -1) {
            return configManager.getLang().getString("daily_limit.unlimited", "&a无限");
        }
        try {
            String effectiveDate = databaseManager.getEffectiveDate();
            double earnedToday = databaseManager.getDailyEMCEarned(player.getUniqueId(), effectiveDate);
            double remaining = Math.max(0, dailyLimit - earnedToday);
            return "&6" + String.format("%.0f", remaining);
        } catch (Exception e) {
            return configManager.getLang().getString("daily_limit.unlimited", "&a无限");
        }
    }

    // ══════════════════════════ 缓存 ══════════════════════════

    private void startCacheCleanupTask() {
        schedulerUtil.runTimerAsync(() -> {
            long currentTime = System.currentTimeMillis();
            long thirtyMinutesAgo = currentTime - (30 * 60 * 1000);
            List<UUID> toRemove = new ArrayList<>();
            for (Map.Entry<UUID, Long> entry : playerCacheTimestamps.entrySet()) {
                if (entry.getValue() < thirtyMinutesAgo) {
                    toRemove.add(entry.getKey());
                }
            }
            for (UUID playerUUID : toRemove) {
                clearPlayerCache(playerUUID);
                playerCacheTimestamps.remove(playerUUID);
            }
        }, 0L, 5 * 60 * 20L);
    }

    private double getCachedBalance(UUID playerUUID) {
        if (playerBalanceCache.containsKey(playerUUID)) {
            playerCacheTimestamps.put(playerUUID, System.currentTimeMillis());
            return playerBalanceCache.get(playerUUID);
        }
        try {
            double balance = databaseManager.getEMCBalance(playerUUID);
            playerBalanceCache.put(playerUUID, balance);
            playerCacheTimestamps.put(playerUUID, System.currentTimeMillis());
            return balance;
        } catch (SQLException | ClassNotFoundException e) {
            plugin.getLogger().severe("Error getting balance: " + e.getMessage());
            return 0.0;
        }
    }

    private Map<String, Boolean> getCachedUnlockedStatuses(UUID playerUUID, List<String> materials) {
        if (playerUnlockedCache.containsKey(playerUUID)) {
            Map<String, Boolean> cachedStatuses = playerUnlockedCache.get(playerUUID);
            boolean allCached = true;
            for (String material : materials) {
                if (!cachedStatuses.containsKey(material)) {
                    allCached = false;
                    break;
                }
            }
            if (allCached) {
                playerCacheTimestamps.put(playerUUID, System.currentTimeMillis());
                Map<String, Boolean> result = new HashMap<>();
                for (String material : materials) {
                    result.put(material, cachedStatuses.get(material));
                }
                return result;
            }
        }
        try {
            Map<String, Boolean> statuses = databaseManager.getUnlockedStatuses(playerUUID, materials);
            playerUnlockedCache.computeIfAbsent(playerUUID, k -> new HashMap<>()).putAll(statuses);
            playerCacheTimestamps.put(playerUUID, System.currentTimeMillis());
            return statuses;
        } catch (SQLException | ClassNotFoundException e) {
            plugin.getLogger().severe("Error getting unlocked statuses: " + e.getMessage());
            Map<String, Boolean> defaultStatuses = new HashMap<>();
            for (String material : materials) {
                defaultStatuses.put(material, false);
            }
            return defaultStatuses;
        }
    }

    private Map<String, Integer> getCachedMineProgresses(UUID playerUUID, List<String> materials) {
        if (playerMineProgressCache.containsKey(playerUUID)) {
            Map<String, Integer> cachedProgresses = playerMineProgressCache.get(playerUUID);
            boolean allCached = true;
            for (String material : materials) {
                if (!cachedProgresses.containsKey(material)) {
                    allCached = false;
                    break;
                }
            }
            if (allCached) {
                Map<String, Integer> result = new HashMap<>();
                for (String material : materials) {
                    result.put(material, cachedProgresses.get(material));
                }
                return result;
            }
        }
        try {
            Map<String, Integer> progresses = databaseManager.getMineProgresses(playerUUID, materials);
            playerMineProgressCache.computeIfAbsent(playerUUID, k -> new HashMap<>()).putAll(progresses);
            return progresses;
        } catch (SQLException | ClassNotFoundException e) {
            plugin.getLogger().severe("Error getting mine progresses: " + e.getMessage());
            Map<String, Integer> defaultProgresses = new HashMap<>();
            for (String material : materials) {
                defaultProgresses.put(material, 0);
            }
            return defaultProgresses;
        }
    }

    private Map<String, Integer> getCachedSellProgresses(UUID playerUUID, List<String> materials) {
        if (playerSellProgressCache.containsKey(playerUUID)) {
            Map<String, Integer> cachedProgresses = playerSellProgressCache.get(playerUUID);
            boolean allCached = true;
            for (String material : materials) {
                if (!cachedProgresses.containsKey(material)) {
                    allCached = false;
                    break;
                }
            }
            if (allCached) {
                Map<String, Integer> result = new HashMap<>();
                for (String material : materials) {
                    result.put(material, cachedProgresses.get(material));
                }
                return result;
            }
        }
        try {
            Map<String, Integer> progresses = databaseManager.getSellProgresses(playerUUID, materials);
            playerSellProgressCache.computeIfAbsent(playerUUID, k -> new HashMap<>()).putAll(progresses);
            return progresses;
        } catch (SQLException | ClassNotFoundException e) {
            plugin.getLogger().severe("Error getting sell progresses: " + e.getMessage());
            Map<String, Integer> defaultProgresses = new HashMap<>();
            for (String material : materials) {
                defaultProgresses.put(material, 0);
            }
            return defaultProgresses;
        }
    }

    public void clearPlayerCache(UUID playerUUID) {
        playerBalanceCache.remove(playerUUID);
        playerUnlockedCache.remove(playerUUID);
        playerMineProgressCache.remove(playerUUID);
        playerSellProgressCache.remove(playerUUID);
        playerCacheTimestamps.remove(playerUUID);
    }

    /**
     * 跨服同步回调：清理该玩家缓存；若其正打开本插件菜单，回到其线程自动重绘。
     * 可从异步线程调用。
     */
    public void handleRemoteInvalidate(UUID uuid) {
        clearPlayerCache(uuid);
        org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(uuid);
        if (player == null) return;
        schedulerUtil.runAtEntity(player, () -> {
            if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder holder)) return;
            switch (holder.menuType()) {
                case "browse" -> openBrowseMenu(player, holder.page());
                case "exchange" -> openExchangeMenu(player, holder.page());
                case "sell" -> openSellMenu(player);
                case "main" -> openMainMenu(player);
                default -> { }
            }
        }, null);
    }

    public void clearAllCache() {
        playerBalanceCache.clear();
        playerUnlockedCache.clear();
        playerMineProgressCache.clear();
        playerSellProgressCache.clear();
        playerCacheTimestamps.clear();
        baseIconCache.clear();
        CeItemUtil.clearCache();
    }

    private void checkUnlock(Player player, String materialName) throws SQLException, ClassNotFoundException {
        int requiredMine = configManager.getRequiredMine(materialName);
        int requiredSell = configManager.getRequiredSell(materialName);

        int mineProgress = databaseManager.getMineProgress(player.getUniqueId(), materialName);
        int sellProgress = databaseManager.getSellProgress(player.getUniqueId(), materialName);

        boolean mineCondition = requiredMine == 0 || mineProgress >= requiredMine;
        boolean sellCondition = requiredSell == 0 || sellProgress >= requiredSell;

        if (mineCondition && sellCondition && !databaseManager.isUnlocked(player.getUniqueId(), materialName)) {
            databaseManager.unlockItem(player.getUniqueId(), materialName);
            clearPlayerCache(player.getUniqueId());
            String itemDisplayName = configManager.getItemName(materialName);
            final String message = configManager.getLang().getString("prefix") +
                    configManager.getLang().getString("mine.unlocked").replace("{item}", itemDisplayName);
            schedulerUtil.runTask(() -> player.sendMessage(ColorUtil.translateColorCodes(message)));
        }
    }

    // ══════════════════════════ 数据结构 ══════════════════════════

    /** 菜单持有者：点击/关闭事件由此识别本插件菜单 */
    public static class MenuHolder implements InventoryHolder {
        private final String menuType;
        private final int page;
        private final String search;
        private final List<Integer> aSlots;
        private final MenuLayout layout;
        private Inventory inventory;

        public MenuHolder(String menuType, int page, String search, List<Integer> aSlots) {
            this(menuType, page, search, aSlots, null);
        }

        public MenuHolder(String menuType, int page, String search, List<Integer> aSlots, MenuLayout layout) {
            this.menuType = menuType;
            this.page = page;
            this.search = search;
            this.aSlots = aSlots;
            this.layout = layout;
        }

        public MenuLayout layout() {
            return layout;
        }

        void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        public String menuType() {
            return menuType;
        }

        public int page() {
            return page;
        }

        public String search() {
            return search;
        }

        public List<Integer> layoutASlots() {
            return aSlots;
        }
    }

    /** 菜单布局：shape 解析结果 */
    static class MenuLayout {
        final String name;
        final List<String> shape;
        final int size;
        final String title;
        final Map<Character, ButtonDef> buttons = new LinkedHashMap<>();
        final List<Integer> aSlots = new ArrayList<>();
        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);

        MenuLayout(String name, List<String> shape, String title) {
            this.name = name;
            this.shape = shape;
            this.size = shape.size() * 9;
            this.title = title != null ? title : "";
            for (int row = 0; row < shape.size(); row++) {
                String line = shape.get(row);
                for (int col = 0; col < 9 && col < line.length(); col++) {
                    if (line.charAt(col) == 'A') {
                        aSlots.add(row * 9 + col);
                    }
                }
            }
        }

        List<Integer> slotsOf(char ch) {
            List<Integer> slots = new ArrayList<>();
            for (int row = 0; row < shape.size(); row++) {
                String line = shape.get(row);
                for (int col = 0; col < 9 && col < line.length(); col++) {
                    if (line.charAt(col) == ch) {
                        slots.add(row * 9 + col);
                    }
                }
            }
            return slots;
        }
    }

    /** 按钮定义 */
    private static class ButtonDef {
        String name;
        List<String> lore = Collections.emptyList();
        String action;
        String rightAction;
        String pluginItem;
        ItemStack template;
        // ALwarp 风格触发器：left / right / shift_left / shift_right -> 动作列表
        Map<String, List<String>> triggers = Collections.emptyMap();
    }
}
