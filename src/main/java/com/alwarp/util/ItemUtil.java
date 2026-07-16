package com.alwarp.util;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 物品构建工具类。支持原版Material、CustomModelData、CE/IA/Oraxen自定义物品。
 */
public final class ItemUtil {

    private static final Logger LOG = Logger.getLogger("ALwarp");
    private static final Map<String, ItemStack> PLUGIN_ITEM_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> WARNED_PLUGIN_ITEMS = ConcurrentHashMap.newKeySet();
    private static final int PLAYER_SKIN_CACHE_MAX_SIZE = 4096;
    private static final Map<UUID, SkinTexture> PLAYER_SKIN_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, SkinTexture> eldest) {
                    return size() > PLAYER_SKIN_CACHE_MAX_SIZE;
                }
            });

    public record SkinTexture(String value, String signature) {}

    /** 从序列化数据还原物品（CE/IA等自定义物品图标） */
    public static ItemStack buildItem(Map<String, Object> iconData) {
        if (iconData != null && !iconData.isEmpty()) {
            try {
                return ItemStack.deserialize(iconData);
            } catch (Exception ignored) {}
        }
        return null;
    }

    public static ItemStack buildItem(String type, Integer customModelData, String pluginItem,
                                       String name, List<String> lore) {
        return buildItem(type, customModelData, pluginItem, null, name, lore, null);
    }

    /** 带 iconData 的完整版：优先用序列化数据，其次 pluginItem，最后 type */
    public static ItemStack buildItem(String type, Integer customModelData, String pluginItem,
                                       Map<String, Object> iconData,
                                       String name, List<String> lore) {
        return buildItem(type, customModelData, pluginItem, iconData, name, lore, null);
    }

    public static ItemStack buildItem(String type, Integer customModelData, String pluginItem,
                                       Map<String, Object> iconData,
                                       String name, List<String> lore,
                                       Map<String, String> pluginItemArguments) {
        pluginItem = normalizePluginItemReference(pluginItem);
        if (pluginItem == null) {
            pluginItem = normalizePluginItemReference(type);
            if (pluginItem != null) {
                type = "PAPER";
            }
        }
        boolean isPluginItem = pluginItem != null && !pluginItem.isEmpty();
        ItemStack item = null;
        if (iconData != null && !iconData.isEmpty()) {
            try {
                item = ItemStack.deserialize(iconData);
            } catch (Exception ignored) {}
        }
        if (item == null && isPluginItem) item = resolvePluginItem(pluginItem, pluginItemArguments);
        if (item == null) {
            try {
                item = new ItemStack(Material.valueOf(type != null ? type : "BARRIER"));
            } catch (IllegalArgumentException e) {
                item = new ItemStack(Material.BARRIER);
            }
        }
        if (item != null && isPluginItem && isCraftEngineItem(pluginItem)) {
            item = applyCeArguments(item, pluginItemArguments);
        }
        if (isPluginItem && item != null) {
            // CE/IA/Oraxen: 只使用物品纹理，显示文本由菜单模板接管。
            if (name != null && !name.isEmpty()) {
                item.editMeta(meta -> meta.setDisplayName(MessageUtil.colorize(name)));
            }
            if (lore != null) {
                List<net.kyori.adventure.text.Component> components = new ArrayList<>();
                for (String l : lore)
                    components.add(MessageUtil.deserialize(l));
                item.lore(components);
            }
        } else {
            if (customModelData != null && customModelData > 0) {
                item.editMeta(meta -> meta.setCustomModelData(customModelData));
            }
            item.editMeta(meta -> {
                hideVanillaTooltipDetails(meta);
                if (name != null && !name.isEmpty())
                    meta.setDisplayName(MessageUtil.colorize(name));
                if (lore != null && !lore.isEmpty()) {
                    List<String> cl = new ArrayList<>();
                    for (String l : lore) cl.add(MessageUtil.colorize(l));
                    meta.setLore(cl);
                }
            });
        }
        item.editMeta(ItemUtil::hideVanillaTooltipDetails);
        return item.clone();
    }

    /**
     * 构建玩家头颅物品。调用此方法时请优先使用带 ownerName 的重载方法，
     * 以确保离线玩家的皮肤也能正确显示。
     */
    public static ItemStack buildPlayerHead(String name, UUID uuid, List<String> lore) {
        return buildPlayerHead(name, uuid, null, lore);
    }

    /**
     * 构建玩家头颅物品。当玩家在线时直接设置OwningPlayer；
     */
    public static ItemStack buildPlayerHead(String name, UUID uuid, String ownerName, List<String> lore) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (name != null) meta.setDisplayName(MessageUtil.colorize(name));
        if (lore != null) {
            List<String> cl = new ArrayList<>();
            for (String l : lore) cl.add(MessageUtil.colorize(l));
            meta.setLore(cl);
        }
        if (uuid != null) {
            try {
                Player online = Bukkit.getPlayer(uuid);
                if (online != null) {
                    SkinTexture skin = cachePlayerSkin(online);
                    PlayerProfile profile = createProfile(uuid, ownerName, skin);
                    if (profile != null) {
                        meta.setPlayerProfile(profile);
                    }
                } else {
                    PlayerProfile profile = createProfile(uuid, ownerName, PLAYER_SKIN_CACHE.get(uuid));
                    if (profile != null) {
                        meta.setPlayerProfile(profile);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        head.setItemMeta(meta);
        return head;
    }

    public static boolean applyPlayerHeadOwner(ItemStack item, UUID uuid, String ownerName) {
        if (item == null || item.getType() != Material.PLAYER_HEAD || uuid == null) return false;
        if (!(item.getItemMeta() instanceof SkullMeta meta)) return false;

        boolean applied = false;
        try {
            Player online = Bukkit.getPlayer(uuid);
            SkinTexture skin = online != null ? cachePlayerSkin(online) : PLAYER_SKIN_CACHE.get(uuid);
            PlayerProfile profile = createProfile(uuid, ownerName, skin);
            if (profile != null) {
                meta.setPlayerProfile(profile);
                applied = true;
            } else {
                OfflinePlayer owner = Bukkit.getOfflinePlayer(uuid);
                meta.setOwningPlayer(owner);
                applied = true;
            }
        } catch (Exception ignored) {
        }

        if (applied) {
            item.setItemMeta(meta);
        }
        return applied;
    }

    public static ItemStack freezeCraftEngineClientDisplay(ItemStack item, Map<String, String> arguments) {
        ItemStack rendered = applyCraftEngineClientBoundData(item, arguments);
        if (rendered == null) return item;
        return stripCraftEngineRuntimeTags(rendered);
    }

    private static ItemStack applyCraftEngineClientBoundData(ItemStack item, Map<String, String> arguments) {
        if (item == null || item.getType().isAir()
                || !Bukkit.getPluginManager().isPluginEnabled("CraftEngine")) {
            return null;
        }
        try {
            Class<?> adaptorClass = findPluginClass("net.momirealms.craftengine.bukkit.api.BukkitAdaptor");
            Class<?> itemClass = findPluginClass("net.momirealms.craftengine.core.item.Item");
            Class<?> itemBuildContextClass = findPluginClass("net.momirealms.craftengine.core.item.ItemBuildContext");
            Class<?> networkContextClass = findPluginClass("net.momirealms.craftengine.core.item.network.NetworkItemBuildContext");
            Class<?> processorClass = findPluginClass("net.momirealms.craftengine.core.item.processor.ItemProcessor");
            Class<?> compoundTagClass = findPluginClass("net.momirealms.craftengine.libraries.nbt.CompoundTag");
            if (adaptorClass == null || itemClass == null || itemBuildContextClass == null
                    || networkContextClass == null || processorClass == null || compoundTagClass == null) {
                return null;
            }

            Object ceItem = adaptorClass.getMethod("adapt", ItemStack.class).invoke(null, item);
            if (ceItem == null) return null;

            Object optionalDefinition = itemClass.getMethod("getDefinition").invoke(ceItem);
            if (!(optionalDefinition instanceof java.util.Optional<?> definitionOptional)
                    || definitionOptional.isEmpty()) {
                return null;
            }

            Object context = createCeBuildContext(networkContextClass, arguments);
            if (context == null) return null;

            Object networkData = compoundTagClass.getConstructor().newInstance();
            Object processors = definitionOptional.get().getClass()
                    .getMethod("clientBoundProcessors")
                    .invoke(definitionOptional.get());
            Method prepareNetworkItem = processorClass.getMethod(
                    "prepareNetworkItem",
                    itemClass,
                    itemBuildContextClass,
                    compoundTagClass
            );
            Method apply = processorClass.getMethod("apply", itemClass, itemBuildContextClass);
            int length = java.lang.reflect.Array.getLength(processors);
            for (int i = 0; i < length; i++) {
                Object processor = java.lang.reflect.Array.get(processors, i);
                prepareNetworkItem.invoke(processor, ceItem, context, networkData);
            }
            for (int i = 0; i < length; i++) {
                Object processor = java.lang.reflect.Array.get(processors, i);
                apply.invoke(processor, ceItem, context);
            }

            Object bukkitItem = ceItem.getClass().getMethod("getBukkitItem").invoke(ceItem);
            return bukkitItem instanceof ItemStack stack ? stack.clone() : null;
        } catch (Exception | LinkageError ignored) {
            return null;
        }
    }

    public static ItemStack stripCraftEngineRuntimeTags(ItemStack item) {
        if (item == null) return null;

        item = stripCraftEngineInternalTags(item);
        if (item == null || !item.hasItemMeta()) return item;

        ItemMeta meta = item.getItemMeta();
        var data = meta.getPersistentDataContainer();
        for (NamespacedKey key : new ArrayList<>(data.getKeys())) {
            String namespace = key.getNamespace().toLowerCase(java.util.Locale.ROOT);
            String keyName = key.getKey().toLowerCase(java.util.Locale.ROOT);
            if ((namespace.contains("craftengine") || namespace.equals("ce"))
                    && (keyName.equals("id") || keyName.equals("arguments"))) {
                data.remove(key);
            }
        }
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack stripCraftEngineInternalTags(ItemStack item) {
        if (item == null || item.getType().isAir()
                || !Bukkit.getPluginManager().isPluginEnabled("CraftEngine")) {
            return item;
        }
        try {
            Class<?> adaptorClass = findPluginClass("net.momirealms.craftengine.bukkit.api.BukkitAdaptor");
            Class<?> itemClass = findPluginClass("net.momirealms.craftengine.core.item.Item");
            if (adaptorClass == null || itemClass == null) return item;

            Object ceItem = adaptorClass.getMethod("adapt", ItemStack.class).invoke(null, item);
            if (ceItem == null) return item;

            Method removeTag = findMethod(itemClass, "removeTag", 1);
            if (removeTag == null) return item;
            invokeCeRemoveTag(ceItem, removeTag, "craftengine:id");
            invokeCeRemoveTag(ceItem, removeTag, "craftengine:arguments");

            Object bukkitItem = ceItem.getClass().getMethod("getBukkitItem").invoke(ceItem);
            return bukkitItem instanceof ItemStack stack ? stack.clone() : item;
        } catch (Exception | LinkageError ignored) {
            return item;
        }
    }

    /** 只保留物品作为图标所需的数据，丢弃原物品名称和lore。 */
    private static void invokeCeRemoveTag(Object ceItem, Method removeTag, String key) throws Exception {
        Class<?> parameterType = removeTag.getParameterTypes()[0];
        Object argument = key;
        if (parameterType.isArray()) {
            argument = java.lang.reflect.Array.newInstance(parameterType.getComponentType(), 1);
            java.lang.reflect.Array.set(argument, 0, key);
        }
        removeTag.invoke(ceItem, new Object[]{argument});
    }

    public static SkinTexture cachePlayerSkin(Player player) {
        if (player == null) return null;
        try {
            PlayerProfile profile = player.getPlayerProfile();
            if (profile == null) return null;
            for (ProfileProperty property : profile.getProperties()) {
                if ("textures".equals(property.getName()) && hasText(property.getValue())) {
                    SkinTexture skin = new SkinTexture(property.getValue(), property.getSignature());
                    PLAYER_SKIN_CACHE.put(player.getUniqueId(), skin);
                    return skin;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static void cachePlayerSkin(UUID uuid, String textureValue, String textureSignature) {
        if (uuid == null || !hasText(textureValue)) return;
        PLAYER_SKIN_CACHE.put(uuid, new SkinTexture(textureValue, textureSignature));
    }

    public static int getPlayerSkinCacheMaxSize() {
        return PLAYER_SKIN_CACHE_MAX_SIZE;
    }

    private static PlayerProfile createProfile(UUID uuid, String ownerName, SkinTexture skin) {
        if (skin == null || !hasText(skin.value())) return null;
        PlayerProfile profile = hasText(ownerName)
                ? Bukkit.createProfileExact(uuid, ownerName)
                : Bukkit.createProfile(uuid);
        if (hasText(skin.signature())) {
            profile.setProperty(new ProfileProperty("textures", skin.value(), skin.signature()));
        } else {
            profile.setProperty(new ProfileProperty("textures", skin.value()));
        }
        return profile;
    }

    public static Map<String, Object> serializeIconOnly(ItemStack source) {
        if (source == null || source.getType().isAir()) return null;
        ItemStack icon = source.clone();
        icon.setAmount(1);
        icon.editMeta(meta -> {
            meta.setDisplayName(null);
            meta.setLore(null);
            hideVanillaTooltipDetails(meta);
            stripEnchantments(meta);
            clearComponentText(meta, "displayName");
            clearComponentText(meta, "itemName");
            clearComponentLore(meta);
        });
        return icon.serialize();
    }

    public static ItemStack resolveIconSource(ItemStack source) {
        if (source == null || source.getType().isAir()) return source;
        ItemStack ySkinSource = resolveYiyunCeSkinIcon(source);
        return ySkinSource != null ? ySkinSource : source;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void hideVanillaTooltipDetails(ItemMeta meta) {
        meta.addItemFlags(
                ItemFlag.HIDE_ATTRIBUTES,
                ItemFlag.HIDE_DYE,
                ItemFlag.HIDE_ARMOR_TRIM,
                ItemFlag.HIDE_ITEM_SPECIFICS,
                ItemFlag.HIDE_ADDITIONAL_TOOLTIP
        );
    }

    private static void stripEnchantments(ItemMeta meta) {
        for (var enchantment : new ArrayList<>(meta.getEnchants().keySet())) {
            meta.removeEnchant(enchantment);
        }
        if (meta instanceof EnchantmentStorageMeta storageMeta) {
            for (var enchantment : new ArrayList<>(storageMeta.getStoredEnchants().keySet())) {
                storageMeta.removeStoredEnchant(enchantment);
            }
        }
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_STORED_ENCHANTS);
        hideVanillaTooltipDetails(meta);
        setEnchantmentGlintOverride(meta, false);
    }

    private static void setEnchantmentGlintOverride(ItemMeta meta, boolean enabled) {
        try {
            Method method = ItemMeta.class.getMethod("setEnchantmentGlintOverride", Boolean.class);
            method.invoke(meta, enabled);
        } catch (Exception ignored) {}
    }

    private static void clearComponentText(ItemMeta meta, String methodName) {
        try {
            Method method = meta.getClass().getMethod(methodName, net.kyori.adventure.text.Component.class);
            method.invoke(meta, new Object[]{null});
        } catch (Exception ignored) {}
    }

    private static void clearComponentLore(ItemMeta meta) {
        try {
            Method method = meta.getClass().getMethod("lore", List.class);
            method.invoke(meta, new Object[]{null});
        } catch (Exception ignored) {}
    }

    /** 从物品检测 CE/IA/Oraxen 物品 ID */
    public static String detectPluginItemId(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        if (Bukkit.getPluginManager().isPluginEnabled("CraftEngine")) {
            String ceId = detectCeId(item);
            if (ceId != null) return "ce:" + ceId;
        }
        if (Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) {
            String iaId = detectItemsAdderId(item);
            if (iaId != null) return "ia:" + iaId;
        }
        if (Bukkit.getPluginManager().isPluginEnabled("Oraxen")) {
            String oraxenId = detectOraxenId(item);
            if (oraxenId != null) return "oraxen:" + oraxenId;
        }
        return null;
    }

    private static ItemStack resolvePluginItem(String pluginItem) {
        return resolvePluginItem(pluginItem, null);
    }

    private static ItemStack resolvePluginItem(String pluginItem, Map<String, String> arguments) {
        pluginItem = normalizePluginItemReference(pluginItem);
        if (pluginItem == null || pluginItem.isEmpty()) return null;
        boolean cacheable = arguments == null || arguments.isEmpty();
        if (cacheable) {
            ItemStack cached = PLUGIN_ITEM_CACHE.get(pluginItem);
            if (cached != null) return cached.clone();
        }
        int colon = pluginItem.indexOf(':');
        if (colon <= 0) return null;
        String prefix = pluginItem.substring(0, colon);
        String id = pluginItem.substring(colon + 1);
        ItemStack resolved = null;
        if ("ce".equals(prefix) || "craftengine".equals(prefix)) resolved = resolveCeItem(id, arguments);
        if ("ia".equals(prefix) || "itemsadder".equals(prefix)) resolved = resolveItemsAdderItem(id);
        if ("oraxen".equals(prefix)) resolved = resolveOraxenItem(id);
        if (resolved == null && WARNED_PLUGIN_ITEMS.add(pluginItem)) {
            LOG.warning("自定义菜单图标解析失败: " + pluginItem
                    + "。请确认对应插件已启用、物品ID存在，并且ID使用英文冒号。");
        }
        if (resolved == null) return null;
        if (cacheable) {
            PLUGIN_ITEM_CACHE.put(pluginItem, resolved.clone());
        }
        return resolved;
    }

    private static String normalizePluginItemReference(String value) {
        if (value == null) return null;
        String normalized = value.trim().replace('：', ':');
        if (normalized.isEmpty()) return null;

        int colon = normalized.indexOf(':');
        if (colon <= 0 || colon >= normalized.length() - 1) return null;

        String prefix = normalized.substring(0, colon).toLowerCase(java.util.Locale.ROOT);
        String id = normalized.substring(colon + 1).trim();
        if (id.isEmpty()) return null;

        return switch (prefix) {
            case "ce", "craftengine" -> "ce:" + id;
            case "ia", "itemsadder" -> "ia:" + id;
            case "oraxen" -> "oraxen:" + id;
            default -> null;
        };
    }

    private static boolean isCraftEngineItem(String pluginItem) {
        if (pluginItem == null) return false;
        String normalized = pluginItem.toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("ce:") || normalized.startsWith("craftengine:");
    }

    private static String detectCeId(ItemStack item) {
        try {
            Class<?> apiClass = findPluginClass("net.momirealms.craftengine.bukkit.api.CraftEngineItems");
            if (apiClass == null) return null;
            for (String methodName : List.of("getCustomItemId", "getCustomItemID", "getItemId", "getItemID")) {
                Object result = invokeStaticIfCompatible(findMethod(apiClass, methodName, 1), item);
                if (result != null) return normalizeNamespacedId(result.toString());
            }
            Object customItem = invokeStaticIfCompatible(findMethod(apiClass, "getCustomItem", 1), item);
            if (customItem == null) {
                customItem = invokeStaticIfCompatible(findMethod(apiClass, "byItemStack", 1), item);
            }
            if (customItem != null) {
                String id = extractId(customItem);
                if (id != null) return normalizeNamespacedId(id);
            }
        } catch (Exception ignored) {}
        return detectCeIdFromPersistentData(item);
    }

    private static String normalizeNamespacedId(String id) {
        if (id == null) return null;
        id = id.trim();
        if (id.isEmpty()) return null;
        if (id.startsWith("craftengine:")) return id.substring("craftengine:".length());
        if (id.startsWith("ce:")) return id.substring("ce:".length());
        return id;
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

    private static String detectCeIdFromPersistentData(ItemStack item) {
        if (!item.hasItemMeta()) return null;
        try {
            var data = item.getItemMeta().getPersistentDataContainer();
            for (var key : data.getKeys()) {
                String namespace = key.getNamespace().toLowerCase();
                String keyName = key.getKey().toLowerCase();
                if (!namespace.contains("craftengine") && !namespace.equals("ce")) continue;
                if (!keyName.contains("id") && !keyName.contains("item")) continue;
                String value = data.get(key, org.bukkit.persistence.PersistentDataType.STRING);
                if (value != null && !value.isBlank()) return normalizeNamespacedId(value);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 通过 CE 公共 Bukkit API 构建物品 */
    private static ItemStack resolveYiyunCeSkinIcon(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        try {
            Class<?> apiEntry = findPluginClass("cn.yiyun.craftengine.skin.YiyunCESkin");
            if (apiEntry == null) return null;

            Method apiMethod = findMethod(apiEntry, "api", 0);
            Object api = apiMethod != null ? apiMethod.invoke(null) : null;
            if (api == null) return null;

            Method getAppliedSkin = findMethod(api.getClass(), "getAppliedSkin", 1);
            if (getAppliedSkin == null) {
                getAppliedSkin = findMethod(apiEntry, "getAppliedSkin", 1);
            }
            Object skin = getAppliedSkin != null ? getAppliedSkin.invoke(api, item) : null;
            if (skin == null) return null;

            Boolean isCe = invokeBoolean(skin, "isCe");
            if (!Boolean.TRUE.equals(isCe)) return item.clone();

            ItemStack sourceItem = invokeItemStack(skin, "getCeSourceItem");
            if (sourceItem != null && !sourceItem.getType().isAir()) return sourceItem.clone();

            String id = invokeString(skin, "id");
            ItemStack resolved = resolveYiyunItem(api, id);
            if (resolved != null) return resolved;

            String ceId = invokeString(skin, "ceId");
            if (hasText(ceId)) {
                resolved = resolveYiyunItem(api, "craftengine:" + ceId);
                if (resolved != null) return resolved;
                resolved = resolveCeItem(ceId);
                if (resolved != null) return resolved;
            }

            return item.clone();
        } catch (Exception | LinkageError ignored) {
        }
        return null;
    }

    private static Class<?> findPluginClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException | LinkageError ignored) {
        }
        for (org.bukkit.plugin.Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (!plugin.isEnabled()) continue;
            try {
                return Class.forName(className, true, plugin.getClass().getClassLoader());
            } catch (ClassNotFoundException | LinkageError ignored) {
            }
        }
        return null;
    }

    private static ItemStack resolveYiyunItem(Object api, String id) {
        if (api == null || !hasText(id)) return null;
        try {
            Method resolveItem = findMethod(api.getClass(), "resolveItem", 1);
            Object result = resolveItem != null ? resolveItem.invoke(api, id) : null;
            if (result instanceof ItemStack stack && !stack.getType().isAir()) {
                return stack.clone();
            }
        } catch (Exception | LinkageError ignored) {
        }
        return null;
    }

    private static Boolean invokeBoolean(Object target, String methodName) {
        try {
            Method method = findMethod(target.getClass(), methodName, 0);
            Object result = method != null ? method.invoke(target) : null;
            return result instanceof Boolean value ? value : null;
        } catch (Exception | LinkageError ignored) {
            return null;
        }
    }

    private static String invokeString(Object target, String methodName) {
        try {
            Method method = findMethod(target.getClass(), methodName, 0);
            Object result = method != null ? method.invoke(target) : null;
            return result != null ? result.toString() : null;
        } catch (Exception | LinkageError ignored) {
            return null;
        }
    }

    private static ItemStack invokeItemStack(Object target, String methodName) {
        try {
            Method method = findMethod(target.getClass(), methodName, 0);
            Object result = method != null ? method.invoke(target) : null;
            return result instanceof ItemStack stack ? stack : null;
        } catch (Exception | LinkageError ignored) {
            return null;
        }
    }

    private static ItemStack resolveCeItem(String id) {
        return resolveCeItem(id, null);
    }

    private static ItemStack resolveCeItem(String id, Map<String, String> arguments) {
        if (!Bukkit.getPluginManager().isPluginEnabled("CraftEngine")) return null;
        try {
            // CraftEngineItems.byId("namespace:id") → BukkitItemDefinition
            Class<?> apiClass = findPluginClass("net.momirealms.craftengine.bukkit.api.CraftEngineItems");
            if (apiClass == null) return null;
            Object definition = null;
            for (String methodName : List.of("byId", "byID", "getById", "getByID", "getCustomItem", "getItem")) {
                Method method = findMethod(apiClass, methodName, 1);
                if (method == null || method.getParameterTypes()[0] != String.class) continue;
                definition = method.invoke(null, id);
                if (definition == null && !id.contains(":")) {
                    definition = method.invoke(null, "craftengine:" + id);
                }
                if (definition != null) break;
            }
            if (definition == null) return null;

            ItemStack contextStack = buildCeBukkitItem(definition, arguments);
            if (contextStack != null) return contextStack;

            ItemStack apiStack = extractBukkitItemStack(definition);
            if (apiStack != null) return apiStack;

            // 反射读取 definition.item 字段（CE内部物品对象）
            var itemField = definition.getClass().getDeclaredField("item");
            itemField.setAccessible(true);
            Object ceItemObj = itemField.get(definition);
            if (ceItemObj == null) return null;

            // ItemStackProxy.INSTANCE.newInstance(ceItemObj, 1) → NMS ItemStack
            Class<?> proxyClass = Class.forName("net.momirealms.craftengine.proxy.minecraft.world.item.ItemStackProxy");
            Object instance = proxyClass.getField("INSTANCE").get(null);
            var newInstanceMethod = proxyClass.getMethod("newInstance", Object.class, int.class);
            Object nmsStack = newInstanceMethod.invoke(instance, ceItemObj, 1);

            // ItemStackUtils.getBukkitStack(nmsStack) → Bukkit ItemStack
            Class<?> utilsClass = Class.forName("net.momirealms.craftengine.bukkit.util.ItemStackUtils");
            var getBukkitStack = utilsClass.getMethod("getBukkitStack", nmsStack.getClass());
            return (ItemStack) getBukkitStack.invoke(null, nmsStack);
        } catch (Exception e) {
            LOG.log(Level.FINE, "CE物品构建失败 " + id + ": " + e.getMessage());
            return null;
        }
    }

    private static ItemStack buildCeBukkitItem(Object definition, Map<String, String> arguments) {
        if (definition == null || arguments == null || arguments.isEmpty()) return null;
        try {
            Class<?> contextKeyClass = findPluginClass("net.momirealms.craftengine.core.plugin.context.ContextKey");
            Class<?> contextHolderClass = findPluginClass("net.momirealms.craftengine.core.plugin.context.ContextHolder");
            Class<?> itemBuildContextClass = findPluginClass("net.momirealms.craftengine.core.item.ItemBuildContext");
            if (contextKeyClass == null || contextHolderClass == null || itemBuildContextClass == null) return null;

            Object builder = contextHolderClass.getMethod("builder").invoke(null);
            Method chainMethod = contextKeyClass.getMethod("chain", String.class);
            Method directMethod = contextKeyClass.getMethod("direct", String.class);
            Method withParameter = builder.getClass().getMethod("withParameter", contextKeyClass, Object.class);

            for (Map.Entry<String, String> entry : arguments.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (!hasText(key) || value == null) continue;
                withParameter.invoke(builder, chainMethod.invoke(null, key), value);
                withParameter.invoke(builder, directMethod.invoke(null, key), value);
            }

            Class<?> cePlayerClass = findPluginClass("net.momirealms.craftengine.core.entity.player.Player");
            Object context = itemBuildContextClass
                    .getMethod("of", cePlayerClass, builder.getClass())
                    .invoke(null, resolveCePlayer(arguments, cePlayerClass), builder);

            for (String methodName : List.of("buildBukkitItem", "build")) {
                ItemStack item = invokeCeBuildMethod(definition, methodName, itemBuildContextClass, context, 1);
                if (item != null && !item.getType().isAir()) return item.clone();
            }
        } catch (Exception | LinkageError ignored) {
        }
        return null;
    }

    private static Object createCeBuildContext(Class<?> contextClass, Map<String, String> arguments) {
        try {
            Class<?> contextKeyClass = findPluginClass("net.momirealms.craftengine.core.plugin.context.ContextKey");
            Class<?> contextHolderClass = findPluginClass("net.momirealms.craftengine.core.plugin.context.ContextHolder");
            Class<?> cePlayerClass = findPluginClass("net.momirealms.craftengine.core.entity.player.Player");
            if (contextClass == null || contextKeyClass == null || contextHolderClass == null || cePlayerClass == null) {
                return null;
            }

            Object builder = contextHolderClass.getMethod("builder").invoke(null);
            Method chainMethod = contextKeyClass.getMethod("chain", String.class);
            Method directMethod = contextKeyClass.getMethod("direct", String.class);
            Method withParameter = builder.getClass().getMethod("withParameter", contextKeyClass, Object.class);

            if (arguments != null) {
                for (Map.Entry<String, String> entry : arguments.entrySet()) {
                    String key = entry.getKey();
                    String value = entry.getValue();
                    if (!hasText(key) || value == null) continue;
                    withParameter.invoke(builder, chainMethod.invoke(null, key), value);
                    withParameter.invoke(builder, directMethod.invoke(null, key), value);
                }
            }

            return contextClass
                    .getMethod("of", cePlayerClass, builder.getClass())
                    .invoke(null, resolveCePlayer(arguments, cePlayerClass), builder);
        } catch (Exception | LinkageError ignored) {
            return null;
        }
    }

    private static Object resolveCePlayer(Map<String, String> arguments, Class<?> cePlayerClass) {
        if (arguments == null || cePlayerClass == null) return null;
        String playerName = arguments.get("player.name");
        if (!hasText(playerName)) return null;
        Player player = Bukkit.getPlayerExact(playerName);
        if (player == null) return null;
        try {
            Class<?> adaptorClass = findPluginClass("net.momirealms.craftengine.bukkit.api.BukkitAdaptor");
            if (adaptorClass == null) return null;
            Object adapted = adaptorClass.getMethod("adapt", Player.class).invoke(null, player);
            return cePlayerClass.isInstance(adapted) ? adapted : null;
        } catch (Exception | LinkageError ignored) {
            return null;
        }
    }

    private static ItemStack applyCeArguments(ItemStack item, Map<String, String> arguments) {
        if (item == null || item.getType().isAir() || arguments == null || arguments.isEmpty()
                || !Bukkit.getPluginManager().isPluginEnabled("CraftEngine")) {
            return item;
        }
        try {
            Class<?> adaptorClass = findPluginClass("net.momirealms.craftengine.bukkit.api.BukkitAdaptor");
            Class<?> itemClass = findPluginClass("net.momirealms.craftengine.core.item.Item");
            if (adaptorClass == null || itemClass == null) return item;

            Object ceItem = adaptorClass.getMethod("adapt", ItemStack.class).invoke(null, item);
            if (ceItem == null) return item;

            Map<String, String> cleanArguments = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : arguments.entrySet()) {
                if (hasText(entry.getKey()) && entry.getValue() != null) {
                    cleanArguments.put(entry.getKey(), entry.getValue());
                }
            }
            if (cleanArguments.isEmpty()) return item;

            itemClass.getMethod("setJavaTag", Object.class, Object[].class)
                    .invoke(ceItem, cleanArguments, new Object[]{"craftengine:arguments"});
            Object bukkitItem = ceItem.getClass().getMethod("getBukkitItem").invoke(ceItem);
            return bukkitItem instanceof ItemStack stack ? stack.clone() : item;
        } catch (Exception | LinkageError ignored) {
            return item;
        }
    }

    private static ItemStack invokeCeBuildMethod(Object definition, String methodName,
                                                 Class<?> itemBuildContextClass, Object context, int amount) {
        try {
            Method method = definition.getClass().getMethod(methodName, itemBuildContextClass, int.class);
            Object result = method.invoke(definition, context, amount);
            if (result instanceof ItemStack stack) return stack;
        } catch (Exception ignored) {}

        try {
            Method method = definition.getClass().getMethod(methodName, itemBuildContextClass);
            Object result = method.invoke(definition, context);
            if (result instanceof ItemStack stack) return stack;
            ItemStack converted = extractBukkitItemStack(result);
            if (converted != null) return converted;
        } catch (Exception ignored) {}

        return null;
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

    // ── ItemsAdder ──

    private static String detectItemsAdderId(ItemStack item) {
        try {
            var c = findPluginClass("dev.lone.itemsadder.api.CustomStack");
            if (c == null) return null;
            var m = c.getMethod("byItemStack", ItemStack.class);
            Object r = m.invoke(null, item);
            if (r != null) return (String) r.getClass().getMethod("getNamespacedID").invoke(r);
        } catch (Exception ignored) {}
        return null;
    }

    private static ItemStack resolveItemsAdderItem(String id) {
        try {
            var c = findPluginClass("dev.lone.itemsadder.api.CustomStack");
            if (c == null) return null;
            var m = c.getMethod("getInstance", String.class);
            Object stack = m.invoke(null, id);
            if (stack != null)
                return ((ItemStack) stack.getClass().getMethod("getItemStack").invoke(stack)).clone();
        } catch (Exception ignored) {}
        return null;
    }

    // ── Oraxen ──

    private static String detectOraxenId(ItemStack item) {
        try {
            var c = findPluginClass("io.th0rgal.oraxen.items.OraxenItems");
            if (c == null) return null;
            return (String) c.getMethod("getIdByItem", ItemStack.class).invoke(null, item);
        } catch (Exception ignored) {}
        return null;
    }

    private static ItemStack resolveOraxenItem(String id) {
        try {
            var c = findPluginClass("io.th0rgal.oraxen.items.OraxenItems");
            if (c == null) return null;
            var m = c.getMethod("getItemById", String.class);
            Object builder = m.invoke(null, id);
            if (builder != null) return (ItemStack) builder.getClass().getMethod("build").invoke(builder);
        } catch (Exception ignored) {}
        return null;
    }

    private static java.lang.reflect.Method findMethod(Class<?> c, String name, int paramCount) {
        for (var m : c.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                setAccessible(m);
                return m;
            }
        }
        for (var m : c.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                setAccessible(m);
                return m;
            }
        }
        return null;
    }

    private static void setAccessible(Method method) {
        try {
            method.setAccessible(true);
        } catch (Exception ignored) {
        }
    }

    // ── 通用工具 ──

    public static ItemStack replacePlaceholders(ItemStack item, String search, String replace) {
        ItemStack clone = item.clone();
        ItemMeta meta = clone.getItemMeta();
        String safeReplace = replace != null ? replace : "";
        if (meta.hasDisplayName()) {
            meta.setDisplayName(colorizeIfNeeded(meta.getDisplayName().replace(search, safeReplace)));
        }
        if (meta.hasLore()) {
            List<String> newLore = new ArrayList<>();
            for (String line : meta.getLore()) {
                String replaced = line.replace(search, safeReplace);
                if (replaced.contains("\n") || replaced.contains("\r")) {
                    for (String splitLine : replaced.split("\\R", -1)) {
                        newLore.add(colorizeIfNeeded(splitLine));
                    }
                } else {
                    newLore.add(colorizeIfNeeded(replaced));
                }
            }
            meta.setLore(newLore);
        }
        clone.setItemMeta(meta);
        return clone;
    }

    public static ItemStack replacePlaceholdersDefaultWhite(ItemStack item, String search, String replace) {
        ItemStack clone = item.clone();
        ItemMeta meta = clone.getItemMeta();
        String safeReplace = replace != null ? replace : "";
        if (meta.hasDisplayName()) {
            String displayName = replacePlaceholderWithDefaultColor(
                    meta.getDisplayName(), search, safeReplace, "§f");
            meta.setDisplayName(colorizeIfNeeded(displayName));
        }
        if (meta.hasLore()) {
            List<String> newLore = new ArrayList<>();
            for (String line : meta.getLore()) {
                String replaced = replacePlaceholderWithDefaultColor(line, search, safeReplace, "§f");
                if (replaced.contains("\n") || replaced.contains("\r")) {
                    for (String splitLine : replaced.split("\\R", -1)) {
                        newLore.add(colorizeIfNeeded(splitLine));
                    }
                } else {
                    newLore.add(colorizeIfNeeded(replaced));
                }
            }
            meta.setLore(newLore);
        }
        clone.setItemMeta(meta);
        return clone;
    }

    private static String replacePlaceholderWithDefaultColor(
            String line,
            String search,
            String replace,
            String defaultColor
    ) {
        if (line == null || search == null || search.isEmpty()) return line;
        StringBuilder result = new StringBuilder();
        int start = 0;
        int index;
        while ((index = line.indexOf(search, start)) >= 0) {
            result.append(line, start, index);
            String replacement = replace;
            if (!hasColorCodeImmediatelyBefore(line, index)) {
                replacement = applyDefaultColor(replacement, defaultColor);
            }
            result.append(replacement);
            start = index + search.length();
        }
        result.append(line, start, line.length());
        return result.toString();
    }

    private static String applyDefaultColor(String replacement, String defaultColor) {
        if (replacement == null || replacement.isEmpty() || startsWithExplicitColor(replacement)) {
            return replacement;
        }
        String[] lines = replacement.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].isEmpty() && !startsWithExplicitColor(lines[i])) {
                int insertAt = leadingResetEnd(lines[i]);
                lines[i] = lines[i].substring(0, insertAt)
                        + defaultColor
                        + lines[i].substring(insertAt);
            }
        }
        return String.join("\n", lines);
    }

    private static int leadingResetEnd(String text) {
        int index = 0;
        while (index + 1 < text.length() && isLegacyColorPrefix(text.charAt(index))) {
            char code = text.charAt(index + 1);
            if (code != 'r' && code != 'R') break;
            index += 2;
        }
        return index;
    }

    private static boolean hasColorCodeImmediatelyBefore(String text, int index) {
        return index >= 2
                && isLegacyColorPrefix(text.charAt(index - 2))
                && isLegacyColor(text.charAt(index - 1));
    }

    private static boolean startsWithExplicitColor(String text) {
        int index = 0;
        while (index + 1 < text.length() && isLegacyColorPrefix(text.charAt(index))) {
            char code = text.charAt(index + 1);
            if (isLegacyColor(code)) return true;
            if (code != 'r' && code != 'R'
                    && "klmnoKLMNO".indexOf(code) < 0) {
                return false;
            }
            index += 2;
        }
        if (text.regionMatches(true, index, "&#", 0, 2)
                && index + 8 <= text.length()) {
            return isHexColor(text, index + 2);
        }
        return text.regionMatches(true, index, "<#", 0, 2)
                && index + 9 <= text.length()
                && isHexColor(text, index + 2)
                && text.charAt(index + 8) == '>';
    }

    private static boolean isLegacyColorPrefix(char c) {
        return c == '&' || c == '§';
    }

    private static boolean isLegacyColor(char c) {
        return "0123456789abcdefABCDEF".indexOf(c) >= 0;
    }

    private static boolean isHexColor(String text, int start) {
        if (start + 6 > text.length()) return false;
        for (int i = start; i < start + 6; i++) {
            char c = text.charAt(i);
            boolean hex = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    private static String colorizeIfNeeded(String text) {
        if (text == null) return "";
        for (int i = 0; i < text.length() - 1; i++) {
            if (text.charAt(i) == '&'
                    && "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(text.charAt(i + 1)) >= 0) {
                return MessageUtil.colorize(text);
            }
        }
        return text;
    }
}
