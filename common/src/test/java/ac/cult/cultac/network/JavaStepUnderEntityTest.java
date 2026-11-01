package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static ac.cult.cultac.network.JavaTickFixture.setField;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHappyGhast;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A grounded player stepping up under an entity. Entity#collide collides the move and its step with the entities
 * EntityGetter#getEntityCollisions gathers once: 26.3 gathers them up to maxUpStep above the move (Entity.java:1323),
 * 26.2 along the move only (Entity.java:1276), so only 26.3 stops a step against an entity above the player's head.
 */
class JavaStepUnderEntityTest {
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final float SPEED = 0.1F;
    private static final double GROUNDED_Y = -0.0784000015258789;
    // Yaw -90 faces +x.
    private static final float TOWARDS = -90.0F;
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final double BOAT_HALF_WIDTH = 1.375 / 2.0;
    private static final double BOAT_TOP = 0.5625;
    private static final double FACE_GAP = 0.05;
    // The player's box is 1.8 tall.
    private static final double ABOVE_HEAD = 1.8 + 0.2;
    private static final int STONE_SLAB =
            DataTables.defaults().registry().block("minecraft:stone_slab").defaultState();
    private static final double SLAB_TOP = 0.5;

    @Test
    void aClientBefore26_3StepsOntoABoatUnderAnotherBoat() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 input = boatUnderBoat(f, ClientVersion.V_26_2);
            PredictionResult step = f.tick(START.add(input.x, BOAT_TOP, input.z), true);
            assertTrue(exact(step), describe(step));
        }
    }

    @Test
    void aClientBefore26_3StoppingAtABoatUnderAnotherBoatIsRejected() throws Exception {
        try (var f = new JavaTickFixture()) {
            boatUnderBoat(f, ClientVersion.V_26_2);
            assertRejected(f.tick(START.add(FACE_GAP, 0.0, 0.0), true), "InputKeys");
        }
    }

    @Test
    void a26_3ClientStopsAtABoatUnderAnotherBoat() throws Exception {
        try (var f = new JavaTickFixture()) {
            boatUnderBoat(f, ClientVersion.V_26_3);
            PredictionResult stop = f.tick(START.add(FACE_GAP, 0.0, 0.0), true);
            assertTrue(exact(stop), describe(stop));
        }
    }

    @Test
    void a26_3ClientSteppingOntoABoatUnderAnotherBoatIsRejected() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 input = boatUnderBoat(f, ClientVersion.V_26_3);
            assertRejected(f.tick(START.add(input.x, BOAT_TOP, input.z), true), "VerticalOffset");
        }
    }

    @Test
    void aClientBefore26_3StepsOntoASlabUnderAStillHappyGhast() throws Exception {
        try (var f = new JavaTickFixture()) {
            version(f, ClientVersion.V_26_2);
            f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y, 0), STONE_SLAB);
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 from = new Vec3(1.0 - 0.3 - FACE_GAP, FLOOR_Y, START.z);
            f.player.compensatedEntities.addEntity(
                    100, EntityTypeIds.HAPPY_GHAST, new Vec3(from.x, FLOOR_Y + ABOVE_HEAD, from.z), 0, 0, 0);
            assertTrue(f.player.compensatedEntities.getEntity(100) instanceof PacketEntityHappyGhast);
            ((PacketEntityHappyGhast) f.player.compensatedEntities.getEntity(100)).staysStill = true;
            Vec3 input = start(f, from);
            PredictionResult step = f.tick(from.add(input.x, SLAB_TOP, input.z), true);
            assertTrue(exact(step), describe(step));
        }
    }

    /** A boat FACE_GAP ahead on the floor and a second boat ABOVE_HEAD over the player; the keys' input. */
    private static Vec3 boatUnderBoat(JavaTickFixture f, ClientVersion version) throws Exception {
        version(f, version);
        f.player.compensatedEntities.addEntity(
                100,
                EntityTypeIds.OAK_BOAT,
                new Vec3(START.x + 0.3 + FACE_GAP + BOAT_HALF_WIDTH, FLOOR_Y, START.z),
                0,
                0,
                0);
        f.player.compensatedEntities.addEntity(
                101, EntityTypeIds.OAK_BOAT, new Vec3(START.x + 0.3, FLOOR_Y + ABOVE_HEAD, START.z), 0, 0, 0);
        return start(f, START);
    }

    private static Vec3 start(JavaTickFixture f, Vec3 from) throws Exception {
        f.place(from, new Vec3(0.0, GROUNDED_Y, 0.0));
        f.player.onGround = f.player.lastOnGround = true;
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.fromBoolean(true));
        f.player.xRot = f.player.lastTickXRot = TOWARDS;
        setField(SimulationProcessor.class, f.player.checkManager.getSimulationProcessor(), "lastSneaking", false);
        f.input(FORWARD);
        return ClientMoveInput.of(f, FORWARD, 1.0F, SPEED, TOWARDS);
    }

    private static void version(JavaTickFixture f, ClientVersion version) throws Exception {
        setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
    }
}
