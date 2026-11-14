package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPaddleBoat;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.VelocityCandidates;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;

/**
 * Clients before 26.2 bounce off slime and beds through SlimeBlock#bounceUp and BedBlock#bounceUp: a falling
 * deltaMovement turns to {@code -y} (slime) or {@code -y * 0.66F} (bed), times 0.8 for a mover that is not living,
 * unless Entity#isSuppressingBounce. LivingEntity#travelInAir then applies gravity and the 0.98F drag.
 */
class JavaBounceUpBeforeRestitutionTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput SHIFT =
            new ServerboundPlayerInput(false, false, false, false, false, true, false);
    private static final ServerboundPlayerInput JUMP =
            new ServerboundPlayerInput(false, false, false, false, true, false, false);
    private static final ServerboundPlayerInput SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final ServerboundPlayerInput SPRINT_JUMP =
            new ServerboundPlayerInput(true, false, false, false, true, false, true);
    private static final int BOAT = 400;
    private static final double GRAVITY = 0.08D;
    private static final double AIR_DRAG = 0.98F;
    private static final double FALL = 0.5D;
    private static final double BED_TOP = FLOOR_Y - 0.4375D;

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_1_21_11"})
    void aSlimeBounceRisesOnlyByTheVanillaBounce(ClientVersion version) throws Exception {
        double vanilla = (FALL - GRAVITY) * AIR_DRAG;
        assertTrue(bounceTick(version, slime(), FLOOR_Y, vanilla), "vanilla bounce");
        assertFalse(bounceTick(version, slime(), FLOOR_Y, FALL), "a bounce of the impact speed");
        assertFalse(bounceTick(version, slime(), FLOOR_Y, FALL + GRAVITY), "a bounce of the impact speed and gravity");
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_1_21_11"})
    void aBedBounceRisesOnlyByTheVanillaBounce(ClientVersion version) throws Exception {
        double vanilla = (FALL * 0.66F - GRAVITY) * AIR_DRAG;
        assertTrue(bounceTick(version, bed(), BED_TOP, vanilla), "vanilla bed bounce");
        assertFalse(bounceTick(version, bed(), BED_TOP, (FALL + GRAVITY) * 0.66F), "a bed bounce of 0.66 above");
    }

    /** A ten-block drop onto slime, bouncing freely until the bounces fade: every tick is the vanilla move. */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_1_21_11"})
    void aDropOntoSlimeBouncesUntilTheBouncesFade(ClientVersion version) throws Exception {
        try (var f = slimeFloor(version)) {
            Vec3 top = new Vec3(0.5, FLOOR_Y + 10, 0.5);
            double velocity = (0.0D - GRAVITY) * AIR_DRAG;
            f.place(top, new Vec3(0.0, velocity, 0.0));
            f.input(NONE);
            double y = top.y;
            int landings = 0;
            for (int tick = 0; tick < 400; tick++) {
                if (Math.abs(velocity) < 0.003D) velocity = 0.0D;
                boolean landed = y + velocity < FLOOR_Y;
                double movement = landed ? FLOOR_Y - y : velocity;
                if (landed) {
                    landings++;
                    velocity = -velocity;
                }
                y += movement;
                PredictionResult result = f.tick(new Vec3(top.x, y, top.z), landed);
                assertTrue(exact(result), "tick " + tick + " landing " + landings + ": " + describe(result));
                if (landed && velocity < 0.2D) break;
                velocity = (velocity - GRAVITY) * AIR_DRAG;
            }
            assertTrue(landings >= 5, "landings " + landings);
        }
    }

    /** Shift held on the landing: Entity#isSuppressingBounce keeps the client on the slime. */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_1_21_11"})
    void aSneakingLandingOnSlimeDoesNotBounce(ClientVersion version) throws Exception {
        try (var f = slimeFloor(version)) {
            landOnSlime(f, SHIFT);
            PredictionResult staying = f.tick(new Vec3(0.5, FLOOR_Y, 0.5), true);
            assertTrue(exact(staying), describe(staying));
        }
        try (var f = slimeFloor(version)) {
            landOnSlime(f, SHIFT);
            double rise = (FALL - GRAVITY) * AIR_DRAG;
            PredictionResult bouncing = f.tick(new Vec3(0.5, FLOOR_Y + rise, 0.5), false);
            assertFalse(exact(bouncing), describe(bouncing));
        }
    }

    /** A jump on the tick after a slime landing: LivingEntity#jumpFromGround keeps the higher of the jump and bounce. */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_1_21_11"})
    void aJumpFromASlimeLandingRisesByTheJump(ClientVersion version) throws Exception {
        try (var f = slimeFloor(version)) {
            landOnSlime(f, NONE);
            f.input(JUMP);
            PredictionResult jumping = f.tick(new Vec3(0.5, FLOOR_Y + Math.max(0.42F, (FALL - GRAVITY) * AIR_DRAG), 0.5), false);
            assertTrue(exact(jumping), describe(jumping));
        }
    }

    /**
     * Knockback into the slime sent while falling, its ping answered {@code late} moves later: the client falls and
     * bounces at its own speed until then, and the move after the pong lands on the knockback and bounces off it.
     */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_1_21_11"})
    void aSlimeLandingOnLateKnockbackBouncesOffTheKnockback(ClientVersion version) throws Exception {
        Vec3 knockback = new Vec3(0.0, -0.7, 0.0);
        for (int late = 0; late <= 3; late++) {
            try (var f = slimeFloor(version)) {
                double y = FLOOR_Y + 0.5;
                double velocity = -0.3;
                f.place(new Vec3(0.5, y, 0.5), new Vec3(0.0, velocity, 0.0));
                f.input(NONE);
                int proof = f.velocity(knockback);
                for (int tick = 0; tick < late + 2; tick++) {
                    if (tick == late) {
                        f.pongThrough(proof);
                        velocity = knockback.y;
                    }
                    boolean landed = y + velocity < FLOOR_Y;
                    double movement = landed ? FLOOR_Y - y : velocity;
                    if (landed) velocity = -velocity;
                    y += movement;
                    PredictionResult result = f.tick(new Vec3(0.5, y, 0.5), landed);
                    assertTrue(exact(result), "pong " + late + " moves late, tick " + tick + ": " + describe(result));
                    velocity = (velocity - GRAVITY) * AIR_DRAG;
                }
                assertEquals((-knockback.y - GRAVITY) * AIR_DRAG, y - FLOOR_Y, 1.0E-9, "the bounce off the knockback");
            }
        }
    }

    /**
     * A ridden boat dropped onto slime: AbstractBoat#floatBoat adds -0.04 each tick in the air and on land, and
     * SlimeBlock#bounceUp keeps 0.8 of the impact for the boat. Every bounce until they fade is the vanilla move.
     */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_1_21_11"})
    void aRiddenBoatDroppedOntoSlimeBouncesByEightTenths(ClientVersion version) throws Exception {
        try (var f = slimeFloor(version)) {
            Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
            Vec3 top = new Vec3(0.5, FLOOR_Y + 2, 0.5);
            // A start off the 0.04 grid, so no rise returns exactly to the slime's top.
            double velocity = -0.01D;
            f.place(top.add(0, 0.5, 0), Vec3.ZERO);
            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, top, 0, 0, 0);
            f.setPassengers(BOAT, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.FALSE);
            f.player.checkManager.getSimulationProcessor().seedStartingVelocity(new Vec3(0, velocity, 0));
            f.input(NONE);
            double y = top.y;
            int landings = 0;
            for (int tick = 0; tick < 200; tick++) {
                velocity -= 0.04D;
                boolean landed = y + velocity < FLOOR_Y;
                double movement = landed ? FLOOR_Y - y : velocity;
                if (landed) {
                    landings++;
                    velocity = -velocity * 0.8D;
                }
                y += movement;
                var paddle = new ServerboundPaddleBoat(false, false);
                CheckManagerListener.receiveGeneric(f.event(ServerboundPackets.PADDLE_BOAT, paddle), f.player);
                PredictionResult result = f.rideTick(new Vec3(top.x, y, top.z), landed);
                assertTrue(exact(result), "tick " + tick + " landing " + landings + ": " + describe(result));
                if (landed && velocity < 0.1D) break;
            }
            assertTrue(landings >= 4, "landings " + landings);
        }
    }

    /**
     * Forty ticks sprinting forward over a slime floor, jumping whenever the client stands on it: LivingEntity#aiStep's
     * 0.003 cut and jumpFromGround, LivingEntity#travelInAir on slime's friction, SlimeBlock#bounceUp in Entity#move
     * and SlimeBlock#stepOn after travel. Every tick is the vanilla move.
     */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_1_21_11"})
    void sprintJumpingAcrossSlimeIsExactEveryTick(ClientVersion version) throws Exception {
        try (var f = slimeFloor(version)) {
            double rise = 0.033382;
            Vec3 at = new Vec3(0.5, FLOOR_Y, -7.5);
            f.place(at, new Vec3(0.0, rise, 0.0));
            f.player.xRot = 0.0F;
            f.player.yRot = 0.0F;
            f.input(NONE);
            PredictionResult rising = f.tick(at.add(0.0, rise, 0.0), false);
            assertTrue(exact(rising), "rise: " + describe(rising));
            PredictionResult landing = f.tick(at, true);
            assertTrue(exact(landing), "land: " + describe(landing));
            double vy = (-((rise - GRAVITY) * AIR_DRAG) - GRAVITY) * AIR_DRAG;
            double vz = 0.0D;
            boolean onGround = true;
            boolean jumped = false;
            f.command(PlayerCommandAction.START_SPRINTING);
            double y = at.y;
            double z = at.z;
            for (int tick = 0; tick < 40; tick++) {
                boolean jump = onGround && !jumped;
                f.input(jump ? SPRINT_JUMP : SPRINT);
                if (Math.abs(vz) < 0.003D) vz = 0.0D;
                if (Math.abs(vy) < 0.003D) vy = 0.0D;
                if (jump) {
                    vy = Math.max((double) 0.42F, vy);
                    vz += 0.2D;
                }
                float friction = onGround ? 0.8F : 1.0F;
                float speed = onGround ? 0.13F * (0.21600002F / (friction * friction * friction)) : 0.025999999F;
                vz += (double) 0.98F * (double) speed;
                boolean collided = vy < 0.0D && y + vy <= FLOOR_Y;
                double dy = collided ? FLOOR_Y - y : vy;
                boolean landed = collided && dy != vy;
                if (landed) vy = -vy;
                y += dy;
                z += vz;
                onGround = landed;
                vy = (vy - GRAVITY) * AIR_DRAG;
                vz *= (double) (friction * 0.91F);
                if (onGround && Math.abs(vy) < 0.1D) vz *= 0.4D + Math.abs(vy) * 0.2D;
                jumped = jump;
                PredictionResult result = f.tick(new Vec3(at.x, y, z), onGround);
                assertTrue(exact(result), "tick " + tick + (jump ? " jump" : "") + ": " + describe(result));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_1_21_11"})
    void aMoverThatIsNotLivingKeepsEightTenthsOfTheBounce(ClientVersion version) throws Exception {
        try (var f = slimeFloor(version)) {
            CultPlayer player = f.player;
            int slime = slime();
            int bed = bed();
            assertEquals(0.5D, VelocityCandidates.afterVerticalCollision(player, slime, false, true, -0.5, 0.0));
            assertEquals(0.5D * 0.8D, VelocityCandidates.afterVerticalCollision(player, slime, false, false, -0.5, 0.0));
            assertEquals(
                    0.5D * 0.66F * 0.8D, VelocityCandidates.afterVerticalCollision(player, bed, false, false, -0.5, 0.0));
            assertEquals(0.0D, VelocityCandidates.afterVerticalCollision(player, slime, true, true, -0.5, 0.0));
        }
    }

    /** The landing at {@link #FALL} onto the slime under {@code input}, then the next tick's input is none. */
    private static void landOnSlime(JavaTickFixture f, ServerboundPlayerInput input) {
        f.place(new Vec3(0.5, FLOOR_Y + FALL - 0.1, 0.5), new Vec3(0, -FALL, 0));
        f.input(input);
        PredictionResult landing = f.tick(new Vec3(0.5, FLOOR_Y, 0.5), true);
        assertTrue(exact(landing), "landing " + describe(landing));
        f.input(NONE);
    }

    private static boolean bounceTick(ClientVersion version, int state, double top, double rise) throws Exception {
        try (var f = floor(version, state)) {
            f.place(new Vec3(0.5, top + FALL - 0.1, 0.5), new Vec3(0, -FALL, 0));
            f.input(NONE);
            PredictionResult landing = f.tick(new Vec3(0.5, top, 0.5), true);
            assertTrue(exact(landing), "landing " + describe(landing));
            return exact(f.tick(new Vec3(0.5, top + rise, 0.5), false));
        }
    }

    private static JavaTickFixture slimeFloor(ClientVersion version) throws Exception {
        return floor(version, slime());
    }

    private static JavaTickFixture floor(ClientVersion version, int state) throws Exception {
        var f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y - 1, z), state);
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        return f;
    }

    private static int slime() {
        return BlockIds.SLIME_BLOCK.defaultState();
    }

    private static int bed() {
        return DataTables.defaults().registry().block("minecraft:red_bed").defaultState();
    }
}
