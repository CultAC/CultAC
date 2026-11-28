package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.PacketServerRegistries;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.math.Vec3;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Entity#stuckSpeedMultiplier starts at Vec3.ZERO, and ClientPacketListener#handleLogin and #handleRespawn build a new
 * LocalPlayer: a cobweb the old player stood in never slows the new player's first move.
 */
class JavaNewLocalPlayerStuckSpeedTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final Vec3 IN_WEB = new Vec3(0.5, FLOOR_Y + 1.5, 0.5);
    private static final Vec3 SPAWN = new Vec3(0.5, FLOOR_Y, 0.5);
    /** 01a213d8 seq 12: the first move after player_loaded, forward at yaw 0 from rest, airborne. */
    private static final double FIRST_MOVE_Z = 16.519599999943374 - 16.5;
    /** 01a213d8 seq 13: the second move, landing. */
    private static final double SECOND_MOVE_Z = 16.557036000349253 - 16.519599999943374;

    @Test
    void aBackendSwitchOutOfACobwebLeavesTheFirstMoveUnslowed() throws Exception {
        try (var f = new JavaTickFixture()) {
            Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
            f.input(NONE);
            f.player.compensatedWorld.updateBlock(
                    BlockPos.containing(IN_WEB.x, IN_WEB.y, IN_WEB.z), BlockIds.COBWEB.defaultState());
            f.place(IN_WEB, Vec3.ZERO);
            PredictionResult stuck = f.tick(IN_WEB, false);
            assertTrue(exact(stuck), describe(stuck));

            switchBackend(f);
            JavaTickFixture.floor(f.player);
            f.playerPosition(SPAWN, Vec3.ZERO, Set.of());
            f.player.packetStateData.playerLoadedIntoLevel = true;
            f.input(FORWARD);

            Vec3 firstAt = SPAWN.add(0, 0, FIRST_MOVE_Z);
            PredictionResult first = f.tick(firstAt, false);
            assertTrue(exact(first), describe(first));
            PredictionResult second = f.tick(firstAt.add(0, 0, SECOND_MOVE_Z), true);
            assertTrue(exact(second), describe(second));
        }
    }

    /** Entity#checkInsideBlocks at the end of the new player's first tick still slows its second move. */
    @Test
    void aCobwebAtTheSpawnStillSlowsTheSecondMove() throws Exception {
        try (var f = new JavaTickFixture()) {
            Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
            f.input(NONE);
            f.place(SPAWN, Vec3.ZERO);
            switchBackend(f);
            JavaTickFixture.floor(f.player);
            f.player.compensatedWorld.updateBlock(
                    BlockPos.containing(SPAWN.x, SPAWN.y, SPAWN.z), BlockIds.COBWEB.defaultState());
            f.playerPosition(SPAWN, Vec3.ZERO, Set.of());
            f.player.packetStateData.playerLoadedIntoLevel = true;
            f.input(FORWARD);

            Vec3 first = SPAWN.add(0, 0, FIRST_MOVE_Z);
            PredictionResult unslowed = f.tick(first, false);
            assertTrue(exact(unslowed), describe(unslowed));
            PredictionResult second = f.tick(first.add(0, 0, SECOND_MOVE_Z), true);
            assertFalse(exact(second), describe(second));
            assertTrue(
                    second.getFlags().stream()
                            .anyMatch(flag -> flag.getCheck().getCheckName().equals("Strafe")),
                    describe(second));
        }
    }

    /** A reconfiguration and a LOGIN, answered by the client. */
    private static void switchBackend(JavaTickFixture f) {
        var type = ClientboundPackets.FINISH_CONFIGURATION;
        var finish = new PacketSendEvent<>(f.player.user, ConnectionPhase.CONFIGURATION, type, new Opaque(type), false);
        new PacketServerRegistries().onFinishConfiguration(finish, f.player, new Opaque(type));
        finish.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(ItemUseTestSupport.login(f.player, GameMode.SURVIVAL));
    }
}
