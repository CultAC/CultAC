package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A client sprinting off the top of an oak fence toward the slime block the fence connects to. The fence's collision
 * shape is 1.5 blocks tall, so its top is in the cell above it; Entity#checkSupportingBlock still finds it because
 * BlockCollisions visits the cells one layer outside the search box for blocks with a large collision shape.
 * Entity#getBlockPosBelowThatAffectsMyMovement is then the fence even while the client's center is over the slime, and
 * the ground friction is the fence's 0.6F. The moves are a real client's, translated onto the fixture: the slime is at
 * z = 1, the fence at z = 2.
 */
class JavaFenceSupportFrictionTest {
    private static final ServerboundPlayerInput SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final int OAK_FENCE = DataTables.defaults()
            .registry()
            .with(DataTables.defaults().registry().block("minecraft:oak_fence").defaultState(), "north", "true");

    @Test
    void sprintingOverTheSlimeFromAFenceTopKeepsTheFenceFriction() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y - 1, 1), BlockIds.SLIME_BLOCK.defaultState());
            f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y - 1, 2), OAK_FENCE);
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);

            // A client standing on the fence: LivingEntity#travelInAir's gravity, (0 - 0.08) * 0.98F.
            f.place(
                    recorded(165.61683180380206, 63.5, 175.06269095950154),
                    new Vec3(0.0, (0.0 - 0.08) * (double) 0.98F, 0.0));
            f.player.onGround = true;
            f.player.lastOnGround = true;
            f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
            f.player.xRot = 178.5625F;
            f.player.yRot = 0.0F;
            f.input(SPRINT);
            var sprint = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
            new PacketEntityAction()
                    .onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, sprint), f.player, sprint);

            // Each move after the first is the previous one times the fence's 0.6F * 0.91F plus the sprint input.
            Vec3[] moves = {
                recorded(165.61363198755546, 63.5, 174.9353311401886),
                recorded(165.60868507143533, 63.5, 174.7384328514537),
                recorded(165.60278423867342, 63.5, 174.50356655400432)
            };
            for (Vec3 move : moves) {
                PredictionResult result = f.tick(move, true);
                assertTrue(exact(result), move + ": " + describe(result));
            }
        }
    }

    /** A recorded position moved onto the fixture: the fence top 63.5 becomes FLOOR_Y + 0.5. */
    private static Vec3 recorded(double x, double y, double z) {
        return new Vec3(x - 164.0, y - 63.0 + FLOOR_Y, z - 173.0);
    }
}
