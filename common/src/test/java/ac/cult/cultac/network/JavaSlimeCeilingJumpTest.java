package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A 26.1 client holding jump and sprint on a slime floor under a stone ceiling two blocks up. Each landing bounces off
 * the slime and the ceiling, and LivingEntity#aiStep scales the horizontal velocity by SlimeBlock#stepOn at the end
 * of each grounded tick. The jump that follows starts from that scaled velocity, and LivingEntity#jumpFromGround adds
 * the full sprint push on top. The moves are a real client's, translated onto the fixture.
 */
class JavaSlimeCeilingJumpTest {
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, true, false, true);
    private static final ServerboundPlayerInput FORWARD_LEFT =
            new ServerboundPlayerInput(true, false, true, false, true, false, true);

    @Test
    void aSprintJumpFromASlimeFloorAddsTheFullSprintPushAfterStepOn() throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_26_1);
            for (int x = -8; x <= 8; x++) {
                for (int z = -8; z <= 8; z++) {
                    f.player.compensatedWorld.updateBlock(
                            new BlockPos(x, FLOOR_Y - 1, z), BlockIds.SLIME_BLOCK.defaultState());
                    f.player.compensatedWorld.updateBlock(
                            new BlockPos(x, FLOOR_Y + 2, z), BlockIds.STONE.defaultState());
                }
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);

            Vec3 landingFrom = recorded(269.03072763870426, 62.12160004615784, 149.91959936579076);
            Vec3 landingTo = recorded(268.9837428803694, 62.0, 150.169719739147);
            double fallingY = ((0.0 - 0.08) * (double) 0.98F - 0.08) * (double) 0.98F;
            double bouncedY = (-fallingY - 0.08) * (double) 0.98F;
            double stepOn = 0.4 + Math.abs(bouncedY) * 0.2;
            Vec3 landed = landingTo.subtract(landingFrom);
            f.place(landingTo, new Vec3(landed.x * 0.91F * stepOn, bouncedY, landed.z * 0.91F * stepOn));
            f.player.xRot = 10.795F;
            f.player.yRot = 0.0F;
            f.input(FORWARD);
            var sprint = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
            new PacketEntityAction()
                    .onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, sprint), f.player, sprint);

            check(f, "J1", 268.9184883544484, 62.200000047683716, 150.51337702125713, false);
            check(f, "T2", 268.86621117267396, 62.12160004615784, 150.78858871092436, false);
            f.input(FORWARD_LEFT);
            check(f, "T3", 268.8332553283618, 62.0, 151.06053393415743, true);
            check(f, "T4", 268.8516486451636, 62.07372736586121, 151.20852807257927, false);
            check(f, "T5", 268.8796553730934, 62.06758018428555, 151.33777039040203, false);
            check(f, "T6", 268.91975788871764, 62.0, 151.47688348142842, true);
            check(f, "T7", 268.96521830856926, 62.00433575487991, 151.5729874328954, false);
            check(f, "T8", 269.0129298887325, 62.0, 151.66445369228916, true);
            check(f, "T9", 269.0611781373317, 62.0, 151.74319984213093, true);
            f.input(FORWARD);
            check(f, "T10", 269.06567362725383, 62.0, 151.81976112802704, true);
            check(f, "T11", 269.01946572962197, 62.200000047683716, 152.09139187474668, false);
        }
    }

    private static void check(JavaTickFixture f, String name, double x, double y, double z, boolean onGround) {
        PredictionResult result = f.tick(recorded(x, y, z), onGround);
        assertTrue(exact(result), name + ": " + describe(result));
    }

    /** A recorded position moved onto the fixture: the slime top 62.0 becomes FLOOR_Y. */
    private static Vec3 recorded(double x, double y, double z) {
        return new Vec3(x - 269.0, y - 62.0 + FLOOR_Y, z - 151.0);
    }
}
