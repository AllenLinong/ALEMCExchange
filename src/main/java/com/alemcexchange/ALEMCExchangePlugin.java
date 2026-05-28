package com.alemcexchange;

import org.bukkit.plugin.java.JavaPlugin;

public class ALEMCExchangePlugin extends JavaPlugin {

    private PluginInitializer initializer;

    @Override
    public void onEnable() {
        getLogger().info("ALEMCExchange Plugin enabled!");
        initializer = new PluginInitializer(this);
    }

    @Override
    public void onDisable() {
        if (initializer != null) {
            initializer.shutdown();
        }
        getLogger().info("ALEMCExchange Plugin disabled!");
    }

}
