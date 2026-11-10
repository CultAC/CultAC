package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaBlockEntityPass;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPassState;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
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
import ac.cult.cultac.utils.data.ClientPistonDeltas;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.latency.ClientBlockEntityPass;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JavaPistonHoneyCarryTest {
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final double FALL = 0.0784000015258789;
    private static final BlockPos PISTON = new BlockPos(0, FLOOR_Y, 2);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    /**
     * PistonMovingBlockEntity#moveStuckEntities carries grounded players by deltaProgress, without its collision
     * shove's extra 0.01 or any horizontal deltaMovement. matchesStickyCritera also accepts a supporting block
     * match: x=1.1 is outside the honey's sticky query's center bounds, but the player's box still rests on it.
     */
    @ParameterizedTest
    @CsvSource({"V_26_3, 0.5", "V_26_3, 1.1", "V_1_21_5, 0.5", "V_1_21_5, 1.1"})
    void honeyCarriesBothItsCenterAndItsSupportedEdge(ClientVersion version, double x) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            int piston = honeyPiston(f);
            Vec3 position = new Vec3(x, FLOOR_Y + 0.9375, 1.5);
            f.place(position, new Vec3(0, -FALL, 0));
            f.input(NONE);
            blockEvent(f, piston);
            if (version.isOlderThan(ClientVersion.V_26_3)) assertExact(f.tick(position, true));
            assertExact(f.tick(position.add(0, 0, -0.5), version.isOlderThan(ClientVersion.V_26_3)));
            assertExact(f.tick(position.add(0, 0, -1.0), version.isOlderThan(ClientVersion.V_26_3)));
            assertTrue(
                    f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels().stream()
                            .anyMatch(v -> v.x == 0 && v.z == 0 && v.y == -FALL),
                    "honey carries position without launching or adding horizontal velocity");
            assertExact(f.tick(position.add(0, 0, -1.0), true));
        }
    }

    /** The honey's tall query intersects an airborne player, but matchesStickyCritera requires onGround. */
    @ParameterizedTest
    @CsvSource({"V_26_3, false", "V_26_3, true", "V_1_21_5, false", "V_1_21_5, true"})
    void airbornePlayersAboveTheHoneyAreNotCarried(ClientVersion version, boolean claimsCarry) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            int piston = honeyPiston(f);
            Vec3 position = new Vec3(0.5, FLOOR_Y + 1.1, 1.5);
            f.place(position, Vec3.ZERO);
            f.input(NONE);
            blockEvent(f, piston);
            PredictionResult result = f.tick(claimsCarry ? position.add(0, 0, -0.5) : position, false);
            if (claimsCarry) {
                assertTrue(
                        !result.isExempt() && result.getOffset() > 0.1, "airborne honey cannot admit a made-up carry");
            } else {
                assertExact(result);
            }
        }
    }

    /**
     * Entity#move ends with getBlockSpeedFactor, and a MOVING_PISTON's is 1. A PistonMovingBlockEntity past its fifth
     * client death tick replaces it with the moved honey in its own Level#blockEntityTickers slot, so a shove by a
     * ticker before that slot keeps the perpendicular velocity and one after it takes honey's 0.4, whether the replay
     * reads the block before the tick end settled it (26.3) or after (older clients' deferred pass).
     */
    @ParameterizedTest(name = "settled at tick end {0}, settling before the shove {1}")
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void aSettlingHoneyTickerDampsOnlyTheShovesAfterIt(boolean settledAtTickEnd, boolean settlesFirst)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            int honey = REGISTRY.block("minecraft:honey_block").defaultState();
            int moving = REGISTRY.with(REGISTRY.block("minecraft:moving_piston").defaultState(), "facing", "east");
            BlockPos below = new BlockPos(0, FLOOR_Y - 1, 0);
            f.player.compensatedWorld.updateBlock(below, settledAtTickEnd ? honey : moving);
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
            Vec3 position = new Vec3(0.5, FLOOR_Y - 0.0625, 0.5);
            f.place(position, Vec3.ZERO);
            f.input(NONE);
            var context = f.tick(position, true).getSimulationContext();
            var shove = new ClientBlockEntityPass.Piston(
                    new BlockPos(0, FLOOR_Y, 0), REGISTRY.block("minecraft:stone").defaultState(),
                    Direction.EAST, true, false, 0);
            var settles = new ClientBlockEntityPass.Final(
                    below, moving, honey, Direction.EAST, true, false, honey, settlesFirst ? 0 : 1);
            var pass = new ClientBlockEntityPass(List.of(shove), List.of(), List.of(settles), 10);
            var state = new JavaPassState(
                    position,
                    new Vec3(0, 0, 0.1),
                    true,
                    new MainSupportingBlockData(below, true),
                    null,
                    0,
                    ClientPistonDeltas.NONE);
            var pushed = JavaBlockEntityPass.run(f.player, context, pass, state, Pose.STANDING, false, position)
                    .state();
            assertEquals(0.81 + 0.0000000119209, pushed.position().x, 1e-6);
            assertEquals(0.1 * (settlesFirst ? (double) 0.4F : 1.0), pushed.velocity().z, 1e-12);
        }
    }

    private static int honeyPiston(JavaTickFixture f) {
        int piston = REGISTRY.with(REGISTRY.block("minecraft:sticky_piston").defaultState(), "facing", "north");
        f.player.compensatedWorld.updateBlock(PISTON, piston);
        f.player.compensatedWorld.updateBlock(
                new BlockPos(0, FLOOR_Y, 1),
                REGISTRY.block("minecraft:honey_block").defaultState());
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
        return piston;
    }

    private static void blockEvent(JavaTickFixture f, int state) {
        var packet = new ClientboundBlockEvent(PISTON, 0, 2, REGISTRY.blockIndex(state));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.BLOCK_EVENT, packet, false);
        new PacketWorldReaderTwentySix().handleBlockEvent(f.player, event, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }
}
