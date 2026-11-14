package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.checks.impl.movement.timer.AbstractTimerCheck;
import ac.cult.cultac.checks.impl.movement.timer.DumbTimer;
import ac.cult.cultac.checks.impl.movement.timer.TimerCheck;
import ac.cult.cultac.checks.impl.movement.timer.VehicleTimer;
import ac.cult.cultac.events.packets.listeners.PacketPlayerRespawn;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundLogin;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import ac.cult.cultac.utils.math.Vec3;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A login keeps the tick timers' windows, so moves a client sent before it still arrive within them. Only a Java
 * player's DumbTimer backlog restarts, which never shortens it.
 */
class LoginTimerWindowTest {
    private static final Vec3 GROUND = new Vec3(0.5, JavaTickFixture.FLOOR_Y, 0.5);

    @Test
    void aJavaLoginKeepsTheTickTimersAndNeverShortensDumbTimersBacklog() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(GROUND, Vec3.ZERO);
            List<Object> timers = timerWindows(f);
            DumbTimer dumb = f.player.checkManager.getListener(DumbTimer.class);
            JavaTickFixture.setField(DumbTimer.class, dumb, "balance", (double) System.nanoTime());
            double before = doubleField(dumb, "balance");
            login(f);
            assertEquals(timers, timerWindows(f));
            assertTrue(doubleField(dumb, "balance") <= before, "the backlog shortened");
        }
    }

    @Test
    void aBedrockLoginLeavesEveryTimerAsItWas() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(GROUND, Vec3.ZERO);
            f.player.movementPlatform = MovementPlatform.BEDROCK;
            DumbTimer dumb = f.player.checkManager.getListener(DumbTimer.class);
            List<Object> before = List.of(
                    timerWindows(f), doubleField(dumb, "balance"), doubleField(dumb, "lastLongTeleport"));
            login(f);
            assertEquals(
                    before,
                    List.of(timerWindows(f), doubleField(dumb, "balance"), doubleField(dumb, "lastLongTeleport")));
        }
    }

    private static void login(JavaTickFixture f) {
        var login = new ClientboundLogin(7, false, new PlayerSpawnInfo(0, "minecraft:overworld", GameMode.SURVIVAL));
        var event = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.LOGIN, login, false);
        new PacketPlayerRespawn().onLogin(event, f.player, login);
        event.getTasksAfterSend().forEach(Runnable::run);
    }

    private static List<Object> timerWindows(JavaTickFixture f) throws Exception {
        AbstractTimerCheck timer = f.player.checkManager.getCheck(TimerCheck.class);
        AbstractTimerCheck vehicle = f.player.checkManager.getCheck(VehicleTimer.class);
        return List.of(
                longField(timer, "timerBalanceRealTime"),
                longField(timer, "knownPlayerClockTime"),
                longField(vehicle, "timerBalanceRealTime"),
                longField(vehicle, "knownPlayerClockTime"));
    }

    private static long longField(AbstractTimerCheck check, String name) throws Exception {
        Field field = AbstractTimerCheck.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(check);
    }

    private static double doubleField(DumbTimer dumb, String name) throws Exception {
        Field field = DumbTimer.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getDouble(dumb);
    }
}
