package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.combat.KeepSprint;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.events.packets.listeners.PacketPlayerAttack;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.math.Vec3;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A player falling onto slime blocks beside a boat it never touches. Entity#move sets verticalCollision only when
 * Entity#collide clipped the vertical movement, so the tick that ends just above the slime does not bounce; the
 * landing tick does, through Entity#restituteMovementAfterCollisions.
 */
class JavaSlimeBounceBesideBoatTest {
    private static final int BOAT = 100;
    private static final double GRAVITY = 0.08D;
    private static final double AIR_DRAG = 0.98F;
    private static final double AIR_FRICTION = 0.91F;
    private static final ServerboundPlayerInput SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    // The capture's fall: one tick past the apex, then a tick ending 0.168 above the slime.
    private static final double APEX_Y = FLOOR_Y + 0.40177530940784;
    private static final Vec3 START = new Vec3(3.43, APEX_Y, 1.87);
    // Clear of the player's box on X and Z, within reach of its fall.
    private static final Vec3 BOAT_AT = new Vec3(2.06, FLOOR_Y, 3.33);
    private static final ServerboundPlayerInput JUMP =
            new ServerboundPlayerInput(false, false, false, false, true, false, false);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final double JUMP_POWER = 0.42F;
    private static final Vec3 IN_PLACE = new Vec3(0.5, FLOOR_Y, 0.5);
    // Clear of the player's box on X, 0.0125 from its face.
    private static final Vec3 BOAT_BESIDE = IN_PLACE.add(1.0, 0.0, 0.0);
    private static final double[] FALL_HEIGHTS = {1.0D, 2.5D};
    private static final double[] REPORTED_ABOVE = {2.0E-7D, 1.0E-4D};
    // Boats clear of the player's box, one to two and a half blocks away.
    private static final Vec3[] BOAT_OFFSETS = {
        new Vec3(1.0, 0.0, 0.0),
        new Vec3(0.0, 0.0, 1.0),
        new Vec3(1.2, 0.0, 1.2),
        new Vec3(-1.6, 0.0, 0.4),
        new Vec3(0.3, 0.0, -2.0),
        new Vec3(2.4, 0.0, 0.0)
    };

