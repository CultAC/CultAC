package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.packet.WorldPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.VanillaMath;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Entity#collide on a ridden vehicle's box: collideBoundingBox clips the move on each axis at the first shape in its
 * way, and a grounded vehicle whose horizontal move collides steps up to the lowest candidate height within its step
 * height that lets it move further.
 */
class VehicleWallCollisionTest {
    private static final int VEHICLE = 100;
    private static final double HORSE_HALF_WIDTH = 1.3964844F / 2.0;
    private static final double CAMEL_HALF_WIDTH = 1.7F / 2.0;
    private static final double BOAT_HALF_WIDTH = 1.375F / 2.0;
    /** LivingEntity#moveRelative: the ridden 0.98F input times getFrictionInfluencedSpeed on 0.6-friction ground. */
    private static final double HORSE_INPUT = (double) 0.98F * (0.225F * (0.21600002F / (0.6F * 0.6F * 0.6F)));

    private static final double CAMEL_INPUT = (double) 0.98F * (0.09F * (0.21600002F / (0.6F * 0.6F * 0.6F)));
    /** LivingEntity#travelInAir on a 0.6-friction block. */
    private static final double GROUND_FRICTION = 0.6F * 0.91F;
    /** AbstractBoat#floatBoat scales a boat on land by its ground friction, ice's 0.98F. */
    private static final double ICE_FRICTION = 0.98F;
    /** The gap a vehicle's box leaves to the wall in the stopping cases. */
    private static final double GAP = 0.001;

    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput NO_KEYS =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    @Test
    void aHorseWalkingIntoAWallStopsAtIt() throws Exception {
        assertStopsAtTwoHighWall(EntityTypeIds.HORSE, HORSE_HALF_WIDTH, HORSE_INPUT);
    }

    @Test
    void aCamelWalkingIntoAWallStopsAtIt() throws Exception {
        assertStopsAtTwoHighWall(EntityTypeIds.CAMEL, CAMEL_HALF_WIDTH, CAMEL_INPUT);
    }

    /** A camel walking from rest towards a three-high wall: its move into the wall is clipped at the wall's face. */
    @Test
    void aCamelCannotWalkIntoAThickWall() throws Exception {
        Vec3 seat = new Vec3(0.5, FLOOR_Y, 2.0 - 0.6 - CAMEL_HALF_WIDTH);
        try (var f = ridden(EntityTypeIds.CAMEL, seat)) {
            fill(f, 64, 66, 2, 6, BlockIds.STONE.defaultState());
            f.input(FORWARD);
            assertExact(f.rideTick(seat, true));
            double velocity = 0.0;
            double z = seat.z;
            while (true) {
                velocity = velocity * GROUND_FRICTION + CAMEL_INPUT;
                z += velocity;
                PredictionResult result = f.rideTick(new Vec3(seat.x, seat.y, z), true);
                if (z + CAMEL_HALF_WIDTH <= 2.0) {
                    assertExact(result);
                    continue;
                }
                assertRejected(result, "VehicleOffset");
                assertTrue(f.player.getSetbackTeleportUtil().isPendingSetback(), describe(result));
                return;
            }
        }
    }

    /** A standing camel's 2.375-tall box reaches the top slabs on the next layer, so they stop it as a wall does. */
    @Test
    void aStandingCamelStopsAtTopSlabsAhead() throws Exception {
        int topSlab = slab("top");
        Vec3 seat = new Vec3(0.5, FLOOR_Y, 2.0 - GAP - CAMEL_HALF_WIDTH);
        try (var f = ridden(EntityTypeIds.CAMEL, seat)) {
            fill(f, 65, 65, 2, 6, topSlab);
            f.input(FORWARD);
            assertExact(f.rideTick(seat, true));
            assertExact(f.rideTick(new Vec3(seat.x, seat.y, seat.z + GAP), true));
        }
        try (var f = ridden(EntityTypeIds.CAMEL, seat)) {
            fill(f, 65, 65, 2, 6, topSlab);
            f.input(FORWARD);
            assertExact(f.rideTick(seat, true));
            assertRejected(f.rideTick(new Vec3(seat.x, seat.y, seat.z + CAMEL_INPUT), true), "VehicleOffset");
        }
    }

