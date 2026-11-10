package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaLivingEntityPushes;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.CarriedPush;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.EntityPush;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.events.packets.PacketChangeGameState;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.packet.EntityPositionPath;
import ac.cult.cultac.network.packet.WorldPackets;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityEvent;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityPositionSync;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundGameEvent;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRemoveEntities;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundUpdateAttributes;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.EntityPose;
import ac.cult.cultac.protocol.value.GameEventType;
import ac.cult.cultac.protocol.value.PositionPath;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** ClientLevel#tickEntities order: an entity ticking before the player pushes this travel, one after it the next. */
class JavaEntityPushTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y + 10, 0.5);
    private static final Vec3 TOUCHING = new Vec3(0.5, FLOOR_Y + 9, 1.0);
    /** Entity#push from {@link #TOUCHING}, 0.5 along Z: more than airborne input reaches. */
    private static final double PUSH = -0.05 * 0.5 / Math.sqrt(0.5);

    private static final double FIRST_FALL = fall(0);
    private static final double SECOND_FALL = fall(FIRST_FALL);
    private static final Vec3 CONTACT = START.add(0, FIRST_FALL, 0);
    private static final Vec3 AWAY = new Vec3(4.5, FLOOR_Y, 4.5);
    /** 0.6 along Z: a 26.1 bee's 0.7-wide box reaches the player's box, the 0.55-wide bee from 26.2 does not. */
    private static final Vec3 BEE_EDGE = new Vec3(0.5, START.y, 1.1);
    /** Entity#push from {@link #BEE_EDGE}. */
    private static final double BEE_PUSH = -0.05 * 0.6 / Math.sqrt(0.6);
    /** A zombie whose top stays below the player's contact box while a slime seated on it reaches into it. */
    private static final Vec3 BELOW_TOUCHING = TOUCHING.add(0, -1.5, 0);

    private static final int RIDER = 101;
    private static final int CAMEL = 102;
    /** Out of reach of the player's contact box until its first interpolation step lands it on {@link #TOUCHING}. */
    private static final Vec3 STEPPING_FROM = TOUCHING.add(0, 0, 0.4);
    /** Three interpolation steps of -0.4 each. */
    private static final double STEPPING_DZ = -1.2;

    /**
     * A recorded interaction at (593.6777343125403, 61.675, 1176.112483456511) less the player's
     * (593.5222185027443, 62.92159999847412, 1175.578128188504).
     */
    private static final Vec3 INTERACTION_FROM_CONTACT =
            new Vec3(593.6777343125403 - 593.5222185027443, 61.675 - 62.92159999847412, 1176.112483456511 - 1175.578128188504);
    /** The update_attributes scale of the slime riding that interaction. */
    private static final double INTERACTION_SLIME_SCALE = 1.2254902194528017;
    /** Entity#push on the player from the slime at {@link #INTERACTION_FROM_CONTACT}. */
    private static final double INTERACTION_PUSH_X = -push(INTERACTION_FROM_CONTACT.x, INTERACTION_FROM_CONTACT.z);
    private static final double INTERACTION_PUSH_Z = -push(INTERACTION_FROM_CONTACT.z, INTERACTION_FROM_CONTACT.x);

    private static final RelativeFlag ALL_DELTAS =
            keep(RelativeFlag.DELTA_X, RelativeFlag.DELTA_Y, RelativeFlag.DELTA_Z, RelativeFlag.ROTATE_DELTA);

    @Test
    void anEntityTickingAfterThePlayerDoesNotPushItOnFirstContact() throws Exception {
        try (var f = falling()) {
            entity(f, 100, TOUCHING);

            PredictionResult contact = f.tick(START.add(0, FIRST_FALL, PUSH), false);

            assertFalse(exact(contact), describe(contact));
            WorldData world = contact.getSimulationContext().getWorldData();
            assertEquals(1, world.getNumCollidingTickingAfterPlayer());
            assertEquals(0, world.getNumPushing());
        }
    }

    @Test
    void anEntityTickingAfterThePlayerPushesItTheNextTick() throws Exception {
        try (var f = falling()) {
            entity(f, 100, TOUCHING);

            assertExact(f.tick(START.add(0, FIRST_FALL, 0), false));
            assertExact(f.tick(START.add(0, FIRST_FALL + SECOND_FALL, PUSH), false));
        }
    }

    /** LivingEntity#aiStep runs the interpolation step before pushEntities: the push comes from where it stepped to. */
    @Test
    void anEntityTickingAfterThePlayerPushesFromWhereItsInterpolationStepLeftIt() throws Exception {
        try (var f = falling()) {
            PacketEntity entity = entity(f, 100, STEPPING_FROM);
            entity.onBundleFirstTransaction(true, true, 0, 0, STEPPING_DZ, f.player);
            entity.onSecondTransaction();

            PredictionResult first = f.tick(START.add(0, FIRST_FALL, 0), false);
            assertExact(first);
            assertEquals(0, first.getSimulationContext().getWorldData().getNumPushing());
            assertExact(f.tick(START.add(0, FIRST_FALL + SECOND_FALL, PUSH), false));
        }
    }

    /** EntityType#BEE is sized(0.7F, 0.6F) through 26.1, so its box still touches the player's from here. */
    @Test
    void a26Dot1BeePushesFromItsWiderBox() throws Exception {
        try (var f = falling(ProtocolVersion.V26_1, ClientVersion.V_26_1)) {
            bee(f, 100, BEE_EDGE);

            assertExact(f.tick(START.add(0, FIRST_FALL, 0), false));
            assertExact(f.tick(START.add(0, FIRST_FALL + SECOND_FALL, BEE_PUSH), false));
        }
    }

    /** From 26.2 EntityType#BEE is sized(0.55F, 0.5F), whose box does not reach the player's from the same place. */
    @Test
    void a26Dot2BeeDoesNotReachThePlayerFromThere() throws Exception {
        try (var f = falling()) {
            bee(f, 100, BEE_EDGE);

            assertExact(f.tick(START.add(0, FIRST_FALL, 0), false));
            assertExact(f.tick(START.add(0, FIRST_FALL + SECOND_FALL, 0), false));
        }
    }

    /**
     * Vex#tick sets noPhysics around super.tick(), and Entity#push skips a pusher with noPhysics, so the Vex's
     * LivingEntity#pushEntities never moves the player, on either side of its tick.
     */
    @Test
    void aVexNeverPushesThePlayer() throws Exception {
        for (boolean beforePlayer : new boolean[] {true, false}) {
            try (var f = falling()) {
                // 0.4 along Z from the falling player, and within its box: a zombie there pushes it by -0.0316.
                f.player.compensatedEntities.addEntity(100, EntityTypeIds.VEX, START.add(0, -0.5, 0.4), 0, 0, 0);
                if (beforePlayer) f.player.compensatedEntities.getEntity(100).setClientTickOrder(-1L);

                assertExact(f.tick(CONTACT, false));
                assertExact(f.tick(CONTACT.add(0, SECOND_FALL, 0), false));
            }
        }
    }

    @Test
    void anEntityTickingBeforeThePlayerPushesItOnFirstContact() throws Exception {
        try (var f = falling()) {
            entity(f, 100, TOUCHING).setClientTickOrder(-1L);

            assertExact(f.tick(START.add(0, FIRST_FALL, PUSH), false));
        }
    }

    /**
     * Player#tick assigns noPhysics from isSpectator at its start, and Entity#push skips a player with noPhysics. In the
     * first tick out of spectator an entity ticking before the player still meets the spectator's noPhysics, so only
     * the next tick is pushed, and the ticks after the flight grace carry that push alone.
     */
    @Test
    void anEntityTickingBeforeThePlayerFirstPushesItTheTickAfterSpectator() throws Exception {
        try (var f = new JavaTickFixture()) {
            leaveSpectatorBeside(f, -1L);

            PredictionResult first = f.tick(START, false);
            assertTrue(first.getOffset() < 1.0E-5, describe(first));
            PredictionResult pushed = f.tick(CONTACT.add(0, 0, PUSH), false);
            assertTrue(pushed.getOffset() < 1.0E-5, describe(pushed));
            removeEntity(f, 100);
            f.pongThrough(f.player.lastTransactionSent.get());
            assertCarried(f, PUSH * 0.91F);
        }
    }

    /** Spectator noPhysics never reaches an entity ticking after the player: Player#tick has reassigned it. */
    @Test
    void anEntityTickingAfterThePlayerPushesItFromTheFirstTickAfterSpectator() throws Exception {
        try (var f = new JavaTickFixture()) {
            leaveSpectatorBeside(f, Long.MAX_VALUE);

            PredictionResult first = f.tick(START, false);
            assertTrue(first.getOffset() < 1.0E-5, describe(first));
            PredictionResult pushed = f.tick(CONTACT.add(0, 0, PUSH), false);
            assertTrue(pushed.getOffset() < 1.0E-5, describe(pushed));
            removeEntity(f, 100);
            f.pongThrough(f.player.lastTransactionSent.get());
            // Entity#push after the second travel, from 0.5 - PUSH along Z.
            double dz = 0.5 - PUSH;
            assertCarried(f, PUSH * 0.91F - dz / Math.sqrt(dz) * 0.05F);
        }
    }

    /**
     * The third and fourth falling ticks with no pusher left, carrying {@code velocityZ} from the second through the
     * 0.91F air drag; the fourth is past the flight grace.
     */
    private static void assertCarried(JavaTickFixture f, double velocityZ) {
        Vec3 third = CONTACT.add(0, SECOND_FALL, PUSH + velocityZ);
        PredictionResult result = f.tick(third, false);
        assertTrue(result.getOffset() < 1.0E-5, describe(result));
        assertExact(f.tick(third.add(0, fall(SECOND_FALL), velocityZ * 0.91F), false));
    }

    /**
     * One spectator tick hovering at {@link #START}, then a zombie at {@link #TOUCHING} ticking at {@code order} and the
     * return to survival, whose GameType#updatePlayerAbilities stops the flight.
     */
    private static void leaveSpectatorBeside(JavaTickFixture f, long order) throws Exception {
        f.place(START, Vec3.ZERO);
        f.input(NONE);
        gameMode(f, 3);
        f.tick(START, false);
        PacketEntity zombie = entity(f, 100, TOUCHING);
        if (order != Long.MAX_VALUE) zombie.setClientTickOrder(order);
        gameMode(f, 0);
    }

    /**
     * PolarBear#tick refreshes the dimensions after super.tick(), whose LivingEntity#pushEntities meets the box of the
     * last refresh. 1.75 below the player's feet, a standing bear's box reaches them once 1.8667 tall: refreshed in its
     * third tick of standing, it first pushes in the fourth, which moves the player's fifth travel. Through 26.2 the
     * late push follows the report, after the entity tick that refreshed the box.
     */
    @Test
    void aStandingPolarBearPushesFromTheBoxBeforeItsTicksRefresh() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_2, ClientVersion.V_26_2)) {
            Vec3 at = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(at, new Vec3(0, -0.0784000015258789, 0));
            f.input(NONE);
            PredictionResult rest = f.tick(at, true);
            assertTrue(exact(rest), "rest: " + describe(rest));
            f.player.compensatedEntities.addEntity(
                    100, EntityTypes.defaults().byKey("minecraft:polar_bear").id(), at.add(0.7, -1.75, 0), 0, 0, 0);
            int proof = f.player.lastTransactionSent.get() + 1;
            f.player.compensatedEntities.updateEntityMetadata(
                    100,
                    List.of(new EntityMetadata.Entry(
                            WatchableIndexUtil.polarBearStanding(ProtocolVersion.V26_2), true, ByteBuffer.allocate(0))),
                    proof);
            f.pongThrough(f.player.sendTrailingTransaction());

            for (int tick = 0; tick < 4; tick++) {
                PredictionResult result = f.tick(at, true);
                assertTrue(exact(result), tick + ": " + describe(result));
            }
            // Entity#push from 0.7 along X.
            assertExact(f.tick(at.add(-(0.7 / Math.sqrt(0.7)) * 0.05F, 0, 0), true));
        }
    }

    /** AABB spans a negative height below the position, and LivingEntity#pushEntities pushes from that box. */
    @Test
    void aSlimeOfNegativeSizePushesFromBelowItsPosition() throws Exception {
        try (var f = falling()) {
            // Size -4 spans 2.08 down to 0.5 above the player's feet; above its position it would miss the player.
            f.player.compensatedEntities.addEntity(100, EntityTypeIds.SLIME, TOUCHING.add(0, 3.5, 0), 0, 0, 0);
            f.player.compensatedEntities.updateEntityMetadata(
                    100, List.of(new EntityMetadata.Entry(18, -4, ByteBuffer.allocate(0))));
            f.player.compensatedEntities.getEntity(100).setClientTickOrder(-1L);

            assertExact(f.tick(START.add(0, FIRST_FALL, PUSH), false));
        }
    }

    /**
     * Camel#getDefaultDimensions sizes a SITTING adult camel 2.375 - 1.43 tall: 1.5 below the player's feet, its top
     * stays 0.555 under them, while its 2.375-tall standing box would reach 0.875 into the player.
     */
    @Test
    void aSittingCamelDoesNotPushFromItsStandingHeight() throws Exception {
        try (var f = falling()) {
            camel(f, EntityPose.SITTING);

            assertExact(f.tick(CONTACT, false));
        }
    }

    /** Entity#onSyncedDataUpdated refreshes the dimensions on DATA_POSE, so a camel standing up pushes again. */
    @Test
    void aSittingCamelThatStandsUpPushesFromItsStandingBox() throws Exception {
        try (var f = falling()) {
            camel(f, EntityPose.SITTING);
            assertExact(f.tick(CONTACT, false));

            pose(f, CAMEL, EntityPose.STANDING);
            // Entity#push from 1.0 along Z: the 0.05F step.
            assertExact(f.tick(CONTACT.add(0, SECOND_FALL, -(double) 0.05F), false));
        }
    }

    @Test
    void aTeleportKeepingHorizontalVelocityKeepsTheCarriedPush() throws Exception {
        try (var f = falling()) {
            entity(f, 100, TOUCHING);
            Vec3 contact = START.add(0, FIRST_FALL, 0);
            assertExact(f.tick(contact, false));

            f.teleport(contact, keep(RelativeFlag.DELTA_X, RelativeFlag.DELTA_Y, RelativeFlag.DELTA_Z));

            assertExact(f.tick(contact.add(0, SECOND_FALL, PUSH), false));
        }
    }

    @Test
    void aTeleportReplacingVelocityDropsTheCarriedPush() throws Exception {
        try (var f = falling()) {
            entity(f, 100, TOUCHING);
            Vec3 contact = START.add(0, FIRST_FALL, 0);
            assertExact(f.tick(contact, false));

            f.teleport(contact, new RelativeFlag(0));

            PredictionResult after = f.tick(contact.add(0, 0, PUSH), false);
            assertFalse(exact(after), describe(after));
            assertEquals(0, after.getSimulationContext().getWorldData().getNumPushing());
        }
    }

    @Test
    void aRotatedDeltaKeepsThePushOnTheAxisItLandsOn() throws Exception {
        try (var f = falling()) {
            entity(f, 100, TOUCHING);
            Vec3 contact = START.add(0, FIRST_FALL, 0);
            assertExact(f.tick(contact, false));

            // Turning by 135 degrees keeps part of each axis on X, though it turns the X+Z diagonal onto Z alone.
            int transaction = f.player.sendTrailingTransaction();
            f.teleport(transaction, contact, keep(RelativeFlag.DELTA_X, RelativeFlag.ROTATE_DELTA), 0, -135);

            Vec3 kept = new Vec3(0, 0, PUSH).yRot((float) Math.toRadians(135));
            PredictionResult after = f.tick(contact.add(kept.x, 0, 0), false);
            assertEquals(1, after.getSimulationContext().getWorldData().getNumPushing());
            assertExact(after);
        }
    }

    @Test
    void aTeleportKeepingOnlyXDeltaDropsTheCarriedPushOnZ() throws Exception {
        try (var f = falling()) {
            entity(f, 100, TOUCHING);
            Vec3 contact = START.add(0, FIRST_FALL, 0);
            assertExact(f.tick(contact, false));

            f.teleport(contact, keep(RelativeFlag.DELTA_X, RelativeFlag.DELTA_Y));

            PredictionResult after = f.tick(contact.add(0, SECOND_FALL, PUSH), false);
            assertEquals(1, after.getSimulationContext().getWorldData().getNumPushing());
            assertFalse(exact(after), describe(after));
        }
    }

    @Test
    void aTeleportKeepingOnlyXDeltaKeepsTheCarriedPushOnX() throws Exception {
        try (var f = falling()) {
            entity(f, 100, new Vec3(1.0, FLOOR_Y + 9, 0.5));
            Vec3 contact = START.add(0, FIRST_FALL, 0);
            assertExact(f.tick(contact, false));

            f.teleport(contact, keep(RelativeFlag.DELTA_X, RelativeFlag.DELTA_Y));

            assertExact(f.tick(contact.add(PUSH, SECOND_FALL, 0), false));
        }
    }

    @Test
    void teleportsThatProjectTheCarriedPushAwayDropIt() throws Exception {
        try (var f = falling()) {
            entity(f, 100, new Vec3(1.0, FLOOR_Y + 9, 0.5));
            Vec3 contact = START.add(0, FIRST_FALL, 0);
            assertExact(f.tick(contact, false));

            // Keeping X, turning by 45 degrees and back, then keeping Z leaves nothing of a push along X.
            f.teleport(contact, keep(RelativeFlag.DELTA_X));
            RelativeFlag both = keep(RelativeFlag.DELTA_X, RelativeFlag.DELTA_Z, RelativeFlag.ROTATE_DELTA);
            f.teleport(f.player.sendTrailingTransaction(), contact, both, 0, 45);
            RelativeFlag back = keep(RelativeFlag.DELTA_Z, RelativeFlag.ROTATE_DELTA);
            f.teleport(f.player.sendTrailingTransaction(), contact, back, 45, 0);

            PredictionResult after = f.tick(contact.add(0, 0, PUSH), false);
            assertEquals(1, after.getSimulationContext().getWorldData().getNumPushing());
            assertFalse(exact(after), describe(after));
        }
    }

    @Test
    void aRotationKeepingBothDeltasKeepsTheCarriedPushOnTheAxisItLandsOn() throws Exception {
        try (var f = falling()) {
            entity(f, 100, TOUCHING);
            Vec3 contact = START.add(0, FIRST_FALL, 0);
            assertExact(f.tick(contact, false));

            RelativeFlag both = keep(RelativeFlag.DELTA_X, RelativeFlag.DELTA_Z, RelativeFlag.ROTATE_DELTA);
            f.teleport(f.player.sendTrailingTransaction(), contact, both, 0, -90);

            Vec3 kept = new Vec3(0, 0, PUSH).yRot((float) Math.toRadians(90));
            assertExact(f.tick(contact.add(kept.x, 0, 0), false));
        }
    }

    @Test
    void aRotatedCarriedPushReachesNoFurtherThanOnePushDiagonally() throws Exception {
        try (var f = falling()) {
            entity(f, 100, TOUCHING);
            Vec3 contact = START.add(0, FIRST_FALL, 0);
            assertExact(f.tick(contact, false));

            RelativeFlag both = keep(RelativeFlag.DELTA_X, RelativeFlag.DELTA_Z, RelativeFlag.ROTATE_DELTA);
            f.teleport(f.player.sendTrailingTransaction(), contact, both, 0, -45);

            // Inside one push per axis, as a box would allow, but past one push of length sqrt(2)*0.05 on the diagonal.
            double diagonal = 0.07;
            PredictionResult after = f.tick(contact.add(diagonal, 0, diagonal), false);
            assertEquals(1, after.getSimulationContext().getWorldData().getNumPushing());
            assertFalse(exact(after), describe(after));
        }
    }

    @Test
    void aProjectedThenRotatedCarriedPushStaysOnItsLine() throws Exception {
        Vec3 line = new Vec3(PUSH, 0, 0).yRot((float) Math.toRadians(-45));
        try (var f = projectedThenRotated()) {
            assertExact(f.tick(CONTACT.add(line), false));
        }
        try (var f = projectedThenRotated()) {
            // Keeping X then turning by 45 degrees leaves the push on the line X = Z; this is across it.
            PredictionResult after = f.tick(CONTACT.add(0.04, 0, -0.04), false);
            assertEquals(1, after.getSimulationContext().getWorldData().getNumPushing());
            assertFalse(exact(after), describe(after));
        }
    }

    @Test
    void aPitchTurnOutAndBackKeepsTheCarriedPush() throws Exception {
        try (var f = falling()) {
            entity(f, 100, TOUCHING);
            assertExact(f.tick(CONTACT, false));

            f.teleport(f.player.sendTrailingTransaction(), CONTACT, ALL_DELTAS, 0, 0, 0, 90);
            f.teleport(f.player.sendTrailingTransaction(), CONTACT, ALL_DELTAS, 0, 90, 0, 0);

            assertExact(f.tick(CONTACT.add(0, SECOND_FALL, PUSH), false));
        }
    }

    @Test
    void aPitchTurnCarriesThePushVertically() throws Exception {
        try (var f = falling()) {
            entity(f, 100, TOUCHING);
            assertExact(f.tick(CONTACT, false));

            // Turning the pitch by 90 degrees takes the Z push onto Y and the fall onto Z.
            f.teleport(f.player.sendTrailingTransaction(), CONTACT, ALL_DELTAS, 0, 0, 0, 90);

            Vec3 kept = new Vec3(0, 0, PUSH).xRot((float) Math.toRadians(-90));
            PredictionResult after = f.tick(CONTACT.add(0, kept.y, SECOND_FALL), false);
            assertExact(after);
        }
    }

    @Test
    void aNearlyFlatTransformKeepsTheCarriedPushWithinOnePush() throws Exception {
        try (var f = falling()) {
            RelativeFlag flat = keep(RelativeFlag.DELTA_X, RelativeFlag.DELTA_Z, RelativeFlag.ROTATE_DELTA);
            f.teleport(f.player.sendTrailingTransaction(), CONTACT, flat, 0, 45);
            f.teleport(f.player.sendTrailingTransaction(), CONTACT, flat, 45, 0, 45, 89);
            f.teleport(f.player.sendTrailingTransaction(), CONTACT, flat, 45, 89, 45, 0);
            f.teleport(f.player.sendTrailingTransaction(), CONTACT, flat, 45, 0, 45, 89);
            f.teleport(f.player.sendTrailingTransaction(), CONTACT, flat, 45, 89, 45, 0);

            CarriedPush map = f.player.checkManager.getSimulationProcessor().getCarriedPush();
            Vec3 x = new Vec3(map.xx(), map.xy(), map.xz());
            Vec3 z = new Vec3(map.zx(), map.zy(), map.zz());
            double xLength = x.dot(x);
            double zLength = z.dot(z);
            double determinant = xLength * zLength - x.dot(z) * x.dot(z);
            assertTrue(determinant > 0 && determinant <= 1.0E-12 * xLength * zLength && zLength > xLength);

            // The columns are independent, so h * z comes only from the push (0, h), longer than one push.
            double radius = EntityPush.maxHorizontalImpulse(1);
            Vec3 target = z.scale(radius * Math.sqrt((xLength + zLength) / zLength));
            Vec3 pushed = map.push(new PredVector(Vec3.ZERO), target, radius);
            assertTrue(pushed.distanceTo(target) > 1.0E-9, pushed + " reaches " + target);
        }
    }

    private static JavaTickFixture projectedThenRotated() throws Exception {
        JavaTickFixture f = falling();
        entity(f, 100, new Vec3(1.0, FLOOR_Y + 9, 0.5));
        assertExact(f.tick(CONTACT, false));
        f.teleport(CONTACT, keep(RelativeFlag.DELTA_X));
        RelativeFlag both = keep(RelativeFlag.DELTA_X, RelativeFlag.DELTA_Z, RelativeFlag.ROTATE_DELTA);
        f.teleport(f.player.sendTrailingTransaction(), CONTACT, both, 0, 45);
        return f;
    }

    @Test
    void aReplacementVelocityDropsTheCarriedPush() throws Exception {
        try (var f = falling()) {
            entity(f, 100, TOUCHING);
            Vec3 contact = START.add(0, FIRST_FALL, 0);
            assertExact(f.tick(contact, false));

            f.pongThrough(f.velocity(Vec3.ZERO));

            PredictionResult after = f.tick(contact.add(0, 0, PUSH), false);
            assertEquals(1, after.getSimulationContext().getWorldData().getNumPushing());
            assertFalse(exact(after), describe(after));
        }
    }

    @Test
    void anOverriddenReplacementVelocityDropsTheCarriedPush() throws Exception {
        try (var f = falling()) {
            entity(f, 100, TOUCHING);
            Vec3 contact = START.add(0, FIRST_FALL, 0);
            assertExact(f.tick(contact, false));

            f.pongThrough(f.velocity(Vec3.ZERO));
            f.pongThrough(f.velocity(new Vec3(0.5, 0, 0)));
            assertEquals(
                    1,
                    f.player.checkManager.getKnockbackHandler().overriddenVels.size());

            PredictionResult after = f.tick(contact.add(0, 0, PUSH), false);
            assertEquals(1, after.getSimulationContext().getWorldData().getNumPushing());
            assertFalse(exact(after), describe(after));
        }
    }

    @Test
    void aContactAfterAReplacementVelocityPushesTheNextTick() throws Exception {
        try (var f = falling()) {
            entity(f, 100, TOUCHING);
            Vec3 contact = START.add(0, FIRST_FALL, 0);
            assertExact(f.tick(contact, false));

            f.pongThrough(f.velocity(Vec3.ZERO));
            assertExact(f.tick(contact, false));

            assertExact(f.tick(contact.add(0, FIRST_FALL, PUSH), false));
        }
    }

    @Test
    void thePlayersOwnPassengerDoesNotPushIt() throws Exception {
        try (var f = falling()) {
            entity(f, 100, TOUCHING).mount(f.player.compensatedEntities.getSelf());

            PredictionResult contact = f.tick(START.add(0, FIRST_FALL, 0), false);
            assertEquals(0, contact.getSimulationContext().getWorldData().getNumColliding());
            PredictionResult next = f.tick(START.add(0, FIRST_FALL + SECOND_FALL, PUSH), false);
            assertFalse(exact(next), describe(next));
        }
    }

    /** Entity#push moves no entity that isVehicle: a player carrying a passenger takes no pushes. */
    @Test
    void aPlayerCarryingAPassengerIsNotPushed() throws Exception {
        try (var f = falling()) {
            carry(f, true);
            entity(f, 102, TOUCHING).setClientTickOrder(-1L);

            PredictionResult contact = f.tick(START.add(0, FIRST_FALL, PUSH), false);

            assertFalse(exact(contact), describe(contact));
            assertEquals(0, contact.getSimulationContext().getWorldData().getNumPushing());
        }
    }

    @Test
    void aPlayerCarryingAPassengerCarriesNoPushIntoTheNextTick() throws Exception {
        try (var f = falling()) {
            carry(f, true);
            entity(f, 102, TOUCHING);

            PredictionResult contact = f.tick(START.add(0, FIRST_FALL, 0), false);
            assertExact(contact);
            assertEquals(0, contact.getSimulationContext().getWorldData().getNumCollidingTickingAfterPlayer());
            PredictionResult next = f.tick(START.add(0, FIRST_FALL + SECOND_FALL, PUSH), false);
            assertFalse(exact(next), describe(next));
        }
    }

    /** Before its ping is answered the client may not have read the set_passengers: it may still be pushed. */
    @Test
    void anUnansweredPassengerKeepsThePush() throws Exception {
        try (var f = falling()) {
            carry(f, false);
            entity(f, 102, TOUCHING).setClientTickOrder(-1L);

            assertExact(f.tick(START.add(0, FIRST_FALL, PUSH), false));
        }
    }

    @Test
    void anUnansweredDismountOfThePassengerKeepsThePush() throws Exception {
        try (var f = falling()) {
            carry(f, true);
            f.setPassengers(f.player.entityID, List.of());
            assertUnanswered(f);
            assertFalse(f.player.compensatedEntities.getSelf().passengers.isEmpty());
            entity(f, 102, TOUCHING).setClientTickOrder(-1L);

            assertExact(f.tick(START.add(0, FIRST_FALL, PUSH), false));
        }
    }

    @Test
    void anUnansweredMountOfThePassengerElsewhereKeepsThePush() throws Exception {
        try (var f = falling()) {
            carry(f, true);
            entity(f, 103, AWAY);
            f.setPassengers(103, List.of(100));
            assertUnanswered(f);
            entity(f, 102, TOUCHING).setClientTickOrder(-1L);

            assertExact(f.tick(START.add(0, FIRST_FALL, PUSH), false));
        }
    }

    @Test
    void anUnansweredRemovalOfThePassengerKeepsThePush() throws Exception {
        try (var f = falling()) {
            carry(f, true);
            removeEntity(f, 100);
            assertUnanswered(f);
            assertFalse(f.player.compensatedEntities.getSelf().passengers.isEmpty());
            entity(f, 102, TOUCHING).setClientTickOrder(-1L);

            assertExact(f.tick(START.add(0, FIRST_FALL, PUSH), false));
        }
    }

    /** A removal sent without a ping settles at the next one: the client ejects the passenger on receipt. */
    @Test
    void aRemovalOfThePassengerWithoutAPingKeepsThePush() throws Exception {
        try (var f = falling()) {
            carry(f, true);
            f.fixture.connection.phase(PacketDirection.CLIENTBOUND, ConnectionPhase.CONFIGURATION);
            removeEntity(f, 100);
            f.fixture.connection.phase(PacketDirection.CLIENTBOUND, ConnectionPhase.PLAY);
            assertFalse(f.player.compensatedEntities.selfCertainlyCarriesPassenger());
            entity(f, 102, TOUCHING).setClientTickOrder(-1L);

            assertExact(f.tick(START.add(0, FIRST_FALL, PUSH), false));
        }
    }

    /** Entity#setRemoved stops the discarded passenger riding the player. */
    @Test
    void aRemovedPassengerLeavesThePlayerPushable() throws Exception {
        try (var f = falling()) {
            carry(f, true);
            f.player.compensatedEntities.removeEntity(100);
            entity(f, 102, TOUCHING).setClientTickOrder(-1L);

            assertExact(f.tick(START.add(0, FIRST_FALL, PUSH), false));
        }
    }

    @Test
    void aRiderOutsideTheOwnRidingTreePushesFromItsSeatNotItsLastPosition() throws Exception {
        try (var f = falling()) {
            PacketEntity vehicle = entity(f, 100, new Vec3(4.5, FLOOR_Y, 4.5));
            vehicle.setClientTickOrder(-1L);
            entity(f, 101, TOUCHING).mount(vehicle);
            f.player.compensatedEntities.tickClientEntities(entity -> {});
            f.player.packetStateData.acceptedClientTick++;

            PredictionResult contact = f.tick(START.add(0, FIRST_FALL, 0), false);

            assertEquals(0, contact.getSimulationContext().getWorldData().getNumPushing());
            assertExact(contact);
        }
    }

    /** Entity#rideTick runs LivingEntity#tick, whose aiStep pushes from the seat positionRider gave the rider. */
    @Test
    void aLivingRiderOfAnotherEntityPushesFromItsSeat() throws Exception {
        try (var f = falling()) {
            seatSlime(f, entity(f, 100, BELOW_TOUCHING));

            PredictionResult contact = f.tick(START.add(0, FIRST_FALL, 0), false);
            assertExact(contact);
            WorldData world = contact.getSimulationContext().getWorldData();
            assertEquals(1, world.getNumColliding());
            assertEquals(1, world.getNumCollidingTickingAfterPlayer());
            assertExact(f.tick(START.add(0, FIRST_FALL + SECOND_FALL, PUSH), false));
        }
    }

    /** ClientLevel#tickNonPassenger ticks a passenger right after its vehicle, in the vehicle's tick-list place. */
    @Test
    void aLivingRiderTicksInItsVehiclesPlace() throws Exception {
        try (var f = falling()) {
            PacketEntity vehicle = entity(f, 100, BELOW_TOUCHING);
            vehicle.setClientTickOrder(-1L);
            seatSlime(f, vehicle);

            assertExact(f.tick(START.add(0, FIRST_FALL, PUSH), false));
        }
    }

    @Test
    void aLivingRiderAndItsVehiclePushTheirOwnPushes() throws Exception {
        try (var f = falling()) {
            seatSlime(f, entity(f, 100, TOUCHING));

            PredictionResult contact = f.tick(START.add(0, FIRST_FALL, 0), false);
            assertEquals(2, contact.getSimulationContext().getWorldData().getNumCollidingTickingAfterPlayer());
            PredictionResult next = f.tick(START.add(0, FIRST_FALL + SECOND_FALL, 2 * PUSH), false);
            assertEquals(2, next.getSimulationContext().getWorldData().getNumPushing());
            assertExact(next);
        }
    }

    /**
     * Interaction#getDimensions is EntityDimensions.scalable(width, height), whose default passenger attachment sits
     * at the height: a slime seated on a 0.7 tall interaction pushes from 0.7 above the interaction's feet.
     */
    @Test
    void aLivingRiderOfAnInteractionPushesFromItsHeight() throws Exception {
        try (var f = falling()) {
            seatScaledSlimeOnInteraction(f, 0.7F);

            assertExact(f.tick(CONTACT, false));
            PredictionResult pushed = f.tick(CONTACT.add(INTERACTION_PUSH_X, SECOND_FALL, INTERACTION_PUSH_Z), false);
            assertEquals(1, pushed.getSimulationContext().getWorldData().getNumPushing());
            assertExact(pushed);
        }
    }

    /** A zero height interaction seats the slime at its feet, below the player's box: the same move is not explained. */
    @Test
    void aLivingRiderOfAFlatInteractionDoesNotReachThePlayer() throws Exception {
        try (var f = falling()) {
            seatScaledSlimeOnInteraction(f, 0.0F);

            assertExact(f.tick(CONTACT, false));
            PredictionResult pushed = f.tick(CONTACT.add(INTERACTION_PUSH_X, SECOND_FALL, INTERACTION_PUSH_Z), false);
            assertEquals(0, pushed.getSimulationContext().getWorldData().getNumPushing());
            assertFalse(exact(pushed), describe(pushed));
        }
    }

    /** Avatar#POSES: a swimming player is 0.6 tall, so its hat sits 0.6 above its feet. */
    @Test
    void aLivingRiderOfASwimmingPlayerPushesFromItsLoweredSeat() throws Exception {
        assertVehicleAndRiderPush(EntityPose.SWIMMING, 0.5);
    }

    /** Avatar#POSES: a crouching player is 1.5 tall, so its hat sits 1.5 above its feet. */
    @Test
    void aLivingRiderOfACrouchingPlayerPushesFromItsLoweredSeat() throws Exception {
        assertVehicleAndRiderPush(EntityPose.CROUCHING, 0.29);
    }

    private static void assertVehicleAndRiderPush(EntityPose pose, double aboveContact) throws Exception {
        try (var f = falling()) {
            f.player.compensatedEntities.addEntity(
                    100, EntityTypeIds.PLAYER, TOUCHING.add(0, CONTACT.y - TOUCHING.y + aboveContact, 0), 0, 0, 0);
            PacketEntity vehicle = f.player.compensatedEntities.entityMap.get(100);
            vehicle.onPose(pose, Integer.MIN_VALUE);
            vehicle.onSizeHandled(Integer.MAX_VALUE);
            seatSlime(f, vehicle);

            PredictionResult contact = f.tick(CONTACT, false);
            assertExact(contact);
            assertEquals(2, contact.getSimulationContext().getWorldData().getNumCollidingTickingAfterPlayer());
            assertExact(f.tick(START.add(0, FIRST_FALL + SECOND_FALL, 2 * PUSH), false));
        }
    }

    /**
     * ClientLevel#tickPassenger runs a non-player passenger's rideTick, whose LivingEntity#aiStep pushes, only while
     * that passenger is in tickingEntities itself. AbstractBoat#getPassengerAttachmentPoint seats an Animal first
     * passenger 0.4 along the boat, rotated by -yRot, so at yaw 90 the pig sits in chunk -1 while its boat stays in
     * chunk 0. Once the client unloads chunk -1, the boat keeps ticking but the pig neither pushes nor is reseated.
     */
    @Test
    void aRiderWhoseOwnChunkStoppedTickingDoesNotPushWhileItsVehicleTicks() throws Exception {
        try (var f = falling()) {
            var entities = f.player.compensatedEntities;
            entities.addEntity(100, EntityTypeIds.OAK_BOAT, new Vec3(0.25, CONTACT.y - 0.8, 0.5), 90, 0, 0);
            PacketEntity boat = entities.entityMap.get(100);
            entities.addEntity(RIDER, EntityTypeIds.PIG, AWAY, 0, 0, 0);
            PacketEntity pig = entities.entityMap.get(RIDER);
            pig.mount(boat);
            entities.addEntity(102, EntityTypeIds.ARMOR_STAND, AWAY, 0, 0, 0);
            entities.entityMap.get(102).mount(boat);
            // The established tree: the last tick's positionRider seated the pig, overlapping the falling player.
            entities.tickClientEntities(entity -> {});
            f.player.packetStateData.acceptedClientTick++;
            SimpleCollisionBox seat = pig.getPossibleMovementCollisionBoxes();
            assertEquals(-0.15, (seat.minX + seat.maxX) / 2, 1.0E-6);

            f.player.compensatedWorld.removeChunkLater(-1, 0, 0);
            assertTrue(entities.isTicking(boat));
            assertFalse(entities.ticksInClientTick(pig));

            assertExact(f.tick(CONTACT, false));
            assertExact(f.tick(START.add(0, FIRST_FALL + SECOND_FALL, 0), false));
            seat = pig.getPossibleMovementCollisionBoxes();
            assertEquals(-0.15, (seat.minX + seat.maxX) / 2, 1.0E-6, "a non-ticking passenger keeps its seat");
        }
    }

    /**
     * ClientLevel#tickPassenger admits a rider by the tickingEntities membership it holds as its tick starts. Its
     * Entity#rideTick interpolates, then positionRider seats it in a chunk that no longer ticks, and only that move's
     * section callback stops its ticking: the step it took stays taken. Once the chunk ticks again,
     * InterpolationHandler#interpolate adds the reseat since that step to the target, so the pig pushes from its seat.
     */
    @Test
    void aRiderSeatedIntoANonTickingChunkKeepsTheInterpolationStepItTook() throws Exception {
        try (var f = falling()) {
            var world = f.player.compensatedWorld;
            var entities = f.player.compensatedEntities;
            var west = world.getChunk(-1, 0);
            world.removeChunkLater(-1, 0, 0);
            // A second passenger seats the pig 0.4 along the boat, rotated by -yRot: west of it at yaw 90.
            entities.addEntity(100, EntityTypeIds.OAK_BOAT, new Vec3(0.6, FLOOR_Y, 0.5), 90, 0, 0);
            PacketEntity boat = entities.entityMap.get(100);
            entities.addEntity(RIDER, EntityTypeIds.PIG, AWAY, 0, 0, 0);
            PacketEntity pig = entities.entityMap.get(RIDER);
            pig.mount(boat);
            entities.addEntity(102, EntityTypeIds.ARMOR_STAND, AWAY, 0, 0, 0);
            entities.entityMap.get(102).mount(boat);
            entities.tickClientEntities(entity -> entity.onMovement(f.player));
            f.player.packetStateData.acceptedClientTick++;
            double first = centerX(pig.lazyRiderCommittedBox(new SimpleCollisionBox(), false));
            assertTrue(first > 0, "the pig starts in the ticking chunk");

            // A rotation-only move interpolates the pig toward where it sits; the boat then moves west.
            pig.onPositionPath(EntityPositionPath.linear(Vec3.ZERO), false, 10F, 0F, f.player);
            snap(f, boat, new Vec3(0.25, FLOOR_Y, 0.5));
            entities.tickClientEntities(entity -> entity.onMovement(f.player));
            f.player.packetStateData.acceptedClientTick++;
            double second = centerX(pig.lazyRiderCommittedBox(new SimpleCollisionBox(), false));
            assertTrue(second < 0, "positionRider seats the pig in the unloaded chunk");
            assertFalse(entities.ticksInClientTick(pig));

            world.putChunk(-1, 0, west);
            assertTrue(entities.ticksInClientTick(pig));
            var pushes = entities.untrackedRiderPushBoxes(
                    pig, pig.lazyRiderCommittedBox(new SimpleCollisionBox(), false).copy().expand(1),
                    new SimpleCollisionBox(), new SimpleCollisionBox());
            assertEquals(1, pushes.size());
            assertEquals(second, centerX(pushes.getFirst()), 1.0E-9, "not from the place of an untaken first step");
        }
    }

    /**
     * The pig's Entity#rideTick, after the player's tick, pushes from the seat it held in the ticking chunk before
     * positionRider seats it in the one that stopped: that push reaches the player's next travel. Through 26.2 the
     * movement report precedes that rideTick, so the push is read after the tick's entity traversal.
     */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_26_2", "V_26_3"})
    void aRiderSeatedIntoANonTickingChunkStillPushesFromTheSeatItTickedOn(ClientVersion version) throws Exception {
        try (var f = falling()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            var entities = f.player.compensatedEntities;
            f.player.compensatedWorld.removeChunkLater(-1, 0, 0);
            // A second passenger seats the pig 0.4 along the boat, rotated by -yRot: west of it at yaw 90.
            entities.addEntity(100, EntityTypeIds.OAK_BOAT, new Vec3(0.45, CONTACT.y - 0.8, 0.5), 90, 0, 0);
            PacketEntity boat = entities.entityMap.get(100);
            entities.addEntity(RIDER, EntityTypeIds.PIG, AWAY, 0, 0, 0);
            PacketEntity pig = entities.entityMap.get(RIDER);
            pig.mount(boat);
            entities.addEntity(102, EntityTypeIds.ARMOR_STAND, AWAY, 0, 0, 0);
            entities.entityMap.get(102).mount(boat);
            entities.tickClientEntities(entity -> entity.onMovement(f.player));
            f.player.packetStateData.acceptedClientTick++;
            SimpleCollisionBox seat = pig.lazyRiderCommittedBox(new SimpleCollisionBox(), false).copy();
            assertTrue(centerX(seat) > 0, "the pig starts in the ticking chunk");
            snap(f, boat, new Vec3(0.25, CONTACT.y - 0.8, 0.5));

            assertExact(f.tick(CONTACT, false));
            assertTrue(centerX(pig.lazyRiderCommittedBox(new SimpleCollisionBox(), false)) < 0);
            Vec3 push = JavaLivingEntityPushes.impulse(new Vec3(centerX(seat), 0, 0.5), CONTACT);
            assertTrue(push.x > 0);
            assertExact(f.tick(START.add(push.x, FIRST_FALL + SECOND_FALL, push.z), false));
        }
    }

    private static double centerX(SimpleCollisionBox box) {
        return (box.minX + box.maxX) / 2;
    }

    /**
     * PistonMovingBlockEntity#moveCollidedEntities shoves the vehicle east, but its rider sits above the piston's reach
     * and keeps the seat Entity#positionRider gave it: the rider's next Entity#rideTick pushes from there before
     * positionRider reseats it on the moved vehicle.
     */
    @Test
    void aRiderPushesFromTheSeatAPistonLeftItOnWhileItsVehicleMovesAway() throws Exception {
        try (var f = falling()) {
            PacketEntity vehicle = entity(f, 100, BELOW_TOUCHING);
            seatSlime(f, vehicle);
            PacketEntity rider = f.player.compensatedEntities.getEntity(RIDER);
            // A piston west of the vehicle extends stone east through the vehicle's lower half, below the rider.
            extendStoneEast(f, FLOOR_Y + 8);

            assertExact(f.tick(CONTACT, false));
            assertEquals(
                    BELOW_TOUCHING.x + 0.31, vehicle.clientPhysicalPosition.x, 1.0E-6, "the stone shoves the vehicle");
            SimpleCollisionBox near = GetBoundingBox.getPlayerBoundingBox(f.player, 0.5, CONTACT.y, 0.5);
            var seats = f.player.compensatedEntities.untrackedRiderPushBoxes(
                    rider, near, new SimpleCollisionBox(), new SimpleCollisionBox());
            assertEquals(1, seats.size());
            assertEquals(
                    BELOW_TOUCHING.x,
                    (seats.getFirst().minX + seats.getFirst().maxX) / 2,
                    1.0E-9,
                    "the rider still holds its seat on the vehicle's place before the shove");

            Vec3 second = START.add(0, FIRST_FALL + SECOND_FALL, PUSH);
            assertExact(f.tick(second, false));
            // The rider's push from its kept seat lands on the drag-reduced first push.
            Vec3 push = JavaLivingEntityPushes.impulse(new Vec3(BELOW_TOUCHING.x, 0, BELOW_TOUCHING.z), second);
            assertEquals(0.0, push.x, 0.0, "the kept seat is straight ahead of the player");
            assertExact(f.tick(second.add(0, fall(SECOND_FALL), PUSH * 0.91F + push.z), false));
        }
    }

    @Test
    void aPistonStillMovesARiderWhoseNonTickingRootWasTeleportedAway() throws Exception {
        try (var f = falling()) {
            PacketEntity root = entity(f, 100, BELOW_TOUCHING);
            seatSlime(f, root);
            PacketEntity rider = f.player.compensatedEntities.getEntity(RIDER);
            snap(f, root, new Vec3(1000.5, FLOOR_Y, 1000.5));
            assertFalse(f.player.compensatedEntities.isTicking(root));
            extendStoneEast(f, FLOOR_Y + 9);
            assertExact(f.tick(CONTACT, false));
            SimpleCollisionBox box = rider.getPossibleMovementCollisionBoxes();
            assertEquals(0.51, box.minX, 1.0E-6, "the piston must find the held rider independently of its root");
        }
    }

    /**
     * PistonMovingBlockEntity#moveCollidedEntities moves each entity its area meets on its own, riders included. An
     * armor stand neither pushes nor collides with the player, yet a boat seated on it is shoved like one seated on a
     * zombie, and its collision and reach boxes hold where the stone left it until its next Entity#rideTick.
     */
    @Test
    void aPistonMovesABoatRidingARootThePlayerNeitherPushesNorCollidesWith() throws Exception {
        for (int rootType : new int[] {EntityTypeIds.ZOMBIE, EntityTypeIds.ARMOR_STAND}) {
            try (var f = falling()) {
                var entities = f.player.compensatedEntities;
                // Both roots stay below the stone; each seats the boat across its lower half, south of the player.
                entities.addEntity(100, rootType, new Vec3(0.5, FLOOR_Y + 7, 1.5), 0, 0, 0);
                entities.addEntity(RIDER, EntityTypeIds.OAK_BOAT, AWAY, 0, 0, 0);
                PacketEntity boat = entities.getEntity(RIDER);
                boat.mount(entities.getEntity(100));
                entities.tickClientEntities(entity -> {});
                f.player.packetStateData.acceptedClientTick++;
                assertEquals(-0.1875, boat.getPossibleMovementCollisionBoxes().minX, 1.0E-6);
                extendStoneEast(f, FLOOR_Y + 9);

                assertExact(f.tick(CONTACT, false));
                // The stone's face reaches half a block east; Entity#limitPistonMovement holds the shove to 0.51.
                String root = EntityTypes.defaults().byId(rootType).key();
                assertEquals(0.3225, boat.getPossibleMovementCollisionBoxes().minX, 1.0E-6, root + " collision");
                assertEquals(0.3225, boat.getPossibleCollisionBoxes().minX, 1.0E-6, root + " reach");
            }
        }
    }

    /**
     * Entity#limitPistonMovement holds the piston moves an entity takes at one Level#getGameTime to 0.51 per axis, and
     * Entity#pistonDeltas stays on the rider between block-entity passes. A time packet that sets the game time back
     * makes the stone's second stroke run at the game time of its first, so the held rider moves only what is left.
     */
    @Test
    void aRepeatedGameTimeLimitsAHeldRidersSecondShove() throws Exception {
        try (var f = falling()) {
            PacketEntity root = entity(f, 100, BELOW_TOUCHING);
            seatSlime(f, root);
            PacketEntity rider = f.player.compensatedEntities.getEntity(RIDER);
            snap(f, root, new Vec3(1000.5, FLOOR_Y, 1000.5));
            double seat = rider.getPossibleMovementCollisionBoxes().minX;
            extendStoneEast(f, FLOOR_Y + 9);
            long gameTime = f.player.compensatedWorld.clientGameTime();
            assertExact(f.tick(CONTACT, false));
            double first = rider.getPossibleMovementCollisionBoxes().minX - seat;
            assertEquals(0.51 - seat, first, 1.0E-6, "the first stroke shoves the rider clear of its face");

            f.player.compensatedWorld.applyClientTime(new WorldPackets.TimeUpdate(gameTime, java.util.Map.of()));
            f.tick(START.add(0, FIRST_FALL + SECOND_FALL, 0), false);
            // The second stroke's face reaches x 1, which would shove the rider 1.01 - 0.51 = 0.5 more.
            double shoved = rider.getPossibleMovementCollisionBoxes().minX - seat;
            assertEquals(
                    Math.min(0.51, first + 0.5),
                    shoved,
                    1.0E-6,
                    "the rider's piston deltas carry the first stroke's move within that game time");
        }
    }

    /**
     * LivingEntity#handleEntityEvent(3) leaves a dying mob in the level until the server removes it, and its aiStep
     * still pushes the player. PistonMovingBlockEntity#moveCollidedEntities shoves it like any other non-spectator, so
     * its next push comes from where the stone left it.
     */
    @Test
    void aDyingMobPushesFromWhereAPistonShovedIt() throws Exception {
        try (var f = falling()) {
            PacketEntity zombie = entity(f, 100, TOUCHING);
            kill(f, 100);
            // A piston west of the zombie extends stone east through its lower half, beside the player.
            extendStoneEast(f, FLOOR_Y + 9);

            assertExact(f.tick(CONTACT, false));
            Vec3 shoved = new Vec3(TOUCHING.x + 0.31, 0, TOUCHING.z);
            assertEquals(shoved.x, zombie.clientPhysicalPosition.x, 1.0E-6, "the stone shoves the dying zombie");

            Vec3 second = START.add(0, FIRST_FALL + SECOND_FALL, PUSH);
            assertExact(f.tick(second, false));
            Vec3 push = JavaLivingEntityPushes.impulse(shoved, second);
            assertExact(f.tick(second.add(push.x, fall(SECOND_FALL), PUSH * 0.91F + push.z), false));
        }
    }

    /** LivingEntity#die does not dismount a dying rider: it pushes from where the piston shoved it, as a living one. */
    @Test
    void aDyingRiderPushesFromWhereAPistonShovedIt() throws Exception {
        try (var f = falling()) {
            PacketEntity vehicle = entity(f, 100, BELOW_TOUCHING);
            seatSlime(f, vehicle);
            PacketEntity rider = f.player.compensatedEntities.getEntity(RIDER);
            kill(f, RIDER);
            // The stone extends east through the rider's seat and the top of its vehicle.
            extendStoneEast(f, FLOOR_Y + 9);

            assertExact(f.tick(CONTACT, false));
            SimpleCollisionBox near = GetBoundingBox.getPlayerBoundingBox(f.player, 0.5, CONTACT.y, 0.5);
            var seats = f.player.compensatedEntities.untrackedRiderPushBoxes(
                    rider, near, new SimpleCollisionBox(), new SimpleCollisionBox());
            assertEquals(1, seats.size());
            SimpleCollisionBox seat = seats.getFirst();
            // The stone's face reaches half a block east this tick; the shove clears it by 0.01.
            assertEquals(0.51, seat.minX, 1.0E-6, "the stone shoves the dying rider off its seat");

            Vec3 second = START.add(0, FIRST_FALL + SECOND_FALL, PUSH);
            assertExact(f.tick(second, false));
            Vec3 push = JavaLivingEntityPushes.impulse(seat, second);
            assertExact(f.tick(second.add(push.x, fall(SECOND_FALL), PUSH * 0.91F + push.z), false));
        }
    }

    /**
     * ClientPacketListener#handleEntityPositionSync moves an entity too far to interpolate with Entity#snapTo, which
     * leaves its InterpolationHandler running: the next interpolate shifts the steps it still owes by the jump, so they
     * carry it on from the synced position into the player.
     */
    @Test
    void anEntitySnappedMidInterpolationPushesFromWhereItsRemainingStepsLeaveIt() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(STANDING, Vec3.ZERO);
            f.input(NONE);
            PacketEntity entity = entity(f, 100, SNAP_FROM);
            // Entity#move's zero Y attempt clears ground on the local authoritative instance.
            assertExact(f.tick(STANDING, false));
            entity.onBundleFirstTransaction(true, true, SNAP_STEPPING_DX, 0, 0, f.player);
            entity.onSecondTransaction();
            assertExact(f.tick(STANDING, true));

            positionSync(f, 100, SNAPPED);
            assertExact(f.tick(STANDING, true));
            assertExact(f.tick(STANDING, true));
            assertExact(
                    f.tick(STANDING.add(-pushFrom(SNAPPED.x + 2.0 / 3.0 * SNAP_STEPPING_DX - STANDING.x), 0, 0), true));
        }
    }

    @Test
    void anEntityKeptThroughARespawnPushesThePlayerOnFirstContact() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketEntity kept = entity(f, 100, AWAY);
            respawnFalling(f);
            snap(f, kept, CONTACT.add(0, -1, 0.5));

            assertExact(f.tick(CONTACT.add(0, SECOND_FALL, PUSH), false));
        }
    }

    @Test
    void anEntityKeptThroughARespawnPushesNoFurtherThanOnePush() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketEntity kept = entity(f, 100, AWAY);
            respawnFalling(f);
            snap(f, kept, CONTACT.add(0, -1, 0.5));

            PredictionResult contact = f.tick(CONTACT.add(0, SECOND_FALL, 3 * PUSH), false);
            assertEquals(1, contact.getSimulationContext().getWorldData().getNumPushing());
            assertFalse(exact(contact), describe(contact));
        }
    }

    @Test
    void anEntityAddedAfterARespawnTicksAfterThePlayer() throws Exception {
        try (var f = new JavaTickFixture()) {
            entity(f, 100, AWAY);
            respawnFalling(f);
            entity(f, 101, CONTACT.add(0, -1, 0.5));

            PredictionResult contact = f.tick(CONTACT.add(0, SECOND_FALL, 0), false);
            assertExact(contact);
            assertEquals(1, contact.getSimulationContext().getWorldData().getNumCollidingTickingAfterPlayer());
            Vec3 pushed = CONTACT.add(0, SECOND_FALL + fall(SECOND_FALL), PUSH);
            assertExact(f.tick(pushed, false));
        }
    }

    /**
     * LocalPlayer#tick waits for the level to load while ClientLevel#tickEntities runs the other entity's
     * pushEntities, so every waiting tick's push is in the first loaded move.
     */
    @Test
    void pushesWhileTheLevelLoadsMoveTheFirstLoadedTick() throws Exception {
        try (var f = loadingBesideOther()) {
            PredictionResult first =
                    f.tick(LOADING.add(-TICKS_LOADING * pushFrom(LOADING_OTHER.x - LOADING.x), 0, 0), false);
            assertExact(first);
            assertEquals(0, first.getSimulationContext().getWorldData().getNumPushing());
        }
    }

    /** EntitySelector#pushableBy's team collision rules can stop a pusher touching the player from pushing it. */
    @Test
    void pushesWhileTheLevelLoadsMayNotHappen() throws Exception {
        try (var f = loadingBesideOther()) {
            assertExact(f.tick(LOADING, false));
        }
    }

    /** Entity#push points away from the pusher, so the waiting ticks' pushes cannot move the player towards it. */
    @Test
    void pushesWhileTheLevelLoadsDoNotMoveThePlayerTowardsThePusher() throws Exception {
        try (var f = loadingBesideOther()) {
            PredictionResult first =
                    f.tick(LOADING.add(TICKS_LOADING * pushFrom(LOADING_OTHER.x - LOADING.x), 0, 0), false);
            assertFalse(exact(first), describe(first));
        }
    }

    /** A teleport with relative DELTA_X/DELTA_Z keeps the movement the waiting ticks' pushes were added to. */
    @Test
    void aTeleportKeepingHorizontalVelocityWhileTheLevelLoadsKeepsThePushes() throws Exception {
        try (var f = loadingBesideOther()) {
            f.teleport(LOADING, ALL_DELTAS);
            assertExact(f.tick(LOADING.add(-TICKS_LOADING * pushFrom(LOADING_OTHER.x - LOADING.x), 0, 0), false));
        }
    }

    /** A teleport with absolute zero DELTA_X/DELTA_Z replaces the movement the waiting ticks' pushes were added to. */
    @Test
    void aTeleportWhileTheLevelLoadsDropsThePushesBeforeIt() throws Exception {
        try (var f = loadingBesideOther()) {
            f.teleport(LOADING, new RelativeFlag(0));
            PredictionResult first =
                    f.tick(LOADING.add(-TICKS_LOADING * pushFrom(LOADING_OTHER.x - LOADING.x), 0, 0), false);
            assertFalse(exact(first), describe(first));
        }
    }

    /**
     * A client standing at {@link #LOADING} that waited {@link #TICKS_LOADING} ticks for its level beside a zombie,
     * which then left; its level has just loaded. No travel changed its initial zero Y velocity, so the first
     * loaded Entity#move attempts zero Y and clears onGround even though the floor is directly below it.
     */
    private static JavaTickFixture loadingBesideOther() throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.place(LOADING, Vec3.ZERO);
        f.input(NONE);
        f.player.packetStateData.playerLoadedIntoLevel = false;
        entity(f, 100, LOADING_OTHER);
        for (int tick = 0; tick < TICKS_LOADING; tick++) f.tickEnd();
        f.player.compensatedEntities.removeEntity(100);
        f.player.packetStateData.playerLoadedIntoLevel = true;
        return f;
    }

    /** Entity#push's impulse on the pushed entity from one {@code dx} away on the x axis alone. */
    private static double pushFrom(double dx) {
        double root = Math.sqrt(Math.abs(dx));
        return dx / root * Math.min(1.0, 1.0 / root) * 0.05F;
    }

    /** Entity#push's impulse along an axis {@code d} away, {@code other} away on the other horizontal axis. */
    private static double push(double d, double other) {
        double root = Math.sqrt(Math.max(Math.abs(d), Math.abs(other)));
        return d / root * Math.min(1.0, 1.0 / root) * 0.05F;
    }

    private static final int TICKS_LOADING = 3;
    private static final Vec3 LOADING = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final Vec3 LOADING_OTHER = new Vec3(0.94, FLOOR_Y, 0.5);

    /** A client that has just stepped off into the air at {@link #START}. */
    private static JavaTickFixture falling() throws Exception {
        return falling(new JavaTickFixture());
    }

    private static JavaTickFixture falling(ProtocolVersion protocol, ClientVersion client) throws Exception {
        return falling(new JavaTickFixture(protocol, client));
    }

    private static JavaTickFixture falling(JavaTickFixture f) throws Exception {
        f.place(START, Vec3.ZERO);
        f.input(NONE);
        assertExact(f.tick(START, false));
        return f;
    }

    /** LivingEntity#travel: gravity, then the 0.98F air drag. */
    private static double fall(double velocity) {
        return (velocity - 0.08) * 0.98F;
    }

    private static PacketEntity entity(JavaTickFixture f, int id, Vec3 position) {
        f.player.compensatedEntities.addEntity(id, EntityTypeIds.ZOMBIE, position, 0, 0, 0);
        return f.player.compensatedEntities.entityMap.get(id);
    }

    private static void bee(JavaTickFixture f, int id, Vec3 position) {
        f.player.compensatedEntities.addEntity(id, EntityTypeIds.BEE, position, 0, 0, 0);
    }

    /** A CHANGE_GAME_MODE game event, answered through its ping. */
    private static void gameMode(JavaTickFixture f, int mode) {
        var packet = new ClientboundGameEvent(GameEventType.CHANGE_GAME_MODE, mode);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.GAME_EVENT, packet, false);
        f.player.checkManager.getListener(PacketChangeGameState.class).onGameEvent(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    /** An adult camel ticking before the player, 1.0 along Z from it and 1.5 below its feet, in {@code pose}. */
    private static void camel(JavaTickFixture f, EntityPose pose) {
        f.player.compensatedEntities.addEntity(CAMEL, EntityTypeIds.CAMEL, START.add(0, -1.5, 1.0), 0, 0, 0);
        f.player.compensatedEntities.getEntity(CAMEL).setClientTickOrder(-1L);
        pose(f, CAMEL, pose);
    }

    private static void pose(JavaTickFixture f, int id, EntityPose pose) {
        f.player.compensatedEntities.updateEntityMetadata(
                id, List.of(new EntityMetadata.Entry(WatchableIndexUtil.ENTITY_POSE, pose, ByteBuffer.allocate(0))));
    }

    /** Seats a slime, a living rider like a hat mob on a player, on {@code vehicle}; it spawns far from the player. */
    private static void seatSlime(JavaTickFixture f, PacketEntity vehicle) {
        f.player.compensatedEntities.addEntity(RIDER, EntityTypeIds.SLIME, AWAY, 0, 0, 0);
        f.player.compensatedEntities.entityMap.get(RIDER).mount(vehicle);
        // This scenario starts with an established passenger. set_passengers alone does not seat it:
        // run the prior tick's positionRider pass without advancing the movement under test.
        f.player.compensatedEntities.tickClientEntities(entity -> {});
        f.player.packetStateData.acceptedClientTick++;
    }

    /** A piston at x -2, z 1 and height {@code y}, facing east, starts pushing the stone beside it east to x 0. */
    private static void extendStoneEast(JavaTickFixture f, int y) {
        BlockRegistry blocks = DataTables.defaults().registry();
        int piston = blocks.with(blocks.block("minecraft:piston").defaultState(), "facing", "east");
        BlockPos base = new BlockPos(-2, y, 1);
        f.player.compensatedWorld.updateBlock(base, piston);
        f.player.compensatedWorld.updateBlock(
                base.relative(Direction.EAST), blocks.block("minecraft:stone").defaultState());
        f.player.compensatedWorld.pistons.handleBlockEvent(base, blocks.blockIndex(piston), 0, 5, 0);
    }

    /** LivingEntity#handleEntityEvent(3): the mob is dying, and stays in the level until the server removes it. */
    private static void kill(JavaTickFixture f, int id) {
        var packet = new ClientboundEntityEvent(id, (byte) 3);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ENTITY_EVENT, packet, false);
        f.player.packetEntityReplication.onEntityEvent(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
        assertTrue(f.player.compensatedEntities.getEntity(id).isDead);
    }

    /**
     * An interaction {@code height} tall at {@link #INTERACTION_FROM_CONTACT} from {@link #CONTACT}, carrying a slime of
     * scale {@link #INTERACTION_SLIME_SCALE} that spawns far from the player.
     */
    private static void seatScaledSlimeOnInteraction(JavaTickFixture f, float height) {
        var entities = f.player.compensatedEntities;
        entities.addEntity(100, EntityTypeIds.INTERACTION, CONTACT.add(INTERACTION_FROM_CONTACT), 0, 0, 0);
        entities.updateEntityMetadata(
                100,
                List.of(
                        new EntityMetadata.Entry(8, 0.7F, ByteBuffer.allocate(0)),
                        new EntityMetadata.Entry(9, height, ByteBuffer.allocate(0))));
        entities.addEntity(RIDER, EntityTypeIds.SLIME, AWAY, 0, 0, 0);
        var scale = new ClientboundUpdateAttributes(
                RIDER, List.of(new AttributeSnapshot("minecraft:scale", INTERACTION_SLIME_SCALE, List.of())));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.UPDATE_ATTRIBUTES, scale, false);
        f.player.packetEntityReplication.onUpdateAttributes(event, f.player, scale);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
        entities.entityMap.get(RIDER).mount(entities.entityMap.get(100));
    }

    /** A set_passengers putting zombie 100 on the player, its ping answered or not. */
    private static void carry(JavaTickFixture f, boolean answered) {
        entity(f, 100, AWAY);
        f.setPassengers(f.player.entityID, List.of(100));
        if (answered) {
            f.pongThrough(f.player.lastTransactionSent.get());
            assertFalse(f.player.compensatedEntities.getSelf().passengers.isEmpty());
        } else {
            assertUnanswered(f);
        }
    }

    private static void removeEntity(JavaTickFixture f, int id) {
        var packet = new ClientboundRemoveEntities(List.of(id));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.REMOVE_ENTITIES, packet, false);
        f.player.packetEntityReplication.onRemoveEntities(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
    }

    private static void assertUnanswered(JavaTickFixture f) {
        assertTrue(f.player.lastTransactionSent.get() > f.player.lastTransactionReceived.get());
    }

    /** A respawn into the current level, its spawn teleport to {@link #START}, then two falling ticks to {@link #CONTACT}. */
    private static void respawnFalling(JavaTickFixture f) {
        f.fixture.respawn((byte) 0);
        f.teleport(START, new RelativeFlag(0));
        f.player.packetStateData.playerLoadedIntoLevel = true;
        f.place(START, Vec3.ZERO);
        f.input(NONE);
        assertExact(f.tick(START, false));
        assertExact(f.tick(CONTACT, false));
    }

    private static final Vec3 STANDING = new Vec3(0.5, FLOOR_Y, 0.5);
    /** Clear of {@link #STANDING}'s contact box; the last of the steps it still owes there lands the entity on it. */
    private static final Vec3 SNAPPED = new Vec3(1.75, FLOOR_Y, 0.5);
    /** More than 64 blocks from {@link #SNAPPED}, so a position sync there snaps rather than interpolates. */
    private static final Vec3 SNAP_FROM = SNAPPED.add(0, 0, 80);
    /** Three interpolation steps of -0.4 each. */
    private static final double SNAP_STEPPING_DX = -1.2;

    /** An entity_position_sync of entity {@code id} to {@code position}, answered through its pings. */
    private static void positionSync(JavaTickFixture f, int id, Vec3 position) {
        var sync = new ClientboundEntityPositionSync(
                id, new PositionPath.Linear(new Vec3d(position.x, position.y, position.z)), 0.0F, 0.0F, true);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ENTITY_POSITION_SYNC, sync, false);
        f.player.packetEntityReplication.onEntityPositionSync(event, f.player, sync);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    /** An entity snapped to {@code position}, as by a position sync too far to interpolate. */
    private static void snap(JavaTickFixture f, PacketEntity entity, Vec3 position) {
        entity.setPositionRaw(
                GetBoundingBox.getPacketEntityBoundingBox(f.player, position.x, position.y, position.z, entity));
    }

    private static RelativeFlag keep(RelativeFlag... flags) {
        int mask = 0;
        for (RelativeFlag flag : flags) mask |= flag.getMask();
        return new RelativeFlag(mask);
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }
}
