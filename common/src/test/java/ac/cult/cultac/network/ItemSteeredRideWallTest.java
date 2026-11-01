package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.packet.WorldPackets;
import ac.cult.cultac.utils.data.packetentity.PacketEntityRideable;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.VanillaMath;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A rider holding a carrot on a stick steers a saddled pig into a wall: Entity#collide clips the move to nothing and
 * Entity#move zeroes the blocked axes of its deltaMovement, so after standing at the wall the pig walks off from rest.
 */
class ItemSteeredRideWallTest {
    private static final int PIG = 100;
    private static final double HALF_WIDTH = 0.9F / 2.0;
    /** The gap the pig's box leaves to the wall before its first move. */
    private static final double GAP = 0.001;
    /** Pig#getRiddenSpeed, its movement_speed of 0.25 times 0.225, through getFrictionInfluencedSpeed on stone. */
    private static final float INPUT = (float) (0.25 * 0.225) * (0.21600002F / (0.6F * 0.6F * 0.6F));

    private static final float GROUND_FRICTION = 0.6F * 0.91F;
    /** LivingEntity#travelInWater: moveRelative's 0.02F and the pig's getWaterSlowDown. */
    private static final float WATER_INPUT = 0.02F;

    private static final float WATER_FRICTION = 0.8F;
    /** A yaw this far off the wall's normal leaves an along-wall move below Entity#move's setPos guard. */
    private static final float SKEW = 0.05F;

    private static final int STANDING_TICKS = 40;
    private static final int WALKING_TICKS = 5;

    @Test
    void aPigSteeredIntoAWallWalksOffFromRest() throws Exception {
        assertWalksOffWall(false, INPUT, GROUND_FRICTION);
    }

    @Test
    void aPigSteeredIntoACornerWalksOffFromRest() throws Exception {
        assertWalksOffCorner(false, INPUT, GROUND_FRICTION);
    }

    /** In one-deep water the pig stands on the floor, so Entity#move also zeroes the fall of each tick. */
    @Test
    void aPigSteeredIntoAWallInShallowWaterWalksOffFromRest() throws Exception {
        assertWalksOffWall(true, WATER_INPUT, WATER_FRICTION);
    }

    @Test
    void aPigSteeredIntoACornerInShallowWaterWalksOffFromRest() throws Exception {
        assertWalksOffCorner(true, WATER_INPUT, WATER_FRICTION);
    }

    /** The along-wall part of the input survives each clipped tick in deltaMovement while the pig stands still. */
    @Test
    void aPigSteeredSlightlyOffSquareIntoAWallKeepsItsAlongWallVelocity() throws Exception {
        assertWalksOffSkewedWall(false, true, INPUT, GROUND_FRICTION);
    }

    @Test
    void aPigSteeredSlightlyOffSquareIntoAWallInShallowWaterKeepsItsAlongWallVelocity() throws Exception {
        assertWalksOffSkewedWall(true, true, WATER_INPUT, WATER_FRICTION);
    }

    @Test
    void aPigSteeredSlightlyOffSquareIntoAWallWithTheItemInTheHotbarKeepsItsAlongWallVelocity() throws Exception {
        assertWalksOffSkewedWall(false, false, INPUT, GROUND_FRICTION);
    }

    @Test
    void aPigSteeredSlightlyOffSquareIntoAWallInShallowWaterWithTheItemInTheHotbarKeepsItsAlongWallVelocity()
            throws Exception {
        assertWalksOffSkewedWall(true, false, WATER_INPUT, WATER_FRICTION);
    }

    private static void assertWalksOffSkewedWall(boolean water, boolean held, float input, float friction)
            throws Exception {
        Vec3 seat = new Vec3(0.5, FLOOR_Y, 2.0 - GAP - HALF_WIDTH);
        try (var f = steered(seat, SKEW, held)) {
            if (water) PredictionScenarios.fill(f, -3, 64, -3, 3, 64, 1, BlockIds.WATER.defaultState());
            PredictionScenarios.fill(f, -3, 64, 2, 3, 65, 6, BlockIds.STONE.defaultState());
            settle(f);
            float radians = SKEW * (float) (Math.PI / 180.0);
            double alongWall = -(double) input * VanillaMath.sin(radians);
            // The closing move commits; the clipped z velocity is zeroed, the along-wall x velocity is kept.
            double velocityX = alongWall;
            Vec3 wall = seat.add(velocityX, 0, GAP);
            assertNoFlags(f.rideTick(wall, true), 0);
            for (int tick = 1; tick <= STANDING_TICKS; tick++) {
                velocityX = velocityX * friction + alongWall;
                PredictionResult result = f.rideTick(wall, true);
                assertNoFlags(result, tick);
                assertTrue(
                        result.getValidMovements().getClosestTrace().position().hasReason("Entity#move setPos guard"),
                        "tick " + tick + " " + describe(result));
            }
            f.player.xRot = 180.0F;
            float away = 180.0F * (float) (Math.PI / 180.0);
            double inputX = -(double) input * VanillaMath.sin(away);
            double inputZ = (double) input * VanillaMath.cos(away);
            double velocityZ = 0.0;
            Vec3 at = wall;
            for (int tick = STANDING_TICKS + 1; tick <= STANDING_TICKS + WALKING_TICKS; tick++) {
                velocityX = velocityX * friction + inputX;
                velocityZ = velocityZ * friction + inputZ;
                at = new Vec3(at.x + velocityX, at.y, at.z + velocityZ);
                assertNoFlags(f.rideTick(at, true), tick);
            }
        }
    }

