package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Entity#isSuppressingBounce is Entity#isShiftKeyDown, which only LocalPlayer overrides: a ridden horse reads its own
 * shared flag 1 from the server, never the rider's keys. Through 26.1 the flag gates
 * SlimeBlock#updateEntityMovementAfterFallOn; from 26.2 it zeroes Entity#restituteMovementAfterCollisions on every
 * axis. The rider holds no keys throughout.
 */
class RiddenHorseBounceSuppressionTest {
    private static final ServerboundPlayerInput NO_KEYS =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final int HORSE = 100;
    private static final byte SHIFT_KEY_DOWN = 2;
    private static final double HORSE_HALF_WIDTH = 1.3964844F / 2.0;
    private static final double GRAVITY = 0.08D;
    // LivingEntity#travelInAir: a grounded horse's next vertical delta, (0 - 0.08) * 0.98F.
    private static final double GROUNDED_Y = -0.0784000015258789;
    // LivingEntity#travelInAir on stone: its 0.6F friction times the 0.91F air drag.
    private static final double STONE_FRICTION = 0.6F * 0.91F;
    // The horse falls from rest here onto slime whose top is FLOOR_Y.
    private static final Vec3 DROP = new Vec3(0.5D, 68.5D, 0.5D);
    private static final double BOUNCINESS = 0.5D;
    // The horse starts this far short of the wall and carries more z than that into it.
    private static final double WALL_GAP = 0.25D;
    private static final Vec3 WALL_SEED = new Vec3(0.0D, GROUNDED_Y, 0.4D);

    /** The shift flag zeroes the landing's vertical deltaMovement: the horse carries gravity and stays on the slime. */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_11", "V_26_2", "V_26_3"})
    void aShiftingHorseLandingOnSlimeDoesNotBounce(ClientVersion version) throws Exception {
        try (var f = fallOntoSlime(version, SHIFT_KEY_DOWN)) {
            assertCarries(f, new Vec3(0.0D, GROUNDED_Y, 0.0D));
            assertExact(f.rideTick(new Vec3(DROP.x, FLOOR_Y, DROP.z), true), "the move after landing");
        }
    }

    /** The same fall without the shift flag bounces off the slime. */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_11", "V_26_2", "V_26_3"})
    void aHorseWithoutTheShiftFlagBouncesOffSlime(ClientVersion version) throws Exception {
        try (var f = fallOntoSlime(version, (byte) 0)) {
            Landing landing = Landing.fromDrop();
            double bounced = version.isNewerThanOrEquals(ClientVersion.V_26_2)
                    ? landing.restituted()
                    // SlimeBlock#bounceUp reverses a living entity's deltaMovement.
                    : -landing.deltaY();
            double carried = (bounced - GRAVITY) * 0.98F;
            assertCarries(f, new Vec3(0.0D, carried, 0.0D));
            assertExact(f.rideTick(new Vec3(DROP.x, FLOOR_Y + carried, DROP.z), false), "the bounce");
        }
    }

