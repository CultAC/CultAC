package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A horse the rider walks forward over slime: moves replayed from a 26.3 client's capture of a turning rider, and moves
 * that the 26.3 client sources give for a step-up, a horse without gravity, a shifting horse and a heavier horse.
 */
class RiddenHorseSlimeTest {
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final int HORSE = 100;
    // The captured horse's movement_speed: the float whose LivingEntity#travelRidden input reproduces every
    // captured move over the slime to 1.2E-9.
    private static final float SPEED = 0.14517796F;
    // LivingEntity#travelInAir: a grounded horse's next vertical delta, (0 - 0.08) * 0.98F.
    private static final double GROUNDED_Y = -0.0784000015258789;
    // LivingEntity#travelInAir: slime's 0.8F friction times the 0.91F air drag.
    private static final double SLIME_FRICTION = 0.8F * 0.91F;
    // LivingEntity#getFrictionInfluencedSpeed: the grounded horse's ridden input on slime's 0.8F friction.
    private static final double GROUND_INPUT = SPEED * (0.21600002F / (0.8F * 0.8F * 0.8F));
    private static final float[] EAST = {-90.0F, -90.0F};
    private static final float[] LEVEL = {0.0F, 0.0F};

    /**
     * The z the horse carries into the last move, 0.728 * -0.0087878 = -0.0063975, is above LivingEntity#aiStep's
     * 0.003 cutoff, but SlimeBlock#stepOn at the end of the move before scales it by 0.41568 to -0.0026593, which the
     * client zeroes: the last move's z, +0.0328116, is the ridden input alone.
     */
    @Test
    void theSlimeScaledCarriedAxisFallsUnderTheMovementThreshold() throws Exception {
        assertCarriesStepOn(new Scenario(
                List.of(
                        new Vec3(249.6605715004234D, 64.0D, 157.04957381788498D),
                        new Vec3(249.5854137032814D, 64.0D, 157.00922759520216D),
                        new Vec3(249.50151851508951D, 64.0D, 157.00043980177918D),
                        new Vec3(249.42441414954072D, 64.0D, 157.03325141497942D)),
                new float[] {-273.20578F, -302.39413F},
                new float[] {10.09855F, 9.293347F}));
    }

    /**
     * The ridden input (+0.0195908) opposes the carried x (0.728 * -0.0127028 = -0.0092476). SlimeBlock#stepOn scales
     * only that carried x, to -0.0038441, so the client moves x = +0.0157467, past the unscaled sum +0.0103432.
     */
    @Test
    void slimeScalesTheCarriedVelocityAndNotTheRiddenInput() throws Exception {
        assertCarriesStepOn(new Scenario(
                List.of(
                        new Vec3(248.4546638915012D, 64.0D, 157.8044185622659D),
                        new Vec3(248.4365760348503D, 64.0D, 157.72024903504146D),
                        new Vec3(248.42387324747585D, 64.0D, 157.6339592439466D),
                        new Vec3(248.43961997528717D, 64.0D, 157.54981743011976D)),
                new float[] {-186.78123F, -161.3504F},
                new float[] {7.716491F, 8.08554F}));
    }

    /**
     * Entity#move sets onGround from verticalCollisionBelow: a downward requested move that collision changes. A
     * step-up onto a slime block turns the requested -0.0784 into +1, so SlimeBlock#stepOn scales the velocity the
     * horse carries off the step by 0.41568.
     */
    @Test
    void slimeScalesTheVelocityCarriedOffAStepUp() throws Exception {
        try (var f = new JavaTickFixture()) {
            slime(f, 245, 258, 63, 154, 161);
            slime(f, 251, 258, 64, 154, 161);
            Vec3 start = new Vec3(250.2D, 64.0D, 157.5D);
            mount(f, start, true);
            Vec3 seed = new Vec3(0.08D, GROUNDED_Y, 0.0D);
            Vec3 step = new Vec3(start.x + seed.x + GROUND_INPUT, 65.0D, start.z);
            Vec3 carried = carried(start, step);
            Vec3 end = step.add(carried.x + GROUND_INPUT, 0.0D, 0.0D);
            assertRides(f, seed, List.of(step, end), EAST, LEVEL, true, carried);
        }
    }

