package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.elytra.ElytraB;
import ac.cult.cultac.checks.impl.movement.timer.AbstractTimerCheck;
import ac.cult.cultac.checks.impl.movement.timer.NegativeTimerCheck;
import ac.cult.cultac.checks.impl.movement.timer.TimerCheck;
import ac.cult.cultac.checks.impl.movement.timer.VehicleTimer;
import ac.cult.cultac.checks.impl.packetorder.PacketOrderProcessor;
import ac.cult.cultac.manager.init.start.PacketManager;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.buffer.ByteBuf;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A pong that answers no ping CultAC wrote reaches no check; the pong answering CultAC's ping still does. */
class BackendPongCheckStateTest {
    private static final int BACKEND_PONG = 42;
    private static final int CAMERA_ENTITY = 70;
    private static final List<String> TIMER_FIELDS = List.of(
            "timerBalanceRealTime",
            "knownPlayerClockTime",
            "lastMovementPlayerClock",
            "hasGottenMovementAfterTransaction");

    @Test
    void aPongThatAnswersNoCultacPingIsNotATimerEvent() throws Exception {
        try (var f = connect()) {
            assertBackendPongIsNoTimerEvent(f, f.player.checkManager.getCheck(TimerCheck.class));
        }
    }

    @Test
    void aPongThatAnswersNoCultacPingIsNotAVehicleTimerEvent() throws Exception {
        try (var f = connect()) {
            assertBackendPongIsNoTimerEvent(f, f.player.checkManager.getCheck(VehicleTimer.class));
        }
    }

    @Test
    void aPongThatAnswersNoCultacPingIsNotANegativeTimerEvent() throws Exception {
        try (var f = connect()) {
            NegativeTimerCheck timer = f.player.checkManager.getCheck(NegativeTimerCheck.class);
            JavaTickFixture.setField(NegativeTimerCheck.class, timer, "ticksWithoutNewTransactionOrLook", 5);
            assertBackendPongIsNoTimerEvent(f, timer);
            assertEquals(0, get(NegativeTimerCheck.class, timer, "ticksWithoutNewTransactionOrLook"));
        }
    }

    @Test
    void aPongThatAnswersNoCultacPingEndsNoGlideStart() throws Exception {
        List<Object> withoutBackendPong;
        try (var f = connect()) {
            withoutBackendPong = glideStartThenPongs(f, false);
        }
        try (var f = connect()) {
            assertEquals(withoutBackendPong, glideStartThenPongs(f, true));
        }
    }

    @Test
    void aPongThatAnswersNoCultacPingKeepsThePacketOrderAwayFromTheCamera() throws Exception {
        try (var f = connect()) {
            f.player.compensatedEntities.addEntity(
                    CAMERA_ENTITY, EntityTypeIds.ZOMBIE, new Vec3(1, JavaTickFixture.FLOOR_Y, 1), 0, 0, 0);
            f.player.cameraEntity.onSetCamera(CAMERA_ENTITY);
            for (int id : ownPingsToClient(f)) assertFalse(answer(f, id), "a CultAC pong reached the backend");
            assertFalse(f.player.cameraEntity.isSelf(), "the camera is on the other entity");

            PacketOrderProcessor order = f.player.checkManager.getCheck(PacketOrderProcessor.class);
            int own = ownPing(f);
            JavaTickFixture.setField(PacketOrderProcessor.class, order, "attacking", true);
            assertTrue(answer(f, BACKEND_PONG), "a backend pong is forwarded");
            assertTrue(order.isAttacking(), "the backend pong reset the packet order");
            assertFalse(answer(f, own), "a CultAC pong reached the backend");
            assertFalse(order.isAttacking(), "the CultAC pong left the packet order");
        }
    }

    /** A movement after the last pong, then a backend pong and the pong answering CultAC's next ping. */
    private static void assertBackendPongIsNoTimerEvent(JavaTickFixture f, AbstractTimerCheck timer) throws Exception {
        int own = ownPing(f);
        long movement = System.nanoTime() - 1_000_000_000L;
        JavaTickFixture.setField(AbstractTimerCheck.class, timer, "lastMovementPlayerClock", movement);
        JavaTickFixture.setField(AbstractTimerCheck.class, timer, "hasGottenMovementAfterTransaction", true);
        List<Object> before = timerState(timer);

        assertTrue(answer(f, BACKEND_PONG), "a backend pong is forwarded");
        assertEquals(before, timerState(timer), "the backend pong changed " + timer.getClass().getSimpleName());

        assertFalse(answer(f, own), "a CultAC pong reached the backend");
        assertEquals(movement, get(AbstractTimerCheck.class, timer, "knownPlayerClockTime"));
        assertEquals(false, get(AbstractTimerCheck.class, timer, "hasGottenMovementAfterTransaction"));
    }

    /** ElytraB after a glide start without jump, an optional backend pong and the pong answering CultAC's ping. */
    private static List<Object> glideStartThenPongs(JavaTickFixture f, boolean backendPong) throws Exception {
        ElytraB elytraB = f.player.checkManager.getCheck(ElytraB.class);
        int own = ownPing(f);
        var packet = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_FLYING_WITH_ELYTRA, 0);
        elytraB.onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, packet), f.player, packet);
        List<Object> started = elytraState(elytraB);
        if (backendPong) {
            assertTrue(answer(f, BACKEND_PONG), "a backend pong is forwarded");
            assertEquals(started, elytraState(elytraB), "the backend pong ended the glide start");
        }
        assertFalse(answer(f, own), "a CultAC pong reached the backend");
        return elytraState(elytraB);
    }

    private static List<Object> elytraState(ElytraB elytraB) throws Exception {
        return List.of(
                elytraB.violations, get(ElytraB.class, elytraB, "glide"), get(ElytraB.class, elytraB, "setback"));
    }

    private static List<Object> timerState(AbstractTimerCheck timer) throws Exception {
        List<Object> state = new ArrayList<>();
        for (String name : TIMER_FIELDS) state.add(get(AbstractTimerCheck.class, timer, name));
        return state;
    }

    private static JavaTickFixture connect() throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.fixture.registrar.registerOwn(PacketManager::registerListeners);
        return f;
    }

    /** Writes one CultAC ping and returns its id once it reached the client. */
    private static int ownPing(JavaTickFixture f) {
        f.player.sendTransaction();
        List<Integer> pings = ownPingsToClient(f);
        assertEquals(1, pings.size(), "one CultAC ping: " + pings);
        return pings.getFirst();
    }

    private static List<Integer> ownPingsToClient(JavaTickFixture f) {
        f.fixture.channel.flush();
        f.fixture.channel.runPendingTasks();
        int pingType = f.fixture.data.packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND).id("minecraft:ping");
        List<Integer> pings = new ArrayList<>();
        ByteBuf frame;
        while ((frame = f.fixture.channel.readOutbound()) != null) {
            try {
                if (Wire.readVarInt(frame) != pingType) continue;
                int id = frame.readInt();
                if (id < 0) pings.add(id);
            } finally {
                frame.release();
            }
        }
        return pings;
    }

    /** The client's pong for {@code id} through the connection; true when it reached the backend. */
    private static boolean answer(JavaTickFixture f, int id) {
        f.fixture.channel.writeInbound(f.fixture.pong(id));
        ByteBuf forwarded = f.fixture.channel.readInbound();
        if (forwarded == null) return false;
        forwarded.release();
        return true;
    }

    private static Object get(Class<?> owner, Object value, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(value);
    }
}