    @Test
    void fallingOntoSlimeBesideABoatBouncesOnlyOnTheLandingTick() throws Exception {
        try (var f = new JavaTickFixture()) {
            slimeFloor(f);
            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, BOAT_AT, 0, 0, 0);
            fallAndBounce(f);
        }
    }

    @Test
    void fallingOntoSlimeWithoutABoatBouncesOnlyOnTheLandingTick() throws Exception {
        try (var f = new JavaTickFixture()) {
            slimeFloor(f);
            fallAndBounce(f);
        }
    }

    /**
     * The capture's KeepSprint flag: the landing tick's sprinting attack on the boat. Player#causeExtraKnockback
     * multiplies X/Z by 0.6 and LocalPlayer#aiStep sprints again before travel, so the move holds the damping.
     */
    @Test
    void attackingTheBoatOnTheLandingTickKeepsTheAttackSlowdown() throws Exception {
        try (var f = new JavaTickFixture()) {
            slimeFloor(f);
            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, BOAT_AT, 0, 0, 0);
            float yaw = 0.0F;
            f.player.xRot = yaw;
            f.player.yRot = 0.0F;
            // Player#getAttackStrengthScale passes 0.9 once the ticker reaches the item's attack delay.
            for (int tick = 0; tick < 20; tick++) f.tickEnd();
            double firstFall = (0.0D - GRAVITY) * AIR_DRAG;
            double secondFall = (firstFall - GRAVITY) * AIR_DRAG;
            f.place(START, new Vec3(0.0D, firstFall, 0.0D));
            var sprint = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
            new PacketEntityAction()
                    .onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, sprint), f.player, sprint);
            f.input(SPRINT);
            // Player#getFlyingSpeed while sprinting; LivingEntity#travelInAir keeps 0.91F of X/Z.
            Vec3 input = ClientMoveInput.of(f, SPRINT, 1.0F, 0.025999999F, yaw);

            Vec3 delta = input;
            Vec3 at = START.add(delta.x, firstFall, delta.z);
            PredictionResult falling = f.tick(at, false);
            assertTrue(exact(falling), describe(falling));

            delta = new Vec3(delta.x * AIR_FRICTION, 0.0D, delta.z * AIR_FRICTION).add(input);
            at = at.add(delta.x, secondFall, delta.z);
            PredictionResult aboveSlime = f.tick(at, false);
            assertTrue(exact(aboveSlime), describe(aboveSlime));

            var attack = new ServerboundInteract(BOAT, InteractAction.ATTACK, Hand.MAIN_HAND, Optional.empty(), false);
            new PacketPlayerAttack()
                    .onInteract(
                            new PacketReceiveEvent<>(
                                    f.fixture.user, ConnectionPhase.PLAY, ServerboundPackets.INTERACT, attack),
                            f.player,
                            attack);
            delta = new Vec3(delta.x * AIR_FRICTION * 0.6D, 0.0D, delta.z * AIR_FRICTION * 0.6D).add(input);
            at = new Vec3(at.x + delta.x, FLOOR_Y, at.z + delta.z);
            PredictionResult landing = f.tick(at, true);
            assertEquals(1, landing.getSimulationContext().getMinAttackSlow(), describe(landing));
            assertEquals(
                    0.0D, f.player.checkManager.getListener(KeepSprint.class).getViolations(), describe(landing));
            assertTrue(exact(landing), describe(landing));
        }
    }

    /** The issue's jump in place on slime beside a boat, through its first bounces until it settles. */
    @Test
    void jumpingInPlaceOnSlimeBesideABoatBouncesOnlyOnLandingTicks() throws Exception {
        for (Vec3 boatOffset : BOAT_OFFSETS) {
            try (var f = new JavaTickFixture()) {
                slimeFloor(f);
                f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, IN_PLACE.add(boatOffset), 0, 0, 0);
                jumpInPlace(f);
            }
        }
    }

    @Test
    void jumpingInPlaceOnSlimeWithoutABoatBouncesOnlyOnLandingTicks() throws Exception {
        try (var f = new JavaTickFixture()) {
            slimeFloor(f);
            jumpInPlace(f);
        }
    }

    /**
     * A fall that Entity#collide clips onto the slime's top collides vertically wherever the client reports the
     * landing, so the next move must bounce.
     */
    @Test
    void aLandingReportedAboveTheSlimeStillBounces() throws Exception {
        for (double height : FALL_HEIGHTS) {
            for (double above : REPORTED_ABOVE) {
                try (var f = new JavaTickFixture()) {
                    slimeFloor(f);
                    assertRejected(landAndStay(f, IN_PLACE, height, FLOOR_Y + above), "VerticalOffset");
                }
            }
        }
    }

    /** A boat clear of the player's box is not under it, so the slime alone clips the fall. */
    @Test
    void aLandingReportedAboveTheSlimeBesideABoatStillBounces() throws Exception {
        for (double height : FALL_HEIGHTS) {
            for (double above : REPORTED_ABOVE) {
                try (var f = new JavaTickFixture()) {
                    slimeFloor(f);
                    f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, BOAT_BESIDE, 0, 0, 0);
                    assertRejected(landAndStay(f, IN_PLACE, height, FLOOR_Y + above), "VerticalOffset");
                }
            }
        }
    }

    /** Entity#collide clips a fall onto a boat's top, and a boat does not bounce. */
    @Test
    void fallingOntoABoatOnSlimeLandsOnItsTop() throws Exception {
        for (double height : FALL_HEIGHTS) {
            try (var f = new JavaTickFixture()) {
                slimeFloor(f);
                f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, IN_PLACE, 0, 0, 0);
                double boatTop = f.player.compensatedEntities.getEntity(BOAT).getPossibleMovementCollisionBoxes().maxY;
                PredictionResult standing = landAndStay(f, IN_PLACE.add(0.0, boatTop - FLOOR_Y, 0.0), height, boatTop);
                assertTrue(exact(standing), height + ": " + describe(standing));
            }
        }
    }

    /**
     * LivingEntity#travelInAir from rest {@code height} above {@code floor}, a landing reported at {@code landedY}, and
     * the next tick reported at the same height; returns that tick's result.
     */
    private static PredictionResult landAndStay(JavaTickFixture f, Vec3 floor, double height, double landedY) {
        Vec3 top = floor.add(0.0, height, 0.0);
        double velocity = (0.0D - GRAVITY) * AIR_DRAG;
        f.place(top, new Vec3(0.0D, velocity, 0.0D));
        double y = top.y;
        while (y + velocity >= floor.y) {
            y += velocity;
            PredictionResult falling = f.tick(new Vec3(top.x, y, top.z), false);
            assertTrue(exact(falling), height + ": " + describe(falling));
            velocity = (velocity - GRAVITY) * AIR_DRAG;
        }
        Vec3 landed = new Vec3(top.x, landedY, top.z);
        PredictionResult landing = f.tick(landed, true);
        assertTrue(landing.getFlags().isEmpty() && !landing.isExempt(), height + ": " + describe(landing));
        return f.tick(landed, true);
    }

    /**
     * LivingEntity#jumpFromGround, then LivingEntity#travelInAir each tick. Entity#collide clips a fall that reaches
     * the slime onto its top, and Entity#restituteMovementAfterCollisions bounces that move unless it falls no faster
     * than gravity.
     */
    private static void jumpInPlace(JavaTickFixture f) {
        f.place(IN_PLACE, Vec3.ZERO);
        f.input(JUMP);
        double y = IN_PLACE.y;
        double velocity = JUMP_POWER;
        for (int tick = 0; tick < 45; tick++) {
            boolean landed = y + velocity < FLOOR_Y;
            double movement = landed ? FLOOR_Y - y : velocity;
            double after = velocity;
            if (landed) {
                double restitution = -velocity <= GRAVITY ? 0.0D : 1.0D;
                double portion = restitution > 0.0D ? movement / velocity : 0.0D;
                double drag = restitution > 0.0D ? 1.0D + portion * (AIR_DRAG - 1.0D) : 1.0D;
                after = (portion * GRAVITY - velocity) * drag * restitution;
            }
            y += movement;
            PredictionResult result = f.tick(new Vec3(IN_PLACE.x, y, IN_PLACE.z), landed);
            assertTrue(exact(result), "tick " + tick + ": " + describe(result));
            if (tick == 0) f.input(NONE);
            velocity = (after - GRAVITY) * AIR_DRAG;
        }
    }

    private static void fallAndBounce(JavaTickFixture f) {
        // LivingEntity#travelInAir: gravity, then the 0.98F drag, after each move.
        double firstFall = (0.0D - GRAVITY) * AIR_DRAG;
        double secondFall = (firstFall - GRAVITY) * AIR_DRAG;
        double landingVelocity = (secondFall - GRAVITY) * AIR_DRAG;
        f.place(START, new Vec3(0.0D, firstFall, 0.0D));

        Vec3 at = START.add(0.0D, firstFall, 0.0D);
        PredictionResult falling = f.tick(at, false);
        assertTrue(exact(falling), describe(falling));

        at = at.add(0.0D, secondFall, 0.0D);
        PredictionResult aboveSlime = f.tick(at, false);
        assertTrue(exact(aboveSlime), describe(aboveSlime));

        double landed = FLOOR_Y - at.y;
        at = new Vec3(at.x, FLOOR_Y, at.z);
        PredictionResult landing = f.tick(at, true);
        assertTrue(exact(landing), describe(landing));

        // Entity#restituteMovementAfterCollisions on slime (restitution 1.0) for the clipped part of the fall.
        double portionWithMovement = landed / landingVelocity;
        double effectiveDrag = 1.0D + portionWithMovement * (AIR_DRAG - 1.0D);
        double bounce = (portionWithMovement * GRAVITY - landingVelocity) * effectiveDrag;
        double rise = (bounce - GRAVITY) * AIR_DRAG;
        PredictionResult bouncing = f.tick(at.add(0.0D, rise, 0.0D), false);
        assertTrue(exact(bouncing), describe(bouncing));
    }

    private static void slimeFloor(JavaTickFixture f) {
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                f.player.compensatedWorld.updateBlock(
                        new BlockPos(x, FLOOR_Y - 1, z), BlockIds.SLIME_BLOCK.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }
}
