package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Entity#push from a pushable entity of the player's size that stands where the player stood one tick earlier and
 * ticks after it: the push lands in the next tick's start.
 */
class JavaTrailingEntityPushTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput SPRINT_KEY =
            new ServerboundPlayerInput(false, false, false, false, false, false, true);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final Vec3 GROUND = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final Vec3 AIR = new Vec3(0.5, FLOOR_Y + 10, 0.5);
    private static final double STANDING_Y = (0 - 0.08) * 0.98F;
    private static final double GROUND_FRICTION = 0.6F * 0.91F;
    private static final double ICE_FRICTION = 0.98F * 0.91F;
    private static final double AIR_FRICTION = 0.91F;
    /** LivingEntity#travelInFluid's horizontal water slowdown off a sprint. */
    private static final double WATER_FRICTION = 0.8F;

    private static final double WALK_STEP = (double) 0.98F * (double) (0.1F * (0.21600002F / (0.6F * 0.6F * 0.6F)));
    private static final int PACKED_ICE =
            DataTables.defaults().registry().block("minecraft:packed_ice").defaultState();
    private static final int HORSE = 100;
    private static final int ZOMBIE = 101;
    private static final int IDLE_RIDDEN_TICKS = 5;

    /** A steady drift of a 1.21.11 client holding no key. */
    private static final Vec3[] STEADY = {
        new Vec3(0.01212909610919155, 0, -0.003022728009732134),
        new Vec3(0.012129096108438375, 0, -0.0030227280095438402),
        new Vec3(0.01212909610785573, 0, -0.003022728009398179),
        new Vec3(0.012129096107400983, 0, -0.003022728009284492),
        new Vec3(0.012129096107059922, 0, -0.003022728009199227),
    };

    /** The drift of a 1.21.11 client after it released its keys and landed on a block. */
    private static final Vec3[] DECAY = {
        new Vec3(0.01225141876069813, 0, -0.030254555917167636),
        new Vec3(0.010211045537246832, 0, -0.025215907986989805),
        new Vec3(0.008790393940032004, 0, -0.02170764628874622),
        new Vec3(0.007782684283938579, 0, -0.019219133836941182),
        new Vec3(0.0070562821625088645, 0, -0.017425302932110753),
    };

    @Test
    void aClientHoldingNoKeyDriftsByThePushOfTheEntityWhereItStood() throws Exception {
        try (var f = onGround(STEADY[0])) {
            Vec3 at = GROUND;
            for (Vec3 move : STEADY) {
                at = at.add(move);
                assertExact(f.tick(at, true));
            }
        }
    }

    @Test
    void aClientHoldingNoKeyDriftsDiagonallyByThePushOfTheEntityWhereItStood() throws Exception {
        Vec3 move = new Vec3(STEADY[0].x, 0, STEADY[0].x);
        try (var f = onGround(move)) {
            Vec3 at = GROUND;
            for (int tick = 0; tick < 5; tick++) {
                at = at.add(move);
                assertExact(f.tick(at, true));
                move = pushed(move, GROUND_FRICTION);
            }
        }
    }

    @Test
    void aClientHoldingNoKeySlowsByFrictionLessThatPush() throws Exception {
        try (var f = onGround(DECAY[0])) {
            Vec3 at = GROUND;
            for (Vec3 move : DECAY) {
                at = at.add(move);
                assertExact(f.tick(at, true));
            }
        }
    }

    @Test
    void aClientHoldingNoKeyWithoutThatEntitySlowsByFriction() throws Exception {
        try (var f = onGround(DECAY[0])) {
            Vec3 move = DECAY[0];
            Vec3 at = GROUND.add(move);
            assertExact(f.tick(at, true));
            for (int tick = 0; tick < 2; tick++) {
                move = move.multiply(GROUND_FRICTION, 0, GROUND_FRICTION);
                at = at.add(move);
                assertExact(f.tick(at, true));
            }
        }
    }

    @Test
    void theTickThatPressesSprintCarriesThePushOfTheTickBefore() throws Exception {
        try (var f = onGround(STEADY[0])) {
            Vec3 at = GROUND.add(STEADY[0]);
            assertExact(f.tick(at, true));
            f.input(SPRINT_KEY);
            Vec3 move = pushed(STEADY[0], GROUND_FRICTION);
            at = at.add(move);
            assertExact(f.tick(at, true));
            assertExact(f.tick(at.add(move.multiply(GROUND_FRICTION, 0, GROUND_FRICTION)), true));
        }
    }

    @Test
    void aPushedMoveAfterATickWithTheSprintKey() throws Exception {
        try (var f = onGround(STEADY[0])) {
            Vec3 at = GROUND.add(STEADY[0]);
            assertExact(f.tick(at, true));
            f.input(SPRINT_KEY);
            Vec3 move = pushed(STEADY[0], GROUND_FRICTION);
            at = at.add(move);
            assertExact(f.tick(at, true));
            assertFlagged(f.tick(at.add(pushed(move, GROUND_FRICTION)), true));
        }
    }

    @Test
    void aWalkingClientMovesByItsInput() throws Exception {
        try (var f = onGround(Vec3.ZERO)) {
            f.input(FORWARD);
            double z = WALK_STEP;
            Vec3 at = GROUND.add(0, 0, z);
            assertExact(f.tick(at, true));
            for (int tick = 0; tick < 3; tick++) {
                z = z * GROUND_FRICTION + WALK_STEP;
                at = at.add(0, 0, z);
                assertExact(f.tick(at, true));
            }
        }
    }

    @Test
    void aPushedMoveAfterATickWithAMovementKey() throws Exception {
        try (var f = onGround(Vec3.ZERO)) {
            f.input(FORWARD);
            Vec3 move = new Vec3(0, 0, WALK_STEP);
            Vec3 at = GROUND.add(move);
            assertExact(f.tick(at, true));
            f.input(NONE);
            assertFlagged(f.tick(at.add(pushed(move, GROUND_FRICTION)), true));
        }
    }

    @Test
    void aDriftFromRest() throws Exception {
        try (var f = onGround(Vec3.ZERO)) {
            assertExact(f.tick(GROUND, true));
            assertFlagged(f.tick(GROUND.add(STEADY[0]), true));
        }
    }

    @Test
    void aDriftThatKeepsItsSpeed() throws Exception {
        Vec3 drift = new Vec3(0.02, 0, 0);
        try (var f = onGround(drift)) {
            Vec3 at = GROUND.add(drift);
            assertExact(f.tick(at, true));
            assertFlagged(f.tick(at.add(drift), true));
        }
    }

    @Test
    void aPushAcrossTheLastMove() throws Exception {
        try (var f = onGround(STEADY[0])) {
            Vec3 at = GROUND.add(STEADY[0]);
            assertExact(f.tick(at, true));
            Vec3 slid = STEADY[0].multiply(GROUND_FRICTION, 0, GROUND_FRICTION);
            Vec3 push = pushed(STEADY[0], GROUND_FRICTION).subtract(slid);
            Vec3 across = slid.add(-push.z, 0, push.x);
            assertFlagged(f.tick(at.add(across), true));
        }
    }

    @Test
    void aPushedSlideOnPackedIce() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11)) {
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y - 1, z), PACKED_ICE);
                }
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 drift = new Vec3(0.05, 0, 0);
            f.place(GROUND, new Vec3(drift.x, STANDING_Y, drift.z));
            f.input(NONE);
            Vec3 at = GROUND.add(drift);
            assertExact(f.tick(at, true));
            assertExact(f.tick(at.add(drift.multiply(ICE_FRICTION, 0, ICE_FRICTION)), true));
            Vec3 slid = drift.multiply(ICE_FRICTION, 0, ICE_FRICTION);
            at = at.add(slid);
            assertFlagged(f.tick(at.add(pushed(slid, ICE_FRICTION)), true));
        }
    }

    @Test
    void aPushedMoveAfterTicksInTheAir() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11)) {
            Vec3 move = new Vec3(0.5, 0, 0);
            f.place(AIR, move);
            f.input(NONE);
            Vec3 at = AIR;
            for (int tick = 0; tick < 3; tick++) {
                if (tick > 0) move = new Vec3(move.x * AIR_FRICTION, fall(move.y), 0);
                at = at.add(move);
                assertExact(f.tick(at, false));
            }
            Vec3 pushed = pushed(move.multiply(1, 0, 1), AIR_FRICTION).add(0, fall(move.y), 0);
            assertFlagged(f.tick(at.add(pushed), false));
        }
    }

    @Test
    void aPushedMoveStandingInWater() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11)) {
            f.water(new BlockPos(-2, FLOOR_Y, -2), new BlockPos(2, FLOOR_Y, 2));
            Vec3 drift = new Vec3(0.5, 0, -0.125);
            f.place(GROUND, new Vec3(drift.x, -0.005, drift.z));
            f.input(NONE);
            Vec3 at = GROUND.add(drift);
            assertExact(f.tick(at, true));
            assertFlagged(f.tick(at.add(pushed(drift, WATER_FRICTION)), true));
        }
    }

    @Test
    void aPushedMoveWhileRiding() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11)) {
            f.place(GROUND, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(HORSE, EntityTypeIds.HORSE, GROUND, 0, 0, 0);
            PacketEntityHorse horse = (PacketEntityHorse) f.player.compensatedEntities.getEntity(HORSE);
            horse.hasSaddle = true;
            horse.isTame = true;
            horse.onGround = true;
            f.setPassengers(HORSE, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.pongThrough(f.player.sendTrailingTransaction());
            f.tickEnd();
            f.input(NONE);
            f.player.checkManager.getSimulationProcessor().seedStartingVelocity(new Vec3(0, STANDING_Y, 0));
            f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
            for (int tick = 0; tick <= IDLE_RIDDEN_TICKS; tick++) f.rideTick(GROUND, true);
            f.player
                    .checkManager
                    .getSimulationProcessor()
                    .seedStartingVelocity(new Vec3(STEADY[0].x, STANDING_Y, STEADY[0].z));
            Vec3 at = GROUND.add(STEADY[0]);
            assertExact(f.rideTick(at, true));
            assertFlagged(f.rideTick(at.add(pushed(STEADY[0], GROUND_FRICTION)), true));
        }
    }

    @Test
    void aPushedMoveBesideAnotherEntity() throws Exception {
        try (var f = onGround(STEADY[0])) {
            f.player.compensatedEntities.addEntity(ZOMBIE, EntityTypeIds.ZOMBIE, GROUND.add(0, 0, 0.5), 0, 0, 0);
            f.player.compensatedEntities.getEntity(ZOMBIE).setClientTickOrder(-1L);
            Vec3 at = GROUND.add(STEADY[0]);
            assertExact(f.tick(at, true));
            Vec3 move = pushed(STEADY[0], GROUND_FRICTION).add(0.07, 0, 0);
            assertFlagged(f.tick(at.add(move), true));
        }
    }

    /** A 1.21.11 client standing on stone, holding no key, whose next move starts from {@code move}. */
    private static JavaTickFixture onGround(Vec3 move) throws Exception {
        JavaTickFixture f = new JavaTickFixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11);
        f.place(GROUND, new Vec3(move.x, STANDING_Y, move.z));
        f.input(NONE);
        return f;
    }

    /** The next move after {@code move}: its friction plus Entity#push from where it started. */
    private static Vec3 pushed(Vec3 move, double friction) {
        double absMax = Math.max(Math.abs(move.x), Math.abs(move.z));
        double root = Math.sqrt(absMax);
        double scale = Math.min(1.0D, 1.0D / root);
        return new Vec3(
                move.x * friction + move.x / root * scale * 0.05F,
                0,
                move.z * friction + move.z / root * scale * 0.05F);
    }

    /** LivingEntity#travel: gravity, then the 0.98F air drag. */
    private static double fall(double velocity) {
        return (velocity - 0.08) * 0.98F;
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }

    private static void assertFlagged(PredictionResult result) {
        assertFalse(result.getFlags().isEmpty(), describe(result));
    }
}
