package dev.openarcade;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Hotbar items and the clickable GUIs (game list + match info). */
public final class Menus {

    public enum Kind { GAMES, INFO }

    /** Marks our inventories so click events can recognise them. */
    public static final class Holder implements InventoryHolder {
        private final Kind kind;
        private final Map<Integer, String> slots = new HashMap<>();
        private Inventory inventory;

        Holder(Kind kind) {
            this.kind = kind;
        }

        public Kind kind() { return kind; }

        public String arenaAt(int slot) { return slots.get(slot); }

        public int size() { return inventory.getSize(); }

        @Override
        public Inventory getInventory() { return inventory; }
    }

    public static final String ITEM_MENU = "menu";
    public static final String ITEM_INFO = "info";
    public static final String ITEM_LEAVE = "leave";

    private static final String PREFIX = "[Arcade] ";

    private final OpenArcade plugin;
    private final NamespacedKey key;

    public Menus(OpenArcade plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "arcade_item");
    }

    // ------------------------------------------------------------ text utils

    private static Component text(String s, NamedTextColor color) {
        return Component.text(s, color).decoration(TextDecoration.ITALIC, false);
    }

    private static Component bold(String s, NamedTextColor color) {
        return text(s, color).decoration(TextDecoration.BOLD, true);
    }

    private static void tell(Player p, String msg, NamedTextColor color) {
        p.sendMessage(Component.text(PREFIX + msg, color));
    }

    // ----------------------------------------------------------- hotbar items

    public ItemStack item(String id) {
        Material mat;
        Component name;
        List<Component> lore = new ArrayList<>();
        switch (id) {
            case ITEM_MENU -> {
                mat = Material.COMPASS;
                name = bold("Game Menu", NamedTextColor.GREEN);
                lore.add(text("Right-click to see all games", NamedTextColor.GRAY));
                lore.add(text("and join a match.", NamedTextColor.GRAY));
            }
            case ITEM_INFO -> {
                mat = Material.CLOCK;
                name = bold("Match Info", NamedTextColor.AQUA);
                lore.add(text("Right-click to see the players", NamedTextColor.GRAY));
                lore.add(text("and status of your match.", NamedTextColor.GRAY));
            }
            default -> {
                mat = Material.RED_DYE;
                name = bold("Leave Game", NamedTextColor.RED);
                lore.add(text("Right-click to leave this match.", NamedTextColor.GRAY));
            }
        }
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, id);
        it.setItemMeta(meta);
        return it;
    }

    /** Returns our item id (menu/info/leave), or null if this is a normal item. */
    public String idOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    /** The compass that players carry when they are not in a game. */
    public void giveLobbyItem(Player p) {
        if (!plugin.getConfig().getBoolean("lobby-item.give-on-join", true)) return;
        PlayerInventory inv = p.getInventory();
        for (ItemStack s : inv.getContents()) {
            if (ITEM_MENU.equals(idOf(s))) return;
        }
        int slot = plugin.getConfig().getInt("lobby-item.slot", 8);
        if (slot < 0 || slot > 8 || inv.getItem(slot) != null) {
            slot = inv.firstEmpty();
        }
        if (slot >= 0) inv.setItem(slot, item(ITEM_MENU));
    }

    /** Hotbar shown while waiting in an arena lobby. */
    public void giveWaitingItems(Player p) {
        PlayerInventory inv = p.getInventory();
        inv.setItem(0, item(ITEM_MENU));
        inv.setItem(4, item(ITEM_INFO));
        inv.setItem(8, item(ITEM_LEAVE));
    }

    // ------------------------------------------------------------- games menu

    public void openGames(Player p) {
        List<Arena> list = new ArrayList<>(plugin.arenas());
        int rows = Math.max(2, Math.min(6, (list.size() + 8) / 9 + 1));
        int size = rows * 9;

        Holder h = new Holder(Kind.GAMES);
        Inventory inv = Bukkit.createInventory(h, size, text("Arcade Games", NamedTextColor.DARK_GREEN));
        h.inventory = inv;

        if (list.isEmpty()) {
            inv.setItem(4, named(Material.BARRIER, bold("No arenas yet", NamedTextColor.RED),
                    List.of(text("An admin needs to create one.", NamedTextColor.GRAY))));
        }
        int max = size - 9;
        for (int i = 0; i < list.size() && i < max; i++) {
            Arena a = list.get(i);
            inv.setItem(i, arenaIcon(p, a));
            h.slots.put(i, a.name());
        }
        inv.setItem(size - 5, named(Material.NETHER_STAR, bold("Refresh", NamedTextColor.YELLOW),
                List.of(text("Click to update this list.", NamedTextColor.GRAY))));
        p.openInventory(inv);
    }

    private ItemStack arenaIcon(Player viewer, Arena a) {
        Material mat = switch (a.type()) {
            case SPLEEF -> Material.SNOW_BLOCK;
            case TNT_RUN -> Material.TNT;
            case BLOCK_PARTY -> Material.LIME_WOOL;
        };
        List<Component> lore = new ArrayList<>();
        lore.add(text("Game: " + a.type().display(), NamedTextColor.GRAY));
        lore.add(text("Status: " + statusText(a), statusColor(a)));
        lore.add(text("Players: " + a.playerCount() + "/" + a.maxPlayers(), NamedTextColor.GRAY));
        if (a.state() == Arena.State.RUNNING) {
            lore.add(text("Still alive: " + a.aliveCount(), NamedTextColor.GRAY));
        }
        lore.add(Component.empty());
        if (a.hasPlayer(viewer)) {
            lore.add(text("You are in this match", NamedTextColor.GREEN));
        } else if (a.state() == Arena.State.RUNNING || a.state() == Arena.State.ENDING) {
            lore.add(text("In progress - wait for the next round", NamedTextColor.RED));
        } else if (a.playerCount() >= a.maxPlayers()) {
            lore.add(text("Arena is full", NamedTextColor.RED));
        } else {
            lore.add(text("Click to join!", NamedTextColor.YELLOW));
        }
        return named(mat, bold(a.name(), NamedTextColor.GOLD), lore);
    }

    private static String statusText(Arena a) {
        return switch (a.state()) {
            case WAITING -> "Waiting for players";
            case COUNTDOWN -> "Starting soon";
            case RUNNING -> "In progress";
            case ENDING -> "Ending";
        };
    }

    private static NamedTextColor statusColor(Arena a) {
        return switch (a.state()) {
            case WAITING -> NamedTextColor.GREEN;
            case COUNTDOWN -> NamedTextColor.YELLOW;
            case RUNNING -> NamedTextColor.RED;
            case ENDING -> NamedTextColor.GRAY;
        };
    }

    // -------------------------------------------------------------- info menu

    public void openInfo(Player p) {
        Arena a = plugin.arenaOf(p);
        if (a == null) {
            tell(p, "You're not in a match. Use the Game Menu to join one.", NamedTextColor.RED);
            return;
        }
        Holder h = new Holder(Kind.INFO);
        Inventory inv = Bukkit.createInventory(h, 36, text("Match: " + a.name(), NamedTextColor.DARK_AQUA));
        h.inventory = inv;

        List<Component> lore = new ArrayList<>();
        lore.add(text("Game: " + a.type().display(), NamedTextColor.GRAY));
        lore.add(text("Status: " + statusText(a), statusColor(a)));
        lore.add(text("Players: " + a.playerCount() + "/" + a.maxPlayers(), NamedTextColor.GRAY));
        lore.add(text("Needed to start: " + a.minPlayers(), NamedTextColor.GRAY));
        if (a.state() == Arena.State.RUNNING) {
            lore.add(text("Still alive: " + a.aliveCount(), NamedTextColor.GRAY));
        }
        inv.setItem(4, named(Material.PAPER, bold(a.type().display(), NamedTextColor.GOLD), lore));

        int slot = 9;
        for (UUID id : a.playerIds()) {
            if (slot >= inv.getSize()) break;
            Player member = Bukkit.getPlayer(id);
            if (member == null) continue;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(member);
            meta.displayName(text(member.getName(), NamedTextColor.WHITE));
            if (a.state() == Arena.State.RUNNING) {
                boolean alive = a.isAlive(member);
                meta.lore(List.of(text(alive ? "Alive" : "Eliminated",
                        alive ? NamedTextColor.GREEN : NamedTextColor.RED)));
            }
            head.setItemMeta(meta);
            inv.setItem(slot++, head);
        }
        p.openInventory(inv);
    }

    private static ItemStack named(Material mat, Component name, List<Component> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    // ----------------------------------------------------------------- clicks

    public void handleClick(Player p, Holder h, int slot) {
        if (h.kind() != Kind.GAMES) return;

        if (slot == h.size() - 5) {
            Bukkit.getScheduler().runTask(plugin, () -> openGames(p));
            return;
        }
        String name = h.arenaAt(slot);
        if (name == null) return;

        Arena target = plugin.arena(name);
        if (target == null) {
            Bukkit.getScheduler().runTask(plugin, () -> openGames(p));
            return;
        }
        Arena current = plugin.arenaOf(p);
        if (current != null) {
            if (current == target) {
                tell(p, "You're already in that match.", NamedTextColor.RED);
            } else {
                tell(p, "Leave your current match first (red dye in your hotbar).", NamedTextColor.RED);
            }
            return;
        }
        if (!p.hasPermission("arcade.play")) {
            tell(p, "No permission.", NamedTextColor.RED);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            p.closeInventory();
            target.join(p);
        });
    }
}
