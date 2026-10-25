package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
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
 * A 26.1 client sprint-jumping on a slime floor under top-half trapdoors with 0.0125 of headroom. The box stays
 * at Entity#getOnPosLegacy's slime, so SlimeBlock#updateEntityMovementAfterFallOn keeps the rising velocity through
 * every head hit until gravity turns it; the landing then bounces, and the grounded tick after it scales the jump's
 * velocity by SlimeBlock#stepOn. The moves are a real client's, translated onto the fixture.
 */
class JavaSlimeTrapdoorHeadHitTest {
    private static final ServerboundPlayerInput FORWARD_JUMP =
            new ServerboundPlayerInput(true, false, false, false, true, false, true);
    private static final ServerboundPlayerInput FORWARD_LEFT_JUMP =
            new ServerboundPlayerInput(true, false, true, false, true, false, true);
    private static final ServerboundPlayerInput FORWARD_LEFT =
            new ServerboundPlayerInput(true, false, true, false, false, false, true);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);

    @Test
    void theGroundTickAfterAHeadHitLandingCarriesStepOnIntoTheNextJump() throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_26_1);
            var registry = DataTables.defaults().registry();
            int trapdoor =
                    registry.with(registry.block("minecraft:oak_trapdoor").defaultState(), "half", "top");
            for (int x = -8; x <= 8; x++) {
                for (int z = -8; z <= 8; z++) {
                    f.player.compensatedWorld.updateBlock(
                            new BlockPos(x, FLOOR_Y - 1, z), BlockIds.SLIME_BLOCK.defaultState());
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y + 1, z), trapdoor);
                }
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);

            // The sprint jump off the slime, cut to 0.0125 by the trapdoors.
            Vec3 jumpFrom = recorded(269.3992434403807, 62.0, 148.8933003526408);
            Vec3 jumpTo = recorded(269.4309727524378, 62.012500047683716, 149.15548685817885);
            Vec3 jumped = jumpTo.subtract(jumpFrom);
            double friction = 0.8F * 0.91F;
            double jumpY = (0.42F - 0.08) * (double) 0.98F;
            f.place(jumpTo, new Vec3(jumped.x * friction, jumpY, jumped.z * friction));
            f.player.xRot = 1.5474854F;
            f.player.yRot = 9.881536F;
            f.input(FORWARD_LEFT_JUMP);
            var sprint = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
            new PacketEntityAction()
                    .onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, sprint), f.player, sprint);

            check(f, "T1", 269.47195455945223, 62.012500047683716, 149.36523198388656, false);
            f.input(FORWARD_LEFT);
            check(f, "T2", 269.5271308713248, 62.012500047683716, 149.5749733917055, false);
            f.input(FORWARD_JUMP);
            check(f, "T3", 269.5766549547544, 62.012500047683716, 149.7913088315119, false);
            check(f, "T4", 269.6210355101527, 62.012500047683716, 150.01364484059988, false);
            check(f, "T5", 269.6607354549076, 62.012500047683716, 150.24144136789144, false);
            check(f, "T6", 269.6961760438542, 62.0, 150.47420696689147, true);
            f.input(FORWARD);
            check(f, "T7", 269.7076574227699, 62.0, 150.61285017180182, true);
            f.input(FORWARD_JUMP);
            check(f, "J8", 269.70428929608687, 62.012500047683716, 150.90837217248153, false);
            check(f, "T9", 269.70115093788917, 62.012500047683716, 151.14898295541298, false);
            check(f, "T10", 269.6976086700255, 62.012500047683716, 151.39340952738135, false);
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
