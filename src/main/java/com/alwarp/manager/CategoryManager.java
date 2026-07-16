package com.alwarp.manager;

import com.alwarp.ALwarp;
import com.alwarp.gui.shape.MenuConfig;
import com.alwarp.gui.shape.MenuItem;
import com.alwarp.model.Category;
import com.alwarp.storage.StorageManager;
import org.bukkit.ChatColor;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分类管理器。
 * 从 menus.yml 的 category_select 读取分类定义，
 * 启动时同步到数据库（新分类插入，已有跳过），然后加载到缓存。
 */
public class CategoryManager {

    private final ALwarp plugin;
    private final StorageManager storage;
    private final Map<Integer, Category> cache = new ConcurrentHashMap<>();

    public CategoryManager(ALwarp plugin, StorageManager storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    /**
     * 从 menus.yml 同步分类到数据库，然后加载全部到缓存。
     */
    public void loadAll() {
        syncFromMenuConfig();
        cache.clear();
        List<Category> all = storage.getAllCategories();
        for (Category c : all) {
            cache.put(c.getId(), c);
        }
    }

    public void refreshCache() {
        cache.clear();
        List<Category> all = storage.getAllCategories();
        for (Category c : all) {
            cache.put(c.getId(), c);
        }
    }

    /**
     * 读取 category_select 菜单，与数据库完全同步。
     * YAML 中有的 → 按YAML更新或插入；以配置文件为准。
     */
    private void syncFromMenuConfig() {
        MenuConfig menu = plugin.getShapeMenuManager().getMenu("category_select");
        if (menu == null) return;

        int updated = 0, inserted = 0;
        for (Map.Entry<String, MenuItem> entry : menu.getItems().entrySet()) {
            MenuItem item = entry.getValue();
            if (item.getAction() == null || !item.getAction().startsWith("filter:")) continue;

            int id;
            try {
                id = Integer.parseInt(item.getAction().substring("filter:".length()));
            } catch (NumberFormatException e) { continue; }

            String rawName = item.getName();
            String colorCode = "&7";
            String pureName = rawName;
            if (rawName != null && rawName.length() >= 2 && rawName.charAt(0) == '&') {
                colorCode = rawName.substring(0, 2);
                pureName = rawName.substring(2);
            }

            Category existing = storage.getCategoryById(id);
            if (existing != null) {
                existing.setName(pureName != null ? pureName : existing.getName());
                existing.setIcon(item.getType());
                existing.setColor(colorCode);
                existing.setDefault(item.getIsDefault() != null && item.getIsDefault());
                existing.setIconCustomModelData(item.getCustomModelData());
                existing.setIconPluginItem(item.getPluginItem());
                storage.updateCategory(existing);
                updated++;
            } else {
                Category cat = new Category(id,
                        pureName != null ? pureName : "未命名",
                        item.getType(), colorCode,
                        item.getIsDefault() != null && item.getIsDefault());
                if (item.getCustomModelData() != null) cat.setIconCustomModelData(item.getCustomModelData());
                if (item.getPluginItem() != null && !item.getPluginItem().isEmpty())
                    cat.setIconPluginItem(item.getPluginItem());
                storage.createCategory(cat);
                inserted++;
            }
        }
        if (updated > 0 || inserted > 0)
            plugin.logInfo("分类同步: 更新 " + updated + " 个，新增 " + inserted + " 个",
                    "Category sync: updated " + updated + ", inserted " + inserted);
    }

    public Category getCategory(int id) {
        return cache.get(id);
    }

    public Category getCategoryByName(String name) {
        for (Category c : cache.values()) {
            if (c.getName().equals(name)) return c;
        }
        return null;
    }

    public List<Category> getAllCategories() {
        List<Category> list = new ArrayList<>(cache.values());
        list.sort(Comparator.comparingInt(Category::getId));
        return list;
    }

    public Category getDefaultCategory() {
        for (Category c : cache.values()) {
            if (c.isDefault()) return c;
        }
        return cache.isEmpty() ? null : cache.values().iterator().next();
    }
}
