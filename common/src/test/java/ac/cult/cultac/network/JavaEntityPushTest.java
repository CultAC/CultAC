package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.CarriedPush;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.EntityPush;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import org.junit.jupiter.api.Test;

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
    /** Out of reach of the player's contact box until its first interpolation step lands it on {@link #TOUCHING}. */
    private static final Vec3 STEPPING_FROM = TOUCHING.add(0, 0, 0.4);
    /** Three interpolation steps of -0.4 each. */
    private static final double STEPPING_DZ = -1.2;

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
            entity.onFirstTransaction(true, true, 0, 0, STEPPING_DZ, f.player);
            entity.onSecondTransaction();

            PredictionResult first = f.tick(START.add(0, FIRST_FALL, 0), false);
            assertExact(first);
            assertEquals(0, first.getSimulationContext().getWorldData().getNumPushing());
            assertExact(f.tick(START.add(0, FIRST_FALL + SECOND_FALL, PUSH), false));
        }
    }

    @Test
    void anEntityTickingBeforeThePlayerPushesItOnFirstContact() throws Exception {
        try (var f = falling()) {
            entity(f, 100, TOUCHING).setClientTickOrder(-1L);

            assertExact(f.tick(START.add(0, FIRST_FALL, PUSH), false));
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

    @Test
    void aRiderOutsideTheOwnRidingTreeDoesNotPush() throws Exception {
        try (var f = falling()) {
            PacketEntity vehicle = entity(f, 100, new Vec3(4.5, FLOOR_Y, 4.5));
            vehicle.setClientTickOrder(-1L);
            entity(f, 101, TOUCHING).mount(vehicle);

            PredictionResult contact = f.tick(START.add(0, FIRST_FALL, 0), false);

            assertEquals(0, contact.getSimulationContext().getWorldData().getNumPushing());
            assertExact(contact);
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
                    f.tick(LOADING.add(-TICKS_LOADING * pushFrom(LOADING_OTHER.x - LOADING.x), 0, 0), true);
            assertExact(first);
            assertEquals(0, first.getSimulationContext().getWorldData().getNumPushing());
        }
    }

    /** EntitySelector#pushableBy's team collision rules can stop a pusher touching the player from pushing it. */
    @Test
    void pushesWhileTheLevelLoadsMayNotHappen() throws Exception {
        try (var f = loadingBesideOther()) {
            assertExact(f.tick(LOADING, true));
        }
    }

    /** Entity#push points away from the pusher, so the waiting ticks' pushes cannot move the player towards it. */
    @Test
    void pushesWhileTheLevelLoadsDoNotMoveThePlayerTowardsThePusher() throws Exception {
        try (var f = loadingBesideOther()) {
            PredictionResult first =
                    f.tick(LOADING.add(TICKS_LOADING * pushFrom(LOADING_OTHER.x - LOADING.x), 0, 0), true);
            assertFalse(exact(first), describe(first));
        }
    }

    /** A teleport with relative DELTA_X/DELTA_Z keeps the movement the waiting ticks' pushes were added to. */
    @Test
    void aTeleportKeepingHorizontalVelocityWhileTheLevelLoadsKeepsThePushes() throws Exception {
        try (var f = loadingBesideOther()) {
            f.teleport(LOADING, ALL_DELTAS);
            assertExact(f.tick(LOADING.add(-TICKS_LOADING * pushFrom(LOADING_OTHER.x - LOADING.x), 0, 0), true));
        }
    }

    /** A teleport with absolute zero DELTA_X/DELTA_Z replaces the movement the waiting ticks' pushes were added to. */
    @Test
    void aTeleportWhileTheLevelLoadsDropsThePushesBeforeIt() throws Exception {
        try (var f = loadingBesideOther()) {
            f.teleport(LOADING, new RelativeFlag(0));
            PredictionResult first =
                    f.tick(LOADING.add(-TICKS_LOADING * pushFrom(LOADING_OTHER.x - LOADING.x), 0, 0), true);
            assertFalse(exact(first), describe(first));
        }
    }

    /**
     * A client standing at {@link #LOADING} that waited {@link #TICKS_LOADING} ticks for its level beside a zombie,
     * which then left; its level has just loaded.
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

    private static final int TICKS_LOADING = 3;
    private static final Vec3 LOADING = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final Vec3 LOADING_OTHER = new Vec3(0.94, FLOOR_Y, 0.5);

    /** A client that has just stepped off into the air at {@link #START}. */
    private static JavaTickFixture falling() throws Exception {
        JavaTickFixture f = new JavaTickFixture();
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
