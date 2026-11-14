package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.prediction.OffsetHandler;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.verbose.VerboseCodecs;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.manager.datastore.DataStoreLifecycle;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.Test;

public final class JavaSleepingMovementGateTest {
    @Test
    public void sleepingPositionInsideHorizontalAndVerticalBoundsCanCommit() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = offlineJavaPlayer();
        AutoCloseable store = dataStore();
        try {
            player.x = 3.0D;
            player.y = 64.0D;
            player.z = -2.0D;
            player.isInBed = true;
            player.bedPosition = new Vec3(3.0D, 64.0D, -2.0D);

            ServerboundMovePlayer packet =
                    new ServerboundMovePlayer(3.5D, 64.09D, -1.5D, 0, 0, false, false, true, false);
            var event = RecordReceiveTestEvents.movement(player, packet);

            new CheckManagerListener().onMovePlayer(event, player, packet);

            assertFalse(event.isCancelled());
            assertEquals(3.5D, player.x, 0.0D);
            assertEquals(64.09D, player.y, 0.0D);
            assertEquals(-1.5D, player.z, 0.0D);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
            store.close();
        }
    }

    @Test
    public void sleepingPositionCannotChainBeyondStableBedAnchor() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = offlineJavaPlayer();
        AutoCloseable store = dataStore();
        try {
            player.x = 3.5D;
            player.y = 64.0D;
            player.z = -2.0D;
            player.isInBed = true;
            player.bedPosition = new Vec3(3.0D, 64.0D, -2.0D);

            ServerboundMovePlayer packet =
                    new ServerboundMovePlayer(4.0D, 64.0D, -2.0D, 0, 0, false, false, true, false);
            var event = RecordReceiveTestEvents.movement(player, packet);

            new CheckManagerListener().onMovePlayer(event, player, packet);

            assertRejected(player);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
            store.close();
        }
    }

    @Test
    public void sleepingPositionAtVerticalLimitCannotCommit() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = offlineJavaPlayer();
        AutoCloseable store = dataStore();
        try {
            player.x = 3.0D;
            player.y = 0.0D;
            player.z = -2.0D;
            player.isInBed = true;
            player.bedPosition = new Vec3(3.0D, 0.0D, -2.0D);

            ServerboundMovePlayer packet =
                    new ServerboundMovePlayer(3.0D, 0.1D, -2.0D, 0, 0, false, false, true, false);
            var event = RecordReceiveTestEvents.movement(player, packet);

            new CheckManagerListener().onMovePlayer(event, player, packet);

            assertRejected(player);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
            store.close();
        }
    }

    /**
     * A Java sleeper still travels by its carried velocity, so its moves are the engine's to judge and no gate holds
     * them back. Player#isImmobile makes LivingEntity#aiStep zero a sleeper's input: nothing moves it along the bed
     * or lifts it off it.
     */
    private static void assertRejected(CultPlayer player) {
        PredictionResult result = player.checkManager.getSimulationProcessor().getLastPrediction();
        assertFalse(result.isExempt());
        assertTrue(result.hasEffectiveFlags());
        assertTrue(player.checkManager.getCheck(OffsetHandler.class).violations > 0);
    }

    /** A check that flags writes through the data store's hooks, which the offline bootstrap does not install. */
    private static AutoCloseable dataStore() throws Exception {
        Field field = CultAPI.class.getDeclaredField("dataStoreLifecycle");
        field.setAccessible(true);
        Object original = field.get(CultAPI.INSTANCE);
        field.set(
                CultAPI.INSTANCE,
                new DataStoreLifecycle(CultAPI.INSTANCE.getGrimPlugin(), CultAPI.INSTANCE.getBackendRegistry()));
        return () -> field.set(CultAPI.INSTANCE, original);
    }

    private static CultPlayer offlineJavaPlayer() {
        // The checks a player builds parse verbose templates whose tags VerboseCodecs registers.
        VerboseCodecs.ensureRegistered();
        UUID playerId = UUID.fromString("df1e6a87-e857-4d54-8178-60b139cacbf8");
        User user = ac.cult.cultac.network.TestUsers.create(
                new User.Profile(playerId, ".Java_Sleep_Test"), new EmbeddedChannel());
        return new CultPlayer(user);
    }
}
