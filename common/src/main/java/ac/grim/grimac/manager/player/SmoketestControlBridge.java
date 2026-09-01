package ac.grim.grimac.manager.player;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.events.packets.patch.ResyncWorldUtil;
import ac.grim.grimac.network.protocol.util.FoliaCompatUtil;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.LogUtil;
import ac.grim.grimac.utils.nmsutil.NmsIdentifierUtil;
import ac.grim.grimac.utils.latency.SectionPool;
import ac.grim.grimac.utils.blockplace.SmoketestPredictionSafety;
import org.bukkit.Location;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

public final class SmoketestControlBridge {
    private static final String CHANNEL = "grim:smoketest_control";
    private static final int MAGIC = 0x47534D43; // GSMC
    private static final int VERSION = 1;

    private SmoketestControlBridge() {
    }

    static boolean handle(GrimPlayer player, String channel, byte[] data) {
        if (!CHANNEL.equals(channel)) {
            return false;
        }
        if (!enabled()) {
            LogUtil.warn("Grim smoketest control ignored player=" + player.getName() + " reason=disabled");
            return true;
        }
        if (data.length == 0) {
            return true;
        }

        ControlRequest request;
        try {
            request = readRequest(data);
        } catch (IOException | RuntimeException exception) {
            LogUtil.warn("Grim smoketest control failed player=" + player.getName()
                    + " reason=decode-error error=" + exception.getClass().getSimpleName()
                    + ":" + exception.getMessage());
            return true;
        }

        try {
            switch (request.action()) {
                case "poolstats" -> logSectionPoolStats(request.target());
                case "playerpoolstats" -> logPlayerPoolStats(player, request.target());
                case "chunkstats" -> logChunkStats(player, request.target());
                case "resyncsection" -> resyncCurrentSection(player);
                case "placementsafetyreset" -> SmoketestPredictionSafety.reset();
                case "placementsafetyreport" -> SmoketestPredictionSafety.report(request.target(), player.getName());
                default -> {
                    return false;
                }
            }
        } catch (RuntimeException exception) {
            LogUtil.warn("Grim smoketest control failed player=" + player.getName()
                    + " action=" + request.action()
                    + " target=" + request.target()
                    + " error=" + exception.getClass().getSimpleName()
                    + ":" + exception.getMessage());
        }
        return true;
    }

    public static boolean enabled() {
        return Boolean.getBoolean("grim.validation.smoketestControl")
                || Boolean.parseBoolean(System.getenv().getOrDefault("GRIM_VALIDATION_SMOKETEST_CONTROL", "false"));
    }

    private static ControlRequest readRequest(byte[] data) throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(data));
        int magic = input.readInt();
        if (magic != MAGIC) {
            throw new IOException("invalid magic " + magic);
        }
        int version = input.readInt();
        if (version != VERSION) {
            throw new IOException("unsupported version " + version);
        }
        return new ControlRequest(input.readUTF(), input.readUTF());
    }

    public static void logSectionPoolStats(String label) {
        int uniformSections = SectionPool.globalUniformPoolSize();
        int nonUniformSections = SectionPool.globalNonUniformPoolSize();
        int pooledUniqueSections = uniformSections + nonUniformSections;
        int canonicalAirSections = 1;
        LogUtil.info("SectionPool stats: "
                + "label=" + safeLabel(label)
                + " internCalls=" + SectionPool.internCallCounter.get()
                + " internDedupHits=" + SectionPool.internDedupCounter.get()
                + " tryReinternSuccesses=" + SectionPool.tryReinternSuccessCounter.get()
                + " poolCount=" + SectionPool.poolCount()
                + " sectionReferences=" + SectionPool.totalReferences()
                + " activeGrimPlayers=" + GrimAPI.INSTANCE.getPlayerDataManager().size()
                + " uniformSections=" + uniformSections
                + " nonUniformSections=" + nonUniformSections
                + " pooledUniqueSections=" + pooledUniqueSections
                + " canonicalAirSections=" + canonicalAirSections
                + " totalCanonicalSections=" + (canonicalAirSections + pooledUniqueSections)
                + " deduplicatedSections=" + pooledUniqueSections
                + " mutableCopies=" + ac.grim.grimac.utils.latency.CompensatedWorld.CachedSection.mutableCopyCounter.get());
        for (SectionPool.DimensionStats stats : SectionPool.dimensionStats()) {
            LogUtil.info("SectionPool dimension stats: "
                    + "label=" + safeLabel(label)
                    + " dimension=" + stats.dimension()
                    + " poolCount=" + stats.poolCount()
                    + " sectionReferences=" + stats.sectionReferences());
        }
    }

    private static void logPlayerPoolStats(GrimPlayer player, String label) {
        int cachedChunks = player.compensatedWorld.cachedChunkCount();
        int retainedChunks = player.compensatedWorld.sectionPoolLeases().retainedChunkCount();
        LogUtil.info("SectionPool player stats: "
                + "label=" + safeLabel(label)
                + " player=" + player.getName()
                + " dimension=" + NmsIdentifierUtil.resourceKey(player.dimension)
                + " cachedChunks=" + cachedChunks
                + " retainedChunks=" + retainedChunks
                + " countsMatch=" + (cachedChunks == retainedChunks)
                + " globalSectionReferences=" + SectionPool.totalReferences());
    }

    private static void logChunkStats(GrimPlayer player, String target) {
        String[] parts = target.split(":");
        String label = parts.length > 0 ? parts[0] : "";
        if (parts.length != 3) {
            throw new IllegalArgumentException("chunkstats target must be label:chunkX:chunkZ");
        }
        int chunkX = Integer.parseInt(parts[1]);
        int chunkZ = Integer.parseInt(parts[2]);
        LogUtil.info("SectionPool chunk stats: "
                + "label=" + safeLabel(label)
                + " player=" + player.getName()
                + " dimension=" + NmsIdentifierUtil.resourceKey(player.dimension)
                + " chunkX=" + chunkX
                + " chunkZ=" + chunkZ
                + " cached=" + player.compensatedWorld.isChunkLoaded(chunkX, chunkZ)
                + " retained=" + player.compensatedWorld.hasRetainedChunk(chunkX, chunkZ)
                + " cachedChunks=" + player.compensatedWorld.cachedChunkCount()
                + " retainedChunks=" + player.compensatedWorld.sectionPoolLeases().retainedChunkCount());
    }

    private static void resyncCurrentSection(GrimPlayer player) {
        if (player.bukkitPlayer == null) {
            return;
        }
        FoliaCompatUtil.runTaskForEntity(player.bukkitPlayer, GrimAPI.INSTANCE.getPlugin(), () -> {
            if (player.bukkitPlayer == null || !player.bukkitPlayer.isOnline()) {
                return;
            }
            Location location = player.bukkitPlayer.getLocation();
            int blockX = location.getBlockX();
            int blockY = location.getBlockY();
            int blockZ = location.getBlockZ();
            int minX = blockX & ~15;
            int minY = ((blockY - 2) >> 4) << 4;
            int minZ = blockZ & ~15;
            ResyncWorldUtil.resyncPositions(player, minX, minY, minZ, minX + 15, minY + 15, minZ + 15);
        }, null, 0);
    }

    private static String safeLabel(String label) {
        if (label == null || label.isEmpty()) {
            return "-";
        }
        return label.replace(' ', '_');
    }

    private record ControlRequest(String action, String target) {
    }
}
