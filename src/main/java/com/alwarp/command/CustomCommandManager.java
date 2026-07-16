package com.alwarp.command;

import com.alwarp.ALwarp;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Registers configurable root command aliases such as /pw at runtime.
 */
public final class CustomCommandManager {

    private static final String CONFIG_ENABLED = "custom_commands.enabled";
    private static final String CONFIG_ALIASES = "custom_commands.aliases";
    private static final String CUSTOM_COMMAND_DESCRIPTION = "ALwarp custom command";
    private static final Pattern COMMAND_NAME = Pattern.compile("[a-z0-9_-]+");

    private final ALwarp plugin;
    private final ALwarpCommand commandExecutor;
    private final Map<String, Command> registeredCommands = new LinkedHashMap<>();
    private volatile Set<String> configuredLabelCache = Set.of();
    private boolean executingCustomCommand;
    private boolean reloadQueued;

    public CustomCommandManager(ALwarp plugin, ALwarpCommand commandExecutor) {
        this.plugin = plugin;
        this.commandExecutor = commandExecutor;
    }

    public void reload() {
        boolean enabled = plugin.getConfig().getBoolean(CONFIG_ENABLED, true);
        Set<String> configuredLabels = new LinkedHashSet<>();
        if (enabled) {
            configuredLabels.addAll(readConfiguredLabels());
        }
        configuredLabelCache = Set.copyOf(configuredLabels);

        if (executingCustomCommand) {
            if (!reloadQueued) {
                reloadQueued = true;
                plugin.getScheduler().runTaskLater(() -> {
                    reloadQueued = false;
                    reload();
                }, 1L);
            }
            return;
        }

        CommandMap commandMap = Bukkit.getCommandMap();

        if (!enabled) {
            List<String> unregisteredLabels = unregisterUnconfiguredCommands(commandMap, configuredLabels);
            syncCommandTree();
            logUnregistered(unregisteredLabels);
            plugin.logInfo("自定义命令: 已关闭", "Custom commands: disabled");
            return;
        }

        List<String> unregisteredLabels = unregisterUnconfiguredCommands(commandMap, configuredLabels);
        List<String> existingLabels = new ArrayList<>();
        List<String> registeredLabels = new ArrayList<>();

        for (String label : configuredLabels) {
            Command ownedCommand = findOwnedCustomCommand(commandMap, label);
            if (ownedCommand != null) {
                registeredCommands.put(label, ownedCommand);
                existingLabels.add("/" + label);
                continue;
            }

            Command existingCommand = commandMap.getCommand(label);
            if (existingCommand != null && !isOwnedByThisPlugin(existingCommand)) {
                String ownerZh = describeCommandOwner(existingCommand, false);
                String ownerEn = describeCommandOwner(existingCommand, true);
                plugin.logWarning("自定义命令 /" + label + " 已被 " + ownerZh + " 占用，已跳过",
                        "Custom command /" + label + " is already registered by " + ownerEn + "; skipped");
                continue;
            }

            removeOwnedCommandKeys(commandMap, label);
            Command command = new CustomRootCommand(label);
            commandMap.register(plugin.getName().toLowerCase(Locale.ROOT), command);
            forcePrimaryCommandLabel(commandMap, label, command);
            if (commandMap.getCommand(label) == command) {
                registeredCommands.put(label, command);
                registeredLabels.add("/" + label);
            } else {
                Command current = commandMap.getCommand(label);
                String ownerZh = current != null ? describeCommandOwner(current, false) : "未知";
                String ownerEn = current != null ? describeCommandOwner(current, true) : "unknown";
                unregister(commandMap, command);
                plugin.logWarning("自定义命令 /" + label + " 注册失败，当前占用: " + ownerZh + "，已跳过",
                        "Custom command /" + label + " registration failed; current owner: " + ownerEn + "; skipped");
            }
        }

        syncCommandTree();
        logUnregistered(unregisteredLabels);
        if (!registeredLabels.isEmpty()) {
            plugin.logInfo("自定义命令: 已注册 " + String.join(", ", registeredLabels),
                    "Custom commands: registered " + String.join(", ", registeredLabels));
        }
        if (!existingLabels.isEmpty()) {
            plugin.logInfo("自定义命令: 已存在 " + String.join(", ", existingLabels) + "，本次未注册新命令",
                    "Custom commands: already registered " + String.join(", ", existingLabels)
                            + "; no new commands registered");
        }
        if (registeredLabels.isEmpty() && existingLabels.isEmpty()) {
            plugin.logInfo("自定义命令: 未注册任何命令",
                    "Custom commands: no commands registered");
        }
    }

    public void unregisterAll() {
        unregisterAll(true);
    }