    /**
     * A horse facing 45 degrees off a wall it touches: the wall clips the move's z at the gap and leaves its x, and the
     * step to the top of the first layer is still blocked by the second.
     */
    @Test
    void aHorseWalkingDiagonallyIntoAWallSlidesAlongIt() throws Exception {
        float yaw = -45.0F;
        float radians = yaw * (float) (Math.PI / 180.0);
        Vec3 seat = new Vec3(0.5, FLOOR_Y, 2.0 - GAP - HORSE_HALF_WIDTH);
        try (var f = new JavaTickFixture()) {
            f.player.xRot = yaw;
            f.player.yRot = yaw;
            mountRidden(f, EntityTypeIds.HORSE, seat);
            fill(f, 64, 65, 2, 4, BlockIds.STONE.defaultState());
            f.input(FORWARD);
            assertExact(f.rideTick(seat, true));
            double x = seat.x - HORSE_INPUT * VanillaMath.sin(radians);
            assertExact(f.rideTick(new Vec3(x, seat.y, seat.z + GAP), true));
        }
    }

    /**
     * A horse facing 30 degrees off +z towards a two-high column ahead of its corner. Direction#axisStepOrder moves z,
     * the larger axis, before x: z passes beside the column and x stops at its face.
     */
    @Test
    void aHorsePassingAColumnCornerMovesItsLargerAxisFirst() throws Exception {
        float yaw = -30.0F;
        float radians = yaw * (float) (Math.PI / 180.0);
        Vec3 seat = new Vec3(2.0 - 0.05 - HORSE_HALF_WIDTH, FLOOR_Y, 2.0 - 0.1 - HORSE_HALF_WIDTH);
        try (var f = new JavaTickFixture()) {
            f.player.xRot = yaw;
            f.player.yRot = yaw;
            mountRidden(f, EntityTypeIds.HORSE, seat);
            column(f, 2, 2);
            f.input(FORWARD);
            assertExact(f.rideTick(seat, true));
            double z = seat.z + HORSE_INPUT * VanillaMath.cos(radians);
            assertExact(f.rideTick(new Vec3(2.0 - HORSE_HALF_WIDTH, seat.y, z), true));
        }
    }

    /**
     * A horse facing 60 degrees off +z beside the end of a two-high column. Direction#axisStepOrder moves x, the larger
     * axis, first: the column stops x at its face, and z then passes its end.
     */
    @Test
    void aHorseLeavingAColumnEndMovesItsLargerAxisFirst() throws Exception {
        float yaw = -60.0F;
        float radians = yaw * (float) (Math.PI / 180.0);
        Vec3 seat = new Vec3(2.0 - 0.05 - HORSE_HALF_WIDTH, FLOOR_Y, 2.9 + HORSE_HALF_WIDTH);
        double x = -HORSE_INPUT * VanillaMath.sin(radians);
        double z = HORSE_INPUT * VanillaMath.cos(radians);
        for (boolean clipped : new boolean[] {true, false}) {
            try (var f = new JavaTickFixture()) {
                f.player.xRot = yaw;
                f.player.yRot = yaw;
                mountRidden(f, EntityTypeIds.HORSE, seat);
                column(f, 2, 2);
                f.input(FORWARD);
                assertExact(f.rideTick(seat, true));
                double endX = clipped ? 2.0 - HORSE_HALF_WIDTH : seat.x + x;
                PredictionResult result = f.rideTick(new Vec3(endX, seat.y, seat.z + z), true);
                if (clipped) assertExact(result);
                else assertRejected(result, "VehicleOffset");
            }
        }
    }

    /**
     * A horse facing 60 degrees off +z with a block 0.05 ahead and a glass pane post 0.05 to its side one layer up.
     * The pane stops x and the block stops z; stepping 1.0 onto the block, x moves first again and the pane stops it,
     * while z passes over the block.
     */
    @Test
    void aHorseSteppingBesideAPaneMovesItsLargerAxisFirst() throws Exception {
        float yaw = -60.0F;
        float radians = yaw * (float) (Math.PI / 180.0);
        Vec3 seat = new Vec3(2.4375 - 0.05 - HORSE_HALF_WIDTH, FLOOR_Y, 5.0 - 0.05 - HORSE_HALF_WIDTH);
        try (var f = new JavaTickFixture()) {
            f.player.xRot = yaw;
            f.player.yRot = yaw;
            mountRidden(f, EntityTypeIds.HORSE, seat);
            PredictionScenarios.fill(f, 0, 64, 5, 3, 64, 5, BlockIds.STONE.defaultState());
            PredictionScenarios.fill(f, 2, 65, 3, 2, 65, 3, block("minecraft:glass_pane"));
            settle(f);
            f.input(FORWARD);
            assertExact(f.rideTick(seat, true));
            double z = seat.z + HORSE_INPUT * VanillaMath.cos(radians);
            assertExact(f.rideTick(new Vec3(2.4375 - HORSE_HALF_WIDTH, FLOOR_Y + 1.0, z), true));
        }
    }

