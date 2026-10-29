package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A 1.21.11 client teleported onto a floor whose chunk reaches the server after its StatusOnly claims the ground.
 * LocalPlayer#sendPosition sends StatusOnly whenever onGround changes, so the ticks that follow without one keep the
 * claim; once the floor is there, the first strafe walks from the ground.
 */
class JavaIdleTickGroundTest {
    private static final ServerboundPlayerInput SPRINT_LEFT =
            new ServerboundPlayerInput(false, false, true, false, false, false, true);
    private static final ServerboundPlayerInput SPRINT_FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final double FLOOR = 61.0;
    private static final Vec3 SPAWN = new Vec3(-599.5, FLOOR, -1449.5);
    private static final double[] STRAFE_X = {-599.4019999966323, -599.2504919852108, -599.0697685979985};
    private static final Vec3 WARP = new Vec3(2095.5, 64.0, 1528.5);
    /** LivingEntity#travelInAir's velocity one tick into a fall from rest. */
    private static final Vec3 FALLING = new Vec3(0.0, -0.08 * 0.98F, 0.0);

    @Test
    void theStrafeAfterIdleTicksOnTheLoadedFloorIsExact() throws Exception {
        try (var f = spawnedBeforeTheChunk()) {
            f.statusTick(true, false);
            loadFloor(f);
            for (int tick = 0; tick < 6; tick++) f.tickEnd();
            f.input(SPRINT_LEFT);
            for (int tick = 0; tick < STRAFE_X.length; tick++) {
                PredictionResult result = f.tick(new Vec3(STRAFE_X[tick], FLOOR, SPAWN.z), true);
                assertTrue(exact(result), "tick " + tick + ": " + describe(result));
            }
        }
    }

    @Test
    void theSprintFromRestAfterIdleTicksOnTheLoadedFloorIsExact() throws Exception {
        try (var f = spawnedBeforeTheChunk(WARP)) {
            f.statusTick(true, false);
            f.tickEnd();
            loadFloor(f, WARP);
            for (int tick = 0; tick < 5; tick++) f.tickEnd();
            f.input(SPRINT_FORWARD);
            f.command(PlayerCommandAction.START_SPRINTING);
            PredictionResult result = f.tick(new Vec3(WARP.x, WARP.y, 1528.6274000124097), true);
            assertTrue(exact(result), describe(result));
        }
    }

    @Test
    void aSprintHeldWithoutMovingAfterTheIdleTicksStillFlags() throws Exception {
        try (var f = spawnedBeforeTheChunk(WARP)) {
            f.statusTick(true, false);
            f.tickEnd();
            loadFloor(f, WARP);
            for (int tick = 0; tick < 5; tick++) f.tickEnd();
            f.input(SPRINT_FORWARD);
            f.command(PlayerCommandAction.START_SPRINTING);
            assertRejected(f.tick(WARP, true), "InputKeys");
        }
    }

    @Test
    void aGroundClaimHoveringAboveTheFloorStillFlagsTheStrafe() throws Exception {
        try (var f = spawnedBeforeTheChunk()) {
            loadFloor(f);
            double hover = FLOOR + 1.0;
            f.place(new Vec3(SPAWN.x, hover, SPAWN.z), Vec3.ZERO);
            f.statusTick(true, false);
            for (int tick = 0; tick < 6; tick++) f.tickEnd();
            f.input(SPRINT_LEFT);
            PredictionResult result = f.tick(new Vec3(STRAFE_X[0], hover, SPAWN.z), true);
            assertRejected(result, "Strafe");
            assertRejected(result, "GroundSpoof");
        }
    }

    @Test
    void aGroundClaimHeldWithinAGravityTickOfTheFloorFlagsTheStrafeAsVerticalOffset() throws Exception {
        double hover = FLOOR - FALLING.y / 2;
        try (var f = spawnedBeforeTheChunk(new Vec3(SPAWN.x, hover, SPAWN.z), FALLING)) {
            f.statusTick(true, false);
            loadFloor(f);
            for (int tick = 0; tick < 6; tick++) f.tickEnd();
            assertEquals(
                    DesyncStatus.TRUE,
                    f.player.checkManager.getSimulationProcessor().getLastOnGround());
            f.input(SPRINT_LEFT);
            PredictionResult result = f.tick(new Vec3(STRAFE_X[0], hover, SPAWN.z), true);
            assertRejected(result, "VerticalOffset");
        }
    }

    private static JavaTickFixture spawnedBeforeTheChunk() throws Exception {
        return spawnedBeforeTheChunk(SPAWN);
    }

    private static JavaTickFixture spawnedBeforeTheChunk(Vec3 spawn) throws Exception {
        return spawnedBeforeTheChunk(spawn, Vec3.ZERO);
    }

    private static JavaTickFixture spawnedBeforeTheChunk(Vec3 spawn, Vec3 velocity) throws Exception {
        JavaTickFixture f = new JavaTickFixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11);
        f.player.xRot = 0.0F;
        f.player.yRot = 0.0F;
        f.place(spawn, velocity);
        f.player.onGround = false;
        f.player.lastOnGround = false;
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.FALSE);
        return f;
    }

    private static void loadFloor(JavaTickFixture f) {
        loadFloor(f, SPAWN);
    }

    private static void loadFloor(JavaTickFixture f, Vec3 spawn) {
        int blockX = (int) Math.floor(spawn.x);
        int blockZ = (int) Math.floor(spawn.z);
        f.player.compensatedWorld.ensureValidationChunkLoaded(blockX >> 4, blockZ >> 4);
        for (int x = blockX - 5; x <= blockX + 4; x++) {
            for (int z = blockZ - 5; z <= blockZ + 4; z++) {
                f.player.compensatedWorld.updateBlock(
                        new BlockPos(x, (int) spawn.y - 1, z), BlockIds.STONE.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }
}
