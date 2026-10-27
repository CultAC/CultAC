package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.elytra.ElytraF;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaBlockEntityPass;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementPatch;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPassState;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.ClientPistonDeltas;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.latency.ClientBlockEntityPass;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

class JavaProtocol773PistonTest {
    private static final BlockRegistry BLOCKS = DataTables.defaults().registry();

    /** Three real-client paired trials prove the 0.4F/0.5 second shoves; the midpoint is neither client. */
    @ParameterizedTest
    @CsvSource({"0.800000011920929, true", "1.0, true", "0.9, false"})
    void protocol773AcceptsBothExactBerryShovesAndRejectsTheGap(double scale, boolean valid) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21_9);
            Vec3 start = new Vec3(0.7, FLOOR_Y, 0.5);
            f.place(start, new Vec3(0, -0.0784000015258789, 0));
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            var reported = shoveThroughBerry(f, 0, scale);
            assertEquals(valid, exact(reported), () -> describe(reported));
        }
    }

    @ParameterizedTest
    @CsvSource({"0.800000011920929, false", "1.0, false", "0.800000011920929, true", "1.0, true"})
    void aSecondStrokeCanUseEitherExactPatchBehaviorAfterTeleport(double scale, boolean switchPatch) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21_9);
            Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
            f.place(new Vec3(0.7, FLOOR_Y, 0.5), new Vec3(0, -0.0784000015258789, 0));
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            var first = shoveThroughBerry(f, 0, scale);
            assertTrue(exact(first), () -> describe(first));
            f.playerPosition(new Vec3(0.7, FLOOR_Y, 3.5), Vec3.ZERO, Set.of());
            double nextScale = switchPatch ? (scale == 1.0 ? 0.800000011920929 : 1.0) : scale;
            var second = shoveThroughBerry(f, 3, nextScale);
            assertTrue(exact(second), () -> describe(second));
        }
    }

    /** handleMovePlayer adds a relative coordinate to the physical piston position, before the next travel report. */
    @ParameterizedTest
    @CsvSource({"0.800000011920929, true", "1.0, true", "0.9, false"})
    void aRelativeTeleportCanEchoEitherExactPistonStartButNotTheGap(double scale, boolean valid) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21_9);
            Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
            f.place(new Vec3(0.7, FLOOR_Y, 0.5), new Vec3(0, -0.0784000015258789, 0));
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            Vec3 first = firstBerryShove(f, 0);
            Vec3 actual = new Vec3(first.x + 0.5 * scale + 2, FLOOR_Y, 3.5);
            int transaction = f.player.sendTrailingTransaction();
            f.player
                    .getSetbackTeleportUtil()
                    .addSentTeleport(new Vec3(2, FLOOR_Y, 3.5), Vec3.ZERO, transaction, RelativeFlag.X, true, 42);
            f.pongThrough(transaction);
            var ack = new ServerboundAcceptTeleportation(42, new Vec3d(actual.x, actual.y, actual.z), 0, 0);
            var event = f.event(ServerboundPackets.ACCEPT_TELEPORTATION, ack);
            new PacketServerTeleport().onAcceptTeleportation(event, f.player, ack);
            assertEquals(!valid, event.isCancelled());
            if (valid) {
                assertEquals(actual.x, f.player.x);
                var carry = (JavaPredictionCarry)
                        f.player.checkManager.getSimulationProcessor().getCurrentPredictionCarry();
                assertTrue(carry.states().stream()
                        .allMatch(state -> state.pistonStart().position().equals(actual)));
                assertTrue(exact(f.tick(actual, false)));
            }
        }
    }

    /** A .51 downward piston lands the unscaled client; .51*.75 leaves the scaled client airborne. */
    @ParameterizedTest
    @CsvSource({"true, true", "true, false", "false, true", "false, false"})
    void aGlideCommandKeepsOnlyThePatchWhoseExactPistonStateIsAirborne(boolean someAirborne, boolean modernFirst)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21_9);
            Vec3 position = new Vec3(0.5, FLOOR_Y + (someAirborne ? 0.49 : 0.1), 0.5);
            f.place(position, Vec3.ZERO);
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            var context = f.tick(position, false).getSimulationContext();
            int head = BLOCKS.with(BLOCKS.block("minecraft:piston_head").defaultState(), "facing", "down");
            var pass = new ClientBlockEntityPass(
                    List.of(new ClientBlockEntityPass.Piston(
                            new BlockPos(0, FLOOR_Y + 1, 0), head, Direction.DOWN, true, true, 0)),
                    10);
            var initial = new JavaPassState(
                    position,
                    Vec3.ZERO,
                    false,
                    new MainSupportingBlockData(null, false),
                    new Vec3(0.8F, 0.75, 0.8F),
                    0,
                    ClientPistonDeltas.NONE);
            var patches = modernFirst
                    ? List.of(JavaMovementPatch.V_1_21_10, JavaMovementPatch.V_1_21_9)
                    : JavaMovementPatch.PROTOCOL_773;
            var states = new ArrayList<JavaPredictionCarry.State>();
            for (var patch : patches) {
                var after = JavaBlockEntityPass.run(
                                f.player, context, pass, initial, Pose.STANDING, false, position, patch)
                        .state();
                assertEquals(!someAirborne || patch == JavaMovementPatch.V_1_21_10, after.onGround());
                states.add(new JavaPredictionCarry.State(
                        after.velocity(),
                        after.fallDistance(),
                        after.stuckSpeed(),
                        patch,
                        new JavaPredictionCarry.PistonStart(
                                after.position(), after.onGround(), after.support(), after.pistonDeltas())));
            }
            var sim = f.player.checkManager.getSimulationProcessor();
            JavaTickFixture.setField(
                    SimulationProcessor.class,
                    sim,
                    "profileCarry",
                    new JavaPredictionCarry(f.player.compensatedEntities.getSelf(), 0, states));
            f.player.onGround = states.getFirst().pistonStart().onGround();
            f.player
                    .getInventory()
                    .inventory
                    .getInventoryStorage()
                    .setItem(Inventory.SLOT_CHESTPLATE, OfflineCultTestBootstrap.item("minecraft:elytra"));
            var packet =
                    new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_FLYING_WITH_ELYTRA, 0);
            var event = f.event(ServerboundPackets.PLAYER_COMMAND, packet);
            new PacketEntityAction().onPlayerCommand(event, f.player, packet);
            f.player.checkManager.getCheck(ElytraF.class).onPlayerCommand(event, f.player, packet);
            assertEquals(!someAirborne, event.isCancelled());
            assertEquals(someAirborne, f.player.isGliding);
            assertEquals(someAirborne ? 0 : 1, f.player.checkManager.getCheck(ElytraF.class).violations);
            if (someAirborne) {
                assertEquals(
                        JavaMovementPatch.V_1_21_9,
                        ((JavaPredictionCarry) sim.getCurrentPredictionCarry())
                                .states()
                                .getFirst()
                                .movementPatch());
                assertFalse(f.player.onGround);
                assertEquals(FLOOR_Y + 0.49 - 0.51 * 0.75, f.player.y);
            }
        }
    }

    private static PredictionResult shoveThroughBerry(JavaTickFixture f, int z, double scale) {
        Vec3 first = firstBerryShove(f, z);
        return f.tick(first.add(0.5 * scale, 0, 0), true);
    }

    private static Vec3 firstBerryShove(JavaTickFixture f, int z) {
        BlockPos base = new BlockPos(-1, FLOOR_Y, z);
        int piston = BLOCKS.with(BLOCKS.block("minecraft:piston").defaultState(), "facing", "east");
        f.player.compensatedWorld.updateBlock(base, piston);
        f.player.compensatedWorld.updateBlock(
                new BlockPos(1, FLOOR_Y, z),
                BLOCKS.block("minecraft:sweet_berry_bush").defaultState());
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        Vec3 start = new Vec3(0.7, FLOOR_Y, z + 0.5);
        assertTrue(exact(f.tick(start, true)));
        f.player.compensatedWorld.pistons.handleBlockEvent(base, BLOCKS.blockIndex(piston), 0, 5, 0);
        assertTrue(exact(f.tick(start, true)));
        Vec3 first = new Vec3(0.5 + (double) (0.6F / 2) + 0.01, FLOOR_Y, z + 0.5);
        assertTrue(exact(f.tick(first, true)));
        return first;
    }
}