    /**
     * A horse without gravity requests no vertical move, so Entity#move leaves verticalCollisionBelow and onGround
     * false while it rests on slime: SlimeBlock#stepOn does not run, and the horse carries its air velocity unscaled.
     */
    @Test
    void aHorseWithoutGravityRestingOnSlimeCarriesItsVelocityUnscaled() throws Exception {
        try (var f = new JavaTickFixture()) {
            slime(f, 247, 251, 63, 155, 159);
            Vec3 start = new Vec3(248.0D, 64.0D, 157.5D);
            mount(f, start, false).hasGravity = false;
            Vec3 seed = new Vec3(0.05D, 0.0D, 0.0D);
            // LivingEntity#getFlyingSpeed of a horse that a player controls.
            double input = SPEED * 0.1F;
            Vec3 first = start.add(seed.x + input, 0.0D, 0.0D);
            Vec3 carried = new Vec3((first.x - start.x) * 0.91F, 0.0D, 0.0D);
            Vec3 second = first.add(carried.x + input, 0.0D, 0.0D);
            assertRides(f, seed, List.of(first, second), EAST, LEVEL, false, carried);
        }
    }

    /** Entity#isSteppingCarefully reads the horse's own shift flag: a shifting horse carries its velocity unscaled. */
    @Test
    void aShiftingHorseCarriesItsVelocityOverSlimeUnscaled() throws Exception {
        try (var f = new JavaTickFixture()) {
            slime(f, 247, 251, 63, 155, 159);
            Vec3 start = new Vec3(248.0D, 64.0D, 157.5D);
            mount(f, start, true).actionSharedFlags = 2;
            Vec3 seed = new Vec3(0.08D, GROUNDED_Y, 0.0D);
            Vec3 first = start.add(seed.x + GROUND_INPUT, 0.0D, 0.0D);
            Vec3 carried = new Vec3((first.x - start.x) * SLIME_FRICTION, GROUNDED_Y, 0.0D);
            Vec3 second = first.add(carried.x + GROUND_INPUT, 0.0D, 0.0D);
            assertRides(f, seed, List.of(first, second), EAST, LEVEL, true, carried);
        }
    }

    /**
     * A 0.11 gravity attribute leaves a grounded horse a vertical delta of -0.11 * 0.98F = -0.1078, outside
     * SlimeBlock#stepOn's 0.1 bound, so the horse carries its velocity over slime unscaled.
     */
    @Test
    void aHorseLeavingAVerticalDeltaOfPointOneCarriesItsVelocityOverSlimeUnscaled() throws Exception {
        try (var f = new JavaTickFixture()) {
            slime(f, 247, 251, 63, 155, 159);
            Vec3 start = new Vec3(248.0D, 64.0D, 157.5D);
            PacketEntityHorse horse = mount(f, start, true);
            horse.gravity = 0.11D;
            double groundedY = (0.0D - horse.gravity) * 0.98F;
            Vec3 seed = new Vec3(0.08D, groundedY, 0.0D);
            Vec3 first = start.add(seed.x + GROUND_INPUT, 0.0D, 0.0D);
            Vec3 carried = new Vec3((first.x - start.x) * SLIME_FRICTION, groundedY, 0.0D);
            Vec3 second = first.add(carried.x + GROUND_INPUT, 0.0D, 0.0D);
            assertRides(f, seed, List.of(first, second), EAST, LEVEL, true, carried);
        }
    }

