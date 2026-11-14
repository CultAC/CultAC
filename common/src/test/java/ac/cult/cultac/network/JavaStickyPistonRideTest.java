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
 * A 1.21.11 client standing on stone above an up-facing sticky piston that extends and later retracts. Before 26.3
 * Level#tickBlockEntities runs after LocalPlayer#sendPosition: each PistonMovingBlockEntity#tick of the extension
 * shoves the player up by the step plus 0.01 and the next travel lands on the moving block; the retraction moves the
 * block away below the player, who falls after it.
 */
class JavaStickyPistonRideTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final BlockPos BASE = new BlockPos(0, FLOOR_Y, 0);
    private static final double FALL = 0.0784000015258789;

    @ParameterizedTest
    @CsvSource({"V_1_21_11, 0", "V_1_21_11, 3", "V_1_21_11, 16", "V_1_21_4, 0", "V_1_21_4, 16"})
    void aPlayerOnAStickyPistonsBlockRidesItUpAndDown(String version, int pendingTicks) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(
                    CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.valueOf(version));
            int piston = REGISTRY.with(REGISTRY.block("minecraft:sticky_piston").defaultState(), "facing", "up");
            int extended = REGISTRY.with(piston, "extended", "true");
            f.player.compensatedWorld.updateBlock(BASE, piston);
            f.player.compensatedWorld.updateBlock(BASE.above(), REGISTRY.block("minecraft:stone").defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);

            double y = FLOOR_Y + 2;
            f.place(new Vec3(0.5, y, 0.5), new Vec3(0, -FALL, 0));
            f.player.onGround = f.player.lastOnGround = true;
            f.input(NONE);
            StringBuilder log = new StringBuilder();
            boolean allExact = true;
            PredictionResult result = f.tick(new Vec3(0.5, y, 0.5), true);
            allExact &= exact(result);

            // Extension: the client handles the event at the start of the tick after it ticks pendingTicks unproven.
            blockEvent(f, 0, piston, pendingTicks == 0);
            for (int tick = 0; tick < pendingTicks; tick++) {
                result = f.tick(new Vec3(0.5, y, 0.5), true);
                log.append("ext pending ").append(tick).append(": ").append(describe(result)).append('\n');
                allExact &= exact(result);
            }
            if (pendingTicks > 0) f.pongThrough(f.player.lastTransactionSent.get());
            double top = FLOOR_Y + 2;
            for (int tick = 0; tick < 8; tick++) {
                // travel lands on the moving block's top
                y = top;
                result = f.tick(new Vec3(0.5, y, 0.5), true);
                log.append("ext ").append(tick).append(": ").append(describe(result)).append('\n');
                allExact &= exact(result);
                if (top < FLOOR_Y + 3) {
                    top += 0.5;
                    y += 0.51;
                }
            }

            blockEvent(f, 1, extended, pendingTicks == 0);
            for (int tick = 0; tick < pendingTicks; tick++) {
                result = f.tick(new Vec3(0.5, y, 0.5), true);
                log.append("ret pending ").append(tick).append(": ").append(describe(result)).append('\n');
                allExact &= exact(result);
            }
            if (pendingTicks > 0) f.pongThrough(f.player.lastTransactionSent.get());
            // The handling tick's travel still stands on the block, which then retracts 0.5 per tick.
            result = f.tick(new Vec3(0.5, y, 0.5), true);
            log.append("ret 0: ").append(describe(result)).append('\n');
            allExact &= exact(result);
            double floor = FLOOR_Y + 2;
            double velocity = -FALL;
            for (int tick = 1; tick < 10; tick++) {
                boolean onGround = y + velocity <= floor;
                if (onGround) {
                    y = floor;
                    velocity = -FALL;
                } else {
                    y += velocity;
                    velocity = (velocity - 0.08) * (double) 0.98F;
                }
                result = f.tick(new Vec3(0.5, y, 0.5), onGround);
                log.append("ret ").append(tick).append(": ").append(describe(result)).append('\n');
                allExact &= exact(result);
            }
            assertTrue(allExact, log.toString());
        }
    }

    /**
     * The client stands still on the stone, so the tick that handles the extension sends only its tick end. The
     * server's update placing the moved stone and the head reaches the client after the second shove, before the
     * travel that lands on the stone's top; LocalPlayer#aiStep then tests the shoved box, which the stone does not hold.
     */
    @ParameterizedTest
    @CsvSource({"V_1_21_11", "V_1_21_4"})
    void theServerPlacingTheMovedBlockUnderTheShovedPlayerKeepsThemOnIt(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            int piston = REGISTRY.with(REGISTRY.block("minecraft:sticky_piston").defaultState(), "facing", "up");
            int stone = REGISTRY.block("minecraft:stone").defaultState();
            f.player.compensatedWorld.updateBlock(BASE, piston);
            f.player.compensatedWorld.updateBlock(BASE.above(), stone);
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);

            double y = FLOOR_Y + 2;
            f.place(new Vec3(0.5, y, 0.5), new Vec3(0, -FALL, 0));
            f.player.onGround = f.player.lastOnGround = true;
            f.input(NONE);
            PredictionResult result = f.tick(new Vec3(0.5, y, 0.5), true);
            assertTrue(exact(result), describe(result));
            for (int tick = 0; tick < 3; tick++) unmoved(f, "idle " + tick);

            blockEvent(f, 0, piston, true);
            unmoved(f, "handling tick");
            result = f.tick(new Vec3(0.5, FLOOR_Y + 2.5, 0.5), true);
            assertTrue(exact(result), "first shove: " + describe(result));

            int head = REGISTRY.with(
                    REGISTRY.with(REGISTRY.block("minecraft:piston_head").defaultState(), "facing", "up"),
                    "type",
                    "sticky");
            int transaction = f.player.sendTrailingTransaction();
            f.player.compensatedWorld.queueServerBlockUpdate(
                    BASE.getX(), BASE.getY(), BASE.getZ(), REGISTRY.with(piston, "extended", "true"), transaction);
            f.player.compensatedWorld.queueServerBlockUpdate(
                    BASE.getX(), BASE.getY() + 1, BASE.getZ(), head, transaction);
            f.player.compensatedWorld.queueServerBlockUpdate(
                    BASE.getX(), BASE.getY() + 2, BASE.getZ(), stone, transaction);
            f.pongThrough(transaction);
            result = f.tick(new Vec3(0.5, FLOOR_Y + 3, 0.5), true);
            assertTrue(exact(result), "second shove: " + describe(result));
        }
    }

    /** A client tick that sends only its tick end; any prediction it commits is exact. */
    private static void unmoved(JavaTickFixture f, String label) {
        PredictionResult before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        f.tickEnd();
        PredictionResult result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        if (result != before) assertTrue(exact(result), label + ": " + describe(result));
    }

    private static void blockEvent(JavaTickFixture f, int action, int state, boolean answered) {
        var packet = new ClientboundBlockEvent(BASE, action, Direction.UP.get3DDataValue(), REGISTRY.blockIndex(state));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.BLOCK_EVENT, packet, false);
        new PacketBlockAction().onBlockEvent(event, f.player, packet);
        new PacketWorldReaderTwentySix().handleBlockEvent(f.player, event, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        if (answered) f.pongThrough(f.player.lastTransactionSent.get());
    }
}
