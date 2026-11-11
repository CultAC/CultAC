package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.packet.WorldPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHappyGhast;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * HappyGhast#tickRidden turns the ghast 8% of the way from its own yaw toward the controlling player's yaw, then
 * LivingEntity#travel moves it along that turned yaw; ServerboundMoveVehiclePacket carries the turned yaw. The
 * expected moves are vanilla's client arithmetic, computed here without the engine.
 */
class HappyGhastRiddenTurnTest {
    private static final int VEHICLE = 100;

    /**
     * A ridden ghast spawned at {@code ghastYaw}, settled for {@code settleTicks} with the rider facing it, then
     * turned toward {@code riderYaw} at {@code riderPitch} for {@code keylessTicks} before {@code keys} are pressed
     * and for {@code movingTicks} after the tick that sends them.
     */
    record Scenario(
            String name,
            float ghastYaw,
            float riderYaw,
            float riderPitch,
            ServerboundPlayerInput keys,
            int settleTicks,
            int keylessTicks,
            int movingTicks) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Scenario> scenarios() {
        return Stream.of(
                new Scenario("forward toward 170", 0.0F, 170.0F, 0.0F, keys(true, false, false, false), 5, 0, 3),
                new Scenario("turn through 180", 170.0F, -170.0F, 0.0F, keys(true, false, false, false), 5, 0, 10),
                new Scenario("strafe and back", 0.0F, 170.0F, 0.0F, keys(false, true, true, false), 5, 0, 4),
                new Scenario("forward at pitch -30", 0.0F, 120.0F, -30.0F, keys(true, false, false, false), 5, 0, 4),
                new Scenario(
                        "strafe and back at pitch 30 through 180",
                        170.0F,
                        -170.0F,
                        30.0F,
                        keys(false, true, false, true),
                        5,
                        2,
                        10),
                new Scenario("first ridden tick", 0.0F, 90.0F, 0.0F, keys(true, false, false, false), 0, 0, 4),
                new Scenario("keyless turn first", 0.0F, 170.0F, 0.0F, keys(true, false, false, false), 5, 2, 4));
    }

