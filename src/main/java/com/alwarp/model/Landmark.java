package com.alwarp.model;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 地标实体类。
 * 对应数据库 landmarks 表。
 */
public class Landmark {

    private int id;
    private String name;
    private String description;
    private String nameColor;
    private String descriptionColor;
    private boolean nameBold;
    private boolean descriptionBold;
    private String icon;
    private Integer iconCustomModelData;
    private String iconPluginItem;
    private Map<String, Object> iconData; // 序列化的物品数据（CE/IA等自定义物品的完整NBT）
    private UUID ownerUuid;
    private String ownerName;
    private String serverName;
    private String world;
    private int x;
    private int y;
    private int z;
    private int categoryId;
    private double price;
    private int visitCount;
    private int weeklyVisits;
    private boolean isPrivate;
    private boolean isGlobal;
    private Timestamp createdAt;
    private Timestamp updatedAt;

    public Landmark() {
    }

    public Landmark(String name, String description, UUID ownerUuid, String ownerName,
                    String serverName, String world, int x, int y, int z, int categoryId, double price,
                    String defaultIcon) {
        this.name = name;
        this.description = description;
        this.icon = defaultIcon;
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.serverName = serverName;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.categoryId = categoryId;
        this.price = price;
        this.visitCount = 0;
        this.weeklyVisits = 0;
        this.isPrivate = false;
        this.isGlobal = true;
    }

    // ─── Getters and Setters ───

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getNameColor() { return nameColor; }
    public void setNameColor(String nameColor) { this.nameColor = nameColor; }

    public String getDescriptionColor() { return descriptionColor; }
    public void setDescriptionColor(String descriptionColor) { this.descriptionColor = descriptionColor; }

    public boolean isNameBold() { return nameBold; }
    public void setNameBold(boolean nameBold) { this.nameBold = nameBold; }

    public boolean isDescriptionBold() { return descriptionBold; }
    public void setDescriptionBold(boolean descriptionBold) { this.descriptionBold = descriptionBold; }

    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }

    public Integer getIconCustomModelData() { return iconCustomModelData; }
    public void setIconCustomModelData(Integer iconCustomModelData) { this.iconCustomModelData = iconCustomModelData; }

    public String getIconPluginItem() { return iconPluginItem; }
    public void setIconPluginItem(String iconPluginItem) { this.iconPluginItem = iconPluginItem; }

    public Map<String, Object> getIconData() { return iconData; }
    public void setIconData(Map<String, Object> iconData) { this.iconData = iconData; }

    public UUID getOwnerUuid() { return ownerUuid; }
    public void setOwnerUuid(UUID ownerUuid) { this.ownerUuid = ownerUuid; }

    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }

    public String getServerName() { return serverName; }
    public void setServerName(String serverName) { this.serverName = serverName; }

    public String getWorld() { return world; }
    public void setWorld(String world) { this.world = world; }

    public int getX() { return x; }
    public void setX(int x) { this.x = x; }

    public int getY() { return y; }
    public void setY(int y) { this.y = y; }

    public int getZ() { return z; }
    public void setZ(int z) { this.z = z; }

    public int getCategoryId() { return categoryId; }
    public void setCategoryId(int categoryId) { this.categoryId = categoryId; }

    public double getPrice() { return price; }
    public void setPrice(double price) { this.price = price; }

    public int getVisitCount() { return visitCount; }
    public void setVisitCount(int visitCount) { this.visitCount = visitCount; }

    public int getWeeklyVisits() { return weeklyVisits; }
    public void setWeeklyVisits(int weeklyVisits) { this.weeklyVisits = weeklyVisits; }

    public boolean isPrivate() { return isPrivate; }
    public void setPrivate(boolean isPrivate) { this.isPrivate = isPrivate; }

    public boolean isGlobal() { return isGlobal; }
    public void setGlobal(boolean isGlobal) { this.isGlobal = isGlobal; }

    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }

    public Timestamp getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Timestamp updatedAt) { this.updatedAt = updatedAt; }

    /** 深拷贝，编辑时用副本避免直接修改缓存 */
    public Landmark copy() {
        Landmark c = new Landmark();
        c.id = this.id;
        c.name = this.name;
        c.description = this.description;
        c.nameColor = this.nameColor;
        c.descriptionColor = this.descriptionColor;
        c.nameBold = this.nameBold;
        c.descriptionBold = this.descriptionBold;
        c.icon = this.icon;
        c.iconCustomModelData = this.iconCustomModelData;
        c.iconPluginItem = this.iconPluginItem;
        c.iconData = this.iconData != null ? new HashMap<>(this.iconData) : null;
        c.ownerUuid = this.ownerUuid;
        c.ownerName = this.ownerName;
        c.serverName = this.serverName;
        c.world = this.world;
        c.x = this.x;
        c.y = this.y;
        c.z = this.z;
        c.categoryId = this.categoryId;
        c.price = this.price;
        c.visitCount = this.visitCount;
        c.weeklyVisits = this.weeklyVisits;
        c.isPrivate = this.isPrivate;
        c.isGlobal = this.isGlobal;
        c.createdAt = this.createdAt;
        c.updatedAt = this.updatedAt;
        return c;
    }

    /**
     * 价格是否有收费（price > 0）。
     */
    public boolean isPaid() {
        return price > 0.0;
    }

    /**
     * 获取价格显示文本。
     */
    public String getPriceDisplay() {
        return price <= 0.0 ? "0" : String.format("%.0f", price);
    }
}
