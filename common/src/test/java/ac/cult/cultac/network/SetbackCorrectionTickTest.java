package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.movement.timer.TickTimer;
import ac.cult.cultac.checks.impl.prediction.OffsetHandler;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.checks.Phase;
import ac.cult.cultac.checks.impl.sprint.SprintA;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.data.SetbackPosWithVector;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import org.junit.jupiter.api.Test;

/**
 * A setback's input-less correction tick stands in for the movement it rejects. A setback with nothing rejected sends
 * the safe state as it is, and a setback raised by any check on a movement rolls back past that movement.
 */
class SetbackCorrectionTickTest {
    private static final double GRAVITY = 0.08D;
    // LivingEntity#travelInAir: the vertical drag and Entity#getAirDrag's horizontal friction.
    private static final double VERTICAL_DRAG = 0.98F;
    private static final float AIR_FRICTION = 0.91F;
    // Player#getFlyingSpeed with the sprint flag set.
    private static final float SPRINT_AIR_SPEED = 0.025999999F;
    private static final float YAW = 30.0F;
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);

    /** A movement Simulation rejects is replaced by one input-less tick from the safe state. */
    @Test
    void aRejectedMovementIsReplacedByTheCorrectionTick() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 start = new Vec3(.5, FLOOR_Y + 6, .5);
            f.place(start, new Vec3(0, -.3, 0));
            proveChunk(f);
            f.input(NONE);
            Vec3 moved = start.add(0, -.3, 0);
            PredictionResult accepted = f.tick(moved, false);
            assertTrue(exact(accepted), describe(accepted));
            SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
            SetbackPosWithVector safe = teleports.lastKnownGoodPosition;
            double carried = safe.getVector().y;

            // The client claims to fall a block further than it can, past the immediate-setback threshold.
            f.move(moved.add(0, -1, 0), false);

            assertTrue(f.player.checkManager.getCheck(OffsetHandler.class).violations > 0);
            assertTrue(teleports.isPendingSetback(), "the rejected movement was not set back");
            SetBackData setback = teleports.getRequiredSetBack();
            Vec3 location = setback.getTeleportData().getLocation();
            assertNotEquals(moved.add(0, -1, 0), teleports.lastKnownGoodPosition.getPos(),
                    "the rejected movement became safe");
            assertEquals(location, teleports.lastKnownGoodPosition.getPos(),
                    "the correction is the new rollback anchor");
            assertEquals(moved.x, location.x, 1.0E-12);
            assertEquals(moved.y + carried, location.y, 1.0E-12);
            assertEquals(moved.z, location.z, 1.0E-12);
            assertEquals((carried - GRAVITY) * VERTICAL_DRAG, setback.getVelocity().y, 1.0E-12);
        }
    }

    /**
     * SprintA runs after SetbackTeleportUtil in the prediction pass. Its setback for a hungry sprint must roll back past
     * the sprinting move rather than start from it.
     */
    @Test
    void aSprintASetbackRollsBackPastTheHungrySprint() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 start = new Vec3(.5, FLOOR_Y + 12, .5);
            f.place(start, Vec3.ZERO);
            proveChunk(f);
            f.player.xRot = YAW;
            f.input(FORWARD);
            f.command(PlayerCommandAction.START_SPRINTING);
            Vec3 input = ClientMoveInput.of(f, FORWARD, 1.0F, SPRINT_AIR_SPEED, YAW);
            Vec3 fed = start.add(input.x, 0, input.z);
            PredictionResult sprint = f.tick(fed, false);
            assertTrue(exact(sprint), describe(sprint));
            SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
            SetbackPosWithVector safe = teleports.lastKnownGoodPosition;
            assertEquals(fed, safe.getPos());

            // LocalPlayer#aiStep stops a client's sprint at 6 food or less; the engine leaves that rule to SprintA.
            f.player.food = 6;
            Vec3 velocity = new Vec3(input.x * AIR_FRICTION, -GRAVITY * VERTICAL_DRAG, input.z * AIR_FRICTION);
            Vec3 hungry = fed.add(velocity.x + input.x, velocity.y, velocity.z + input.z);
            PredictionResult result = f.tick(hungry, false);

            assertTrue(result.getOffset() < 1.0E-5, "Simulation must accept the sprint itself: " + describe(result));
            assertEquals(0.0, f.player.checkManager.getCheck(OffsetHandler.class).violations);
            assertTrue(f.player.checkManager.getCheck(SprintA.class).violations > 0, "SprintA did not flag");
            assertTrue(teleports.isPendingSetback(), "SprintA did not set back");
            Vec3 location = teleports.getRequiredSetBack().getTeleportData().getLocation();
            assertNotEquals(hungry, teleports.lastKnownGoodPosition.getPos(), "the hungry sprint became safe");
            assertEquals(location, teleports.lastKnownGoodPosition.getPos(),
                    "the correction is the new rollback anchor");
            // One input-less tick in free air from the well-fed sprint's end.
            assertEquals(safe.getPos().x + safe.getVector().x, location.x, 1.0E-12);
            assertEquals(safe.getPos().y + safe.getVector().y, location.y, 1.0E-12);
            assertEquals(safe.getPos().z + safe.getVector().z, location.z, 1.0E-12);
        }
    }

    /** A second movement in one client tick is a timer violation; its setback keeps the safe state as it is. */
    @Test
    void aTickTimerSetbackAddsNoCorrectionTick() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 start = new Vec3(.5, FLOOR_Y + 6, .5);
            f.place(start, new Vec3(0, -.3, 0));
            proveChunk(f);
            f.input(NONE);
            Vec3 first = start.add(0, -.3, 0);
            PredictionResult accepted = f.tick(first, false);
            assertTrue(exact(accepted), describe(accepted));
            double carried = (-.3 - GRAVITY) * VERTICAL_DRAG;
            Vec3 second = first.add(0, carried, 0);
            f.move(second, false);
            assertTrue(exact(prediction(f)), describe(prediction(f)));
            SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
            SetbackPosWithVector safe = teleports.lastKnownGoodPosition;
            assertEquals(second, safe.getPos());
            TickTimer timer = f.player.checkManager.getCheck(TickTimer.class);
            // TickTimer sets back only above its setback VL of 1.
            timer.violations = 1;

            // Another movement before this tick's client_tick_end.
            assertTrue(f.move(second.add(0, carried, 0), false).isCancelled());

            assertTrue(timer.violations > 1, "TickTimer did not flag the second movement");
            assertTrue(teleports.isPendingSetback(), "TickTimer did not set back");
            SetBackData setback = teleports.getRequiredSetBack();
            assertEquals(second, setback.getTeleportData().getLocation());
            assertEquals(safe.getVector(), setback.getVelocity());
        }
    }

    /**
     * Phase checks a Java movement in its receive handler, after the prediction pass. A clip Simulation lets through
     * must still be rolled back past, not set back from inside the wall.
     */
    @Test
    void aPhaseSetbackRollsBackPastTheClip() throws Exception {
        try (var f = new JavaTickFixture()) {
            for (int y = FLOOR_Y + 10; y <= FLOOR_Y + 14; y++) {
                for (int z = -1; z <= 1; z++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(1, y, z), BlockIds.STONE.defaultState());
                }
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 start = new Vec3(.5, FLOOR_Y + 12, .5);
            f.place(start, new Vec3(.15, 0, 0));
            proveChunk(f);
            f.input(NONE);
            Phase phase = f.player.checkManager.getCheck(Phase.class);
            // Phase compares each move with the box a join or teleport would have left it.
            JavaTickFixture.setField(
                    Phase.class,
                    phase,
                    "oldBox",
                    GetBoundingBox.getBoundingBoxFromPosAndSize(start.x, start.y, start.z, 0.6F, 1.8F));
            Vec3 first = start.add(.15, 0, 0);
            PredictionResult accepted = f.tick(first, false);
            assertTrue(exact(accepted), describe(accepted));
            SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
            SetbackPosWithVector safe = teleports.lastKnownGoodPosition;
            // Phase sets back only above its setback VL of 1.
            phase.violations = 1;

            // The wall at x = 1 stops the client about 0.05 short of this, inside the 0.1 immediate-setback threshold.
            Vec3 clipped = new Vec3(.75, first.y - GRAVITY * VERTICAL_DRAG, .5);
            f.move(clipped, false);

            PredictionResult result = prediction(f);
            assertTrue(result.getOffset() < 0.1, "Simulation must leave the clip to Phase: " + describe(result));
            assertTrue(phase.violations > 1, "Phase did not flag the clip");
            assertTrue(teleports.isPendingSetback(), "Phase did not set back");
            Vec3 location = teleports.getRequiredSetBack().getTeleportData().getLocation();
            assertNotEquals(clipped, teleports.lastKnownGoodPosition.getPos(), "the clip became safe");
            assertEquals(location, teleports.lastKnownGoodPosition.getPos(),
                    "the correction is the new rollback anchor");
            assertEquals(first.y + safe.getVector().y, location.y, 1.0E-12);
            // The correction tick from the safe state ends at the wall's face, outside the block.
            assertEquals(1.0, location.x + 0.6F / 2.0, 1.0E-6);
        }
    }

    /** The client has the chunk it stands in once it answers a ping sent after it, so a setback there may simulate. */
    private static void proveChunk(JavaTickFixture f) {
        f.pongThrough(f.player.sendTrailingTransaction());
    }

    private static PredictionResult prediction(JavaTickFixture f) {
        return f.player.checkManager.getSimulationProcessor().getLastPrediction();
    }
}
