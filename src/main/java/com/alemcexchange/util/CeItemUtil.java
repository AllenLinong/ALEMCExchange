package com.alemcexchange.util;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * CraftEngine 自定义物品支持（反射调用，无需硬依赖）。
 * 参照 ALwarp 的 ItemUtil 实现，供菜单图标/兑换物品/搜索使用。
 */
public final class CeItemUtil {

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger("ALEMCExchange");
    private static final Map<String, ItemStack> CE_ITEM_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, String> CE_NAME_CACHE = new ConcurrentHashMap<>();

    private CeItemUtil() {}

    /** 判断配置键是否是 CE 物品（ce:ns:id 或 craftengine:ns:id） */
    public static boolean isCeKey(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.startsWith("ce:") || lower.startsWith("craftengine:");
    }

    /** 从配置键提取 CE 物品 ID（ns:id） */
    public static String extractCeId(String key) {
        if (!isCeKey(key)) return null;
        int idx = key.indexOf(':');
        String id = key.substring(idx + 1).trim();
        return id.isEmpty() ? null : id;
    }

    /** 由 CE ID 构建 Bukkit ItemStack（带缓存） */
    public static ItemStack buildCeItem(String ceId) {
        if (ceId == null || ceId.isEmpty()) return null;
        ItemStack cached = CE_ITEM_CACHE.get(ceId);
        if (cached != null) return cached.clone();
        ItemStack built = resolveCeItem(ceId);
        if (built != null) {
            CE_ITEM_CACHE.put(ceId, built.clone());
        }
        return built;
    }

    /** 获取 CE 物品的原始显示名（用于搜索匹配），失败返回 null */
    public static String getCeDisplayName(String ceId) {
        if (ceId == null) return null;
        if (CE_NAME_CACHE.containsKey(ceId)) return CE_NAME_CACHE.get(ceId);
        String name = null;
        ItemStack item = buildCeItem(ceId);
        if (item != null && item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            name = item.getItemMeta().getDisplayName();
        }
        CE_NAME_CACHE.put(ceId, name);
        return name;
    }

    /** 从 ItemStack 检测 CE 物品 ID（ns:id），非 CE 物品返回 null */
    public static String detectCeId(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        try {
            Class<?> apiClass = findPluginClass("net.momirealms.craftengine.bukkit.api.CraftEngineItems");
            if (apiClass != null) {
                for (String methodName : List.of("getCustomItemId", "getCustomItemID", "getItemId", "getItemID")) {
                    Object result = invokeStaticIfCompatible(findMethodByParamCount(apiClass, methodName, 1), item);
                    if (result != null) return normalizeNamespacedId(result.toString());
                }
                Object customItem = invokeStaticIfCompatible(findMethodByParamCount(apiClass, "getCustomItem", 1), item);
                if (customItem == null) {
                    customItem = invokeStaticIfCompatible(findMethodByParamCount(apiClass, "byItemStack", 1), item);
                }
                if (customItem != null) {
                    String id = extractId(customItem);
                    if (id != null) return normalizeNamespacedId(id);
                }
            }
        } catch (Exception ignored) {}
        return detectCeIdFromPersistentData(item);
    }

    /** 出售/兑换时把 ItemStack 映射成 items.yml 的键 */
    public static String resolveSaleKey(ItemStack item) {
        String ceId = detectCeId(item);
        if (ceId != null) return "ce:" + ceId;
        return item.getType().name();
    }

    public static void clearCache() {
        CE_ITEM_CACHE.clear();
        CE_NAME_CACHE.clear();
    }

    // ────────────────────────── 内部实现 ──────────────────────────

    private static String normalizeNamespacedId(String id) {
        if (id == null) return null;
        id = id.trim();
        if (id.isEmpty()) return null;
        if (id.startsWith("craftengine:")) return id.substring("craftengine:".length());
        if (id.startsWith("ce:")) return id.substring("ce:".length());
        return id;
    }

