package dev.openarcade;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * One arena = one rectangular floor layer + a waiting spawn + a game type.
 * The floor is the lowest Y of the two selected corners. Anything that falls
 * more than 2 blocks below it is eliminated.
 */
public final class Arena {

    public enum State { WAITING, COUNTDOWN, RUNNING, ENDING }

    /** Everything we need to put a player back exactly as they were. */
    private record Snapshot(Location loc, ItemStack[] items, GameMode mode,
                            double health, int food, int level, float exp) {
        static Snapshot of(Player p) {
            ItemStack[] src = p.getInventory().getContents();
            ItemStack[] copy = new ItemStack[src.length];
            for (int i = 0; i < src.length; i++) {
                copy[i] = src[i] == null ? null : src[i].clone();
            }
            return new Snapshot(p.getLocation(), copy, p.getGameMode(),
                    p.getHealth(), p.getFoodLevel(), p.getLevel(), p.getExp());
        }

        void restore(Player p) {
            p.teleport(loc);
            p.getInventory().setContents(items);
            p.setGameMode(mode);
            p.setHealth(Math.min(Math.max(health, 1.0), 20.0));
            p.setFoodLevel(food);
            p.setLevel(level);
            p.setExp(exp);
        }
    }

    private static final String PREFIX = "[Arcade] ";

    private static final Material[] WOOL = {
            Material.WHITE_WOOL, Material.ORANGE_WOOL, Material.MAGENTA_WOOL, Material.LIGHT_BLUE_WOOL,
            Material.YELLOW_WOOL, Material.LIME_WOOL, Material.PINK_WOOL, Material.GRAY_WOOL,
            Material.LIGHT_GRAY_WOOL, Material.CYAN_WOOL, Material.PURPLE_WOOL, Material.BLUE_WOOL,
            Material.BROWN_WOOL, Material.GREEN_WOOL, Material.RED_WOOL, Material.BLACK_WOOL
    };
    private static final String[] WOOL_NAMES = {
            "White", "Orange", "Magenta", "Light Blue", "Yellow", "Lime", "Pink", "Gray",
            "Light Gray", "Cyan", "Purple", "Blue", "Brown", "Green", "Red", "Black"
    };

    private final OpenArcade plugin;
    private final String name;
    private final GameType type;
    private final Location pos1;
    private final Location pos2;
    private Location spawn;
    private final int minPlayers;
    private final int maxPlayers;

    private final World world;
    private final int minX, maxX, minZ, maxZ, floorY;

    private final Map<UUID, Snapshot> players = new LinkedHashMap<>();
    private final Set<UUID> alive = new HashSet<>();
    private final Map<Block, Long> pending = new HashMap<>();
    private final Random random = new Random();

    private State state = State.WAITING;
    private BukkitTask task;
    private BukkitTask endTask;
    private int countdown;
    private long ticks;
    private int startedWith;

    // Block Party state
    private int partyRound;
    private int partyPhase; // 0 = colour shown, 1 = floor removed
    private int partyTimer;
    private int partyTarget;
    private int partySeed;