    /**
     * Seeds the velocity the client carries into the third position's move, rides the last two moves, and requires
     * each to predict exactly and the velocity carried between them to be the client's.
     */
    private static void assertCarriesStepOn(Scenario scenario) throws Exception {
        try (var f = new JavaTickFixture()) {
            List<Vec3> positions = scenario.positions();
            slime(f, 247, 251, 63, 155, 159);
            mount(f, positions.get(1), true);
            assertRides(
                    f,
                    carried(positions.get(0), positions.get(1)),
                    positions.subList(2, 4),
                    scenario.yaws(),
                    scenario.pitches(),
                    true,
                    carried(positions.get(1), positions.get(2)));
        }
    }

    /**
     * Seeds {@code seed} as the velocity carried into the first move, rides the two moves in {@code to} holding forward,
     * and requires each to predict exactly and the velocity carried between them to be {@code carried}.
     */
    private static void assertRides(
            JavaTickFixture f,
            Vec3 seed,
            List<Vec3> to,
            float[] yaws,
            float[] pitches,
            boolean onGround,
            Vec3 carried) {
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(seed);
        // AbstractHorse#tick runs before LocalPlayer#rideTick hands it this tick's keys.
        f.input(FORWARD);
        f.tickEnd();

        PredictionResult first = ride(f, to.get(0), yaws[0], pitches[0], onGround);
        assertTrue(exact(first), "first move: " + explain(first));
        Set<Vec3> starting =
                Set.copyOf(f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels());

        PredictionResult second = ride(f, to.get(1), yaws[1], pitches[1], onGround);
        assertTrue(exact(second), "second move: " + explain(second) + ", carried " + starting);
        assertTrue(
                starting.stream().anyMatch(velocity -> velocity.distanceToSqr(carried) < 1.0E-18),
                "carried " + starting + ", the client carries " + carried);
    }

    /**
     * The velocity a horse grounded on slime carries out of the move {@code from} to {@code to}: LivingEntity#travelInAir's
     * friction and gravity, then Entity#applyEffectsFromBlocks' SlimeBlock#stepOn scale of 0.4 + |dy| * 0.2.
     */
    private static Vec3 carried(Vec3 from, Vec3 to) {
        double scale = 0.4 + Math.abs(GROUNDED_Y) * 0.2;
        return new Vec3((to.x - from.x) * SLIME_FRICTION * scale, GROUNDED_Y, (to.z - from.z) * SLIME_FRICTION * scale);
    }

    private static String explain(PredictionResult result) {
        StringBuilder text = new StringBuilder(describe(result));
        for (PredictionResult.Flag flag : result.getFlags())
            text.append(' ').append(flag.getVerbose().getString());
        return text.toString();
    }

    private static PredictionResult ride(JavaTickFixture f, Vec3 to, float yaw, float pitch, boolean onGround) {
        f.player.xRot = yaw;
        f.player.yRot = pitch;
        f.input(FORWARD);
        return f.rideTick(to, onGround);
    }

    /** Slime blocks at height {@code y} over x0..x1 and z0..z1, in the loaded chunks around the captured patch. */
    private static void slime(JavaTickFixture f, int x0, int x1, int y, int z0, int z1) {
        for (int cx = 14; cx <= 16; cx++) {
            for (int cz = 8; cz <= 10; cz++) f.player.compensatedWorld.ensureValidationChunkLoaded(cx, cz);
        }
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), BlockIds.SLIME_BLOCK.defaultState());
            }
        }
    }

    /** A saddled horse at {@code start} with the player riding it. */
    private static PacketEntityHorse mount(JavaTickFixture f, Vec3 start, boolean onGround) {
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.player.compensatedEntities.addEntity(HORSE, EntityTypeIds.HORSE, start, 0, 0, 0);
        PacketEntityHorse horse = (PacketEntityHorse) f.player.compensatedEntities.getEntity(HORSE);
        horse.hasSaddle = true;
        horse.isTame = true;
        horse.movementSpeedAttribute = SPEED;
        horse.onGround = onGround;
        f.setPassengers(HORSE, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.fromBoolean(onGround));
        return horse;
    }

    /** Four consecutive horse positions, and the rider's yaw and pitch of the last two moves. */
    private record Scenario(List<Vec3> positions, float[] yaws, float[] pitches) {}
}