    private void unregisterAll(boolean sync) {
        CommandMap commandMap = Bukkit.getCommandMap();
        Set<Command> commands = new LinkedHashSet<>(registeredCommands.values());
        commands.addAll(findRegisteredCustomCommands(commandMap));

        for (Command command : commands) {
            unregister(commandMap, command);
        }
        registeredCommands.clear();

        if (sync) {
            syncCommandTree();
        }
    }

    private List<String> unregisterUnconfiguredCommands(CommandMap commandMap, Set<String> configuredLabels) {
        List<String> unregisteredLabels = new ArrayList<>();
        Set<Command> commands = new LinkedHashSet<>(registeredCommands.values());
        commands.addAll(findRegisteredCustomCommands(commandMap));

        for (Command command : commands) {
            String label = normalizeLabel(command.getName());
            if (label == null) {
                label = normalizeLabel(command.getLabel());
            }
            if (label != null && configuredLabels.contains(label) && isActiveCustomCommand(command)) {
                registeredCommands.put(label, command);
                continue;
            }

            unregister(commandMap, command);
            if (label != null) {
                registeredCommands.remove(label);
                removeOwnedCommandKeys(commandMap, label);
                unregisteredLabels.add("/" + label);
            }
        }

        registeredCommands.keySet().removeIf(label -> !configuredLabels.contains(label));
        return unregisteredLabels;
    }

    private Command findOwnedCustomCommand(CommandMap commandMap, String label) {
        String fallbackLabel = plugin.getName().toLowerCase(Locale.ROOT) + ":" + label;
        Map<String, Command> knownCommands = getMutableKnownCommands(commandMap);

        for (Map.Entry<String, Command> entry : knownCommands.entrySet()) {
            String key = entry.getKey();
            Command command = entry.getValue();
            if (!isCustomCommandOwnedByThisPlugin(command)) {
                continue;
            }
            if (matchesCommandLabel(key, command, label, fallbackLabel)) {
                if (!isActiveCustomCommand(command)) {
                    unregister(commandMap, command);
                    removeOwnedCommandKeys(commandMap, label);
                    continue;
                }
                return command;
            }
        }

        return null;
    }

    private void removeOwnedCommandKeys(CommandMap commandMap, String label) {
        String fallbackLabel = plugin.getName().toLowerCase(Locale.ROOT) + ":" + label;
        try {
            getMutableKnownCommands(commandMap).entrySet().removeIf(entry -> {
                String key = entry.getKey();
                Command command = entry.getValue();
                return isOwnedByThisPlugin(command) && matchesCommandLabel(key, command, label, fallbackLabel);
            });
        } catch (UnsupportedOperationException ignored) {
            // Some command map implementations may expose a read-only view.
        }
    }

    private boolean matchesCommandLabel(String key, Command command, String label, String fallbackLabel) {
        return label.equalsIgnoreCase(key)
                || fallbackLabel.equalsIgnoreCase(key)
                || label.equalsIgnoreCase(normalizeLabel(command.getName()))
                || label.equalsIgnoreCase(normalizeLabel(command.getLabel()));
    }

    private void forcePrimaryCommandLabel(CommandMap commandMap, String label, Command command) {
        Command current = commandMap.getCommand(label);
        if (current != null && current != command) {
            if (!isOwnedByThisPlugin(current)) {
                return;
            }
            unregister(commandMap, current);
        }

        String fallbackLabel = plugin.getName().toLowerCase(Locale.ROOT) + ":" + label;
        Map<String, Command> knownCommands = getMutableKnownCommands(commandMap);
        knownCommands.put(label, command);
        knownCommands.put(fallbackLabel, command);
        command.register(commandMap);
    }

    private void unregister(CommandMap commandMap, Command command) {
        if (command instanceof CustomRootCommand customRootCommand) {
            customRootCommand.deactivate();
        }
        command.unregister(commandMap);
        try {
            getMutableKnownCommands(commandMap).entrySet().removeIf(entry -> entry.getValue() == command);
        } catch (UnsupportedOperationException ignored) {
            // Some command map implementations may expose a read-only view.
        }
    }

    private Set<Command> findRegisteredCustomCommands(CommandMap commandMap) {
        Set<Command> commands = new LinkedHashSet<>();
        for (Command command : getMutableKnownCommands(commandMap).values()) {
            if (isCustomCommandOwnedByThisPlugin(command)) {
                commands.add(command);
            }
        }
        return commands;
    }

    private boolean isCustomCommandOwnedByThisPlugin(Command command) {
        return isOwnedByThisPlugin(command)
                && CUSTOM_COMMAND_DESCRIPTION.equals(command.getDescription());
    }

