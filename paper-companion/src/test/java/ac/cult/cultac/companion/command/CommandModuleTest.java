package ac.cult.cultac.companion.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import ac.cult.cultac.bridge.protocol.BridgeMessage;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Command;
import ac.cult.cultac.bridge.protocol.BridgeMessage.CommandOutput;
import ac.cult.cultac.companion.Stubs;
import ac.cult.cultac.companion.bridge.CompanionBridge;
import ac.cult.cultac.companion.bridge.CompanionSession;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.ProxiedCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.CommandMinecart;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class CommandModuleTest {
    private static final UUID PLAYER = UUID.fromString("2b5f1e7a-6a39-4d63-9a3f-0f1e2d3c4b5a");

    @Test
    void consoleAndRconForwardAsTheConsole() {
        assertEquals(Command.CONSOLE, CommandModule.forwardedAs(sender(ConsoleCommandSender.class)));
        assertEquals(Command.CONSOLE, CommandModule.forwardedAs(sender(RemoteConsoleCommandSender.class)));
    }

    @Test
    void playersForwardAsThemselves() {
        assertEquals(PLAYER, CommandModule.forwardedAs(sender(Player.class)));
    }

    @Test
    void everyOtherSenderIsRefused() {
        assertNull(CommandModule.forwardedAs(sender(BlockCommandSender.class)));
        assertNull(CommandModule.forwardedAs(sender(CommandMinecart.class)));
        assertNull(CommandModule.forwardedAs(sender(ProxiedCommandSender.class)));
        assertNull(CommandModule.forwardedAs(sender(CommandSender.class)));
    }

    @Test
    void rconHearsAFixedLineAndTheOutputGoesToTheLog() {
        List<BridgeMessage> sent = new ArrayList<>();
        CompanionSession session = proxy(CompanionSession.class, (method, args) -> switch (method) {
            case "send" -> sent.add((BridgeMessage) args[0]);
            case "playerId" -> PLAYER;
            case "isReady" -> true;
            default -> null;
        });
        CompanionBridge bridge =
                proxy(CompanionBridge.class, (method, args) -> method.equals("anySession") ? session : null);
        CommandRelay relay = new CommandRelay(bridge, () -> 0L, Logger.getLogger("module-test"), false);
        List<String> logged = new ArrayList<>();
        Logger log = Stubs.logTo(record -> logged.add(record.getMessage()));
        Plugin plugin = proxy(Plugin.class, (method, args) -> switch (method) {
            case "getLogger" -> log;
            case "isEnabled" -> true;
            default -> null;
        });
        List<Object> heard = new ArrayList<>();
        RemoteConsoleCommandSender rcon = proxy(RemoteConsoleCommandSender.class, (method, args) -> {
            if (method.equals("sendMessage")) heard.add(args[0]);
            return method.equals("getName") ? "Rcon" : null;
        });

        CommandModule.execute(relay, plugin, rcon, "cult", new String[] {"history"});

        assertEquals(List.of(CommandModule.SENT_TO_LOG), heard);
        Command forwarded = assertInstanceOf(Command.class, sent.getFirst());
        assertEquals(Command.CONSOLE, forwarded.sender());
        assertEquals("cult history", forwarded.line());
        relay.onMessage(
                session,
                new CommandOutput(
                        forwarded.requestId(), GsonComponentSerializer.gson().serialize(Component.text("page 1"))));
        assertEquals(List.of("page 1"), logged);
        assertEquals(1, heard.size());
    }

    @Test
    void outputArrivingWhileAnRconCommandIsSentGoesToTheLog() {
        CommandRelay[] relay = new CommandRelay[1];
        CompanionSession[] session = new CompanionSession[1];
        session[0] = proxy(CompanionSession.class, (method, args) -> switch (method) {
            case "send" -> {
                Command command = (Command) args[0];
                relay[0].onMessage(
                        session[0],
                        new CommandOutput(
                                command.requestId(),
                                GsonComponentSerializer.gson().serialize(Component.text("early"))));
                yield null;
            }
            case "playerId" -> PLAYER;
            case "isReady" -> true;
            default -> null;
        });
        CompanionBridge bridge =
                proxy(CompanionBridge.class, (method, args) -> method.equals("anySession") ? session[0] : null);
        relay[0] = new CommandRelay(bridge, () -> 0L, Logger.getLogger("module-test"), false);
        List<String> logged = new ArrayList<>();
        Logger log = Stubs.logTo(record -> logged.add(record.getMessage()));
        Plugin plugin = proxy(Plugin.class, (method, args) -> switch (method) {
            case "getLogger" -> log;
            case "isEnabled" -> true;
            default -> null;
        });
        List<Object> heard = new ArrayList<>();
        RemoteConsoleCommandSender rcon = proxy(RemoteConsoleCommandSender.class, (method, args) -> {
            if (method.equals("sendMessage")) heard.add(args[0]);
            return method.equals("getName") ? "Rcon" : null;
        });

        CommandModule.execute(relay[0], plugin, rcon, "cult", new String[] {"history"});

        assertEquals(List.of(CommandModule.SENT_TO_LOG), heard);
        assertEquals(List.of("early"), logged);
    }

    @Test
    void rconHearsWhyACommandCouldNotBeSent() {
        CompanionBridge bridge = proxy(CompanionBridge.class, (method, args) -> null);
        CommandRelay relay = new CommandRelay(bridge, () -> 0L, Logger.getLogger("module-test"), false);
        Plugin plugin = proxy(Plugin.class, (method, args) -> switch (method) {
            case "getLogger" -> Logger.getLogger("module-test");
            case "isEnabled" -> true;
            default -> null;
        });
        List<Object> heard = new ArrayList<>();
        RemoteConsoleCommandSender rcon = proxy(RemoteConsoleCommandSender.class, (method, args) -> {
            if (method.equals("sendMessage")) heard.add(args[0]);
            return method.equals("getName") ? "Rcon" : null;
        });

        CommandModule.execute(relay, plugin, rcon, "cult", new String[] {"history"});

        assertEquals(List.of(CommandRelay.UNREACHABLE), heard);
    }

    private static <T> T proxy(Class<T> type, BiFunction<String, Object[], Object> answer) {
        return Stubs.stub(type, (proxy, method, args) -> switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            default -> answer.apply(method.getName(), args);
        });
    }

    private static CommandSender sender(Class<? extends CommandSender> type) {
        return Stubs.stub(type, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> PLAYER;
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            default -> null;
        });
    }
}
