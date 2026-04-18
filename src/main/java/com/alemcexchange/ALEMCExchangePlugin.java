package com.alemcexchange;

import org.bukkit.plugin.java.JavaPlugin;

public class ALEMCExchangePlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("ALEMCExchange Plugin enabled!");
        // 初始化插件
        new PluginInitializer(this);
    }

    @Override
    public void onDisable() {
        getLogger().info("ALEMCExchange Plugin disabled!");
    }

}