    private static void assertNoFlags(PredictionResult result, int tick) {
        assertTrue(result.getFlags().isEmpty() && !result.isExempt(), "tick " + tick + " " + describe(result));
    }

    private static void assertWalksOffWall(boolean water, float input, float friction) throws Exception {
        Vec3 seat = new Vec3(0.5, FLOOR_Y, 2.0 - GAP - HALF_WIDTH);
        try (var f = steered(seat, 0.0F)) {
            if (water) PredictionScenarios.fill(f, -3, 64, -3, 3, 64, 1, BlockIds.WATER.defaultState());
            PredictionScenarios.fill(f, -3, 64, 2, 3, 65, 6, BlockIds.STONE.defaultState());
            settle(f);
            standThenWalkOff(f, seat.add(0, 0, GAP), 180.0F, input, friction);
        }
    }

    private static void assertWalksOffCorner(boolean water, float input, float friction) throws Exception {
        Vec3 seat = new Vec3(2.0 - GAP - HALF_WIDTH, FLOOR_Y, 2.0 - GAP - HALF_WIDTH);
        try (var f = steered(seat, -45.0F)) {
            if (water) PredictionScenarios.fill(f, -3, 64, -3, 1, 64, 1, BlockIds.WATER.defaultState());
            PredictionScenarios.fill(f, -3, 64, 2, 6, 65, 6, BlockIds.STONE.defaultState());
            PredictionScenarios.fill(f, 2, 64, -3, 6, 65, 1, BlockIds.STONE.defaultState());
            settle(f);
            standThenWalkOff(f, seat.add(GAP, 0, GAP), 135.0F, input, friction);
        }
    }

    /** The first move closes the gap, the pig then stands at the wall, and the rider turns it away. */
    private static void standThenWalkOff(JavaTickFixture f, Vec3 wall, float away, float input, float friction) {
        PredictionResult closing = f.rideTick(wall, true);
        assertTrue(exact(closing), describe(closing));
        for (int tick = 1; tick <= STANDING_TICKS; tick++) {
            assertVehicleExact(f.rideTick(wall, true), tick);
        }
        f.player.xRot = away;
        float radians = away * (float) (Math.PI / 180.0);
        double inputX = -(double) input * VanillaMath.sin(radians);
        double inputZ = (double) input * VanillaMath.cos(radians);
        double velocityX = 0.0;
        double velocityZ = 0.0;
        Vec3 at = wall;
        for (int tick = STANDING_TICKS + 1; tick <= STANDING_TICKS + WALKING_TICKS; tick++) {
            velocityX = velocityX * friction + inputX;
            velocityZ = velocityZ * friction + inputZ;
            at = new Vec3(at.x + velocityX, at.y, at.z + velocityZ);
            assertVehicleExact(f.rideTick(at, true), tick);
        }
    }

    private static JavaTickFixture steered(Vec3 seat, float yaw) throws Exception {
        return steered(seat, yaw, true);
    }

    /** A saddled pig at {@code seat} under the rider facing {@code yaw}, who then takes a carrot on a stick in hand or in another hotbar slot. */
    private static JavaTickFixture steered(Vec3 seat, float yaw, boolean held) throws Exception {
        var f = new JavaTickFixture();
        f.player.xRot = yaw;
        f.place(seat, Vec3.ZERO);
        f.player.compensatedWorld.applyClientTime(new WorldPackets.TimeUpdate(24_000L, Map.of()));
        f.player.compensatedEntities.addEntity(PIG, EntityTypeIds.PIG, seat, 0, 0, 0);
        ((PacketEntityRideable) f.player.compensatedEntities.getEntity(PIG)).hasSaddle = true;
        f.setPassengers(PIG, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        for (int tick = 0; tick < 20; tick++) f.rideTick();
        var inventory = f.player.getInventory().inventory;
        var item = ItemUtil.modelItems().stack("minecraft:carrot_on_a_stick", 1);
        if (held) {
            inventory.setHeldItem(item);
        } else {
            inventory.getInventoryStorage().setItem(Inventory.HOTBAR_OFFSET + (inventory.selected + 4) % 9, item);
        }
        return f;
    }

    private static void settle(JavaTickFixture f) {
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }
}
