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
    private final SaleDelivery saleDelivery;

    private final NamespacedKey keyAction;
    private final NamespacedKey keyRightAction;
    private final NamespacedKey keyItem;

    // ── 菜单布局缓存 ──
    private volatile long parsedStamp = -1;
    private final Map<String, MenuLayout> layouts = new HashMap<>();
    private final Map<String, ItemStack> baseIconCache = new ConcurrentHashMap<>();

    // ── 按钮点击冷却（按玩家全局，防连点器；gui.button-click-cooldown-ms 可调） ──
    private final Map<UUID, Long> lastButtonClickMillis = new ConcurrentHashMap<>();
    private final Set<UUID> pendingSales = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Object> menuLoads = new ConcurrentHashMap<>();

    // ── 玩家运行状态 ──
    private final Map<UUID, String> browseSearches = new ConcurrentHashMap<>();
    private final Map<UUID, String> exchangeSearches = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingSearchType = new ConcurrentHashMap<>();

    // ── 玩家数据缓存 ──
    private final Map<UUID, PlayerMenuCache> playerMenuCache = new ConcurrentHashMap<>();

    public MenuManager(JavaPlugin plugin, ConfigManager configManager, DatabaseManager databaseManager, SaleDelivery saleDelivery) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.databaseManager = databaseManager;
        this.schedulerUtil = new SchedulerUtil(plugin);
        this.saleDelivery = saleDelivery;
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
        openStaticMenu(player, "main");
    }

    public void openSellMenu(Player player) {
        ensureLayouts();
        MenuLayout layout = layouts.get("sell");
        if (layout == null) {
            plugin.getLogger().severe("Menu 'sell' missing from menus.yml");
            return;
        }
        Inventory previous = player.getOpenInventory().getTopInventory();
        if (!layout.displaysBalance()) {
            menuLoads.remove(player.getUniqueId());
            showSellMenu(player, previous, layout, 0);
            return;
        }
        UUID uuid = player.getUniqueId();
        loadMenu(player, () -> {
            double balance = readSellBalance(uuid);
            return () -> showSellMenu(player, previous, layout, balance);
        });
    }

    private void showSellMenu(Player player, Inventory previous, MenuLayout layout, double balance) {
        if (previous.getHolder() instanceof MenuHolder existing && existing.layout() == layout) {
            renderSellButtons(player, previous, layout, balance);
            return;
        }
        MenuHolder holder = new MenuHolder("sell", 1, null, layout.aSlots, layout);
        Inventory inv = Bukkit.createInventory(holder, layout.size, ColorUtil.translateColorCodes(layout.title));
        holder.setInventory(inv);
        renderSellButtons(player, inv, layout, balance);
        player.openInventory(inv);
    }

    private void loadMenu(Player player, java.util.function.Supplier<Runnable> prepare) {
        UUID uuid = player.getUniqueId();
        Inventory previous = player.getOpenInventory().getTopInventory();
        long stamp = configManager.getConfigStamp();
        Object request = new Object();
        menuLoads.put(uuid, request);
        if (!schedulerUtil.runAsync(() -> {
            boolean scheduled = false;
            try {
                if (menuLoads.get(uuid) != request) return;
                Runnable render = prepare.get();
                if (menuLoads.get(uuid) != request) return;
                scheduled = schedulerUtil.runAtEntity(player, () -> {
                    if (menuLoads.remove(uuid, request) && player.isOnline()
                            && configManager.getConfigStamp() == stamp
                            && player.getOpenInventory().getTopInventory() == previous) render.run();
                }, () -> menuLoads.remove(uuid, request));
            } finally {
                if (!scheduled) menuLoads.remove(uuid, request);
            }
        })) menuLoads.remove(uuid, request);
    }

    private double readSellBalance(UUID uuid) {
        try {
            return databaseManager.getEMCBalance(uuid);
        } catch (SQLException | ClassNotFoundException e) {
            plugin.getLogger().severe("Error getting sell balance: " + e.getMessage());
            return 0;
        }
    }

    private void renderSellButtons(Player player, Inventory inv, MenuLayout layout, double balance) {
        Map<String, String> ph = new HashMap<>();
        ph.put("%balance%", String.format("%.2f", balance));
        ph.put("%tax%", String.format("%.1f", configManager.getTaxRateForPlayer(player) * 100));
        fillStaticButtons(inv, layout, ph, 1, 1, 0);
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
        UUID uuid = player.getUniqueId();
        String search = browseSearches.get(uuid);
        MenuPage menuPage = slicePage(layout, applySearch(configManager.getItemMaterialNames(), search), page);
        loadMenu(player, () -> {
            List<String> keys = menuPage.materials();
            Map<String, Boolean> unlocked = getCachedUnlockedStatuses(uuid, keys);
            Map<String, Integer> mined = getCachedMineProgresses(uuid, keys);
            Map<String, Integer> sold = getCachedSellProgresses(uuid, keys);
            return () -> renderItemPage(player, layout, search, menuPage, unlocked, mined, sold, false, 0);
        });
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
        UUID uuid = player.getUniqueId();
        String search = exchangeSearches.get(uuid);
        List<String> materials = configManager.getItemMaterialNames();
        List<String> matching = applySearch(materials, search);
        loadMenu(player, () -> {
            Map<String, Boolean> unlocked = getCachedUnlockedStatuses(uuid, materials);
            List<String> available = matching.stream().filter(key -> unlocked.getOrDefault(key, false)).toList();
            MenuPage menuPage = slicePage(layout, available, page);
            double balance = layout.displaysBalance() ? readSellBalance(uuid) : 0;
            return () -> renderItemPage(player, layout, search, menuPage, unlocked, Map.of(), Map.of(), true, balance);
        });
    }

    private MenuPage slicePage(MenuLayout layout, List<String> materials, int page) {
        int pageSize = Math.max(1, layout.aSlots.size());
        int pages = Math.max(1, (materials.size() + pageSize - 1) / pageSize);
        int current = Math.max(1, Math.min(page, pages));
        int start = (current - 1) * pageSize;
        return new MenuPage(current, pages, materials.size(),
                materials.subList(start, Math.min(start + layout.aSlots.size(), materials.size())));
    }

    private void renderItemPage(Player player, MenuLayout layout, String search, MenuPage menuPage,
                                Map<String, Boolean> unlocked, Map<String, Integer> mineProg,
                                Map<String, Integer> sellProg, boolean exchange, double balance) {
        int page = menuPage.page();
        int totalPages = menuPage.pages();
        int totalItems = menuPage.total();
        List<String> pageMaterials = menuPage.materials();
        Map<String, String> ph = pagePlaceholders(page, totalPages, totalItems, search);

        if (exchange) ph.put("%balance%", String.format("%.2f", balance));
        MenuHolder holder = new MenuHolder(layout.name, page, search, layout.aSlots, layout);
        Inventory inv = Bukkit.createInventory(holder, layout.size,
                ColorUtil.translateColorCodes(applyPlaceholders(layout.title, ph)));
        holder.setInventory(inv);
        fillStaticButtons(inv, layout, ph, page, totalPages, totalItems);

        ButtonDef template = layout.buttons.get('A');
        for (int i = 0; i < pageMaterials.size(); i++) {
            String materialName = pageMaterials.get(i);
            ItemStack icon = buildItemIcon(materialName, template, exchange,
                    unlocked.getOrDefault(materialName, false),
                    mineProg.getOrDefault(materialName, 0),
                    sellProg.getOrDefault(materialName, 0));
            if (icon != null) {
                inv.setItem(layout.aSlots.get(i), icon);
            }
        }
        player.openInventory(inv);
    }

    private record MenuPage(int page, int pages, int total, List<String> materials) {}

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
        String keyword = message;
        List<String> matching = applySearch(configManager.getItemMaterialNames(), keyword);
        if ("exchange".equals(menuType)) {
            loadMenu(player, () -> {
                Map<String, Boolean> unlocked = getCachedUnlockedStatuses(uuid, matching);
                boolean hasMatch = matching.stream().anyMatch(key -> unlocked.getOrDefault(key, false));
                return () -> applySearchInput(player, menuType, keyword, hasMatch);
            });
        } else {
            applySearchInput(player, menuType, keyword, !matching.isEmpty());
        }
    }

    private void applySearchInput(Player player, String menuType, String message, boolean hasMatch) {
        var lang = configManager.getLang();
        String prefix = lang.getString("prefix", "");
        UUID uuid = player.getUniqueId();
        if (!hasMatch) {
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
            schedulerUtil.runAtEntity(player, () -> {
                if (player.getOpenInventory().getTopInventory() == top) handleSellClick(player);
            }, null);
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
        openStaticMenu(player, menuName);
    }

    private void openStaticMenu(Player player, String menuName) {
        ensureLayouts();
        MenuLayout layout = layouts.get(menuName);
        if (layout == null) {
            player.sendMessage(ColorUtil.translateColorCodes(
                    configManager.getLang().getString("prefix", "") + "&c菜单不存在: " + menuName));
            return;
        }
        UUID uuid = player.getUniqueId();
        double dailyLimit = layout.usesPlaceholder("%daily_limit%") && configManager.isDailyLimitEnabled()
                ? configManager.getDailyLimitForPlayer(player) : -1;
        loadMenu(player, () -> {
            boolean displaysBalance = layout.displaysBalance();
            DatabaseManager.PlayerDisplayData data = null;
            if (displaysBalance || dailyLimit != -1) {
                try {
                    data = databaseManager.getPlayerDisplayData(uuid, dailyLimit == -1 ? null : databaseManager.getEffectiveDate());
                } catch (SQLException | ClassNotFoundException | RuntimeException e) {
                    plugin.getLogger().warning("Error getting menu display data: " + e.getMessage());
                }
            }
            double balance = data == null ? 0 : data.balance();
            String remaining = dailyLimit == -1 || data == null
                    ? configManager.getLang().getString("daily_limit.unlimited", "&a无限")
                    : "&6" + String.format("%.0f", Math.max(0, dailyLimit - data.earned()));
            return () -> {
                Map<String, String> ph = new HashMap<>();
                ph.put("%balance%", String.format("%.2f", balance));
                ph.put("%daily_limit%", remaining);
                ph.put("%tax%", String.format("%.1f", configManager.getTaxRateForPlayer(player) * 100));
                ph.put("%player%", player.getName());
                MenuHolder holder = new MenuHolder(menuName, 1, null, layout.aSlots, layout);
                Inventory inv = Bukkit.createInventory(holder, layout.size, ColorUtil.translateColorCodes(layout.title));
                holder.setInventory(inv);
                fillStaticButtons(inv, layout, ph, 1, 1, 0);
                player.openInventory(inv);
            };
        });
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
        menuLoads.remove(uuid);
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
        Inventory inv = player.getOpenInventory().getTopInventory();
        if (!(inv.getHolder() instanceof MenuHolder holder) || !"sell".equals(holder.menuType())) return;
        UUID uuid = player.getUniqueId();
        if (!pendingSales.add(uuid)) return;

        double taxRate = configManager.getTaxRateForPlayer(player);
        double dailyLimit = configManager.isDailyLimitEnabled() ? configManager.getDailyLimitForPlayer(player) : -1;
        List<SaleLine> lines = new ArrayList<>();
        List<ItemStack> rejected = new ArrayList<>();
        double total = 0;
        for (int slot : holder.layoutASlots()) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType().isAir()) continue;
            String key = CeItemUtil.resolveSaleKey(item);
            double price = configManager.getItemEMC(key);
            double value = item.getAmount() * price;
            if (configManager.hasItem(key) && value > 0 && Double.isFinite(total + value)) {
                lines.add(new SaleLine(key, item.getAmount(), price, item.clone()));
                total += value;
            } else {
                rejected.add(item.clone());
            }
            inv.setItem(slot, null);
        }
        returnItemsToPlayer(player, rejected);
        if (lines.isEmpty()) {
            pendingSales.remove(uuid);
            return;
        }

        double requested = total;
        boolean displaysBalance = holder.layout() != null && holder.layout().displaysBalance();
        try {
            if (schedulerUtil.runAsync(() -> settleSale(uuid, inv, holder, lines, requested, taxRate, dailyLimit, displaysBalance))) return;
        } catch (RuntimeException e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Could not schedule sale", e);
        }
        pendingSales.remove(uuid);
        returnItemsToPlayer(player, lines.stream().map(SaleLine::item).toList());
    }

    private void settleSale(UUID uuid, Inventory inv, MenuHolder holder, List<SaleLine> lines,
                            double requested, double taxRate, double dailyLimit, boolean displaysBalance) {
        int[] sold = new int[lines.size()];
        boolean credited = false;
        double consumed = 0;
        String date = null;
        double net = 0;
        String result = "menu.sell.failed";
        List<String> unlocked = new ArrayList<>();
        Double balance = null;
        try {
            double available = requested;
            if (dailyLimit >= 0) {
                date = databaseManager.getEffectiveDate();
                consumed = databaseManager.consumeDailyEMCLimit(uuid, date, requested, dailyLimit);
                available = consumed;
            }
            double gross = 0;
            Map<String, Integer> soldByKey = new LinkedHashMap<>();
            for (int index = 0; index < lines.size(); index++) {
                SaleLine line = lines.get(index);
                int count = available >= requested || line.amount() * line.price() <= available - gross
                        ? line.amount() : Math.max(0, (int) ((available - gross) / line.price()));
                if (count <= 0) continue;
                sold[index] = count;
                gross += count * line.price();
                soldByKey.merge(line.key(), count, Integer::sum);
            }
            if (date != null && consumed > gross) {
                databaseManager.addDailyEMCEarned(uuid, date, gross - consumed);
                consumed = gross;
            }
            if (gross <= 0) {
                result = "daily_limit.reached";
            } else {
                net = gross - gross * taxRate;
                databaseManager.addEMCBalance(uuid, net);
                credited = true;
                result = "menu.sell.success";
                clearPlayerCache(uuid);
                if (displaysBalance) balance = readSellBalance(uuid);
                updateBatchSellProgress(uuid, soldByKey, unlocked);
            }
        } catch (SQLException | ClassNotFoundException | RuntimeException e) {
            if (!credited && date != null && consumed > 0) {
                try {
                    databaseManager.addDailyEMCEarned(uuid, date, -consumed);
                } catch (SQLException | ClassNotFoundException refundException) {
                    e.addSuppressed(refundException);
                }
            }
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Error handling sell", e);
        } finally {
            completeSale(uuid, inv, holder, lines, sold, credited, result, net, balance, unlocked);
        }
    }

    private void updateBatchSellProgress(UUID uuid, Map<String, Integer> soldByKey, List<String> unlocked)
            throws SQLException, ClassNotFoundException {
        List<String> keys = new ArrayList<>(soldByKey.keySet());
        Map<String, Boolean> statuses = databaseManager.getUnlockedStatuses(uuid, keys);
        keys.removeIf(key -> statuses.getOrDefault(key, false));
        if (keys.isEmpty()) return;
        List<String> sellKeys = keys.stream().filter(key -> configManager.getRequiredSell(key) > 0).toList();
        Map<String, Integer> sellProgress = sellKeys.isEmpty() ? Map.of() : databaseManager.getSellProgresses(uuid, sellKeys);
        Map<String, Integer> increments = new LinkedHashMap<>();
        List<String> unlockCandidates = new ArrayList<>();
        for (String key : keys) {
            int requiredSell = configManager.getRequiredSell(key);
            int progress = sellProgress.getOrDefault(key, 0);
            int increment = Math.min(soldByKey.get(key), requiredSell - progress);
            if (increment > 0) {
                increments.put(key, increment);
                progress += increment;
            }
            if (progress >= requiredSell) {
                unlockCandidates.add(key);
            }
        }
        databaseManager.addSellProgressBatch(uuid, increments);
        List<String> mineKeys = unlockCandidates.stream().filter(key -> configManager.getRequiredMine(key) > 0).toList();
        Map<String, Integer> mineProgress = mineKeys.isEmpty() ? Map.of() : databaseManager.getMineProgresses(uuid, mineKeys);
        for (String key : unlockCandidates) {
            if (mineProgress.getOrDefault(key, 0) < configManager.getRequiredMine(key)) continue;
            databaseManager.unlockItem(uuid, key);
            unlocked.add(key);
        }
        clearPlayerCache(uuid);
    }

    private void completeSale(UUID uuid, Inventory inv, MenuHolder holder, List<SaleLine> lines,
                              int[] sold, boolean credited, String result, double net, Double balance, List<String> unlocked) {
        saleDelivery.deliver(uuid, () -> {
            List<ItemStack> returns = new ArrayList<>();
            for (int index = 0; index < lines.size(); index++) {
                SaleLine line = lines.get(index);
                int unsold = line.amount() - (credited ? sold[index] : 0);
                if (unsold <= 0) continue;
                line.item().setAmount(unsold);
                returns.add(line.item());
            }
            return returns;
        }, current -> {
            String prefix = configManager.getLang().getString("prefix", "");
            String text = configManager.getLang().getString(result, "").replace("{amount}", String.format("%.2f", net));
            current.sendMessage(ColorUtil.translateColorCodes(prefix + text));
            for (String key : unlocked) {
                String message = configManager.getLang().getString("mine.unlocked", "").replace("{item}", configManager.getItemName(key));
                current.sendMessage(ColorUtil.translateColorCodes(prefix + message));
            }
            if (balance != null && current.getOpenInventory().getTopInventory() == inv) {
                renderSellButtons(current, inv, holder.layout(), balance);
            }
        }, () -> pendingSales.remove(uuid));
    }

    private record SaleLine(String key, int amount, double price, ItemStack item) {}

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



    // ══════════════════════════ 缓存 ══════════════════════════

    private void startCacheCleanupTask() {
        schedulerUtil.runTimerAsync(() -> {
            long cutoff = System.currentTimeMillis() - 30 * 60 * 1000;
            playerMenuCache.entrySet().removeIf(entry -> entry.getValue().lastAccess < cutoff);
        }, 0L, 5 * 60 * 20L);
    }

    private PlayerMenuCache playerMenuCache(UUID uuid) {
        PlayerMenuCache cache = playerMenuCache.computeIfAbsent(uuid, key -> new PlayerMenuCache());
        cache.lastAccess = System.currentTimeMillis();
        return cache;
    }

    private Map<String, Boolean> getCachedUnlockedStatuses(UUID uuid, List<String> keys) {
        return getMenuValues(keys, playerMenuCache(uuid).unlocked, requested -> databaseManager.getUnlockedStatuses(uuid, requested));
    }

    private Map<String, Integer> getCachedMineProgresses(UUID uuid, List<String> keys) {
        return getMenuValues(keys, playerMenuCache(uuid).mined, requested -> databaseManager.getMineProgresses(uuid, requested));
    }

    private Map<String, Integer> getCachedSellProgresses(UUID uuid, List<String> keys) {
        return getMenuValues(keys, playerMenuCache(uuid).sold, requested -> databaseManager.getSellProgresses(uuid, requested));
    }

    private <T> Map<String, T> getMenuValues(List<String> keys, Map<String, T> cache, MenuQuery<T> query) {
        List<String> missing = keys.stream().filter(key -> !cache.containsKey(key)).toList();
        try {
            for (int offset = 0; offset < missing.size(); offset += 500) {
                cache.putAll(query.load(missing.subList(offset, Math.min(offset + 500, missing.size()))));
            }
        } catch (SQLException | ClassNotFoundException e) {
            plugin.getLogger().severe("Error loading menu data: " + e.getMessage());
        }
        Map<String, T> result = new HashMap<>();
        for (String key : keys) {
            T value = cache.get(key);
            if (value != null) result.put(key, value);
        }
        return result;
    }

    @FunctionalInterface
    private interface MenuQuery<T> {
        Map<String, T> load(List<String> keys) throws SQLException, ClassNotFoundException;
    }

    private static final class PlayerMenuCache {
        final Map<String, Boolean> unlocked = new ConcurrentHashMap<>();
        final Map<String, Integer> mined = new ConcurrentHashMap<>();
        final Map<String, Integer> sold = new ConcurrentHashMap<>();
        volatile long lastAccess = System.currentTimeMillis();
    }

    public void clearPlayerCache(UUID uuid) {
        playerMenuCache.remove(uuid);
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
            if (menuLoads.containsKey(uuid)) return;
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
        playerMenuCache.clear();
        menuLoads.clear();
        baseIconCache.clear();
        CeItemUtil.clearCache();
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

        boolean displaysBalance() {
            return usesPlaceholder("%balance%");
        }

        boolean usesPlaceholder(String placeholder) {
            return title.contains(placeholder) || buttons.values().stream().anyMatch(button ->
                    (button.name != null && button.name.contains(placeholder))
                            || button.lore.stream().anyMatch(line -> line != null && line.contains(placeholder)));
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
