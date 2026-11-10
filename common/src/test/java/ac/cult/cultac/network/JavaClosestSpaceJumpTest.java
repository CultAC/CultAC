package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A sprinting 26.3 client standing with its feet in a 3x3 of full blocks, open to the east and south, that jumps.
 * LocalPlayer#aiStep's moveTowardsClosestSpace sets X and Z of the zero velocity to 0.1 before
 * LivingEntity#jumpFromGround adds the sprint jump's boost and travel adds the input.
 */
class JavaClosestSpaceJumpTest {
    private static final ServerboundPlayerInput SPRINT_JUMP =
            new ServerboundPlayerInput(true, false, false, false, true, false, true);
    private static final float YAW = 304.1789F;
    private static final float CANONICAL = (float) ((double) 0.1F * (1.0D + (double) 0.3F));
    private static final Vec3 START = new Vec3(0.30356461252, FLOOR_Y, 0.04344841327);

    @Test
    void aSprintJumpOutOfABlockKeepsThePushUnderTheBoost() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.xRot = YAW;
            f.player.yRot = 0.0F;
            f.place(START, Vec3.ZERO);
            assertTrue(exact(f.tick(START, true)));
            for (int x = -2; x <= 0; x++) {
                for (int z = -2; z <= 0; z++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y, z), BlockIds.STONE.defaultState());
                }
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            var sprint = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
            new PacketEntityAction().onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, sprint), f.player, sprint);
            f.input(SPRINT_JUMP);

            float yawRadians = YAW * ((float) Math.PI / 180F);
            Vec3 boost = new Vec3(
                    -f.player.trigHandler.sin(yawRadians) * 0.2F, 0.0, f.player.trigHandler.cos(yawRadians) * 0.2F);
            Vec3 input = ClientMoveInput.of(f, SPRINT_JUMP, 1.0F, CANONICAL, YAW);
            Vec3 delta = new Vec3(0.1, 0.0, 0.1).add(boost).add(input);
            PredictionResult result = f.tick(START.add(delta.x, 0.41999998688697815, delta.z), false);
            assertTrue(exact(result), describe(result));
        }
    }
}