    /** A horse 0.001 from a two-high wall with a boat beside it: the wall stops the horse at the gap. */
    @Test
    void aHorseBesideABoatStopsAtAWall() throws Exception {
        Vec3 seat = new Vec3(0.5, FLOOR_Y, 2.0 - GAP - HORSE_HALF_WIDTH);
        for (boolean clipped : new boolean[] {true, false}) {
            try (var f = ridden(EntityTypeIds.HORSE, seat)) {
                f.player.compensatedEntities.addEntity(
                        VEHICLE + 1, EntityTypeIds.OAK_BOAT, new Vec3(3.0, FLOOR_Y, seat.z), 0, 0, 0);
                fill(f, 64, 65, 2, 4, BlockIds.STONE.defaultState());
                f.pongThrough(f.player.lastTransactionSent.get());
                f.input(FORWARD);
                assertExact(f.rideTick(seat, true));
                PredictionResult result =
                        f.rideTick(new Vec3(seat.x, seat.y, seat.z + (clipped ? GAP : HORSE_INPUT)), true);
                if (clipped) assertExact(result);
                else assertRejected(result, "VehicleOffset");
            }
        }
    }

    /**
     * A horse carried through water flowing along +x, past the end of a two-high column. The current pushes x past z,
     * and Direction#axisStepOrder moves x first: the column stops x at its face, and z then passes the column's end.
     * The current is part of the movement the column stops.
     */
    @Test
    void aHorseInACurrentPassingAColumnEndMovesXFirst() throws Exception {
        Vec3 seat = new Vec3(2.0 - 0.05 - HORSE_HALF_WIDTH, FLOOR_Y, 2.9 + HORSE_HALF_WIDTH);
        for (boolean clipped : new boolean[] {true, false}) {
            try (var f = ridden(EntityTypeIds.HORSE, seat)) {
                flowingWater(f, true);
                column(f, 2, 2);
                carry(f, seat, new Vec3(0.19, 0.0, 0.198));
                double x = 2.0 - HORSE_HALF_WIDTH + (clipped ? 0.0 : 0.01);
                PredictionResult result = f.rideTick(new Vec3(x, seat.y, seat.z + 0.198), true);
                if (clipped) assertExact(result);
                else assertRejected(result, "VehicleOffset");
            }
        }
    }

    /**
     * A horse carried through water flowing along +z, past the end of a two-high column. The current pushes z past x,
     * and Direction#axisStepOrder moves z first: the column stops z at its face, and x then passes the column's end.
     */
    @Test
    void aHorseInACurrentPassingAColumnEndMovesZFirst() throws Exception {
        Vec3 seat = new Vec3(2.9 + HORSE_HALF_WIDTH, FLOOR_Y, 2.0 - 0.05 - HORSE_HALF_WIDTH);
        for (boolean clipped : new boolean[] {true, false}) {
            try (var f = ridden(EntityTypeIds.HORSE, seat)) {
                flowingWater(f, false);
                column(f, 2, 2);
                carry(f, seat, new Vec3(0.198, 0.0, 0.19));
                double z = 2.0 - HORSE_HALF_WIDTH + (clipped ? 0.0 : 0.01);
                PredictionResult result = f.rideTick(new Vec3(seat.x + 0.198, seat.y, z), true);
                if (clipped) assertExact(result);
                else assertRejected(result, "VehicleOffset");
            }
        }
    }