    /** The shift flag zeroes the bounciness restitution of the z the horse runs into the wall: it stays there. */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_26_2", "V_26_3"})
    void aShiftingHorseHittingAWallDoesNotRestitute(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 wall = hitWall(f, version, SHIFT_KEY_DOWN);
            assertCarries(f, new Vec3(0.0D, GROUNDED_Y, 0.0D));
            assertExact(f.rideTick(wall, true), "the move after the wall");
        }
    }

    /** Without the shift flag the horse rebounds by its bounciness attribute. */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_26_2", "V_26_3"})
    void aHorseWithoutTheShiftFlagRestitutesOffAWall(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 wall = hitWall(f, version, (byte) 0);
            double carried = -WALL_SEED.z * BOUNCINESS * STONE_FRICTION;
            assertCarries(f, new Vec3(0.0D, GROUNDED_Y, carried));
            assertExact(f.rideTick(wall.add(0.0D, 0.0D, carried), true), "the rebound");
        }
    }

    /**
     * Mounts a horse with shared {@code flags} at rest at DROP over slime and rides its fall: each of the 11 airborne
     * moves and the landing must predict exactly. The five-tick vehicle-switch window expires during the fall.
     */
    private static JavaTickFixture fallOntoSlime(ClientVersion version, byte flags) throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        PredictionScenarios.fill(f, -3, FLOOR_Y - 1, -3, 3, FLOOR_Y - 1, 3, BlockIds.SLIME_BLOCK.defaultState());
        mount(f, DROP, false).actionSharedFlags = flags;
        f.input(NO_KEYS);
        f.tickEnd();
        double y = DROP.y, velocity = 0.0D;
        int airborne = 0;
        while (y + velocity > FLOOR_Y) {
            y += velocity;
            assertExact(f.rideTick(new Vec3(DROP.x, y, DROP.z), false), "airborne move " + airborne++);
            velocity = (velocity - GRAVITY) * 0.98F;
        }
        assertEquals(11, airborne, "airborne moves");
        assertExact(f.rideTick(new Vec3(DROP.x, FLOOR_Y, DROP.z), true), "the landing");
        return f;
    }

    /** The idle fall from DROP's landing move: the height it starts from and the deltaMovement it carries. */
    private record Landing(double fromY, double deltaY) {
        static Landing fromDrop() {
            double y = DROP.y, velocity = 0.0D;
            while (y + velocity > FLOOR_Y) {
                y += velocity;
                velocity = (velocity - GRAVITY) * 0.98F;
            }
            return new Landing(y, velocity);
        }

        /**
         * Entity#restituteMovementAfterCollisions on slime: the higher of the 0 bounciness attribute and slime's 1.0
         * restitution, with gravity compensation and LivingEntity#getAirDrag over the portion of the move completed.
         */
        double restituted() {
            double portion = (FLOOR_Y - fromY) / deltaY;
            double drag = 1.0D + portion * ((double) 0.98F - 1.0D);
            return (portion * GRAVITY - deltaY) * drag;
        }
    }

    /**
     * A horse with shared {@code flags} and a 0.5 bounciness attribute on stone, WALL_GAP short of a two-high wall at
     * z 2, after the vehicle-switch window. Its carried z runs it into the wall; returns where the move stops it.
     */
    private static Vec3 hitWall(JavaTickFixture f, ClientVersion version, byte flags) throws Exception {
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        PredictionScenarios.fill(f, -3, FLOOR_Y, 2, 3, FLOOR_Y + 1, 4, BlockIds.STONE.defaultState());
        Vec3 seat = new Vec3(0.5D, FLOOR_Y, 2.0D - WALL_GAP - HORSE_HALF_WIDTH);
        mount(f, seat, true).actionSharedFlags = flags;
        f.player.compensatedEntities.updateAttributes(
                HORSE, List.of(new AttributeSnapshot("minecraft:bounciness", BOUNCINESS, List.of())));
        f.pongThrough(f.player.lastTransactionSent.get());
        // A horse standing on the floor carries gravity's delta into each move, so Entity#move keeps it grounded.
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(new Vec3(0.0D, GROUNDED_Y, 0.0D));
        f.input(NO_KEYS);
        f.tickEnd();
        for (int tick = 0; tick < 6; tick++) assertExact(f.rideTick(seat, true), "settling tick " + tick);
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(WALL_SEED);
        Vec3 wall = new Vec3(seat.x, seat.y, 2.0D - HORSE_HALF_WIDTH);
        assertExact(f.rideTick(wall, true), "the move into the wall");
        return wall;
    }

    /** A saddled horse at {@code start} with the player riding it. */
    private static PacketEntityHorse mount(JavaTickFixture f, Vec3 start, boolean onGround) {
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.player.compensatedEntities.addEntity(HORSE, EntityTypeIds.HORSE, start, 0, 0, 0);
        PacketEntityHorse horse = (PacketEntityHorse) f.player.compensatedEntities.getEntity(HORSE);
        horse.hasSaddle = true;
        horse.isTame = true;
        horse.onGround = onGround;
        f.setPassengers(HORSE, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.fromBoolean(onGround));
        return horse;
    }

    /** The velocities committed into the next move include the client's {@code carried}. */
    private static void assertCarries(JavaTickFixture f, Vec3 carried) {
        Set<Vec3> starting =
                Set.copyOf(f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels());
        assertTrue(
                starting.stream().anyMatch(velocity -> velocity.distanceToSqr(carried) < 1.0E-18),
                "carried " + starting + ", the client carries " + carried);
    }

    private static void assertExact(PredictionResult result, String move) {
        StringBuilder text = new StringBuilder(move).append(": ").append(describe(result));
        for (PredictionResult.Flag flag : result.getFlags())
            text.append(' ').append(flag.getVerbose().getString());
        assertTrue(exact(result), text.toString());
    }
}
