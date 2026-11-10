package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static ac.cult.cultac.network.JavaTickFixture.setField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntityShulker;
import ac.cult.cultac.utils.math.VanillaMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Shulker#canBeCollidedWith admits every living shulker, so Entity#collide stops a player on its bounding box,
 * Shulker#makeBoundingBox: the closed box grown away from the attach face by Shulker#getPhysicalPeek of
 * currentPeekAmount, which Shulker#updatePeekAmount moves by 0.05F toward DATA_PEEK_ID * 0.01F each client tick.
 */
class JavaShulkerEntityCollisionTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final double FALL = 0.0784000015258789;
    private static final int SHULKER = 100;
    private static final int PLAYER = 101;
    // A 1.21.4 client's offset from the shulker's position, the block centre the shulker sits on.
    private static final double X = 0.66393651849273;
    private static final double Z = 0.37791566111454;

    @Test
    void aFreshShulkerHasReadableMovementAndReachBoxes() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedEntities.addEntity(
                    SHULKER, EntityTypeIds.SHULKER, new Vec3(5.5, FLOOR_Y, 5.5), 0, 0, 0);
            var shulker = f.player.compensatedEntities.getEntity(SHULKER);
            assertNull(shulker.oldPacketLocation);
            SimpleCollisionBox movement = shulker.getPossibleMovementCollisionBoxes();
            assertEquals(5.0, movement.minX);
            assertEquals(FLOOR_Y, movement.minY);
            assertEquals(5.0, movement.minZ);
            assertEquals(6.0, movement.maxX);
            assertEquals(FLOOR_Y + 1, movement.maxY);
            assertEquals(6.0, movement.maxZ);
            SimpleCollisionBox reach = shulker.getPossibleCollisionBoxes();
            assertTrue(reach.minX <= movement.minX && reach.maxX >= movement.maxX);
            assertTrue(reach.minY <= movement.minY && reach.maxY >= movement.maxY);
            assertTrue(reach.minZ <= movement.minZ && reach.maxZ >= movement.maxZ);
        }
    }

    /**
     * A 1.21.4 client rides a closing shulker down: twenty client ticks after it handles DATA_PEEK_ID 0, its last
     * fall of the closing reaches the closed box, 0.0061528 below.
     */
    @Test
    void aPlayerRidingAClosingShulkerDownLandsOnItsClosedBox() throws Exception {
        try (var f = legacyFixture()) {
            shulker(f);
            peek(f, 100);
            ticks(f, 20);
            peek(f, 0);
            ticks(f, 20);
            stand(f, FLOOR_Y + 1.00615283846855);

            PredictionResult landing = f.tick(at(FLOOR_Y + 1), true);
            assertTrue(exact(landing), describe(landing));
        }
    }

    /** A shulker at rest at DATA_PEEK_ID 30 has a box 0.20609185 above its closed box. */
    @Test
    void aPlayerStandsOnAPeekingShulker() throws Exception {
        try (var f = legacyFixture()) {
            shulker(f);
            peek(f, 30);
            ticks(f, 6);
            stand(f, FLOOR_Y + 1.20609185099602);

            PredictionResult still = f.tick(at(FLOOR_Y + 1.20609185099602), true);
            assertTrue(exact(still), describe(still));
        }
    }

    @Test
    void aPlayerStandsOnAnOpenShulker() throws Exception {
        try (var f = legacyFixture()) {
            shulker(f);
            peek(f, 100);
            ticks(f, 20);
            stand(f, FLOOR_Y + 2);

            PredictionResult still = f.tick(at(FLOOR_Y + 2), true);
            assertTrue(exact(still), describe(still));
        }
    }

    @Test
    void aPlayerAboveAClosedShulkerStillFalls() throws Exception {
        try (var f = legacyFixture()) {
            shulker(f);
            stand(f, FLOOR_Y + 1.1);

            PredictionResult hover = f.tick(at(FLOOR_Y + 1.1), true);
            assertFalse(exact(hover), describe(hover));
        }
    }

    /** Shulker#sanitizeScale caps the scale at 3, so a scale 5 shulker's closed box is three blocks tall. */
    @Test
    void aShulkerScaledPastThreeIsThreeBlocksTall() throws Exception {
        try (var f = legacyFixture()) {
            shulker(f);
            scale(f, 5.0F);
            stand(f, FLOOR_Y + 3);
            PredictionResult still = f.tick(at(FLOOR_Y + 3), true);
            assertTrue(exact(still), describe(still));

            stand(f, FLOOR_Y + 5);
            PredictionResult hover = f.tick(at(FLOOR_Y + 5), true);
            assertFalse(exact(hover), describe(hover));
        }
    }

    /** TickRateManager#isEntityFrozen spares a player, so a shulker riding one keeps stepping its peek. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aShulkerRidingAPlayerStepsWhileFrozen(boolean self) throws Exception {
        try (var f = legacyFixture()) {
            shulker(f);
            var entities = f.player.compensatedEntities;
            if (!self) entities.addEntity(PLAYER, EntityTypeIds.PLAYER, new Vec3(5.0, FLOOR_Y, 5.0), 0, 0, 0);
            PacketEntityShulker shulker = (PacketEntityShulker) entities.getEntity(SHULKER);
            shulker.mount(self ? entities.getSelf() : entities.getEntity(PLAYER));
            peek(f, 100);
            f.player.packetStateData.serverTicksFrozen = true;
            ticks(f, 10);
            float amount = 0.0F;
            for (int tick = 0; tick < 10; tick++) amount += 0.05F;
            SimpleCollisionBox closed = new SimpleCollisionBox(0.0, FLOOR_Y, 0.0, 1.0, FLOOR_Y + 1, 1.0);
            double grow = Math.max(physicalPeek(amount), physicalPeek(amount + 0.05F));
            assertEquals(FLOOR_Y + 1 + grow, shulker.movementBoxAt(closed).maxY);
        }
    }

    /** The shulker's scale attribute, handled by the client. */
    private static void scale(JavaTickFixture f, float scale) {
        var shulker = f.player.compensatedEntities.getEntity(SHULKER);
        shulker.onScale(scale, 0);
        shulker.onSizeHandled(0);
        shulker.refreshDimensions(f.player);
    }

    private static JavaTickFixture legacyFixture() throws Exception {
        var f = new JavaTickFixture();
        setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21_4);
        return f;
    }

    /** A shulker attached to the floor below it, on the block centre (0.5, 0.5). */
    private static void shulker(JavaTickFixture f) {
        f.player.compensatedEntities.addEntity(SHULKER, EntityTypeIds.SHULKER, new Vec3(0.5, FLOOR_Y, 0.5), 0, 0, 0);
        assertEquals(
                1, f.player.compensatedEntities.getEntity(SHULKER).getPossibleMovementCollisionBoxes().maxY - FLOOR_Y);
    }

    /** The server's DATA_PEEK_ID, handled by the client. */
    private static void peek(JavaTickFixture f, int peek) {
        f.pongThrough(sendPeek(f, peek));
    }

    /** The server's DATA_PEEK_ID and the ping after it; the ping's transaction. */
    private static int sendPeek(JavaTickFixture f, int peek) {
        int proof = f.player.lastTransactionSent.get() + 1;
        f.player.compensatedEntities.updateEntityMetadata(
                SHULKER,
                List.of(new EntityMetadata.Entry(WatchableIndexUtil.SHULKER_PEEK, (byte) peek, ByteBuffer.allocate(0))),
                proof);
        assertEquals(proof, f.player.sendTrailingTransaction());
        return proof;
    }

    private static void ticks(JavaTickFixture f, int ticks) {
        for (int tick = 0; tick < ticks; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
    }

    // Shulker#getPhysicalPeek on a 1.21.4 client.
    private static float physicalPeek(float peekAmount) {
        return 0.5F - VanillaMath.sin(ClientVersion.V_1_21_4, (0.5F + peekAmount) * (float) Math.PI) * 0.5F;
    }

    /** The player on the ground at {@code y} after a grounded tick, LivingEntity#travel's gravity next. */
    private static void stand(JavaTickFixture f, double y) {
        f.place(at(y), new Vec3(0, -FALL, 0));
        f.player.onGround = f.player.lastOnGround = true;
        f.input(NONE);
    }

    private static Vec3 at(double y) {
        return new Vec3(X, y, Z);
    }
}
