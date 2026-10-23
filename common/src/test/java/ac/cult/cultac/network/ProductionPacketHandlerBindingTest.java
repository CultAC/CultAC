package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.events.packets.PacketModelRegistryNames;
import ac.cult.cultac.events.packets.PacketServerPlayerRotation;
import ac.cult.cultac.events.packets.PacketServerRegistries;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.events.packets.listeners.PacketBlockAction;
import ac.cult.cultac.events.packets.listeners.PacketConfigurationListener;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.events.packets.listeners.PacketPingListener;
import ac.cult.cultac.events.packets.listeners.PacketPlayerAttack;
import ac.cult.cultac.events.packets.listeners.PacketPlayerCooldown;
import ac.cult.cultac.events.packets.listeners.PacketPlayerDigging;
import ac.cult.cultac.events.packets.listeners.PacketPlayerRespawn;
import ac.cult.cultac.events.packets.listeners.PacketPlayerSteer;
import ac.cult.cultac.events.packets.listeners.PacketPlayerWindow;
import ac.cult.cultac.events.packets.listeners.PacketSelfMetadataListener;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.events.packets.listeners.PlayerInfoListener;
import ac.cult.cultac.events.packets.worldreader.PacketWorldReaderTwentySix;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.PacketDirection;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Every production listener class binds each supported handler through a spun class, in schema order. */
class ProductionPacketHandlerBindingTest {
    /** The scanned listeners {@code PacketManager.start} registers. */
    private static final List<Class<?>> PACKET_MANAGER_LISTENERS = List.of(
            CheckManagerListener.class,
            PacketConfigurationListener.class,
            PacketPingListener.class,
            PacketPlayerWindow.class,
            PacketPlayerDigging.class,
            PacketPlayerAttack.class,
            PacketEntityAction.class,
            PacketServerTeleport.class,
            PacketPlayerSteer.class,
            PacketBlockAction.class,
            PacketSelfMetadataListener.class,
            PacketPlayerCooldown.class,
            PacketPlayerRespawn.class,
            PacketWorldReaderTwentySix.class,
            PlayerInfoListener.class,
            PacketServerPlayerRotation.class,
            PacketServerRegistries.class,
            PacketModelRegistryNames.class);

    @Test
    void everyProductionHandlerIsSpunAndBoundInSchemaOrder() {
        OfflineCultTestBootstrap.installConfig();
        PacketHandlerScanner scanner =
                CultAPI.INSTANCE.getNetworkManager().dispatcher().scanner();
        Set<Class<?>> types = new LinkedHashSet<>(PACKET_MANAGER_LISTENERS);
        CultPlayer player = new CultPlayer(
                TestUsers.create(new User.Profile(UUID.randomUUID(), ".Binding_Test"), new EmbeddedChannel()));
        List<Object> checkListeners;
        try {
            checkListeners = new ArrayList<>(player.checkManager.allCheckListeners.values());
        } finally {
            Object channel = player.user.getChannel();
            player.onRemove();
            if (channel instanceof EmbeddedChannel embedded) embedded.finishAndReleaseAll();
        }
        for (Object listener : checkListeners) types.add(listener.getClass());

        int handlers = 0;
        for (Class<?> type : types) {
            for (var entry : scanner.schema(type).entries()) {
                assertFalse(
                        entry.invoker() instanceof PacketHandlerScanner.HandleInvoker,
                        entry.method().toString());
                handlers++;
            }
        }
        assertTrue(handlers > 100, "scanned " + handlers + " handlers");

        for (Object listener : checkListeners) {
            assertEquals(
                    schemaMethods(scanner, listener.getClass(), PacketDirection.SERVERBOUND),
                    scanner.receiveHandlers(listener).stream()
                            .map(PacketHandlerScanner.ReceiveRegistration::method)
                            .toList());
            assertEquals(
                    schemaMethods(scanner, listener.getClass(), PacketDirection.CLIENTBOUND),
                    scanner.sendHandlers(listener).stream()
                            .map(PacketHandlerScanner.SendRegistration::method)
                            .toList());
        }
    }

    @Test
    void theListCoversEveryListenerPacketManagerConstructs() throws IOException {
        String file = Files.readString(Path.of("src/main/java/ac/cult/cultac/manager/init/start/PacketManager.java"));
        String source = file.substring(file.indexOf("dispatcher().register("));
        Set<String> constructed = new TreeSet<>();
        Matcher matcher =
                Pattern.compile("new (?:[a-z]\\w*\\.)*(\\w+)\\(\\)(::)?").matcher(source);
        while (matcher.find()) {
            if (matcher.group(2) == null) constructed.add(matcher.group(1));
        }
        Set<String> listed = new TreeSet<>();
        for (Class<?> type : PACKET_MANAGER_LISTENERS) listed.add(type.getSimpleName());
        assertEquals(constructed, listed);
    }

    private static List<Method> schemaMethods(PacketHandlerScanner scanner, Class<?> type, PacketDirection direction) {
        return scanner.schema(type).entries().stream()
                .filter(entry -> entry.direction() == direction && entry.packetType() != null)
                .map(PacketHandlerScanner.Entry::method)
                .toList();
    }
}
