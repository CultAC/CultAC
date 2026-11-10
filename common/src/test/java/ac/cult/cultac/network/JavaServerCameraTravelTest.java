package ac.cult.cultac.network;

import ac.cult.cultac.manager.player.SetbackFixtures;
import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.data.SetbackPosWithVector;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * ClientPacketListener#handleSetCamera can make another entity the camera of a survival player. LocalPlayer#sendPosition
 * then sends no movement, while LocalPlayer#tick still travels: LivingEntity#applyInput only decays xxa/zza and keeps
 * jumping, at a look the client no longer reports.
 */
class JavaServerCameraTravelTest {
    private static final int CAMERA = 4242;
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);

    /**
     * Holding no keys, the falling player's travel is its own: each camera tick commits it with no report, through the
     * landing, and the client's first report after the camera returns continues from there.
     */
    @Test
    void aFallingPlayerWithAnotherCameraFallsAndLandsUnreported() throws Exception {
        try (var f = fixture(new Vec3(.5, FLOOR_Y + 6, .5), new Vec3(0, -0.5, 0))) {
            SimulationProcessor simulation = f.player.checkManager.getSimulationProcessor();
            Vec3 position = new Vec3(.5, FLOOR_Y + 6, .5);
            double motion = -0.5;
            position = position.add(0, motion, 0);
            assertExact(f.tick(position, false), "reported fall");
            motion = (motion - 0.08) * 0.98F;

            camera(f, CAMERA);
            boolean landed = false;
            for (int tick = 0; tick < 12; tick++) {
                PredictionResult before = simulation.getLastPrediction();
                f.tickEnd();
                if (position.y + motion <= FLOOR_Y) {
                    // Entity#move stops on the floor; the next travel starts from gravity alone.
                    position = new Vec3(position.x, FLOOR_Y, position.z);
                    motion = -0.08 * 0.98F;
                    landed = true;
                } else {
                    position = position.add(0, motion, 0);
                    motion = (motion - 0.08) * 0.98F;
                }
                PredictionResult hidden = simulation.getLastPrediction();
                String where = "camera tick " + tick;
                assertTrue(hidden != before, where + " committed no travel");
                assertExact(hidden, where);
                assertTrue(new Vec3(f.player.x, f.player.y, f.player.z).equals(position), where + " at " + f.player.y);
                assertFalse(f.player.getSetbackTeleportUtil().isPendingSetback(), where + " set the player back");
            }
            assertTrue(landed, "the player never landed");

            camera(f, f.player.entityID);
            assertExact(f.tick(position, true), "the first report after the camera returned");
        }
    }

    /**
     * Moving by decayed keys at a look nobody reports, the player's camera ticks leave it where the engine cannot
     * follow: nothing is predicted, flagged or set back for them.
     */
    @Test
    void aCameraTickWithDecayingInputPredictsNothing() throws Exception {
        try (var f = fixture(new Vec3(.5, FLOOR_Y, .5), new Vec3(0, -0.08 * 0.98F, 0))) {
            SimulationProcessor simulation = f.player.checkManager.getSimulationProcessor();
            f.input(FORWARD);
            // LivingEntity#getFrictionInfluencedSpeed on stone.
            Vec3 delta = ClientMoveInput.of(f, FORWARD, 1.0F, 0.1F * (0.21600002F / (0.6F * 0.6F * 0.6F)), 0);
            assertExact(f.tick(new Vec3(.5, FLOOR_Y, .5).add(delta.x, 0, delta.z), true), "reported walk");

            camera(f, CAMERA);
            PredictionResult walked = simulation.getLastPrediction();
            for (int tick = 0; tick < 3; tick++) {
                f.tickEnd();
                assertTrue(simulation.getLastPrediction() == walked, "camera tick " + tick + " predicted a position");
                assertFalse(f.player.getSetbackTeleportUtil().isPendingSetback(), "camera tick " + tick);
            }
        }
    }

    /** A tick end without movement while the player is its own camera still withholds only within 2.0E-4. */
    @Test
    void aFallingPlayersOwnCameraTickEndStillCannotWithholdTheFall() throws Exception {
        try (var f = fixture(new Vec3(.5, FLOOR_Y + 6, .5), new Vec3(0, -0.5, 0))) {
            SimulationProcessor simulation = f.player.checkManager.getSimulationProcessor();
            camera(f, CAMERA);
            camera(f, f.player.entityID);
            assertTrue(f.player.cameraEntity.isSelf());
            f.tickEnd();
            JavaTickFixture.assertRejected(simulation.getLastPrediction(), "VerticalOffset");
        }
    }

    private static JavaTickFixture fixture(Vec3 position, Vec3 velocity) throws Exception {
        var f = new JavaTickFixture();
        f.player.nettyScheduler.removeScheduler();
        Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
        f.player.packetStateData.playerLoadedIntoLevel = true;
        f.place(position, velocity);
        SetbackFixtures.seedSafePosition(f.player, new SetbackPosWithVector(position, velocity));
        f.addRemotePlayer(CAMERA, new Vec3(3.5, FLOOR_Y, 3.5), GameMode.SURVIVAL);
        return f;
    }

    /** ClientboundSetCameraPacket, handled by the client once both of its pings are answered. */
    private static void camera(JavaTickFixture f, int id) {
        f.player.cameraEntity.onSetCamera(id);
        f.player.sendTransaction();
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void assertExact(PredictionResult result, String where) {
        assertTrue(JavaTickFixture.exact(result), where + ": " + JavaTickFixture.describe(result));
    }
}
