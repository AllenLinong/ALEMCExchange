package com.alwarp.gui.shape;

import java.util.List;

public class ColorOption {

    private String id;
    private String type;
    private Integer customModelData;
    private String pluginItem;
    private String name;
    private List<String> lore;
    private String style;
    private String permission;
    private String sample;
    private Boolean purchaseEnabled;
    private String purchaseCurrency;
    private Double purchasePrice;
    private String purchaseCurrencyName;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Integer getCustomModelData() { return customModelData; }
    public void setCustomModelData(Integer customModelData) { this.customModelData = customModelData; }

    public String getPluginItem() { return pluginItem; }
    public void setPluginItem(String pluginItem) { this.pluginItem = pluginItem; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public List<String> getLore() { return lore; }
    public void setLore(List<String> lore) { this.lore = lore; }

    public String getStyle() { return style; }
    public void setStyle(String style) { this.style = style; }

    public String getPermission() { return permission; }
    public void setPermission(String permission) { this.permission = permission; }

    public String getSample() { return sample; }
    public void setSample(String sample) { this.sample = sample; }

    public Boolean getPurchaseEnabled() { return purchaseEnabled; }
    public void setPurchaseEnabled(Boolean purchaseEnabled) { this.purchaseEnabled = purchaseEnabled; }

    public String getPurchaseCurrency() { return purchaseCurrency; }
    public void setPurchaseCurrency(String purchaseCurrency) { this.purchaseCurrency = purchaseCurrency; }

    public Double getPurchasePrice() { return purchasePrice; }
    public void setPurchasePrice(Double purchasePrice) { this.purchasePrice = purchasePrice; }

    public String getPurchaseCurrencyName() { return purchaseCurrencyName; }
    public void setPurchaseCurrencyName(String purchaseCurrencyName) { this.purchaseCurrencyName = purchaseCurrencyName; }
}
