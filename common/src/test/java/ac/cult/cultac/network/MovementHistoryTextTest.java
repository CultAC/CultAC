package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.SuperDebug;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The movement history a watch starts with and a Cult Log prints: the last 40 ticks, teleports among them, in order,
 * and a log reads the same after the history moves on.
 */
class MovementHistoryTextTest {
    private static final Vec3 START = new Vec3(0.5, JavaTickFixture.FLOOR_Y, 0.5);
    private static final Vec3 RESTING = new Vec3(0.0, -0.0784000015258789, 0.0);
    private static final int TICKS = 52;
    private static final int WORLD_AT = 18;
    private static final int TELEPORT_AT = 21;
    /** SHA-256 of every history snapshot of {@link #aFixedWalkKeepsTheSameHistory}, joined by NUL. */
    private static final String EXPECTED = "a71598a952d941fa6abb0812fd249bb0a8e50a87525171cba204a15b62305ed3";

    @Test
    void aFixedWalkKeepsTheSameHistory() throws Exception {
        List<String> snapshots = new ArrayList<>();
        String flaggedLog = null;
        String historyAtFlag = null;
        int flaggedId = 0;
        try (JavaTickFixture f = new JavaTickFixture();
                AutoCloseable staff = CultLogViewers.watch(f.player)) {
            SuperDebug debug = f.player.checkManager.getListener(SuperDebug.class);
            f.place(START, RESTING);
            snapshots.add(debug.history());
            for (int tick = 0; tick < TICKS; tick++) {
                if (tick == WORLD_AT) f.player.platformPlayer = inWorld("world_nether");
                if (tick == TELEPORT_AT) {
                    f.teleport(START, new RelativeFlag(0));
                    snapshots.add(debug.history());
                }
                boolean flag = tick % 9 == 4;
                Vec3 to = flag ? START.add(0.4 + tick / 100.0, 0, 0) : START;
                PredictionResult result = f.tick(to, true);
                if (flag) f.place(START, RESTING);
                String history = debug.history();
                snapshots.add(history);
                if (flaggedLog == null && result.getIdentifier() != 0) {
                    flaggedId = result.getIdentifier();
                    flaggedLog = SuperDebug.getFlag(flaggedId);
                    historyAtFlag = history;
                }
            }
            assertNotNull(flaggedLog, "no tick flagged");
            assertNotEquals(0, flaggedId);
            assertEquals(flaggedLog, SuperDebug.getFlag(flaggedId), "the log changed as the history moved on");
            assertTrue(flaggedLog.contains(historyAtFlag), "the log prints the history as it was");
        }
        assertEquals(0, count(snapshots.get(0), "Predicted: "));
        for (int i = 1; i < snapshots.size(); i++) {
            int frames = count(snapshots.get(i), "Predicted: ");
            int before = count(snapshots.get(i - 1), "Predicted: ");
            assertTrue(frames == 40 || frames > before, "frames in snapshot " + i + ": " + frames);
        }
        assertEquals(40, count(snapshots.get(snapshots.size() - 1), "Predicted: "));
        String last = snapshots.get(snapshots.size() - 1);
        assertTrue(last.contains("(123456.0, 123456.0, 123456.0)"), "the teleport left the window");
        assertTrue(last.contains("world: null\n"), "no tick without a platform player");
        assertTrue(last.contains("world: world_nether  teleport!"), "no teleport in a world");
        assertEquals(EXPECTED, sha256(String.join("\0", snapshots)));
    }

    @Test
    void aVectorThatPrintsMoreKeepsItsText() throws Exception {
        try (JavaTickFixture f = new JavaTickFixture()) {
            f.place(START, RESTING);
            SuperDebug debug = f.player.checkManager.getListener(SuperDebug.class);
            PredVector predicted = new PredVector(new Vec3(0.25, -0.5, 0.125));
            SimulationContext context = Mockito.mock(SimulationContext.class);
            Mockito.when(context.getVehicle()).thenReturn(Mockito.mock(PacketEntity.class));
            PredictionResult result = Mockito.mock(PredictionResult.class);
            Mockito.when(result.getSimulationContext()).thenReturn(context);
            Mockito.when(result.getLegacyLikePredictionVector()).thenReturn(predicted);
            debug.onPredictionComplete(new PredictionComplete(result));
            String history = debug.history();
            assertTrue(history.contains("Predicted: " + predicted + "\nEnd: null\n"), history);
        }
    }

    private static PlatformPlayer inWorld(String name) {
        PlatformWorld world = Mockito.mock(PlatformWorld.class);
        Mockito.when(world.getName()).thenReturn(name);
        PlatformPlayer player = Mockito.mock(PlatformPlayer.class);
        Mockito.when(player.getWorld()).thenReturn(world);
        return player;
    }

    private static int count(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) count++;
        return count;
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