    private static ServerboundPlayerInput keys(boolean forward, boolean backward, boolean left, boolean right) {
        return new ServerboundPlayerInput(forward, backward, left, right, false, false, false);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void aTurningPlayerSteersTheGhastAlongItsTurnedYaw(Scenario s) throws Exception {
        Vec3 seat = new Vec3(0.5, FLOOR_Y, 0.5);
        try (var f = new JavaTickFixture()) {
            f.place(seat, Vec3.ZERO);
            f.player.compensatedWorld.applyClientTime(new WorldPackets.TimeUpdate(24_000L, Map.of()));
            f.player.compensatedEntities.addEntity(VEHICLE, EntityTypeIds.HAPPY_GHAST, seat, s.ghastYaw(), 0, 0);
            ((PacketEntityHappyGhast) f.player.compensatedEntities.getEntity(VEHICLE)).hasBodyArmor = true;
            f.setPassengers(VEHICLE, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());

            VanillaGhast ghast = new VanillaGhast(seat, s.ghastYaw());
            for (int tick = 0; tick < s.settleTicks(); tick++) {
                ghast.tick(s.ghastYaw(), 0.0F);
                assertPredicted(clientTick(f, ghast, s.ghastYaw(), 0.0F), s, "settle " + tick);
            }
            for (int tick = 0; tick < s.keylessTicks(); tick++) {
                ghast.tick(s.riderYaw(), s.riderPitch());
                assertPredicted(clientTick(f, ghast, s.riderYaw(), s.riderPitch()), s, "keyless " + tick);
            }
            f.input(s.keys());
            ghast.tick(s.riderYaw(), s.riderPitch());
            ghast.press(s.keys());
            assertPredicted(clientTick(f, ghast, s.riderYaw(), s.riderPitch()), s, "input tick");
            for (int tick = 0; tick < s.movingTicks(); tick++) {
                ghast.tick(s.riderYaw(), s.riderPitch());
                assertPredicted(clientTick(f, ghast, s.riderYaw(), s.riderPitch()), s, "moving " + tick);
            }
        }
    }

    /** A client tick of the rider's Rot and the ghast's MoveVehicle, as LocalPlayer sends them after the root ticks. */
    private static PredictionResult clientTick(
            JavaTickFixture f, VanillaGhast ghast, float riderYaw, float riderPitch) {
        PredictionResult before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        var rot = new ServerboundMovePlayer(0, 0, 0, riderYaw, riderPitch, true, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        f.moveVehicle(new Vec3(ghast.x, ghast.y, ghast.z), ghast.yaw, ghast.pitch, false);
        f.tickEnd();
        PredictionResult result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        assertTrue(result != null && result != before, "the tick committed no prediction");
        return result;
    }

    /** Exact, with the engine's closest movement equal to the client's, below any flag threshold. */
    private static void assertPredicted(PredictionResult result, Scenario s, String tick) {
        double miss =
                result.getTarget().subtract(result.getAcceptedClosestToTarget()).length();
        assertTrue(exact(result), s + " " + tick + ": " + describe(result));
        assertTrue(miss < 1.0E-7, s + " " + tick + ": predicted move misses the client's by " + miss);
    }

    /** The client-side happy ghast and its controlling LocalPlayer's move input, in vanilla's float and double math. */
    private static final class VanillaGhast {
        /** Mth.SIN. */
        private static final float[] SIN = new float[65536];

        static {
            for (int i = 0; i < SIN.length; i++) SIN[i] = (float) Math.sin(i / 10430.378350470453);
        }

        /** HappyGhast's FLYING_SPEED attribute default. */
        private static final double FLYING_SPEED = 0.05;

        double x;
        double y;
        double z;
        double vx;
        double vy;
        double vz;
        float yaw;
        float pitch;
        /** LocalPlayer#xxa and #zza, set by the passenger's aiStep after the root has ticked. */
        float xxa;

        float zza;

        VanillaGhast(Vec3 at, float yaw) {
            this.x = at.x;
            this.y = at.y;
            this.z = at.z;
            this.yaw = yaw;
        }

        /** The root's tick: LivingEntity#aiStep's small-motion cut, then LivingEntity#travelRidden. */
        void tick(float riderYaw, float riderPitch) {
            if (Math.abs(vx) < 0.003) vx = 0.0;
            if (Math.abs(vz) < 0.003) vz = 0.0;
            if (Math.abs(vy) < 0.003) vy = 0.0;

            // HappyGhast#getRiddenInput
            float strafe = xxa;
            float up = 0.0F;
            float forward = 0.0F;
            if (zza != 0.0F) {
                float i = cos(riderPitch * (float) (Math.PI / 180.0));
                float j = -sin(riderPitch * (float) (Math.PI / 180.0));
                if (zza < 0.0F) {
                    i *= -0.5F;
                    j *= -0.5F;
                }
                up = j;
                forward = i;
            }
            double scale = 3.9F * FLYING_SPEED;
            double ix = strafe * scale;
            double iy = up * scale;
            double iz = forward * scale;

            // HappyGhast#tickRidden
            yaw += wrapDegrees(riderYaw - yaw) * 0.08F;
            pitch = riderPitch * 0.5F;

            // HappyGhast#travel -> LivingEntity#travelFlying -> Entity#moveRelative
            float speed = (float) FLYING_SPEED * 5.0F / 3.0F;
            double lengthSqr = ix * ix + iy * iy + iz * iz;
            if (lengthSqr >= 1.0E-7) {
                if (lengthSqr > 1.0) {
                    double length = Math.sqrt(lengthSqr);
                    ix /= length;
                    iy /= length;
                    iz /= length;
                }
                ix *= speed;
                iy *= speed;
                iz *= speed;
                float h = sin(yaw * (float) (Math.PI / 180.0));
                float i = cos(yaw * (float) (Math.PI / 180.0));
                vx += ix * i - iz * h;
                vy += iy;
                vz += iz * i + ix * h;
            }
            x += vx;
            y += vy;
            z += vz;
            vx *= 0.91F;
            vy *= 0.91F;
            vz *= 0.91F;
        }

        /** KeyboardInput#tick and LocalPlayer#modifyInput for a rider that is not crouching or using an item. */
        void press(ServerboundPlayerInput keys) {
            float moveZ = impulse(keys.forward(), keys.backward());
            float moveX = impulse(keys.left(), keys.right());
            float length = (float) Math.sqrt(moveX * moveX + moveZ * moveZ);
            if (length < 1.0E-4F) {
                xxa = 0.0F;
                zza = 0.0F;
                return;
            }
            moveX /= length;
            moveZ /= length;
            moveX *= 0.98F;
            moveZ *= 0.98F;
            float scaled = (float) Math.sqrt(moveX * moveX + moveZ * moveZ);
            float unitX = moveX * (1.0F / scaled);
            float unitZ = moveZ * (1.0F / scaled);
            float absX = Math.abs(unitX);
            float absZ = Math.abs(unitZ);
            float ratio = absZ > absX ? absX / absZ : absZ / absX;
            float toSquare = (float) Math.sqrt(1.0F + ratio * ratio);
            float clamped = Math.min(scaled * toSquare, 1.0F);
            xxa = unitX * clamped;
            zza = unitZ * clamped;
        }

        private static float impulse(boolean positive, boolean negative) {
            return positive == negative ? 0.0F : positive ? 1.0F : -1.0F;
        }

        private static float sin(double radians) {
            return SIN[(int) ((long) (radians * 10430.378350470453) & 65535L)];
        }

        private static float cos(double radians) {
            return SIN[(int) ((long) (radians * 10430.378350470453 + 16384.0) & 65535L)];
        }

        private static float wrapDegrees(float degrees) {
            float wrapped = degrees % 360.0F;
            if (wrapped >= 180.0F) wrapped -= 360.0F;
            if (wrapped < -180.0F) wrapped += 360.0F;
            return wrapped;
        }
    }
}