    private static String detectCeIdFromPersistentData(ItemStack item) {
        if (!item.hasItemMeta()) return null;
        try {
            var data = item.getItemMeta().getPersistentDataContainer();
            for (var key : data.getKeys()) {
                String namespace = key.getNamespace().toLowerCase(Locale.ROOT);
                String keyName = key.getKey().toLowerCase(Locale.ROOT);
                if (!namespace.contains("craftengine") && !namespace.equals("ce")) continue;
                if (!keyName.contains("id") && !keyName.contains("item")) continue;
                String value = data.get(key, org.bukkit.persistence.PersistentDataType.STRING);
                if (value != null && !value.isBlank()) return normalizeNamespacedId(value);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static ItemStack resolveCeItem(String id) {
        if (!Bukkit.getPluginManager().isPluginEnabled("CraftEngine")) return null;
        try {
            Class<?> apiClass = findPluginClass("net.momirealms.craftengine.bukkit.api.CraftEngineItems");
            if (apiClass == null) return null;
            Object definition = null;
            Method stringById = findMethod(apiClass, "byId", String.class);
            if (stringById != null) {
                definition = stringById.invoke(null, id);
                if (definition == null && !id.contains(":")) {
                    definition = stringById.invoke(null, "craftengine:" + id);
                }
            }
            if (definition == null) {
                for (String methodName : List.of("byID", "getById", "getByID", "getCustomItem", "getItem")) {
                    Method method = findMethod(apiClass, methodName, String.class);
                    if (method == null) continue;
                    definition = method.invoke(null, id);
                    if (definition != null) break;
                }
            }
            if (definition == null) return null;

            ItemStack apiStack = extractBukkitItemStack(definition);
            if (apiStack != null) return apiStack;

            // 反射读取 definition.item 字段（CE 内部物品对象），经 NMS 代理转 Bukkit 物品
            var itemField = definition.getClass().getDeclaredField("item");
            itemField.setAccessible(true);
            Object ceItemObj = itemField.get(definition);
            if (ceItemObj == null) return null;

            Class<?> proxyClass = Class.forName("net.momirealms.craftengine.proxy.minecraft.world.item.ItemStackProxy");
            Object instance = proxyClass.getField("INSTANCE").get(null);
            var newInstanceMethod = proxyClass.getMethod("newInstance", Object.class, int.class);
            Object nmsStack = newInstanceMethod.invoke(instance, ceItemObj, 1);

            Class<?> utilsClass = Class.forName("net.momirealms.craftengine.bukkit.util.ItemStackUtils");
            var getBukkitStack = utilsClass.getMethod("getBukkitStack", nmsStack.getClass());
            return (ItemStack) getBukkitStack.invoke(null, nmsStack);
        } catch (Exception e) {
            LOG.log(Level.FINE, "CE物品构建失败 " + id + ": " + e.getMessage());
            return null;
        }
    }

    private static ItemStack extractBukkitItemStack(Object definition) {
        for (String methodName : List.of("buildBukkitItem", "build", "create", "getItemStack",
                "itemStack", "getBukkitItemStack", "bukkitItemStack")) {
            try {
                Method method = definition.getClass().getMethod(methodName);
                Object result = method.invoke(definition);
                if (result instanceof ItemStack stack) return stack.clone();
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static String extractId(Object customItem) {
        for (String methodName : List.of("id", "getId", "getID", "key", "getKey", "namespacedKey", "getNamespacedKey")) {
            try {
                Method method = customItem.getClass().getMethod(methodName);
                Object result = method.invoke(customItem);
                if (result != null) return result.toString();
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static Object invokeStaticIfCompatible(Method method, ItemStack item) {
        if (method == null || method.getParameterCount() != 1) return null;
        Class<?> parameterType = method.getParameterTypes()[0];
        if (!parameterType.isAssignableFrom(ItemStack.class)) return null;
        try {
            return method.invoke(null, item);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 按名称与参数个数查找方法（不限定参数类型，用于探测 API） */
    private static Method findMethodByParamCount(Class<?> clazz, String name, int paramCount) {
        if (clazz == null) return null;
        for (Method method : clazz.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == paramCount) {
                return method;
            }
        }
        return null;
    }

    private static Class<?> findPluginClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException | LinkageError ignored) {}
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (!plugin.isEnabled()) continue;
            try {
                return Class.forName(className, true, plugin.getClass().getClassLoader());
            } catch (ClassNotFoundException | LinkageError ignored) {}
        }
        return null;
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... params) {
        if (clazz == null) return null;
        try {
            return clazz.getMethod(name, params);
        } catch (Exception ignored) {
            return null;
        }
    }
}
