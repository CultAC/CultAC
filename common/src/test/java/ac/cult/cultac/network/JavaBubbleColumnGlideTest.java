package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static ac.cult.cultac.network.JavaTickFixture.setField;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockProps;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ElytraTransform;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A glide across an upward bubble column with air above it. LivingEntity#aiStep runs
 * Entity#applyEffectsFromBlocks after LivingEntity#travel, so Entity#onAboveBubbleColumn's push is the stored
 * velocity LivingEntity#travelFallFlying reads on the next tick.
 */
class JavaBubbleColumnGlideTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final float YAW = -90.0F;
    private static final float PITCH = 9.15F;
    private static final Vec3 VELOCITY = new Vec3(0.266132, -0.137994, 0);

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_1_21_11", "V_26_3"})
    void theColumnPushFeedsTheNextGlide(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            int column = BlockProps.DRAG.with(BlockIds.BUBBLE_COLUMN.defaultState(), false);
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 0), column);
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            f.player
                    .getInventory()
                    .inventory
                    .getInventoryStorage()
                    .setItem(Inventory.SLOT_CHESTPLATE, OfflineCultTestBootstrap.item("minecraft:elytra"));
            f.player.isGliding = true;
            f.player.refreshPlayerPose();
            f.player.xRot = YAW;
            f.player.yRot = PITCH;
            // The glide enters the top block of the column above its water surface at 8/9.
            Vec3 entering = glide(f, VELOCITY);
            Vec3 over = new Vec3(-0.2 + entering.x, FLOOR_Y + 0.9, 0.5 + entering.z);
            f.place(over.subtract(entering), VELOCITY);
            f.input(NONE);

            PredictionResult first = f.tick(over, false);
            assertTrue(exact(first), describe(first));

            // Entity#onAboveBubbleColumn: min(1.8, y + 0.1).
            Vec3 pushed = cut(entering);
            pushed = new Vec3(pushed.x, Math.min(1.8, pushed.y + 0.1), pushed.z);
            PredictionResult second = f.tick(over.add(glide(f, pushed)), false);
            assertTrue(exact(second), describe(second));
        }
    }

    /** LivingEntity#aiStep zeroes a horizontal speed under 0.003 and each axis under 0.003. */
    private static Vec3 cut(Vec3 v) {
        double x = v.x, z = v.z;
        if (x * x + z * z < 9.0E-6) x = z = 0;
        if (Math.abs(x) < 0.003) x = 0;
        if (Math.abs(z) < 0.003) z = 0;
        return new Vec3(x, Math.abs(v.y) < 0.003 ? 0 : v.y, z);
    }

    private static Vec3 glide(JavaTickFixture f, Vec3 v) {
        return ElytraTransform.getElytraMovement(f.player, v, ReachUtils.getLook(f.player, YAW, PITCH), PITCH, 0.08);
    }
}
