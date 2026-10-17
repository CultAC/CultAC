package ac.cult.cultac.platform.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.platform.velocity.bridge.command.RelayCommandSource;
import ac.cult.cultac.platform.velocity.bridge.command.RelaySources;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

class VelocitySendersTest {
    @Test
    void relayedBackendConsoleIsTheConsole() {
        List<Component> relayed = new ArrayList<>();
        RelayCommandSource relay = RelaySources.relay(relayed::add);
        VelocitySenders senders = new VelocitySenders(mock(ProxyServer.class), new VelocityPermissions());

        Sender sender = senders.wrap(relay);
        sender.sendMessage(Component.text("hello"));

        assertTrue(sender.isConsole());
        assertFalse(sender.isPlayer());
        assertEquals(Sender.CONSOLE_UUID, sender.getUniqueId());
        assertTrue(sender.hasPermission("cult.admin"));
        assertSame(relay, senders.unwrap(sender));
        assertEquals(List.of(Component.text("hello")), relayed);
    }
}