    private boolean isActiveCustomCommand(Command command) {
        return !(command instanceof CustomRootCommand customRootCommand) || customRootCommand.isActive();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Command> getMutableKnownCommands(CommandMap commandMap) {
        Class<?> type = commandMap.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField("knownCommands");
                field.setAccessible(true);
                Object value = field.get(commandMap);
                if (value instanceof Map<?, ?> map) {
                    return (Map<String, Command>) map;
                }
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
                continue;
            } catch (IllegalAccessException | RuntimeException ignored) {
                break;
            }
            type = type.getSuperclass();
        }
        return commandMap.getKnownCommands();
    }

    private boolean isOwnedByThisPlugin(Command command) {
        if (!(command instanceof PluginIdentifiableCommand pluginCommand)) {
            return false;
        }
        Plugin owner = pluginCommand.getPlugin();
        return owner != null && plugin.getName().equalsIgnoreCase(owner.getName());
    }

    private String describeCommandOwner(Command command, boolean english) {
        if (command instanceof PluginIdentifiableCommand pluginCommand) {
            Plugin owner = pluginCommand.getPlugin();
            if (owner != null) {
                return owner.getName();
            }
        }
        return english ? "server" : "服务端";
    }

    private void logUnregistered(List<String> labels) {
        if (labels == null || labels.isEmpty()) return;
        plugin.logInfo("自定义命令: 已注销 " + String.join(", ", labels),
                "Custom commands: unregistered " + String.join(", ", labels));
    }

    private List<String> readConfiguredLabels() {
        Set<String> labels = new LinkedHashSet<>();
        List<String> configured = plugin.getConfig().isString(CONFIG_ALIASES)
                ? List.of(plugin.getConfig().getString(CONFIG_ALIASES, ""))
                : plugin.getConfig().getStringList(CONFIG_ALIASES);

        for (String raw : configured) {
            String label = normalizeLabel(raw);
            if (label == null) {
                continue;
            }
            if ("alwarp".equals(label)) {
                plugin.logWarning("自定义命令不能重复注册 /alwarp，已跳过",
                        "Custom command /alwarp duplicates the main command; skipped");
                continue;
            }
            if (!COMMAND_NAME.matcher(label).matches()) {
                plugin.logWarning("自定义命令名无效: " + raw + "，仅允许字母、数字、下划线和横线",
                        "Invalid custom command name: " + raw + "; only letters, numbers, underscores and hyphens are allowed");
                continue;
            }
            labels.add(label);
        }

        return new ArrayList<>(labels);
    }

    private String normalizeLabel(String raw) {
        if (raw == null) {
            return null;
        }
        String label = raw.trim().toLowerCase(Locale.ROOT);
        while (label.startsWith("/")) {
            label = label.substring(1);
        }
        int namespaceIndex = label.indexOf(':');
        if (namespaceIndex >= 0 && namespaceIndex < label.length() - 1) {
            label = label.substring(namespaceIndex + 1);
        }
        return label.isBlank() ? null : label;
    }

    private boolean isConfiguredLabel(String label) {
        String normalized = normalizeLabel(label);
        return normalized != null && configuredLabelCache.contains(normalized);
    }

    private void syncCommandTree() {
        syncServerCommands();
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.updateCommands();
        }
    }

    private void syncServerCommands() {
        try {
            Method method;
            try {
                method = Bukkit.getServer().getClass().getMethod("syncCommands");
            } catch (NoSuchMethodException ignored) {
                method = Bukkit.getServer().getClass().getDeclaredMethod("syncCommands");
            }
            method.setAccessible(true);
            method.invoke(Bukkit.getServer());
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Paper/CraftBukkit exposes this at runtime; other platforms can still update players below.
        }
    }

    private final class CustomRootCommand extends Command implements PluginIdentifiableCommand {
        private boolean active = true;

        private CustomRootCommand(String label) {
            super(label);
            setDescription(CUSTOM_COMMAND_DESCRIPTION);
            setUsage("/" + label + " [create|admin|reload|export|import|clearpin|purge]");
        }

        private void deactivate() {
            active = false;
        }

        private boolean isActive() {
            return active;
        }

        @Override
        public boolean execute(CommandSender sender, String commandLabel, String[] args) {
            if (!active || !isConfiguredLabel(commandLabel)) {
                sender.sendMessage(ChatColor.RED + "未知命令。");
                return true;
            }
            executingCustomCommand = true;
            try {
                return commandExecutor.onCommand(sender, this, commandLabel, args);
            } finally {
                executingCustomCommand = false;
            }
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            if (!active || !isConfiguredLabel(alias)) {
                return List.of();
            }
            List<String> completions = commandExecutor.onTabComplete(sender, this, alias, args);
            return completions != null ? completions : List.of();
        }

        @Override
        public Plugin getPlugin() {
            return plugin;
        }
    }
}