    /**
     * A horse carried through water flowing along +x, with a block 0.05 ahead and a glass pane post 0.05 to its side
     * one layer up. The current pushes x past z; stepping 1.0 onto the block, x moves first and the pane stops it,
     * while z passes over the block.
     */
    @Test
    void aHorseInACurrentSteppingBesideAPaneMovesXFirst() throws Exception {
        Vec3 seat = new Vec3(2.4375 - 0.05 - HORSE_HALF_WIDTH, FLOOR_Y, 5.0 - 0.05 - HORSE_HALF_WIDTH);
        for (boolean clipped : new boolean[] {true, false}) {
            try (var f = ridden(EntityTypeIds.HORSE, seat)) {
                flowingWater(f, true);
                PredictionScenarios.fill(f, 0, 64, 5, 3, 64, 5, BlockIds.STONE.defaultState());
                PredictionScenarios.fill(f, 2, 65, 3, 2, 65, 3, block("minecraft:glass_pane"));
                settle(f);
                carry(f, seat, new Vec3(0.19, 0.0, 0.198));
                double x = 2.4375 - HORSE_HALF_WIDTH + (clipped ? 0.0 : 0.01);
                PredictionResult result = f.rideTick(new Vec3(x, FLOOR_Y + 1.0, seat.z + 0.198), true);
                if (clipped) assertExact(result);
                else assertRejected(result, "VehicleOffset");
            }
        }
    }

    /** A horse walking into a one-high block steps onto it: the 1.0 candidate height clears it. */
    @Test
    void aHorseStepsUpOntoABlock() throws Exception {
        Vec3 seat = new Vec3(0.5, FLOOR_Y, 2.0 - 0.05 - HORSE_HALF_WIDTH);
        try (var f = ridden(EntityTypeIds.HORSE, seat)) {
            fill(f, 64, 64, 2, 4, BlockIds.STONE.defaultState());
            f.input(FORWARD);
            assertExact(f.rideTick(seat, true));
            assertExact(f.rideTick(new Vec3(seat.x, FLOOR_Y + 1.0, seat.z + HORSE_INPUT), true));
        }
    }

    /**
     * A camel walking into a block topped by a bottom slab: the 1.0 candidate height still meets the slab, and the 1.5
     * one clears it.
     */
    @Test
    void aCamelStepsUpABlockAndASlab() throws Exception {
        Vec3 seat = new Vec3(0.5, FLOOR_Y, 2.0 - 0.05 - CAMEL_HALF_WIDTH);
        try (var f = ridden(EntityTypeIds.CAMEL, seat)) {
            fill(f, 64, 64, 2, 4, BlockIds.STONE.defaultState());
            fill(f, 65, 65, 2, 4, slab("bottom"));
            f.input(FORWARD);
            assertExact(f.rideTick(seat, true));
            assertExact(f.rideTick(new Vec3(seat.x, FLOOR_Y + 1.5, seat.z + CAMEL_INPUT), true));
        }
    }

    /** A boat sliding on ice into a wall stops at it, and the collision zeroes its velocity there. */
    @Test
    void aBoatSlidingOnIceStopsAtAWall() throws Exception {
        double gap = 0.05;
        Vec3 start = new Vec3(0.5, FLOOR_Y, 2.0 - gap - BOAT_HALF_WIDTH);
        try (var f = new JavaTickFixture()) {
            iceFloor(f);
            fill(f, 64, 65, 2, 4, BlockIds.STONE.defaultState());
            mountBoat(f, start, new Vec3(0, 0, 0.4));

            Vec3 atWall = new Vec3(start.x, start.y, start.z + gap);
            assertExact(f.rideTick(atWall, true));
            assertExact(f.rideTick(atWall, true));
        }
    }

    /** A boat touching a wall slides along it on ice, its velocity scaled by ice's friction each tick. */
    @Test
    void aBoatSlidesAlongAWallOnIce() throws Exception {
        Vec3 start = new Vec3(-1.5, FLOOR_Y, 2.0 - BOAT_HALF_WIDTH);
        try (var f = new JavaTickFixture()) {
            iceFloor(f);
            fill(f, 64, 65, 2, 4, BlockIds.STONE.defaultState());
            mountBoat(f, start, new Vec3(0.4, 0, 0));

            double velocity = 0.4;
            double x = start.x;
            for (int tick = 0; tick < 3; tick++) {
                velocity *= ICE_FRICTION;
                x += velocity;
                assertExact(f.rideTick(new Vec3(x, start.y, start.z), true));
            }
        }
    }

