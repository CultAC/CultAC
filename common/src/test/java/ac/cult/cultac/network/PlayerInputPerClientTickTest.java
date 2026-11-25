package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.manager.init.start.PacketManager;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;

/** LocalPlayer sends at most one player_input per client tick; it is the tick's input for the proxy and the server. */
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

    @Test
    void aFurtherInputBeforeTheTickEndIsNotForwardedAndTheFirstJudgesTheMove() throws Exception {
        try (var f = connect(new JavaTickFixture())) {
            Vec3[] state = {new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO};
            f.place(state[0], Vec3.ZERO);
            f.player.xRot = YAW;
            assertTrue(send(f, WALK));
            for (int tick = 0; tick < 3; tick++) assertTrue(exact(move(f, state)), "warm-up " + tick);
            boolean rejected = false;
            for (int tick = 0; tick < 3; tick++) {
                assertTrue(send(f, SNEAK_WALK), "tick " + tick + ": the tick's first input is forwarded");
                assertFalse(send(f, WALK), "tick " + tick + ": a second input of the tick is not forwarded");
                assertTrue(f.player.isSneaking, "tick " + tick + ": the first input holds the keys");
                assertTrue(f.player.packetStateData.knownInput.shift(), "tick " + tick);
                rejected |= !exact(move(f, state));
            }
            assertTrue(rejected, "an unslowed move under the tick's sneaking input is never rejected");
        }
    }

    @Test
    void oneInputPerTickWithAKeyChangeEveryTickIsForwarded() throws Exception {
        try (var f = connect(new JavaTickFixture())) {
            for (int tick = 0; tick < 10; tick++) {
                ServerboundPlayerInput keys = tick % 2 == 0 ? SNEAK : NONE;
                assertTrue(send(f, keys), "tick " + tick);
                assertEquals(keys.shift(), f.player.isSneaking, "tick " + tick);
                assertTrue(tickEnd(f), "tick " + tick);
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
            f.fixture.channel.writeInbound((Object[]) frames);
            int forwarded = 0;
            ByteBuf frame;
            while ((frame = f.fixture.channel.readInbound()) != null) {
                forwarded++;
                frame.release();
            }
            assertEquals(ticks * 2, forwarded);
            assertTrue(f.player.isSneaking, "the last tick's input holds the keys");
        }
    }

    private static JavaTickFixture connect(JavaTickFixture f) {
        f.fixture.registrar.registerOwn(PacketManager::registerListeners);
        return f;
    }

    /** One unslowed walking move and its tick end; the prediction the move committed. */
    private static PredictionResult move(JavaTickFixture f, Vec3[] state) {
        Vec3 delta = state[1].add(ClientMoveInput.of(f, WALK, 1.0F, WALK_SPEED, YAW));
        state[0] = state[0].add(delta.x, 0.0, delta.z);
        state[1] = new Vec3(delta.x * FRICTION, 0.0, delta.z * FRICTION);
        var packet = new ServerboundMovePlayer(
                state[0].x, state[0].y, state[0].z, f.player.xRot, f.player.yRot, true, false, true, true);
        assertTrue(forward(f, f.fixture.encode(ServerboundPackets.MOVE_PLAYER, packet)), "the move is forwarded");
        assertTrue(tickEnd(f), "the tick end is forwarded");
        PredictionResult result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        assertFalse(result.isExempt(), describe(result));
        return result;
    }

    private static boolean send(JavaTickFixture f, ServerboundPlayerInput keys) {
        return forward(f, inputFrame(f, keys));
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

    private static boolean tickEnd(JavaTickFixture f) {
        return forward(f, tickEndFrame(f));
    }

    private static ByteBuf tickEndFrame(JavaTickFixture f) {
        return f.fixture.id(ServerboundPackets.CLIENT_TICK_END, "client_tick_end");
    }

    /** Whether {@code frame} reached the backend. */
    private static boolean forward(JavaTickFixture f, ByteBuf frame) {
        f.fixture.channel.writeInbound(frame);
        ByteBuf forwarded = f.fixture.channel.readInbound();
        if (forwarded == null) return false;
        forwarded.release();
        return true;
    }
}
