package com.alwarp.gui.shape;

import java.util.List;
import java.util.Map;

/**
 * 菜单配置POJO。
 * 对应 menus.yml 中单个菜单的定义。
 */
public class MenuConfig {

    private String title;
    private String adminTitle;
    private List<String> shape;
    private List<String> adminShape;
    private Map<String, MenuItem> items;
    private Map<String, ColorOption> colors;
    private String boldPermission;

    public MenuConfig() {
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAdminTitle() {
        return adminTitle;
    }

    public void setAdminTitle(String adminTitle) {
        this.adminTitle = adminTitle;
    }

    public List<String> getShape() {
        return shape;
    }

    public void setShape(List<String> shape) {
        this.shape = shape;
    }

    public List<String> getAdminShape() {
        return adminShape;
    }

    public void setAdminShape(List<String> adminShape) {
        this.adminShape = adminShape;
    }

    public Map<String, MenuItem> getItems() {
        return items;
    }

    public void setItems(Map<String, MenuItem> items) {
        this.items = items;
    }

    public Map<String, ColorOption> getColors() {
        return colors;
    }

    public void setColors(Map<String, ColorOption> colors) {
        this.colors = colors;
    }

    public String getBoldPermission() {
        return boldPermission;
    }

    public void setBoldPermission(String boldPermission) {
        this.boldPermission = boldPermission;
    }
}
