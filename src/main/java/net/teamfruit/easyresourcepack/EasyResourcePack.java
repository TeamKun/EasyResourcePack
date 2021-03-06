package net.teamfruit.easyresourcepack;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class EasyResourcePack extends JavaPlugin implements Listener {

    public static Logger logger;

    private final Map<UUID, AtomicReference<String>> lastHash = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        // Plugin startup logic
        logger = getLogger();
        saveDefaultConfig();

        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }

    private String get(String[] args, int index) {
        if (args.length > index)
            return args[index];
        return null;
    }

    private AtomicReference<String> getPlayerResourcePackHash(Player player) {
        return lastHash.computeIfAbsent(player.getUniqueId(), id -> new AtomicReference<>());
    }

    private void setResourcePack(Player player, String url, String hash) {
        try {
            player.setResourcePack(url, hash);
        } catch (LinkageError e) {
            player.setResourcePack(url, DatatypeConverter.parseHexBinary(hash));
        }
    }

    private boolean hasResourcePack(Player player) {
        try {
            return player.hasResourcePack();
        } catch (LinkageError e) {
            return false;
        }
    }

    private boolean isResourcePackDeclined(Player player) {
        try {
            return player.getResourcePackStatus() == PlayerResourcePackStatusEvent.Status.DECLINED;
        } catch (LinkageError e) {
            return false;
        }
    }

    @EventHandler
    public void onLogin(PlayerJoinEvent event) {
        Configuration config = getConfig();

        String url = config.getString("packs.server-resourcepack.url");
        String hash = config.getString("packs.server-resourcepack.hash");

        Player player = event.getPlayer();
        AtomicReference<String> lastHash = getPlayerResourcePackHash(player);

        if (url != null && hash != null) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    try {
                        if (isResourcePackDeclined(player))
                            setResourcePack(player, url, hash);
                    } catch (Throwable e) {
                        logger.log(Level.WARNING, "Failed to process Login Event", e);
                    }
                }
            }.runTaskLaterAsynchronously(this, 4);
            lastHash.set(hash);
        } else {
            lastHash.set(null);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String arg0 = get(args, 0);
        String arg1 = get(args, 1);
        String arg2 = get(args, 2);

        if (arg0 == null)
            return false;

        Configuration config = getConfig();

        boolean forceResourcePack = true;

        switch (arg0) {
            case "server-resourcepack": {
                if (!sender.hasPermission("easyresourcepack.manage")) {
                    sender.sendMessage(ChatColor.RED + "No Permission to manage resource pack");
                    return true;
                }

                String url = (arg1 == null) ? null : config.getString(String.format("packs.%s.url", arg1));
                String hash = (arg1 == null) ? null : config.getString(String.format("packs.%s.hash", arg1));

                config.set("packs.server-resourcepack.url", url);
                config.set("packs.server-resourcepack.hash", hash);

                if (url == null || hash == null)
                    config.set("packs.server-resourcepack", null);

                saveConfig();

                sender.sendMessage(ChatColor.GREEN + "Successfully set server resource pack!");
            }
            break;

            case "add": {
                if (arg1 == null)
                    return false;

                if (!sender.hasPermission("easyresourcepack.manage")) {
                    sender.sendMessage(ChatColor.RED + "No Permission to manage resource pack");
                    return true;
                }
                if (arg2 == null)
                    return false;
                sender.sendMessage(ChatColor.GREEN + "Calculating hash...");
                ResourcePackUtils.downloadAndGetHash(arg2).thenAccept(result -> {
                    if (!result.success) {
                        sender.sendMessage(ChatColor.RED + "Failed to get resource pack: " + ChatColor.GRAY + result.reason);
                        return;
                    }

                    config.set(String.format("packs.%s.url", arg1), arg2);
                    config.set(String.format("packs.%s.hash", arg1), result.hash);
                    saveConfig();

                    sender.sendMessage(ChatColor.GREEN + "Successfully registered resource pack!");
                    sender.sendMessage(ChatColor.GREEN + "Description: " + ChatColor.WHITE + result.meta.pack.description);
                });
            }
            break;

            case "remove": {
                if (arg1 == null)
                    return false;

                if (!sender.hasPermission("easyresourcepack.manage")) {
                    sender.sendMessage(ChatColor.RED + "No Permission to manage resource pack");
                    return true;
                }

                config.set(String.format("packs.%s", arg1), null);
                saveConfig();

                sender.sendMessage(ChatColor.GREEN + "Successfully unregistered resource pack!");
            }
            break;

            case "apply-if-absent":
                forceResourcePack = false;
            case "apply": {
                if (arg1 == null)
                    return false;

                String url = config.getString(String.format("packs.%s.url", arg1));
                String hash = config.getString(String.format("packs.%s.hash", arg1));

                if (url == null || hash == null) {
                    sender.sendMessage(ChatColor.RED + "No resource pack registered");
                    return true;
                }

                Stream<Entity> entityStream = null;
                if (arg2 != null && !"".equals(arg2)) {
                    try {
                        entityStream = Bukkit.selectEntities(sender, arg2).stream();
                    } catch (LinkageError e) {
                        entityStream = Arrays.stream(CommandUtils.getTargets(sender, arg2));
                    }
                }
                List<Player> targets;
                if (entityStream == null) {
                    if (!(sender instanceof Player)) {
                        sender.sendMessage(ChatColor.RED + "You must be a player");
                        return true;
                    }
                    targets = Collections.singletonList((Player) sender);
                } else {
                    targets = entityStream
                            .filter(Player.class::isInstance)
                            .map(Player.class::cast)
                            .collect(Collectors.toList());

                    if (targets.size() >= 2 || targets.stream().anyMatch(e -> !e.equals(sender))) {
                        if (!sender.hasPermission("easyresourcepack.other")) {
                            sender.sendMessage(ChatColor.RED + "No Permission to apply other player");
                            return true;
                        }
                    }

                    if (targets.isEmpty()) {
                        sender.sendMessage(ChatColor.RED + "Player not found");
                        return true;
                    }
                }

                boolean finalForceResourcePack = forceResourcePack;
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        try {
                            targets.forEach(e -> {
                                if (finalForceResourcePack || !hasResourcePack(e)) {
                                    AtomicReference<String> lastHash = getPlayerResourcePackHash(e);
                                    if (!hash.equals(lastHash.get())) {
                                        if (!isResourcePackDeclined(e))
                                            setResourcePack(e, url, hash);
                                        lastHash.set(hash);
                                    }
                                }
                            });
                        } catch (Throwable e) {
                            logger.log(Level.WARNING, "Failed to apply resource pack", e);
                            sender.sendMessage(ChatColor.RED + "Failed to apply resource pack");
                        }
                    }
                }.runTaskLaterAsynchronously(this, 4);

                sender.sendMessage(ChatColor.GREEN + "Successfully applied resource pack!");
            }
            break;

            default:
                return false;
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        switch (args.length) {
            case 0:
            case 1:
                return (sender.hasPermission("easyresourcepack.manage")
                        ? Stream.of("apply", "apply-if-absent", "add", "server-resourcepack", "remove")
                        : Stream.of("apply", "apply-if-absent"))
                        .filter(e -> e.startsWith(args[0])).collect(Collectors.toList());
            case 2:
                switch (args[0]) {
                    case "add": {
                        return Collections.singletonList("<new name>");
                    }

                    case "remove":
                    case "server-resourcepack":
                    case "apply-if-absent":
                    case "apply": {
                        Configuration config = getConfig();
                        ConfigurationSection packs = config.getConfigurationSection("packs");
                        if (packs == null)
                            return Collections.emptyList();
                        return packs.getKeys(false).stream()
                                .filter(e -> e.startsWith(args[1])).collect(Collectors.toList());
                    }
                }
                break;
            case 3:
                switch (args[0]) {
                    case "add": {
                        return Collections.singletonList("https://");
                    }

                    case "apply": {
                        if (sender.hasPermission("easyresourcepack.other"))
                            return Stream.concat(
                                    Stream.of("@a", "@p", "@a[distance=.."),
                                    Bukkit.getOnlinePlayers().stream().map(Player::getName)
                            ).filter(e -> e.startsWith(args[2])).collect(Collectors.toList());
                        return Collections.emptyList();
                    }
                }
                break;
        }

        return Collections.emptyList();
    }
}
