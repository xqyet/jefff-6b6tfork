package com.stash.hunt.modules;

import com.stash.hunt.Addon;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.set.WaypointSet;
import xaero.hud.minimap.world.MinimapWorld;
import xaero.map.mods.SupportMods;
import xaeroplus.XaeroPlus;
import xaeroplus.event.ChunkDataEvent;
import xaeroplus.module.ModuleManager;
import xaeroplus.module.impl.OldChunks;
import xaeroplus.module.impl.PaletteNewChunks;
import xaero.common.minimap.waypoints.Waypoint;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static com.stash.hunt.Utils.sendWebhook;

public class TrailNotify extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public enum DimensionMode { OVERWORLD, NETHER, BOTH }
    public enum ChunkTypeMode {
        ONLY_112("1.12 Only"),
        ONLY_119("1.19+ Only"),
        BOTH("Both");
        private final String displayName;
        ChunkTypeMode(String displayName) { this.displayName = displayName; }
        @Override public String toString() { return displayName; }
    }

    // ----- Settings -----
    private final Setting<Boolean> notifyAnyChunks = sgGeneral.add(new BoolSetting.Builder()
        .name("Notify Any Chunks").description("Notify on any old chunk.")
        .defaultValue(true).build());

    private final Setting<Integer> minClusterSize = sgGeneral.add(new IntSetting.Builder()
        .name("Min cluster size (chunks)")
        .description("Require at least this many qualifying chunks in the local neighborhood before pinging/setting home.")
        .defaultValue(3).min(1).sliderRange(1, 9).build());

    private final Setting<Integer> clusterRadius = sgGeneral.add(new IntSetting.Builder()
        .name("Cluster radius (chunks)")
        .description("Neighborhood radius (in chunks). 1 = 3x3, 2 = 5x5, etc.")
        .defaultValue(1).min(0).sliderRange(0, 3).build());

    private final Setting<Boolean> notifyOffHighway = sgGeneral.add(new BoolSetting.Builder()
        .name("Notify Trails Off Highway")
        .description("Also notify when an old chunk is detected off your travel axis.")
        .defaultValue(false).build());

    private final Setting<Double> directionOfTravel = sgGeneral.add(new DoubleSetting.Builder()
        .name("Direction of Travel (yaw)")
        .description("Your travel yaw in degrees (for off-highway check).")
        .defaultValue(0).min(-180).max(180)
        .visible(notifyOffHighway::get).build());

    private final Setting<Double> distanceOffAxis = sgGeneral.add(new DoubleSetting.Builder()
        .name("Distance Off Axis (chunks)")
        .description("Min chunk distance from your axis to flag off-highway.")
        .defaultValue(13).sliderRange(0, 20)
        .visible(notifyOffHighway::get).build());

    private final Setting<ChunkTypeMode> chunkTypeMode = sgGeneral.add(new EnumSetting.Builder<ChunkTypeMode>()
        .name("Chunk Type Filter")
        .description("Which type(s) of old chunks trigger.")
        .defaultValue(ChunkTypeMode.BOTH).build());

    private final Setting<LogType> logType = sgGeneral.add(new EnumSetting.Builder<LogType>()
        .name("Log Type")
        .description("What to do on detection.")
        .defaultValue(LogType.Marker).build());

    public final Setting<String> webhookLink = sgGeneral.add(new StringSetting.Builder()
        .name("Webhook Link")
        .description("Discord webhook URL.")
        .defaultValue("").visible(() -> logType.get() == LogType.Webhook || logType.get() == LogType.Both).build());

    public final Setting<Boolean> ping = sgGeneral.add(new BoolSetting.Builder()
        .name("Ping on Webhook").description("Ping a Discord ID in the webhook.")
        .defaultValue(false).visible(() -> logType.get() == LogType.Webhook || logType.get() == LogType.Both).build());

    public final Setting<String> discordId = sgGeneral.add(new StringSetting.Builder()
        .name("Discord ID").description("User ID to ping (if enabled).")
        .defaultValue("").visible(() -> ping.get() && (logType.get() == LogType.Webhook || logType.get() == LogType.Both)).build());

    public final Setting<DimensionMode> dimensionMode = sgGeneral.add(new EnumSetting.Builder<DimensionMode>()
        .name("Dimension Mode").description("Where to detect old chunks.")
        .defaultValue(DimensionMode.BOTH).build());

    // Auto /sethome
    private final Setting<Boolean> setHomeOnDetect = sgGeneral.add(new BoolSetting.Builder()
        .name("Type /sethome on detect")
        .description("Automatically runs /sethome with a unique name when a webhook triggers.")
        .defaultValue(true).build());

    private final Setting<Integer> setHomeCooldownMs = sgGeneral.add(new IntSetting.Builder()
        .name("SetHome cooldown (ms)")
        .description("Minimum time between automatic /sethome commands.")
        .defaultValue(60_000).min(0).max(3_600_000) // 60 sec default, up to 1 hr
        .visible(setHomeOnDetect::get).build());

    // ----- State -----
    private final ArrayDeque<ChunkPos> oldChunks = new ArrayDeque<>();
    private long lastSetHomeMs = 0L;
    private final String homePrefix = "aa-trail-";
    private final Set<String> usedHomeNames = new HashSet<>();

    private static final int MAX_RECENT_CHUNKS = 4096;
    private final ArrayDeque<Long> recentQueue = new ArrayDeque<>(MAX_RECENT_CHUNKS);
    private final HashSet<Long> recentSet = new HashSet<>(MAX_RECENT_CHUNKS);

    public TrailNotify() {
        super(Addon.CATEGORY, "6b6tTrailNotify", "Logs trails on 6b6t");
    }

    @Override
    public void onActivate() {
        XaeroPlus.EVENT_BUS.register(this);
        oldChunks.clear();
        recentQueue.clear();
        recentSet.clear();
    }

    @Override
    public void onDeactivate() {
        XaeroPlus.EVENT_BUS.unregister(this);
    }

    // ------- Event -------
    @net.lenni0451.lambdaevents.EventHandler(priority = -1)
    public void onChunkData(ChunkDataEvent event) {
        if (mc.player == null || mc.world == null) return;
        if (event.seenChunk()) return;

        // avoid 2b2t end loading screen / fake loads
        if (mc.player.getAbilities().allowFlying) return;

        // Dimension filter
        if ((dimensionMode.get() == DimensionMode.NETHER   && mc.world.getRegistryKey() != World.NETHER) ||
            (dimensionMode.get() == DimensionMode.OVERWORLD && mc.world.getRegistryKey() != World.OVERWORLD)) return;

        // De-dupe chunks
        if (oldChunks.size() > 1000) oldChunks.removeFirst();
        if (oldChunks.contains(event.chunk().getPos())) return;
        oldChunks.add(event.chunk().getPos());

        // Classify
        boolean is119NewChunk = ModuleManager.getModule(PaletteNewChunks.class)
            .isNewChunk(event.chunk().getPos().x, event.chunk().getPos().z, event.chunk().getWorld().getRegistryKey());
        boolean is112OldChunk = ModuleManager.getModule(OldChunks.class)
            .isOldChunk(event.chunk().getPos().x, event.chunk().getPos().z, event.chunk().getWorld().getRegistryKey());

        // Filter by type
        ChunkTypeMode typeMode = chunkTypeMode.get();
        boolean shouldNotify;
        switch (typeMode) {
            case ONLY_112: shouldNotify = is112OldChunk; break;
            case ONLY_119: shouldNotify = !is119NewChunk && !is112OldChunk; break; // "old" in 1.19+ sense only
            default:       shouldNotify = (!is119NewChunk) || is112OldChunk; break;
        }
        if (!shouldNotify) return;

        // --- Cluster filter: require a neighborhood of qualifying chunks ---
// Note: only chunks that *passed* the type filter are recorded.
        final int cx = event.chunk().getPos().x;
        final int cz = event.chunk().getPos().z;

// Record this qualifying chunk
        addRecentChunk(key(cx, cz));

// Count neighbors (including this one) within clusterRadius
        int cluster = neighborhoodCount(cx, cz, clusterRadius.get());
        if (cluster < minClusterSize.get()) {
            // Not enough local support -> likely a single-dot false positive. Stop here.
            return;
        }

        // --- Any-chunk branch ---
        if (notifyAnyChunks.get()) {
            if (logType.get() == LogType.Both || logType.get() == LogType.Marker)
                createMapMarker(cx, cz);

            if (logType.get() == LogType.Both || logType.get() == LogType.Webhook) {
                String msg;
                if (is112OldChunk && !is119NewChunk) msg = "1.12 Followed in 1.19+ Old Chunk Detected";
                else if (is112OldChunk)             msg = "1.12 Unfollowed in 1.19+ Old Chunk Detected";
                else                                msg = "1.19+ Old Chunk Detected";
                String finalMessage = msg;

                String id = !ping.get() || discordId.get().isBlank() ? null : discordId.get();
                new Thread(() -> sendWebhook(
                    webhookLink.get(),
                    "Old Chunk Detected",
                    finalMessage + " at " + mc.player.getPos(),
                    id,
                    mc.player.getGameProfile().getName()
                )).start();

                // Consume this neighborhood so we only alert once per cluster
                clearNeighborhood(cx, cz, clusterRadius.get());

                sendSetHomeIfNeeded();
            }
        }


        // --- Off-highway branch ---
        if (notifyOffHighway.get()) {
            ChunkPos chunkPos = event.chunk().getPos();
            Vec3d direction = yawToDirection(directionOfTravel.get());
            ChunkPos playerChunkPos = mc.player.getChunkPos();
            double distance = distancePointToDirection(
                new Vec3d(chunkPos.x, 0, chunkPos.z),
                direction,
                new Vec3d(playerChunkPos.x, 0, playerChunkPos.z)
            );
            if (distance > distanceOffAxis.get()) {
                if (logType.get() == LogType.Both || logType.get() == LogType.Marker)
                    createMapMarker(chunkPos.x, chunkPos.z);

                if (logType.get() == LogType.Both || logType.get() == LogType.Webhook) {
                    String id = !ping.get() || discordId.get().isBlank() ? null : discordId.get();
                    new Thread(() -> sendWebhook(
                        webhookLink.get(),
                        "Old Chunk Detected",
                        "Old chunk detected off the highway at " + (chunkPos.x * 16) + " " + (chunkPos.z * 16),
                        id,
                        mc.player.getGameProfile().getName()
                    )).start();

// Consume this neighborhood so we only alert once per cluster
                    clearNeighborhood(cx, cz, clusterRadius.get());

                    sendSetHomeIfNeeded();
                }
            }
        }
    }
    // ----- Cluster helpers -----
    private static long key(int cx, int cz) {
        return ChunkPos.toLong(cx, cz);
    }

    private void addRecentChunk(long k) {
        if (recentSet.add(k)) {
            recentQueue.addLast(k);
            if (recentQueue.size() > MAX_RECENT_CHUNKS) {
                Long old = recentQueue.removeFirst();
                recentSet.remove(old);
            }
        }
    }

    /** Count qualifying chunks (already passed type filter) in a square neighborhood. */
    private int neighborhoodCount(int cx, int cz, int r) {
        int count = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (recentSet.contains(key(cx + dx, cz + dz))) count++;
            }
        }
        return count;
    }

    /** Optional: after we trigger once for a cluster, consume the area to avoid repeats. */
    private void clearNeighborhood(int cx, int cz, int r) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                long k = key(cx + dx, cz + dz);
                if (recentSet.remove(k)) {
                    recentQueue.remove(k);
                }
            }
        }
    }

    // ------- Helpers -------
    private void createMapMarker(int x, int z) {
        MinimapSession minimapSession = BuiltInHudModules.MINIMAP.getCurrentSession();
        if (minimapSession == null) return;
        MinimapWorld currentWorld = minimapSession.getWorldManager().getCurrentWorld();
        if (currentWorld == null) return;
        WaypointSet waypointSet = currentWorld.getCurrentWaypointSet();
        if (waypointSet == null) return;
        Waypoint waypoint = new Waypoint(
            x * 16, 70, z * 16,
            "Old Chunk", "O",
            5, 0, false
        );
        waypointSet.add(waypoint);
        SupportMods.xaeroMinimap.requestWaypointsRefresh();
    }

    private String generateUniqueHomeName() {
        for (int i = 0; i < 20; i++) {
            int n = ThreadLocalRandom.current().nextInt(1000, 10000); // 1000–9999
            String name = homePrefix + n;
            if (usedHomeNames.add(name)) return name;
        }
        String fallback = homePrefix + (System.currentTimeMillis() % 100000);
        usedHomeNames.add(fallback);
        return fallback;
    }

    private void sendSetHomeIfNeeded() {
        if (!setHomeOnDetect.get()) return;
        long now = System.currentTimeMillis();
        if (now - lastSetHomeMs < setHomeCooldownMs.get()) return;
        lastSetHomeMs = now;

        final String finalName = generateUniqueHomeName();
        mc.execute(() -> {
            if (mc.player != null && mc.player.networkHandler != null) {
                // 1.20+: no leading slash for commands
                mc.player.networkHandler.sendChatCommand("sethome " + finalName);
                // Fallback for older builds:
                // mc.player.networkHandler.sendChatMessage("/sethome " + finalName);
            }
        });
    }

    // Convert yaw (deg) to normalized direction vector on XZ plane
    private static Vec3d yawToDirection(double yawDeg) {
        double rad = Math.toRadians(-yawDeg); // Minecraft yaw is clockwise; negate to convert
        double x = Math.sin(rad);
        double z = Math.cos(rad);
        return new Vec3d(x, 0, z);
    }

    // Perpendicular distance from 'point' to the line through 'origin' in 'dir'
    private static double distancePointToDirection(Vec3d point, Vec3d dir, Vec3d origin) {
        // | (point - origin) x dir | because dir is normalized on XZ plane
        Vec3d po = point.subtract(origin);
        // 2D cross magnitude: |x1*z2 - z1*x2|
        double cross = Math.abs(po.x * dir.z - po.z * dir.x);
        return cross;
    }

    private enum LogType { Webhook, Marker, Both }
}
