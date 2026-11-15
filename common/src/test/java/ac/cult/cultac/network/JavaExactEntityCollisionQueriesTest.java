package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.UnknownCollisionProvider;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.RootVehicleInterpolationCollision;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** CollisionGetter#noCollision and Player#updatePlayerPose read the same EntityTickList position as move. */
class JavaExactEntityCollisionQueriesTest {
    private static final Vec3 START = new Vec3(2.5, FLOOR_Y + 2, 0.5);

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_26_3"})
    void bundledInterpolationHasOneAnswerAtEveryPlaceInTheClientTick(ClientVersion version) throws Exception {
        for (boolean entityFirst : new boolean[] {false, true}) {
            try (var f = new JavaTickFixture()) {
                JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
                f.place(START, Vec3.ZERO);
                var entities = f.player.compensatedEntities;
                entities.addEntity(100, EntityTypeIds.OAK_BOAT, START.add(1.1, 1.6, 0), 0, 0, 0);
                var boat = entities.getEntity(100);
                if (entityFirst) entities.appendSelfToClientTickOrder();
                boat.onBundledPositionSyncTransaction(START.x - 1.9, START.y + 1.6, START.z, f.player);
                entities.beginMovementPositionReads();
                try {
                    assertClearance(f, entityFirst);
                } finally {
                    entities.endMovementPositionReads();
                }
                boat.onMovement(f.player);
                // A deferred piston/entity pass can read either side of that completed interpolation tick.
                entities.setPassTickedThroughOrder(boat.getClientTickOrder() - 1);
                try {
                    assertClearance(f, false);
                    entities.setPassTickedThroughOrder(boat.getClientTickOrder());
                    assertClearance(f, true);
                } finally {
                    entities.setPassTickedThroughOrder(Long.MIN_VALUE);
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_26_3"})
    void aMinecartDoesNotBlockTheLocalPlayersStandingPose(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.place(START, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(
                    100, EntityTypes.defaults().byKey("minecraft:minecart").id(), START.add(0, 1.6, 0), 0, 0, 0);
            assertTrue(f.player.canPlayerFitWithinBlocksAndEntitiesWhen(Pose.STANDING, START.x, START.y, START.z));
            assertTrue(f.player.mayPlayerFitWithinBlocksAndEntitiesWhen(Pose.STANDING, START.x, START.y, START.z));
            assertTrue(f.player.entityCollisionBoxes().isEmpty());
        }
    }

    /**
     * Entity#canCollideWith refuses Entity#isPassengerOfSameVehicle, which compares Entity#getRootVehicle: a boat
     * carrying a pig that carries a strider the local player rides is the player's own root, however many vehicles lie
     * between them.
     */
    @ParameterizedTest
    @CsvSource({"V_1_21_4, false", "V_1_21_4, true", "V_26_3, false", "V_26_3, true"})
    void aBoatAtTheRootOfTheLocalPlayersRidingTreeBlocksNeitherItsPoseNorItsMove(ClientVersion version, boolean riding)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.place(START, Vec3.ZERO);
            var entities = f.player.compensatedEntities;
            // The boat's box crosses the standing box at head height.
            entities.addEntity(100, EntityTypeIds.OAK_BOAT, START.add(0, 1.6, 0), 0, 0, 0);
            if (riding) rideThroughPigAndStrider(f);
            f.place(START, Vec3.ZERO);
            assertEquals(
                    riding, f.player.canPlayerFitWithinBlocksAndEntitiesWhen(Pose.STANDING, START.x, START.y, START.z));
            assertEquals(
                    riding, f.player.mayPlayerFitWithinBlocksAndEntitiesWhen(Pose.STANDING, START.x, START.y, START.z));
            assertEquals(riding ? 0 : 1, f.player.entityCollisionBoxes().size());
        }
    }

    /**
     * AbstractBoat#canCollideWith is AbstractBoat#canVehicleCollide, which admits an Entity#isPushable player unless
     * Entity#isPassengerOfSameVehicle: the boat's interpolation target shifts through the local player riding two
     * vehicles above it, and not through one standing in its way.
     */
    @ParameterizedTest
    @CsvSource({"V_1_21_4, false", "V_1_21_4, true", "V_26_3, false", "V_26_3, true"})
    void aRootBoatsInterpolationTargetShiftsThroughTheLocalPlayerRidingAboveIt(ClientVersion version, boolean riding)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.place(START, Vec3.ZERO);
            var entities = f.player.compensatedEntities;
            entities.addEntity(100, EntityTypeIds.OAK_BOAT, START.add(3, 0, 0), 0, 0, 0);
            if (riding) rideThroughPigAndStrider(f);
            f.place(START, Vec3.ZERO);
            // The shifted box, centred 0.5 east of the player, overlaps it.
            assertEquals(
                    riding,
                    RootVehicleInterpolationCollision.shouldShiftRootInterpolationTarget(
                            f.player, entities.getEntity(100), START.add(1.5, 0, 0), new Vec3(-1, 0, 0)));
        }
    }

    /** Boat 100 carries a pig, the pig a strider, and the strider the local player. */
    private static void rideThroughPigAndStrider(JavaTickFixture f) {
        var entities = f.player.compensatedEntities;
        entities.addEntity(101, EntityTypeIds.PIG, START.add(3, 0, 0), 0, 0, 0);
        entities.addEntity(102, EntityTypeIds.STRIDER, START.add(3, 0, 0), 0, 0, 0);
        f.setPassengers(100, List.of(101));
        f.setPassengers(101, List.of(102));
        f.setPassengers(102, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        assertTrue(entities.getSelf().inVehicle());
        assertSame(entities.getEntity(100), entities.rootOfSelf());
    }

    private static void assertClearance(JavaTickFixture f, boolean blocked) {
        var player = f.player;
        var box = GetBoundingBox.getBoundingBoxFromPosAndSize(START.x, START.y, START.z, 0.6F, 1.8F);
        assertEquals(blocked, UnknownCollisionProvider.hasEntityCollision(player, START, box));
        assertEquals(
                blocked ? DesyncStatus.FALSE : DesyncStatus.TRUE,
                RootVehicleInterpolationCollision.noEntityCollision(
                        player, player.compensatedEntities.getSelf(), START, box));
        assertEquals(
                !blocked, player.canPlayerFitWithinBlocksAndEntitiesWhen(Pose.STANDING, START.x, START.y, START.z));
        assertEquals(
                !blocked, player.mayPlayerFitWithinBlocksAndEntitiesWhen(Pose.STANDING, START.x, START.y, START.z));
        assertTrue(player.canPlayerFitWithinBlocksAndEntitiesWhen(Pose.CROUCHING, START.x, START.y, START.z));
        assertEquals(1, player.entityCollisionBoxes().size());
        assertEquals(
                START.x + 1.1 - (blocked ? 1.0 : 0) - 1.375 / 2,
                player.entityCollisionBoxes().getFirst().minX,
                1.0E-12);
    }
}
