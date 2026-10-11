package dev.openarcade;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class OpenArcade extends JavaPlugin implements Listener, TabExecutor {

    private static final String PREFIX = "[Arcade] ";

    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    private final Map<UUID, Location> sel1 = new HashMap<>();
    private final Map<UUID, Location> sel2 = new HashMap<>();
    private File arenaFile;
    private Menus menus;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        menus = new Menus(this);
        arenaFile = new File(getDataFolder(), "arenas.yml");
        loadArenas();
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("arcade");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        getLogger().info("Loaded " + arenas.size() + " arena(s).");
    }

    @Override
    public void onDisable() {
        for (Arena a : arenas.values()) a.shutdown();
    }

    // ---------------------------------------------------------------- lookup

    public Menus menus() { return menus; }

    public Collection<Arena> arenas() { return arenas.values(); }

    public Arena arena(String name) { return arenas.get(name.toLowerCase(Locale.ROOT)); }

    public Arena arenaOf(Player p) {
        for (Arena a : arenas.values()) {
            if (a.hasPlayer(p)) return a;
        }
        return null;
    }

    // -------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 0) {
            help(s);
            return true;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "join" -> {
                Player p = playerOnly(s);
                if (p == null) return true;
                if (!s.hasPermission("arcade.play")) { msg(s, "No permission.", NamedTextColor.RED); return true; }
                if (a.length < 2) { msg(s, "Usage: /arcade join <arena>", NamedTextColor.RED); return true; }
                if (arenaOf(p) != null) { msg(s, "You're already in a game. Use /arcade leave.", NamedTextColor.RED); return true; }
                Arena ar = arenas.get(a[1].toLowerCase(Locale.ROOT));
                if (ar == null) { msg(s, "No such arena.", NamedTextColor.RED); return true; }
                ar.join(p);
            }
            case "leave" -> {
                Player p = playerOnly(s);
                if (p == null) return true;
                Arena ar = arenaOf(p);
                if (ar == null) { msg(s, "You're not in a game.", NamedTextColor.RED); return true; }
                ar.leave(p);
            }
            case "menu", "games" -> {
                Player p = playerOnly(s);
                if (p == null) return true;
                menus.openGames(p);
            }
            case "info" -> {
                Player p = playerOnly(s);
                if (p == null) return true;
                menus.openInfo(p);
            }
            case "list" -> {
                if (arenas.isEmpty()) { msg(s, "No arenas yet.", NamedTextColor.GRAY); return true; }
                for (Arena ar : arenas.values()) {
                    msg(s, ar.name() + " - " + ar.type().display() + " - " + ar.state()
                            + " (" + ar.playerCount() + "/" + ar.maxPlayers() + ")", NamedTextColor.YELLOW);
                }
            }
            default -> admin(s, sub, a);
        }
        return true;
    }

    private void admin(CommandSender s, String sub, String[] a) {
        if (!s.hasPermission("arcade.admin")) {
            msg(s, "No permission.", NamedTextColor.RED);
            return;
        }
        switch (sub) {
            case "pos1", "pos2" -> {
                Player p = playerOnly(s);
                if (p == null) return;
                // the block you are standing ON
                Location l = p.getLocation().clone().subtract(0, 1, 0).getBlock().getLocation();
                if (sub.equals("pos1")) sel1.put(p.getUniqueId(), l); else sel2.put(p.getUniqueId(), l);
                msg(s, sub + " set to " + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ(), NamedTextColor.GREEN);
            }
            case "create" -> {
                Player p = playerOnly(s);
                if (p == null) return;
                if (a.length < 3) { msg(s, "Usage: /arcade create <name> <spleef|tnt_run|block_party>", NamedTextColor.RED); return; }
                String name = a[1].toLowerCase(Locale.ROOT);
                if (!name.matches("[a-z0-9_-]{1,32}")) { msg(s, "Names: letters, numbers, _ and - only.", NamedTextColor.RED); return; }
                if (arenas.containsKey(name)) { msg(s, "That arena already exists.", NamedTextColor.RED); return; }
                GameType type;
                try {
                    type = GameType.valueOf(a[2].toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    msg(s, "Game types: " + Arrays.toString(GameType.values()), NamedTextColor.RED);
                    return;
                }
                Location l1 = sel1.get(p.getUniqueId());
                Location l2 = sel2.get(p.getUniqueId());
                if (l1 == null || l2 == null) { msg(s, "Set /arcade pos1 and /arcade pos2 first.", NamedTextColor.RED); return; }
                World w = l1.getWorld();
                if (w == null || !w.equals(l2.getWorld())) { msg(s, "Both positions must be in the same world.", NamedTextColor.RED); return; }
                int max = getConfig().getInt("max-arena-size", 80);
                if (Math.abs(l1.getBlockX() - l2.getBlockX()) + 1 > max || Math.abs(l1.getBlockZ() - l2.getBlockZ()) + 1 > max) {
                    msg(s, "Arena too large (max " + max + "x" + max + ").", NamedTextColor.RED);
                    return;
                }
                Arena ar = new Arena(this, name, type, l1, l2, p.getLocation(),
                        getConfig().getInt("min-players", 2), getConfig().getInt("max-players", 16));
                arenas.put(name, ar);
                saveArenas();
                msg(s, "Created " + type.display() + " arena '" + name + "'. Waiting spawn = where you stand. Change it with /arcade setspawn " + name, NamedTextColor.GREEN);
            }
            case "setspawn" -> {
                Player p = playerOnly(s);
                if (p == null) return;
                Arena ar = need(s, a);
                if (ar == null) return;
                ar.setSpawn(p.getLocation());
                saveArenas();
                msg(s, "Spawn updated.", NamedTextColor.GREEN);
            }
            case "delete" -> {
                Arena ar = need(s, a);
                if (ar == null) return;
                ar.shutdown();
                arenas.remove(ar.name());
                saveArenas();
                msg(s, "Deleted '" + ar.name() + "'.", NamedTextColor.GREEN);
            }
            case "start" -> {
                Arena ar = need(s, a);
                if (ar == null) return;
                msg(s, ar.forceStart() ? "Started." : "Can't start right now (needs players, and must be waiting).",
                        ar.state() == Arena.State.RUNNING ? NamedTextColor.GREEN : NamedTextColor.RED);
            }
            case "stop" -> {
                Arena ar = need(s, a);
                if (ar == null) return;
                ar.shutdown();
                msg(s, "Stopped '" + ar.name() + "'.", NamedTextColor.GREEN);
            }
            default -> help(s);
        }
    }

    private Arena need(CommandSender s, String[] a) {
        if (a.length < 2) {
            msg(s, "Usage: /arcade " + a[0] + " <arena>", NamedTextColor.RED);
            return null;
        }
        Arena ar = arenas.get(a[1].toLowerCase(Locale.ROOT));
        if (ar == null) msg(s, "No such arena.", NamedTextColor.RED);
        return ar;
    }

    private Player playerOnly(CommandSender s) {
        if (s instanceof Player p) return p;
        msg(s, "Players only.", NamedTextColor.RED);
        return null;
    }

    private void help(CommandSender s) {
        msg(s, "/arcade join <arena> | leave | list | menu | info", NamedTextColor.YELLOW);
        if (s.hasPermission("arcade.admin")) {
            msg(s, "Admin: pos1, pos2, create <name> <type>, setspawn <arena>, start <arena>, stop <arena>, delete <arena>", NamedTextColor.YELLOW);
        }
    }

    private static void msg(CommandSender s, String text, NamedTextColor color) {
        s.sendMessage(Component.text(PREFIX + text, color));
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.addAll(List.of("join", "leave", "list", "menu", "info"));
            if (s.hasPermission("arcade.admin")) {
                out.addAll(List.of("pos1", "pos2", "create", "setspawn", "start", "stop", "delete"));
            }
        } else if (a.length == 2 && List.of("join", "setspawn", "start", "stop", "delete").contains(a[0].toLowerCase(Locale.ROOT))) {
            out.addAll(arenas.keySet());
        } else if (a.length == 3 && a[0].equalsIgnoreCase("create")) {
            for (GameType t : GameType.values()) out.add(t.name().toLowerCase(Locale.ROOT));
        }
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.startsWith(last));
        return out;
    }

    // --------------------------------------------------------------- storage

    private static String enc(Location l) {
        return l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ()
                + ";" + l.getYaw() + ";" + l.getPitch();
    }

    private static Location dec(String s) {
        String[] p = s.split(";");
        World w = Bukkit.getWorld(p[0]);
        if (w == null) throw new IllegalArgumentException("world '" + p[0] + "' is not loaded");
        return new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                Float.parseFloat(p[4]), Float.parseFloat(p[5]));
    }

    private void saveArenas() {
        YamlConfiguration y = new YamlConfiguration();
        for (Arena a : arenas.values()) {
            String k = "arenas." + a.name();
            y.set(k + ".type", a.type().name());
            y.set(k + ".pos1", enc(a.pos1()));
            y.set(k + ".pos2", enc(a.pos2()));
            y.set(k + ".spawn", enc(a.spawn()));
            y.set(k + ".min", a.minPlayers());
            y.set(k + ".max", a.maxPlayers());
        }
        try {
            getDataFolder().mkdirs();
            y.save(arenaFile);
        } catch (IOException e) {
            getLogger().severe("Could not save arenas.yml: " + e.getMessage());
        }
    }

    private void loadArenas() {
        if (!arenaFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(arenaFile);
        ConfigurationSection root = y.getConfigurationSection("arenas");
        if (root == null) return;
        for (String name : root.getKeys(false)) {
            ConfigurationSection c = root.getConfigurationSection(name);
            if (c == null) continue;
            try {
                GameType type = GameType.valueOf(c.getString("type", ""));
                Arena ar = new Arena(this, name, type,
                        dec(c.getString("pos1", "")), dec(c.getString("pos2", "")), dec(c.getString("spawn", "")),
                        c.getInt("min", 2), c.getInt("max", 16));
                arenas.put(name.toLowerCase(Locale.ROOT), ar);
            } catch (Exception e) {
                getLogger().warning("Skipping arena '" + name + "': " + e.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------- events

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Arena a = arenaOf(e.getPlayer());
        if (a != null) a.leave(e.getPlayer());
        sel1.remove(e.getPlayer().getUniqueId());
        sel2.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Arena a = arenaOf(e.getPlayer());
        if (a == null) return;
        if (a.allowBreak(e.getPlayer(), e.getBlock())) {
            e.setDropItems(false);
        } else {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (arenaOf(e.getPlayer()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && arenaOf(p) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && arenaOf(p) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (arenaOf(e.getPlayer()) != null || menus.idOf(e.getItemDrop().getItemStack()) != null) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventory(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Inventory top = e.getView().getTopInventory();
        if (top.getHolder() instanceof Menus.Holder h) {
            e.setCancelled(true);
            if (e.getClickedInventory() == top) {
                menus.handleClick(p, h, e.getSlot());
            }
            return;
        }
        if (arenaOf(p) != null) e.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Menus.Holder) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!e.getAction().isRightClick()) return;
        String id = menus.idOf(e.getItem());
        if (id == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        switch (id) {
            case Menus.ITEM_MENU -> menus.openGames(p);
            case Menus.ITEM_INFO -> menus.openInfo(p);
            case Menus.ITEM_LEAVE -> {
                Arena a = arenaOf(p);
                if (a != null) a.leave(p);
            }
            default -> { }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        menus.giveLobbyItem(e.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        getServer().getScheduler().runTask(this, () -> {
            if (p.isOnline()) menus.giveLobbyItem(p);
        });
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(it -> menus.idOf(it) != null);
    }
}
