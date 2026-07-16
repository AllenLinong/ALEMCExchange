package com.alwarp.model;

/**
 * 地标分类实体类。
 * 对应数据库 categories 表。
 */
public class Category {

    private int id;
    private String name;
    private String icon;
    private Integer iconCustomModelData;
    private String iconPluginItem;
    private String color;
    private boolean isDefault;

    public Category() {
    }

    public Category(int id, String name, String icon, String color, boolean isDefault) {
        this.id = id;
        this.name = name;
        this.icon = icon;
        this.color = color;
        this.isDefault = isDefault;
    }

    // ─── Getters and Setters ───

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }

    public Integer getIconCustomModelData() { return iconCustomModelData; }
    public void setIconCustomModelData(Integer iconCustomModelData) { this.iconCustomModelData = iconCustomModelData; }

    public String getIconPluginItem() { return iconPluginItem; }
    public void setIconPluginItem(String iconPluginItem) { this.iconPluginItem = iconPluginItem; }

    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }

    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }

    /**
     * 获取带颜色的分类显示名称。
     */
    public String getDisplayName() {
        return (color != null ? color : "&7") + name;
    }
}