    /**
     * A vehicle 0.001 from a two-high wall, ridden forward: the input reaches the move one tick after it is sent, and
     * the move into the wall is clipped at the gap.
     */
    private static void assertStopsAtTwoHighWall(int type, double halfWidth, double input) throws Exception {
        Vec3 seat = new Vec3(0.5, FLOOR_Y, 2.0 - GAP - halfWidth);
        try (var f = ridden(type, seat)) {
            fill(f, 64, 65, 2, 4, BlockIds.STONE.defaultState());
            f.input(FORWARD);
            assertExact(f.rideTick(seat, true));
            assertExact(f.rideTick(new Vec3(seat.x, seat.y, seat.z + GAP), true));
        }
        try (var f = ridden(type, seat)) {
            fill(f, 64, 65, 2, 4, BlockIds.STONE.defaultState());
            f.input(FORWARD);
            assertExact(f.rideTick(seat, true));
            PredictionResult result = f.rideTick(new Vec3(seat.x, seat.y, seat.z + input), true);
            assertRejected(result, "VehicleOffset");
        }
    }

    private static JavaTickFixture ridden(int type, Vec3 seat) throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        mountRidden(f, type, seat);
        return f;
    }

    /** A saddled {@code type} at {@code seat} with the player riding it, its pose settled. */
    private static void mountRidden(JavaTickFixture f, int type, Vec3 seat) {
        f.place(seat, Vec3.ZERO);
        f.player.compensatedWorld.applyClientTime(new WorldPackets.TimeUpdate(24_000L, Map.of()));
        f.player.compensatedEntities.addEntity(VEHICLE, type, seat, 0, 0, 0);
        ((PacketEntityHorse) f.player.compensatedEntities.getEntity(VEHICLE)).hasSaddle = true;
        f.setPassengers(VEHICLE, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        assertExact(f.rideTick(seat, true));
    }

    /** Water on the floor layer, its level rising by one a block along +x, or along +z, so it flows that way. */
    private static void flowingWater(JavaTickFixture f, boolean alongX) {
        var registry = DataTables.defaults().registry();
        for (int i = -3; i <= 8; i++) {
            int water =
                    registry.with(block("minecraft:water"), "level", String.valueOf(Math.max(1, Math.min(7, i + 4))));
            if (alongX) PredictionScenarios.fill(f, i, 64, -3, i, 64, 8, water);
            else PredictionScenarios.fill(f, -3, 64, i, 8, 64, i, water);
        }
    }

    /** Holds the vehicle at {@code seat} with no keys, then gives it {@code velocity} for its next move. */
    private static void carry(JavaTickFixture f, Vec3 seat, Vec3 velocity) {
        f.pongThrough(f.player.lastTransactionSent.get());
        f.input(NO_KEYS);
        // The move follows the client ticks CompensatedVehicleState tracks after a vehicle switch.
        for (int tick = 0; tick < 5; tick++) assertExact(f.rideTick(seat, true));
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(velocity);
    }

    /** A boat at {@code start} on the ground carrying {@code velocity}, ridden with no keys held. */
    private static void mountBoat(JavaTickFixture f, Vec3 start, Vec3 velocity) {
        f.player.compensatedEntities.addEntity(VEHICLE, EntityTypeIds.OAK_BOAT, start, 0, 0, 0);
        PacketEntity boat = f.player.compensatedEntities.getEntity(VEHICLE);
        boat.onGround = true;
        f.setPassengers(VEHICLE, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.input(NO_KEYS);
        f.tickEnd();
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(velocity);
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
    }

    /** {@code state} over x -3..3 on layers {@code y0..y1} and rows {@code z0..z1}. */
    private static void fill(JavaTickFixture f, int y0, int y1, int z0, int z1, int state) {
        PredictionScenarios.fill(f, -3, y0, z0, 3, y1, z1, state);
        settle(f);
    }

    /** A two-high stone column on the floor at {@code x, z}. */
    private static void column(JavaTickFixture f, int x, int z) {
        PredictionScenarios.fill(f, x, 64, z, x, 65, z, BlockIds.STONE.defaultState());
        settle(f);
    }

    private static void settle(JavaTickFixture f) {
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    /** Ice in place of the stone floor under x -3..3, z -3..1. */
    private static void iceFloor(JavaTickFixture f) {
        fill(f, FLOOR_Y - 1, FLOOR_Y - 1, -3, 1, block("minecraft:ice"));
    }

    private static int slab(String type) {
        var registry = DataTables.defaults().registry();
        return registry.with(block("minecraft:oak_slab"), "type", type);
    }

    private static int block(String id) {
        return DataTables.defaults().registry().block(id).defaultState();
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }
}
