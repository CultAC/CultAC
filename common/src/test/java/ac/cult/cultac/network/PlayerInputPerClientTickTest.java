package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.movement.timer.AbstractTimerCheck;
import ac.cult.cultac.checks.impl.movement.timer.TimerCheck;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.manager.init.start.PacketManager;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * LocalPlayer sends at most one player_input per client tick, right before the tick's movement or else the tick end.
 * The server receives the tick's last input with that packet, the input the proxy judges the tick by.
 */
class PlayerInputPerClientTickTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput SNEAK =
            new ServerboundPlayerInput(false, false, false, false, false, true, false);
    private static final ServerboundPlayerInput WALK =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput SNEAK_WALK =
            new ServerboundPlayerInput(true, false, false, false, false, true, false);
    private static final double FRICTION = 0.6F * 0.91F;
    private static final float WALK_SPEED = 0.1F;
    private static final float YAW = 30.0F;
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);

    @Test
    void aLaterInputOfTheTickReplacesTheHeldOneAndBothSidesJudgeTheMoveByIt() throws Exception {
        try (var f = connect(new JavaTickFixture())) {
            Vec3[] state = {START, Vec3.ZERO};
            f.place(state[0], Vec3.ZERO);
            f.player.xRot = YAW;
            read(f, inputFrame(f, WALK));
            for (int tick = 0; tick < 3; tick++) assertTrue(exact(move(f, state)), "warm-up " + tick);
            forwarded(f);
            for (int tick = 0; tick < 3; tick++) {
                read(f, inputFrame(f, SNEAK_WALK));
                assertEquals(List.of(), forwarded(f), "tick " + tick + ": the input went ahead of its movement");
                read(f, inputFrame(f, WALK));
                assertFalse(f.player.isSneaking, "tick " + tick + ": the last input holds the keys");
                assertTrue(exact(move(f, state)), "tick " + tick);
                assertEquals(
                        List.of("player_input", "move_player", "client_tick_end"),
                        forwarded(f),
                        "tick " + tick + ": the server receives the input the move is judged by");
            }
        }
    }

    @Test
    void otherPacketsOfTheTickPassTheHeldInput() throws Exception {
        try (var f = connect(new JavaTickFixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11))) {
            f.place(START, Vec3.ZERO);
            ByteBuf use = useOnTopFrame(f, new BlockPos(0, FLOOR_Y - 1, 0), 1.0F);
            ByteBuf swing = f.fixture.id(ServerboundPackets.SWING, "swing");
            Wire.writeVarInt(swing, 0);
            read(f, inputFrame(f, NONE), use, inputFrame(f, SNEAK), swing, standingFrame(f), tickEndFrame(f));
            assertEquals(
                    List.of("use_item_on", "swing", "player_input shift", "move_player", "client_tick_end"),
                    forwarded(f));
        }
    }

    /**
     * ServerPlayerGameMode#useItemOn skips the block's use while the server player's shift key is down and a hand holds
     * an item: the key of the last input forwarded to it, not of an input still kept for the tick's movement.
     */
    @Test
    void aUseOnABlockReadsTheShiftKeyOfTheLastInputTheServerReceived() throws Exception {
        try (var f = connect(new JavaTickFixture())) {
            f.place(START, Vec3.ZERO);
            BlockPos sneakingUse = new BlockPos(0, FLOOR_Y, 2);
            BlockPos standingUse = new BlockPos(-2, FLOOR_Y, 2);
            f.player.compensatedWorld.updateBlock(sneakingUse, BlockIds.CHEST.defaultState());
            f.player.compensatedWorld.updateBlock(standingUse, BlockIds.CHEST.defaultState());
            f.player.getInventory().inventory.setHeldItem(ItemUtil.modelItems().stack("minecraft:stone", 64));
            read(f, inputFrame(f, SNEAK), standingFrame(f), tickEndFrame(f));

            read(f, inputFrame(f, NONE), useOnTopFrame(f, sneakingUse, 0.875F), standingFrame(f), tickEndFrame(f));
            assertEquals(
                    BlockIds.STONE.defaultState(),
                    f.player.compensatedWorld.getBlockStateIdAt(sneakingUse.above()),
                    "the server still has the shift key down and places the stone on the chest");

            read(f, inputFrame(f, SNEAK), useOnTopFrame(f, standingUse, 0.875F), standingFrame(f), tickEndFrame(f));
            assertEquals(
                    BlockIds.AIR.defaultState(),
                    f.player.compensatedWorld.getBlockStateIdAt(standingUse.above()),
                    "the server has the shift key up and opens the chest");
        }
    }

    @Test
    void theTicksInputReachesTheServerRightBeforeTheMovementOrTickEndAfterIt() throws Exception {
        try (var f = connect(new JavaTickFixture())) {
            f.place(START, Vec3.ZERO);
            read(f, inputFrame(f, SNEAK), standingFrame(f), tickEndFrame(f), inputFrame(f, NONE));
            assertEquals(List.of("player_input shift", "move_player", "client_tick_end"), forwarded(f));
            read(f, standingFrame(f), tickEndFrame(f));
            assertEquals(List.of("player_input", "move_player", "client_tick_end"), forwarded(f));

            read(f, tickEndFrame(f), inputFrame(f, SNEAK));
            assertEquals(List.of("client_tick_end"), forwarded(f));
            read(f, tickEndFrame(f));
            assertEquals(List.of("player_input shift", "client_tick_end"), forwarded(f));
        }
    }

    @Test
    void oneInputPerTickWithAKeyChangeEveryTickIsForwarded() throws Exception {
        try (var f = connect(new JavaTickFixture())) {
            for (int tick = 0; tick < 10; tick++) {
                ServerboundPlayerInput keys = tick % 2 == 0 ? SNEAK : NONE;
                read(f, inputFrame(f, keys));
                assertEquals(keys.shift(), f.player.isSneaking, "tick " + tick);
                read(f, tickEndFrame(f));
                assertEquals(
                        List.of(keys.shift() ? "player_input shift" : "player_input", "client_tick_end"),
                        forwarded(f),
                        "tick " + tick);
            }
        }
    }

    @Test
    void ticksArrivingInOneReadEachForwardTheirInput() throws Exception {
        try (var f = connect(new JavaTickFixture())) {
            int ticks = 5;
            ByteBuf[] frames = new ByteBuf[ticks * 2];
            for (int tick = 0; tick < ticks; tick++) {
                frames[tick * 2] = inputFrame(f, tick % 2 == 0 ? SNEAK : NONE);
                frames[tick * 2 + 1] = tickEndFrame(f);
            }
            read(f, frames);
            assertEquals(ticks * 2, forwarded(f).size());
            assertTrue(f.player.isSneaking, "the last tick's input holds the keys");
        }
    }

    @Test
    void aTickEndTheTimerRejectsStillReleasesTheTicksInput() throws Exception {
        try (var f = connect(new JavaTickFixture())) {
            TimerCheck timer = f.player.checkManager.getCheck(TimerCheck.class);
            JavaTickFixture.setField(Check.class, timer, "isEnabled", true);
            JavaTickFixture.setField(
                    AbstractTimerCheck.class, timer, "timerBalanceRealTime", System.nanoTime() + 1_000_000_000L);
            read(f, inputFrame(f, SNEAK), tickEndFrame(f));
            assertEquals(List.of("player_input shift"), forwarded(f), "the timer accepted the tick end");

            f.resetTimerBalances();
            read(f, inputFrame(f, NONE), tickEndFrame(f));
            assertEquals(List.of("player_input", "client_tick_end"), forwarded(f));
            assertFalse(f.player.isSneaking);
        }
    }

    private static JavaTickFixture connect(JavaTickFixture f) {
        f.fixture.registrar.registerOwn(PacketManager::registerListeners);
        // The fixture's ticks follow its packets, not the wall clock: a slow first prediction is no frozen client.
        f.player.getSetbackTeleportUtil().policy().setFreeze(Integer.MAX_VALUE);
        return f;
    }

    /** One unslowed walking move and its tick end; the prediction the move committed. */
    private static PredictionResult move(JavaTickFixture f, Vec3[] state) {
        Vec3 delta = state[1].add(ClientMoveInput.of(f, WALK, 1.0F, WALK_SPEED, YAW));
        state[0] = state[0].add(delta.x, 0.0, delta.z);
        state[1] = new Vec3(delta.x * FRICTION, 0.0, delta.z * FRICTION);
        var packet = new ServerboundMovePlayer(
                state[0].x, state[0].y, state[0].z, f.player.xRot, f.player.yRot, true, false, true, true);
        read(f, f.fixture.encode(ServerboundPackets.MOVE_PLAYER, packet), tickEndFrame(f));
        PredictionResult result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        assertFalse(result.isExempt(), describe(result));
        return result;
    }

    private static ByteBuf standingFrame(JavaTickFixture f) {
        var packet = new ServerboundMovePlayer(START.x, START.y, START.z, 0.0F, 0.0F, true, false, true, true);
        return f.fixture.encode(ServerboundPackets.MOVE_PLAYER, packet);
    }

    /** A main-hand use_item_on on the block's top face at its centre, {@code height} above the block's base. */
    private static ByteBuf useOnTopFrame(JavaTickFixture f, BlockPos pos, float height) {
        ByteBuf use = f.fixture.id(ServerboundPackets.USE_ITEM_ON, "use_item_on");
        Wire.writeVarInt(use, 0);
        Wire.writeBlockPos(use, pos);
        Wire.writeVarInt(use, 1);
        use.writeFloat(0.5F).writeFloat(height).writeFloat(0.5F).writeBoolean(false).writeBoolean(false);
        Wire.writeVarInt(use, f.player.compensatedWorld.clientPredictionSequence() + 1);
        return use;
    }

    /** ServerboundPlayerInputPacket's Input flags byte. */
    private static ByteBuf inputFrame(JavaTickFixture f, ServerboundPlayerInput keys) {
        int flags = (keys.forward() ? 1 : 0)
                | (keys.backward() ? 2 : 0)
                | (keys.left() ? 4 : 0)
                | (keys.right() ? 8 : 0)
                | (keys.jump() ? 16 : 0)
                | (keys.shift() ? 32 : 0)
                | (keys.sprint() ? 64 : 0);
        return f.fixture.id(ServerboundPackets.PLAYER_INPUT, "player_input").writeByte(flags);
    }

    private static ByteBuf tickEndFrame(JavaTickFixture f) {
        return f.fixture.id(ServerboundPackets.CLIENT_TICK_END, "client_tick_end");
    }

    /** The frames arrive in one channel read. */
    private static void read(JavaTickFixture f, ByteBuf... frames) {
        f.fixture.channel.writeInbound((Object[]) frames);
    }

    /** The packets that reached the server since the last call, in order; an input names its shift key. */
    private static List<String> forwarded(JavaTickFixture f) {
        List<String> packets = new ArrayList<>();
        ByteBuf frame;
        while ((frame = f.fixture.channel.readInbound()) != null) {
            try {
                int id = Wire.readVarInt(frame);
                var type = f.fixture.codec.typeOf(PacketDirection.SERVERBOUND, id);
                String name = type.key().substring(type.key().indexOf('.') + 1);
                if (type == ServerboundPackets.PLAYER_INPUT
                        && ((ServerboundPlayerInput) f.fixture.codec.read(PacketDirection.SERVERBOUND, id, frame))
                                .shift()) name += " shift";
                packets.add(name);
            } finally {
                frame.release();
            }
        }
        return packets;
    }
}
