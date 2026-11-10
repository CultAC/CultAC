package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaTravelInputs;
import ac.cult.cultac.checks.impl.prediction.profile.MovementProfiles;
import ac.cult.cultac.checks.impl.prediction.stage.VelocityTransformer;
import ac.cult.cultac.cloud.capture.EngineVerdicts;
import ac.cult.cultac.cloud.intake.v3.Verdict;
import ac.cult.cultac.cloud.wire.IntakeWire;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.data.ClientItemUseState;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The capture verdict, the prediction debug vector and the intake wire report the exact LivingEntity#moveRelative input
 * the accepted candidate travelled with. That candidate's Entity#move already consumed the input, so the report's
 * remainder past it is no input.
 */
class JavaTravelInputExportTest {
    private static final float GROUND_SPEED = 0.1F * (0.21600002F / (0.6F * 0.6F * 0.6F));
    private static final double FALL = -0.0784000015258789;
    private static final double JUMP_POWER = 0.42F;
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);

    @ParameterizedTest
    @CsvSource({"w, 98, 0", "a, 0, 98", "d, 0, -98", "wa, 71, 71", "wj, 98, 0"})
    void aWalkExportsItsKeys(String keys, int forward, int strafe) throws Exception {
        try (var f = new JavaTickFixture()) {
            assertExported(f, keys(keys), 1.0F, forward, strafe);
        }
    }

    @Test
    void aPre1215WalkExportsItsKeys() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V1_21_4, ClientVersion.V_1_21_4)) {
            assertExported(f, keys("w"), 1.0F, 98, 0);
        }
    }

    @Test
    void anItemUseSlowedWalkExportsItsSlowedKeys() throws Exception {
        try (var f = new JavaTickFixture()) {
            var food = ItemUtil.modelItems().stack("minecraft:golden_apple", 1);
            f.player.getInventory().inventory.getInventoryStorage().setItem(Inventory.SLOT_OFFHAND, food);
            f.player.clientItemUse.start(Hand.OFF_HAND, food, ClientItemUseState.duration(food), true);
            assertExported(f, keys("w"), 0.2F, 20, 0);
        }
    }

    private static void assertExported(
            JavaTickFixture f, ServerboundPlayerInput keys, float itemUse, int forward, int strafe) throws Exception {
        var verdicts = new EngineVerdicts(f.player);
        f.place(START, new Vec3(0, FALL, 0));
        f.input(keys);
        Vec3 local = localInput(f, keys, itemUse);
        // At yaw 0 Entity#getInputVector leaves the input's axes in place.
        PredictionResult result = f.tick(
                new Vec3(START.x + local.x, keys.jump() ? FLOOR_Y + JUMP_POWER : FLOOR_Y, START.z + local.z),
                !keys.jump());
        assertTrue(JavaTickFixture.exact(result), JavaTickFixture.describe(result));
        assertTrue(JavaTravelInputs.isExact(result.getInitialStartingVel()));

        Vec3 debug = MovementProfiles.forPlayer(f.player).debugPredictionVector(result);
        assertEquals(local.x, debug.x, 1e-9);
        assertEquals(local.z, debug.z, 1e-9);

        IntakeWire.Engine engine = verdicts.read();
        assertEquals(forward, engine.forward());
        assertEquals(strafe, engine.strafe());

        var writer = new IntakeWire.VerdictWriter();
        int size = writer.write(0, 1, false, List.of(), null, List.of(), engine, null, -1, 0, List.of());
        Verdict verdict = Verdict.parseFrom(Arrays.copyOf(writer.bytes(), size));
        assertEquals(forward, verdict.getInput().getForward());
        assertEquals(strafe, verdict.getInput().getStrafe());
    }

    /** LocalPlayer#modifyInput's xxa/zza for the keys at the ground moveRelative speed. */
    private static Vec3 localInput(JavaTickFixture f, ServerboundPlayerInput keys, float itemUse) {
        Vec3 raw = new Vec3(impulse(keys.left(), keys.right()), 0, impulse(keys.forward(), keys.backward()));
        Vec3 modified = f.player.getClientVersion().isOlderThan(ClientVersion.V_1_21_5)
                ? new Vec3((float) raw.x * itemUse * 0.98F, 0, (float) raw.z * itemUse * 0.98F)
                : VelocityTransformer.getExactControllerModifiedInputStatic(raw, itemUse, 1.0F);
        return modified.scale(GROUND_SPEED);
    }

    private static float impulse(boolean positive, boolean negative) {
        return positive == negative ? 0.0F : positive ? 1.0F : -1.0F;
    }

    private static ServerboundPlayerInput keys(String keys) {
        return new ServerboundPlayerInput(
                keys.contains("w"),
                keys.contains("s"),
                keys.contains("a"),
                keys.contains("d"),
                keys.contains("j"),
                false,
                false);
    }
}
