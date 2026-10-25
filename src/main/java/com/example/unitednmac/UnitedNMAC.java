package com.example.unitednmac;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class UnitedNMAC extends JavaPlugin implements Listener {

    // Combat checks
    private static final double REACH_LIMIT = 5.3;                 // about 5 blocks, with room for hitbox size and lag
    private static final double CROSSHAIR_LIMIT_DEGREES = 45.0;

    // Storage hiding
    private static final double CLOSE_DISTANCE = 5.0;              // storage within this many blocks looks normal
    private static final long STORAGE_PERIOD_TICKS = 20L;          // once per second
    private static final long STORAGE_RESEND_CYCLES = 10L;         // re-send stone every 10 seconds

    // Ore hiding (anti-xray)
    private static final int ORE_RANGE = 8;                        // blocks around each player that are checked
    private static final double ORE_VIEW_COS = Math.cos(Math.toRadians(60)); // ores within 60 degrees of where you look
    private static final long ORE_PERIOD_TICKS = 10L;              // twice per second
    private static final long ORE_RESEND_CYCLES = 10L;             // re-send stone every 5 seconds

    private static final Set<Material> STORAGE = EnumSet.of(
            Material.CHEST, Material.TRAPPED_CHEST, Material.ENDER_CHEST, Material.BARREL,
            Material.HOPPER, Material.DROPPER, Material.DISPENSER,
            Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER);

    private static final Set<Material> ORES = EnumSet.of(
            Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE,
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
            Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE, Material.NETHER_GOLD_ORE,
            Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE,
            Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
            Material.NETHER_QUARTZ_ORE, Material.ANCIENT_DEBRIS);

    private record Spot(World world, int x, int y, int z) {}

    private final Map<UUID, Integer> flags = new HashMap<>();
    private final Map<UUID, Set<Spot>> stoneStorage = new HashMap<>();
    private final Map<UUID, Set<Spot>> stoneOres = new HashMap<>();
    private final Set<Spot> storage = new HashSet<>();
    private long storageCycle = 0;
    private long oreCycle = 0;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::updateStorageView,
                STORAGE_PERIOD_TICKS, STORAGE_PERIOD_TICKS);
        getServer().getScheduler().runTaskTimer(this, this::updateOres,
                ORE_PERIOD_TICKS, ORE_PERIOD_TICKS);
        getLogger().info("UnitedmnAC enabled.");
    }

    // ---------- Combat checks ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof Player victim)) return;
        if (attacker.hasPermission("unitednmac.bypass")) return;
        if (attacker.getGameMode() == GameMode.SPECTATOR) return;
        if (attacker.getWorld() != victim.getWorld()) return;

        Location eye = attacker.getEyeLocation();
        Location targetCenter = victim.getLocation().add(0, 0.9, 0);

        // Reach check
        if (eye.distance(targetCenter) > REACH_LIMIT) {
            event.setCancelled(true);
            flag(attacker, "reach (hit from more than about 5 blocks away)");
            return;
        }

        // Crosshair check
        Vector look = eye.getDirection();
        Vector toTarget = targetCenter.toVector().subtract(eye.toVector());
        if (toTarget.lengthSquared() < 0.0001) return;
        double angle = Math.toDegrees(look.angle(toTarget));
        if (angle > CROSSHAIR_LIMIT_DEGREES) {
            event.setCancelled(true);
            flag(attacker, "crosshair (hit a player " + (int) angle + " degrees off the crosshair)");
        }
    }

    private void flag(Player player, String check) {
        int total = flags.merge(player.getUniqueId(), 1, Integer::sum);
        String message = "[UnitedmnAC] " + player.getName() + " failed " + check + " (" + total + " flags)";
        getLogger().warning(message);
        Bukkit.broadcast(message, "unitednmac.alerts");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        stoneStorage.remove(id);
        stoneOres.remove(id);
    }

    // ---------- Storage hiding ----------

    private void updateStorageView() {
        storageCycle++;
        boolean resend = storageCycle % STORAGE_RESEND_CYCLES == 0;
        scanStorage();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("unitednmac.bypass")) continue;
            updateStorageFor(player, resend);
        }
    }

    // Finds every loaded storage block on the server.
    private void scanStorage() {
        storage.clear();
        for (World world : getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                for (BlockState state : chunk.getTileEntities()) {
                    if (isStorage(state.getType())) {
                        storage.add(new Spot(world, state.getX(), state.getY(), state.getZ()));
                    }
                }
            }
        }
    }

    private void updateStorageFor(Player player, boolean resend) {
        World world = player.getWorld();
        Set<Spot> shown = stoneStorage.computeIfAbsent(player.getUniqueId(), id -> new HashSet<>());
        shown.removeIf(spot -> spot.world() != world || !storage.contains(spot));

        Location eye = player.getEyeLocation();
        for (Spot spot : storage) {
            if (spot.world() != world) continue;
            Location blockLoc = spotLocation(spot);
            Location center = blockLoc.clone().add(0.5, 0.5, 0.5);

            if (center.distance(eye) <= CLOSE_DISTANCE) {
                // Close: show the real storage block.
                if (shown.remove(spot)) {
                    player.sendBlockChange(blockLoc, blockLoc.getBlock().getBlockData());
                }
            } else if (resend || !shown.contains(spot)) {
                // Far: show stone.
                player.sendBlockChange(blockLoc, Material.STONE.createBlockData());
                shown.add(spot);
            }
        }
    }

    // ---------- Ore hiding (anti-xray) ----------

    private void updateOres() {
        oreCycle++;
        boolean resend = oreCycle % ORE_RESEND_CYCLES == 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("unitednmac.bypass")) continue;
            updateOresFor(player, resend);
        }
    }

    private void updateOresFor(Player player, boolean resend) {
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector look = eye.getDirection();
        Vector eyeVec = eye.toVector();
        Set<Spot> stone = stoneOres.computeIfAbsent(player.getUniqueId(), id -> new HashSet<>());

        int cx = eye.getBlockX();
        int cy = eye.getBlockY();
        int cz = eye.getBlockZ();
        int minY = Math.max(cy - ORE_RANGE, world.getMinHeight());
        int maxY = Math.min(cy + ORE_RANGE, world.getMaxHeight() - 1);

        Set<Spot> hideNow = new HashSet<>();
        for (int x = cx - ORE_RANGE; x <= cx + ORE_RANGE; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = cz - ORE_RANGE; z <= cz + ORE_RANGE; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (!ORES.contains(block.getType())) continue;

                    Vector toOre = block.getLocation().add(0.5, 0.5, 0.5).toVector().subtract(eyeVec);
                    double dist = toOre.length();
                    if (dist < 0.5) continue;

                    boolean inView = look.dot(toOre) / dist >= ORE_VIEW_COS;
                    if (!inView || !hasLineOfSight(world, eye, toOre, dist, x, y, z)) {
                        hideNow.add(new Spot(world, x, y, z));
                    }
                }
            }
        }

        // Ores that are out of view or blocked show as stone.
        for (Spot spot : hideNow) {
            if (resend || !stone.contains(spot)) {
                player.sendBlockChange(spotLocation(spot), Material.STONE.createBlockData());
            }
        }
        // Ores that came into view, were mined, or left the area show their real block again.
        for (Spot spot : stone) {
            if (spot.world() == world && !hideNow.contains(spot)) {
                Location loc = spotLocation(spot);
                player.sendBlockChange(loc, loc.getBlock().getBlockData());
            }
        }

        stone.clear();
        stone.addAll(hideNow);
    }

    // True if nothing solid sits between the eye and the ore block.
    private static boolean hasLineOfSight(World world, Location eye, Vector toOre, double dist,
                                          int x, int y, int z) {
        RayTraceResult hit = world.rayTraceBlocks(eye, toOre.clone().normalize(), dist,
                FluidCollisionMode.NEVER, true);
        if (hit == null || hit.getHitBlock() == null) return true;
        Block first = hit.getHitBlock();
        return first.getX() == x && first.getY() == y && first.getZ() == z;
    }

    private static Location spotLocation(Spot spot) {
        return new Location(spot.world(), spot.x(), spot.y(), spot.z());
    }

    private static boolean isStorage(Material type) {
        return STORAGE.contains(type) || Tag.SHULKER_BOXES.isTagged(type);
    }
          }
