package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.stage.VelocityTransformer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;

/**
 * A vanilla client sneaking on JavaTickFixture's stone floor toward its edge at x = 9. LocalPlayer#sendPosition sends a
 * position only once the player is more than 2.0E-4 from the last one it sent or on the 20th tick without one; every
 * other tick reaches the engine as its CLIENT_TICK_END alone.
 */
final class SneakEdgeClient {
    static final ServerboundPlayerInput SNEAK =
            new ServerboundPlayerInput(false, false, false, false, false, true, false);
    static final ServerboundPlayerInput SNEAK_TOWARD_EDGE =
            new ServerboundPlayerInput(false, false, true, false, false, true, false);
    static final ServerboundPlayerInput SNEAK_ALONG_EDGE =
            new ServerboundPlayerInput(true, false, true, false, false, true, false);
    static final ServerboundPlayerInput SNEAK_BACK_ALONG_EDGE =
            new ServerboundPlayerInput(false, true, true, false, false, true, false);
    static final ServerboundPlayerInput RELEASE_ALONG_EDGE =
            new ServerboundPlayerInput(true, false, true, false, false, false, false);
    static final double EDGE_X = 9.299;
    private static final double GROUNDED_Y = -0.0784000015258789;
    private static final double GROUND_FRICTION = 0.6F * 0.91F;

    /** One client tick as the engine committed it. */
    record Tick(PredictionResult result, boolean sent, boolean shortened) {}

    private final JavaTickFixture f;
    final List<Tick> ticks = new ArrayList<>();
    private double x;
    private double z;
    private double velocityX;
    private double velocityZ;
    private double sentX;
    private double sentZ;
    private int positionReminder;
    private ServerboundPlayerInput keys;
    private boolean lastShift = true;
    private float itemUseMultiplier = 1.0F;

    SneakEdgeClient(JavaTickFixture f, double x) {
        this.f = f;
        this.x = x;
        this.z = 0.5;
        this.sentX = x;
        this.sentZ = z;
        f.place(new Vec3(x, FLOOR_Y, z), new Vec3(0, GROUNDED_Y, 0));
    }

    JavaTickFixture f() {
        return f;
    }

    /** LocalPlayer#modifyInput's item-use multiplier from this tick on. */
    void itemUse(float multiplier) {
        itemUseMultiplier = multiplier;
    }

    PredictionResult tick(ServerboundPlayerInput keys) {
        return tick(keys, false, 0.0);
    }

    /** Runs one client tick; {@code past} moves the sent position that far beyond vanilla's along +x. */
    PredictionResult tick(ServerboundPlayerInput keys, boolean forceSend, double past) {
        if (keys != this.keys) f.input(keys);
        this.keys = keys;
        if (velocityX * velocityX + velocityZ * velocityZ < 9.0E-6) {
            velocityX = 0.0;
            velocityZ = 0.0;
        }
        Vec3 input = input(keys, lastShift ? 0.3F : 1.0F);
        lastShift = keys.shift();
        double deltaX = velocityX + input.x;
        double deltaZ = velocityZ + input.z;
        double movedX = keys.shift() ? backOff(x, deltaX) : deltaX;
        x += movedX + past;
        z += deltaZ;
        velocityX = deltaX * GROUND_FRICTION;
        velocityZ = deltaZ * GROUND_FRICTION;
        positionReminder++;
        double fromSentX = x - sentX;
        double fromSentZ = z - sentZ;
        boolean sent = forceSend || fromSentX * fromSentX + fromSentZ * fromSentZ > 4.0E-8 || positionReminder >= 20;
        PredictionResult result;
        if (sent) {
            result = f.tick(new Vec3(x, FLOOR_Y, z), true);
            sentX = x;
            sentZ = z;
            positionReminder = 0;
        } else {
            f.tickEnd();
            result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        }
        ticks.add(new Tick(result, sent, movedX != deltaX));
        return result;
    }

    /** KeyboardInput#tick, LocalPlayer#modifyInput and Entity#getInputVector at the walking speed. */
    private Vec3 input(ServerboundPlayerInput keys, float movingSlowlyFactor) {
        float strafe = keys.left() == keys.right() ? 0.0F : keys.left() ? 1.0F : -1.0F;
        float forward = keys.forward() == keys.backward() ? 0.0F : keys.forward() ? 1.0F : -1.0F;
        Vec3 modified = VelocityTransformer.getExactControllerModifiedInputStatic(
                new Vec3(strafe, 0.0D, forward), itemUseMultiplier, movingSlowlyFactor);
        double lengthSqr = modified.lengthSqr();
        if (lengthSqr < 1.0E-7D) return Vec3.ZERO;
        Vec3 movement = (lengthSqr > 1.0D ? modified.normalize() : modified).scale(0.1F);
        float yawRadians = f.player.xRot * ((float) Math.PI / 180F);
        float sin = f.player.trigHandler.sin(yawRadians);
        float cos = f.player.trigHandler.cos(yawRadians);
        return new Vec3(movement.x * cos - movement.z * sin, movement.y, movement.z * cos + movement.x * sin);
    }

    /** Player#maybeBackOffFromEdge along +x for a box at {@code x} over the drop at x = 9. */
    private static double backOff(double x, double deltaX) {
        double movedX = deltaX;
        while (movedX != 0.0 && x - 0.3 + 1.0E-7 + movedX >= 9.0) {
            movedX = Math.abs(movedX) <= 0.05 ? 0.0 : movedX - 0.05;
        }
        return movedX;
    }
}
