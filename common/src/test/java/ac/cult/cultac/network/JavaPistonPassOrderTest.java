package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketBlockAction;
import ac.cult.cultac.events.packets.worldreader.PacketWorldReaderTwentySix;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockEvent;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Two pistons extend in one server tick: the first pushes a stone east into the player, the second a stone north
 * beside the player's east side. Before 26.3 Level#tickBlockEntities runs after LocalPlayer#sendPosition and ticks the
 * PistonMovingBlockEntity of each piston in the order the client created them; each PistonMovingBlockEntity#tick
 * tests the player's box where the moves before it in the pass left it, so the first piston's shove carries the player
 * into the second piston's movement area and both move the player before its next travel.
 */
class JavaPistonPassOrderTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final BlockPos EAST_PISTON = new BlockPos(-2, FLOOR_Y, 0);
    private static final BlockPos NORTH_PISTON = new BlockPos(1, FLOOR_Y, 2);
    private static final double FALL = 0.0784000015258789;

    @ParameterizedTest
    @CsvSource({"V_1_21_11", "V_1_21_4"})
    void aShoveIntoASecondPistonsAreaMovesThePlayerTwiceInOnePass(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            int east = REGISTRY.with(REGISTRY.block("minecraft:piston").defaultState(), "facing", "east");
            int north = REGISTRY.with(REGISTRY.block("minecraft:piston").defaultState(), "facing", "north");
            int stone = REGISTRY.block("minecraft:stone").defaultState();
            f.player.compensatedWorld.updateBlock(EAST_PISTON, east);
            f.player.compensatedWorld.updateBlock(EAST_PISTON.relative(Direction.EAST), stone);
            f.player.compensatedWorld.updateBlock(NORTH_PISTON, north);
            f.player.compensatedWorld.updateBlock(NORTH_PISTON.relative(Direction.NORTH), stone);
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);

            // The box spans x 0.4..1.0 and z 0.0..0.6: inside the east stone's first sweep, outside the north one's.
            double x = 0.7, z = 0.3;
            f.place(new Vec3(x, FLOOR_Y, z), new Vec3(0, -FALL, 0));
            f.input(NONE);
            StringBuilder log = new StringBuilder();
            boolean allExact = true;
            PredictionResult result = f.tick(new Vec3(x, FLOOR_Y, z), true);
            allExact &= exact(result);
            log.append("start: ").append(describe(result)).append('\n');

            blockEvent(f, EAST_PISTON, east, Direction.EAST);
            blockEvent(f, NORTH_PISTON, north, Direction.NORTH);
            unmoved(f);

            // First pass: east by 0.1 + 0.01, which enters the north sweep (z 0.5..1.0) 0.1 deep.
            x += 0.11;
            z -= 0.11;
            result = f.tick(new Vec3(x, FLOOR_Y, z), true);
            allExact &= exact(result);
            log.append("first pass: ").append(describe(result)).append('\n');

            // Second pass: each sweep holds the box 0.49 deep, so each moves it the step of 0.5.
            x += 0.5;
            z -= 0.5;
            result = f.tick(new Vec3(x, FLOOR_Y, z), true);
            allExact &= exact(result);
            log.append("second pass: ").append(describe(result)).append('\n');

            for (int tick = 0; tick < 3; tick++) {
                result = f.tick(new Vec3(x, FLOOR_Y, z), true);
                allExact &= exact(result);
                log.append("rest ").append(tick).append(": ").append(describe(result)).append('\n');
            }
            assertTrue(allExact, log.toString());
        }
    }

    /** A client tick that sends only its tick end; any prediction it commits is exact. */
    private static void unmoved(JavaTickFixture f) {
        PredictionResult before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        f.tickEnd();
        PredictionResult result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        if (result != before) assertTrue(exact(result), "handling tick: " + describe(result));
    }

    private static void blockEvent(JavaTickFixture f, BlockPos pos, int state, Direction facing) {
        var packet = new ClientboundBlockEvent(pos, 0, facing.get3DDataValue(), REGISTRY.blockIndex(state));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.BLOCK_EVENT, packet, false);
        new PacketBlockAction().onBlockEvent(event, f.player, packet);
        new PacketWorldReaderTwentySix().handleBlockEvent(f.player, event, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }
}
