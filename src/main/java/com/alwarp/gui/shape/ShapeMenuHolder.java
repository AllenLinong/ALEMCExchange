package com.alwarp.gui.shape;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.List;

/**
 * Shape菜单的InventoryHolder。
 * Paper 1.21+ 处理拖拽时通过 Holder 的 getInventory() 验证一致性，
 * 必须持有真实 Inventory 引用，否则拖拽保护失效导致物品可被取走。
 */
public class ShapeMenuHolder implements InventoryHolder {

    private final String menuName;
    private final List<String> shape;
    private Inventory inventory;

    public ShapeMenuHolder(String menuName) {
        this(menuName, null);
    }

    public ShapeMenuHolder(String menuName, List<String> shape) {
        this.menuName = menuName;
        this.shape = shape;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    void setInventory(Inventory inv) {
        this.inventory = inv;
    }

    public String getMenuName() {
        return menuName;
    }

    public List<String> getShape() {
        return shape;
    }
}
