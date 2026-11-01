package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.VanillaMath;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A riptide released on the ground in water. TridentItem#releaseUsing pushes, then moves the player up by
 * Entity#move(SELF, (0, 1.1999999F, 0)); that move leaves onGround false, so the tick's LivingEntity#aiStep neither
 * jumps nor reads the ground's friction.
 */
class JavaRiptideGroundReleaseTest {
    private static final ServerboundPlayerInput JUMP_KEY =
            new ServerboundPlayerInput(false, false, false, false, true, false, false);
    private static final ServerboundPlayerInput FORWARD_KEY =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final float YAW = 0.0F, PITCH = -90.0F;
    private static final int RIPTIDE = 3;
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final Vec3 CARRIED = new Vec3(0.1, (0.0 - 0.08) * 0.98F, 0.0);
    private static final float LIFT = 1.1999999F;
    private static final double BOUNCINESS = 0.05;

    @Test
    void theReleaseTickDoesNotJump() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 pushed = release(f);
            assertExact(f.tick(START.add(new Vec3(0, LIFT, 0)).add(pushed), false));
        }
        try (var f = new JavaTickFixture()) {
            Vec3 pushed = release(f);
            Vec3 jumped = pushed.add(new Vec3(0, 0.45, 0));
            assertRejected(f.tick(START.add(new Vec3(0, LIFT, 0)).add(jumped), false), "VerticalOffset");
        }
    }

    @Test
    void theNextTickCarriesAirFriction() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 position = releaseExactly(f);
            assertExact(f.tick(position.add(travelInAir(release(null), 0.91F)), false));
        }
        try (var f = new JavaTickFixture()) {
            Vec3 position = releaseExactly(f);
            assertRejected(f.tick(position.add(travelInAir(release(null), 0.6F * 0.91F)), false), "Strafe");
        }
    }

    @Test
    void theNextTickFallsByItsVelocityAlone() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 position = releaseExactly(f);
            Vec3 dropped = travelInAir(release(null), 0.91F).subtract(0, 1.0, 0);
            assertRejected(f.tick(position.add(dropped), false), "VerticalOffset");
        }
    }

    /**
     * A spin on the ground lifts the player off it: the tick's LivingEntity#travel reads onGround false and
     * Player#getFlyingSpeed moves the input.
     */
    @Test
    void aSpinMovesTheInputAtTheAirSpeed() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 pushed = release(f, false, FORWARD_KEY);
            Vec3 walked = new Vec3(0, 0, 0.98F * 0.02F);
            assertExact(f.tick(START.add(new Vec3(0, LIFT, 0)).add(pushed).add(walked), false));
        }
        try (var f = new JavaTickFixture()) {
            Vec3 pushed = release(f, false, FORWARD_KEY);
            Vec3 walked = new Vec3(0, 0, 0.98F * 0.1F);
            assertRejected(f.tick(START.add(new Vec3(0, LIFT, 0)).add(pushed).add(walked), false), "Strafe");
        }
    }

    /**
     * A lift that hits a top slab's underside: Entity#restituteMovementAfterCollisions scales an upward collision by
     * the minecraft:bounciness attribute alone, and the tick travels down by the restituted velocity.
     */
    @ParameterizedTest
    @CsvSource({"-90, -0.14442621406723027", "-60, -0.12417933959735084"})
    void aLiftUnderACeilingRestitutesByTheBounciness(float pitch, double restituted) throws Exception {
        double lift = 2.5 - 1.8F;
        try (var f = new JavaTickFixture()) {
            Vec3 pushed = releaseUnderASlab(f, pitch);
            assertExact(f.tick(START.add(new Vec3(pushed.x, lift + restituted, pushed.z)), false));
        }
        try (var f = new JavaTickFixture()) {
            Vec3 pushed = releaseUnderASlab(f, pitch);
            assertRejected(f.tick(START.add(new Vec3(pushed.x, lift, pushed.z)), false), "VerticalOffset");
        }
    }

    private static Vec3 releaseUnderASlab(JavaTickFixture f, float pitch) {
        BlockRegistry registry = DataTables.defaults().registry();
        int slab = registry.with(registry.block("minecraft:stone_slab").defaultState(), "type", "top");
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 4; z++) f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y + 2, z), slab);
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.player.compensatedEntities.getSelf().bounciness = BOUNCINESS;
        return release(f, pitch, true, JUMP_KEY);
    }

    private static Vec3 releaseExactly(JavaTickFixture f) {
        Vec3 position = START.add(new Vec3(0, LIFT, 0)).add(release(f));
        assertExact(f.tick(position, false));
        return position;
    }

    /** Sets up a certain grounded release when {@code f} is given; the pushed velocity the tick moves by. */
    private static Vec3 release(JavaTickFixture f) {
        return release(f, true, JUMP_KEY);
    }

    private static Vec3 release(JavaTickFixture f, boolean certain, ServerboundPlayerInput input) {
        return release(f, PITCH, certain, input);
    }

    private static Vec3 release(JavaTickFixture f, float pitch, boolean certain, ServerboundPlayerInput input) {
        if (f != null) {
            f.player.xRot = YAW;
            f.player.yRot = pitch;
            f.input(input);
            f.place(START, CARRIED);
            f.player.packetStateData.riptideLevel = RIPTIDE;
            f.player.packetStateData.riptideReleaseCertain = certain;
            f.player.riptideSpinAttackTicks = 20;
        }
        Vec3 pushed = CARRIED.add(push(pitch));
        // LivingEntity#aiStep zeroes each axis under 0.003.
        return new Vec3(
                Math.abs(pushed.x) < 0.003 ? 0 : pushed.x,
                Math.abs(pushed.y) < 0.003 ? 0 : pushed.y,
                Math.abs(pushed.z) < 0.003 ? 0 : pushed.z);
    }

    /** LivingEntity#travelInAir without input: the stored velocity after the move. */
    private static Vec3 travelInAir(Vec3 moved, float friction) {
        return new Vec3(moved.x * friction, (moved.y - 0.08) * 0.98F, moved.z * friction);
    }

    /** TridentItem#releaseUsing: the look scaled to the spin attack strength. */
    private static Vec3 push(float pitch) {
        float strength = 3.0F * ((1.0F + RIPTIDE) / 4.0F);
        float yRot = YAW * (float) (Math.PI / 180.0);
        float xRot = pitch * (float) (Math.PI / 180.0);
        float xd = -VanillaMath.sin(yRot) * VanillaMath.cos(xRot);
        float yd = -VanillaMath.sin(xRot);
        float zd = VanillaMath.cos(yRot) * VanillaMath.cos(xRot);
        float dist = (float) Math.sqrt(xd * xd + yd * yd + zd * zd);
        return new Vec3(xd * (strength / dist), yd * (strength / dist), zd * (strength / dist));
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }
}