    public Arena(OpenArcade plugin, String name, GameType type, Location pos1, Location pos2,
                 Location spawn, int minPlayers, int maxPlayers) {
        this.plugin = plugin;
        this.name = name;
        this.type = type;
        this.pos1 = pos1;
        this.pos2 = pos2;
        this.spawn = spawn;
        this.minPlayers = Math.max(1, minPlayers);
        this.maxPlayers = Math.max(this.minPlayers, maxPlayers);
        this.world = pos1.getWorld();
        this.minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
        this.maxX = Math.max(pos1.getBlockX(), pos2.getBlockX());
        this.minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
        this.maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ());
        this.floorY = Math.min(pos1.getBlockY(), pos2.getBlockY());
    }

    // ------------------------------------------------------------------ info

    public String name() { return name; }
    public GameType type() { return type; }
    public State state() { return state; }
    public Location pos1() { return pos1; }
    public Location pos2() { return pos2; }
    public Location spawn() { return spawn; }
    public void setSpawn(Location spawn) { this.spawn = spawn; }
    public int minPlayers() { return minPlayers; }
    public int maxPlayers() { return maxPlayers; }
    public int playerCount() { return players.size(); }
    public boolean hasPlayer(Player p) { return players.containsKey(p.getUniqueId()); }
    public boolean isAlive(Player p) { return alive.contains(p.getUniqueId()); }
    public int aliveCount() { return alive.size(); }
    public java.util.List<UUID> playerIds() { return new ArrayList<>(players.keySet()); }

    /** True if this block is part of the arena floor layer. */
    public boolean isFloorBlock(Block b) {
        return b.getWorld().equals(world) && b.getY() == floorY
                && b.getX() >= minX && b.getX() <= maxX
                && b.getZ() >= minZ && b.getZ() <= maxZ;
    }

    /** Whether the given player may break the given block right now. */
    public boolean allowBreak(Player p, Block b) {
        return state == State.RUNNING && type == GameType.SPLEEF && isAlive(p)
                && isFloorBlock(b) && b.getType() == Material.SNOW_BLOCK;
    }

    // ------------------------------------------------------------ join/leave

    public boolean join(Player p) {
        if (state == State.RUNNING || state == State.ENDING) {
            tell(p, "That game is already in progress.", NamedTextColor.RED);
            return false;
        }
        if (players.size() >= maxPlayers) {
            tell(p, "That arena is full.", NamedTextColor.RED);
            return false;
        }
        players.put(p.getUniqueId(), Snapshot.of(p));
        p.getInventory().clear();
        p.setGameMode(GameMode.ADVENTURE);
        p.setHealth(20.0);
        p.setFoodLevel(20);
        p.setLevel(0);
        p.setExp(0f);
        p.teleport(spawn);
        plugin.menus().giveWaitingItems(p);
        broadcast(p.getName() + " joined (" + players.size() + "/" + maxPlayers + ")");

        if (state == State.WAITING && players.size() >= minPlayers) {
            startCountdown();
        }
        return true;
    }

    public void leave(Player p) {
        Snapshot snap = players.remove(p.getUniqueId());
        if (snap == null) return;
        alive.remove(p.getUniqueId());
        snap.restore(p);
        broadcast(p.getName() + " left the game.");

        if (state == State.COUNTDOWN && players.size() < minPlayers) {
            cancelTask();
            state = State.WAITING;
            broadcast("Not enough players, countdown cancelled.");
        } else if (state == State.RUNNING) {
            checkWin();
        } else if (state == State.ENDING && players.isEmpty()) {
            shutdown();
        }
    }

    /** Skip the countdown and start immediately. */
    public boolean forceStart() {
        if ((state != State.WAITING && state != State.COUNTDOWN) || players.isEmpty()) return false;
        cancelTask();
        startGame();
        return true;
    }

    /** Stop everything, send everyone back, clear the floor. */
    public void shutdown() {
        cancelTask();
        if (endTask != null) {
            endTask.cancel();
            endTask = null;
        }
        for (Map.Entry<UUID, Snapshot> e : players.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) e.getValue().restore(p);
        }
        players.clear();
        alive.clear();
        pending.clear();
        clearFloor();
        state = State.WAITING;
    }

    // ------------------------------------------------------------- lifecycle

    private void startCountdown() {
        state = State.COUNTDOWN;
        countdown = plugin.getConfig().getInt("countdown", 15);
        task = new BukkitRunnable() {
            @Override
            public void run() {
                if (countdown <= 0) {
                    cancel();
                    task = null;
                    startGame();
                    return;
                }
                if (countdown <= 5 || countdown % 5 == 0) {
                    broadcast(type.display() + " starts in " + countdown + "s");
                    sound(Sound.BLOCK_NOTE_BLOCK_PLING, 1f);
                }
                countdown--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private void startGame() {
        state = State.RUNNING;
        alive.clear();
        alive.addAll(players.keySet());
        startedWith = alive.size();
        ticks = 0;
        pending.clear();

        switch (type) {
            case SPLEEF -> fillFloor(Material.SNOW_BLOCK);
            case TNT_RUN -> fillFloor(Material.SANDSTONE);
            case BLOCK_PARTY -> { /* built per round below */ }
        }

        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            p.getInventory().clear();
            p.setGameMode(GameMode.SURVIVAL);
            p.teleport(randomSpot());
            if (type == GameType.SPLEEF) {
                ItemStack shovel = new ItemStack(Material.DIAMOND_SHOVEL);
                ItemMeta meta = shovel.getItemMeta();
                meta.addEnchant(Enchantment.EFFICIENCY, 5, true);
                meta.setUnbreakable(true);
                shovel.setItemMeta(meta);
                p.getInventory().setItem(0, shovel);
            }
            p.showTitle(Title.title(
                    Component.text("GO!", NamedTextColor.GREEN),
                    Component.text(type.display(), NamedTextColor.GRAY),
                    Title.Times.times(Duration.ofMillis(100), Duration.ofSeconds(1), Duration.ofMillis(300))));
        }
        sound(Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f);

        if (type == GameType.BLOCK_PARTY) {
            partyRound = 1;
            newPartyRound();
        }

        task = new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void tick() {
        if (state != State.RUNNING) return;
        ticks++;

        for (UUID id : new ArrayList<>(alive)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                alive.remove(id);
                continue;
            }
            if (p.getLocation().getY() < floorY - 2) {
                eliminate(p);
            }
        }

        if (state == State.RUNNING) {
            switch (type) {
                case TNT_RUN -> tntTick();
                case BLOCK_PARTY -> partyTick();
                default -> { }
            }
        }
        checkWin();
    }

    private void eliminate(Player p) {
        if (!alive.remove(p.getUniqueId())) return;
        p.setGameMode(GameMode.SPECTATOR);
        p.teleport(new Location(world, (minX + maxX + 1) / 2.0, floorY + 6, (minZ + maxZ + 1) / 2.0));
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
        broadcast(p.getName() + " was eliminated! (" + alive.size() + " left)");
    }

    private void checkWin() {
        if (state != State.RUNNING) return;
        boolean over = startedWith > 1 ? alive.size() <= 1 : alive.isEmpty();
        if (!over) return;
        Player winner = null;
        if (alive.size() == 1) {
            winner = Bukkit.getPlayer(alive.iterator().next());
        }
        end(winner);
    }

    private void end(Player winner) {
        state = State.ENDING;
        cancelTask();
        pending.clear();

        if (winner != null) {
            broadcast(winner.getName() + " won " + type.display() + "!");
            for (String cmd : plugin.getConfig().getStringList("reward-commands")) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", winner.getName()));
            }
        } else {
            broadcast("Nobody won this round.");
        }
        sound(Sound.ENTITY_PLAYER_LEVELUP, 1f);

        endTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            endTask = null;
            shutdown();
        }, 100L);
    }

    // ------------------------------------------------------------- TNT Run

    private void tntTick() {
        double[] offsets = {-0.3, 0.0, 0.3};
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            Location l = p.getLocation();
            if (l.getY() <= floorY) continue;
            for (double dx : offsets) {
                for (double dz : offsets) {
                    Block b = world.getBlockAt((int) Math.floor(l.getX() + dx), floorY,
                            (int) Math.floor(l.getZ() + dz));
                    if (b.getType() == Material.SANDSTONE && !pending.containsKey(b)) {
                        pending.put(b, ticks + 8);
                    }
                }
            }
        }
        Iterator<Map.Entry<Block, Long>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Block, Long> e = it.next();
            if (ticks >= e.getValue()) {
                e.getKey().setType(Material.AIR, false);
                it.remove();
            }
        }
    }

    // --------------------------------------------------------- Block Party

    private void newPartyRound() {
        partySeed = random.nextInt();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                world.getBlockAt(x, floorY, z).setType(WOOL[tileColor(x, z)], false);
            }
        }
        // pick a colour that is guaranteed to exist on the floor
        int rx = minX + random.nextInt(maxX - minX + 1);
        int rz = minZ + random.nextInt(maxZ - minZ + 1);
        partyTarget = tileColor(rx, rz);

        ItemStack hint = new ItemStack(WOOL[partyTarget]);
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            for (int slot = 0; slot < 9; slot++) {
                p.getInventory().setItem(slot, hint.clone());
            }
            p.showTitle(Title.title(
                    Component.text(WOOL_NAMES[partyTarget], NamedTextColor.GOLD),
                    Component.text("Round " + partyRound, NamedTextColor.GRAY),
                    Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(900), Duration.ofMillis(200))));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1f);
        }
        partyPhase = 0;
        partyTimer = Math.max(30, 110 - partyRound * 8);
    }

    private int tileColor(int x, int z) {
        int tx = (x - minX) / 2;
        int tz = (z - minZ) / 2;
        return Math.floorMod(Objects.hash(partySeed, tx, tz), WOOL.length);
    }

    private void partyTick() {
        partyTimer--;
        if (partyPhase == 0) {
            if (partyTimer % 20 == 0 && partyTimer > 0) {
                Component bar = Component.text("Stand on " + WOOL_NAMES[partyTarget] + "! "
                        + (partyTimer / 20) + "s", NamedTextColor.YELLOW);
                for (UUID id : alive) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) p.sendActionBar(bar);
                }
            }
            if (partyTimer <= 0) {
                Material keep = WOOL[partyTarget];
                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        Block b = world.getBlockAt(x, floorY, z);
                        if (b.getType() != keep) b.setType(Material.AIR, false);
                    }
                }
                sound(Sound.ENTITY_GENERIC_EXPLODE, 0.5f);
                partyPhase = 1;
                partyTimer = 60;
            }
        } else if (partyTimer <= 0) {
            partyRound++;
            newPartyRound();
        }
    }

    // --------------------------------------------------------------- floors

    private void fillFloor(Material m) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                world.getBlockAt(x, floorY, z).setType(m, false);
            }
        }
    }

    /** Only removes blocks this plugin places, so builds in the area are safe. */
    private void clearFloor() {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                Block b = world.getBlockAt(x, floorY, z);
                if (isGameMaterial(b.getType())) b.setType(Material.AIR, false);
            }
        }
    }

    private static boolean isGameMaterial(Material m) {
        if (m == Material.SNOW_BLOCK || m == Material.SANDSTONE) return true;
        for (Material w : WOOL) {
            if (w == m) return true;
        }
        return false;
    }

    private Location randomSpot() {
        int w = maxX - minX + 1;
        int d = maxZ - minZ + 1;
        double x = minX + (w > 2 ? 1 + random.nextInt(w - 2) : 0) + 0.5;
        double z = minZ + (d > 2 ? 1 + random.nextInt(d - 2) : 0) + 0.5;
        return new Location(world, x, floorY + 1.0, z, random.nextFloat() * 360f, 0f);
    }

    // -------------------------------------------------------------- helpers

    private void cancelTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void broadcast(String msg) {
        Component c = Component.text(PREFIX + msg, NamedTextColor.YELLOW);
        for (UUID id : players.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendMessage(c);
        }
    }

    private void sound(Sound s, float pitch) {
        for (UUID id : players.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.playSound(p.getLocation(), s, 1f, pitch);
        }
    }

    private static void tell(Player p, String msg, NamedTextColor color) {
        p.sendMessage(Component.text(PREFIX + msg, color));
    }
}
