package com.alwarp.gui.shape;

import java.util.List;
import java.util.Map;

/**
 * 菜单项POJO。对应menus配置中items下的单个物品定义。
 * action = 左键点击、right_action = 右键点击、shift_left_action = Shift+左键、shift_right_action = Shift+右键
 * triggers = 多动作触发器，支持 left/right/shift_left/shift_right。
 */
public class MenuItem {

    private String type;
    private Integer customModelData;
    private String pluginItem;
    private String name;
    private List<String> lore;
    private String action;
    private String rightAction;
    private String shiftLeftAction;
    private String shiftRightAction;
    private Map<String, List<String>> triggers;
    private Double cost;
    private Integer durationDays;
    private Boolean pinSlot;
    private Integer pinSlotId;
    private Boolean isDefault;

    public MenuItem() {}

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Integer getCustomModelData() { return customModelData; }
    public void setCustomModelData(Integer v) { this.customModelData = v; }

    public String getPluginItem() { return pluginItem; }
    public void setPluginItem(String v) { this.pluginItem = v; }

    public String getName() { return name; }
    public void setName(String v) { this.name = v; }

    public List<String> getLore() { return lore; }
    public void setLore(List<String> v) { this.lore = v; }

    public String getAction() { return action; }
    public void setAction(String v) { this.action = v; }

    public String getRightAction() { return rightAction; }
    public void setRightAction(String v) { this.rightAction = v; }

    public String getShiftLeftAction() { return shiftLeftAction; }
    public void setShiftLeftAction(String v) { this.shiftLeftAction = v; }

    public String getShiftRightAction() { return shiftRightAction; }
    public void setShiftRightAction(String v) { this.shiftRightAction = v; }

    public Map<String, List<String>> getTriggers() { return triggers; }
    public void setTriggers(Map<String, List<String>> v) { this.triggers = v; }

    public List<String> getTriggerActions(String trigger) {
        if (triggers == null || trigger == null) return List.of();
        List<String> actions = triggers.get(trigger);
        return actions != null ? actions : List.of();
    }

    public Double getCost() { return cost; }
    public void setCost(Double v) { this.cost = v; }

    public Integer getDurationDays() { return durationDays; }
    public void setDurationDays(Integer v) { this.durationDays = v; }

    public Boolean getPinSlot() { return pinSlot; }
    public void setPinSlot(Boolean v) { this.pinSlot = v; }

    public Integer getPinSlotId() { return pinSlotId; }
    public void setPinSlotId(Integer v) { this.pinSlotId = v; }

    public Boolean getIsDefault() { return isDefault; }
    public void setIsDefault(Boolean v) { this.isDefault = v; }
}
