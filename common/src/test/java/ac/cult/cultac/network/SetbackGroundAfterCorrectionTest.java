package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.math.Vec3;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * A movement a setback rejects must not leave its ground claim behind. ClientPacketListener#handleMovePlayer moves the
 * player without touching onGround, and the server only forwards the correction tick's own ground, so the client's
 * next tick starts from the ground the correction left it with, not from the rejected claim. A client that jumps
 * against a stone block topped with a bottom slab, sends a glide start it cannot make and a floor position claiming
 * ground at the apex, then answers the correction, starts its next tick in the air and cannot step onto the slab.
 * Positions come from the live bypass of 2026-11-06, moved onto the fixture floor.
 */
class SetbackGroundAfterCorrectionTest {
    private static final double GRAVITY = 0.08D;
    private static final double VERTICAL_DRAG = 0.98F;
    private static final double JUMP = 0.41999998688697815D;
    private static final Vec3 AGAINST_THE_WALL = new Vec3(1.6999, FLOOR_Y, 0.5);
    private static final Vec3 GROUNDED = new Vec3(0, -0.0784000015258789, 0);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput JUMPING =
            new ServerboundPlayerInput(false, false, false, false, true, false, false);

    @Test
    void aRejectedGroundClaimDoesNotOutliveTheCorrection() throws Exception {
        try (var f = new JavaTickFixture()) {
            Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
            int slab = DataTables.defaults()
                    .registry()
                    .block("minecraft:stone_slab")
                    .defaultState();
            f.player.compensatedWorld.updateBlock(new BlockPos(2, FLOOR_Y, 0), BlockIds.STONE.defaultState());
            f.player.compensatedWorld.updateBlock(new BlockPos(2, FLOOR_Y + 1, 0), slab);
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            f.place(AGAINST_THE_WALL, GROUNDED);
            f.playerPosition(AGAINST_THE_WALL, Vec3.ZERO, Set.of());
            f.pongThrough(f.player.sendTrailingTransaction());
            f.input(NONE);
            f.tick(AGAINST_THE_WALL, true);
            PredictionResult settled = f.tick(AGAINST_THE_WALL, true);
            assertTrue(exact(settled), describe(settled));

            // An ordinary jump up to its apex.
            f.input(JUMPING);
            Vec3 at = AGAINST_THE_WALL;
            double motion = JUMP;
            for (int tick = 0; tick < 6; tick++) {
                at = at.add(0, motion, 0);
                PredictionResult rising = f.tick(at, false);
                assertTrue(exact(rising), "jump tick " + tick + ": " + describe(rising));
                motion = (motion - GRAVITY) * VERTICAL_DRAG;
                if (tick == 0) f.input(NONE);
            }
            SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();

            // A glide start without a glider, then a floor position claiming ground.
            f.command(PlayerCommandAction.START_FLYING_WITH_ELYTRA);
            assertTrue(teleports.isPendingSetback(), "the glide start without a glider was not set back");
            f.move(AGAINST_THE_WALL, true);
            f.tickEnd();
            SetBackData correction = teleports.getRequiredSetBack();
            assertFalse(correction.isExpectedOnGround(), "the correction tick at the apex cannot land");

            acceptCorrection(f, correction);
            assertFalse(teleports.isPendingSetback());
            assertNotEquals(
                    DesyncStatus.TRUE,
                    f.player.checkManager.getSimulationProcessor().getLastOnGround(),
                    "the rejected floor claim's ground outlived the correction");
        }
    }

    /** The client handles the correction: it answers the correction's ping and accepts its exact target. */
    private static void acceptCorrection(JavaTickFixture f, SetBackData correction) {
        var teleport = correction.getTeleportData();
        f.pongThrough(teleport.getTransaction());
        var at = teleport.getLocation();
        var packet = new ServerboundAcceptTeleportation(
                teleport.getTeleportId(), JavaTickFixture.vec3d(at), f.player.xRot, f.player.yRot);
        var event = f.event(ServerboundPackets.ACCEPT_TELEPORTATION, packet);
        new PacketServerTeleport().onAcceptTeleportation(event, f.player, packet);
        assertFalse(event.isCancelled(), "the correction's acceptance was rejected");
    }
}
