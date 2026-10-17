package ac.cult.cultac.player;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.combat.PlayerReach;
import ac.cult.cultac.cloud.CloudModule;
import ac.cult.cultac.command.commands.CultCloud;
import ac.cult.cultac.command.commands.CultFindings;
import ac.cult.cultac.command.commands.CultLabel;
import ac.cult.cultac.command.commands.CultTop;
import ac.cult.cultac.events.packets.listeners.PacketPlayerRespawn;
import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityEvent;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundLogin;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSetCarriedItem;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.internal.CommandRegistrationHandler;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** With the cloud upload not configured, registering its commands and a player's play touch none of its code. */
class CloudOffTest {
    @Test
    void aPlayersPlayLoadsNoCloudClassButTheModule() throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (!argument.startsWith("-javaagent") && !argument.startsWith("-agentlib")) command.add(argument);
        }
        command.addAll(List.of("-Xlog:class+load=info", "-cp", System.getProperty("java.class.path")));
        command.add(Play.class.getName());
        Process child = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String printed = output.lines()
                .filter(line -> !line.contains("[class,load]"))
                .collect(Collectors.joining(System.lineSeparator()));
        assertTrue(child.waitFor(2, TimeUnit.MINUTES), printed);
        assertEquals(0, child.exitValue(), printed);
        assertTrue(output.contains(Play.DONE), printed);

        List<String> loaded = output.lines()
                .filter(line -> line.contains("[class,load]"))
                .map(line -> line.substring(line.indexOf("[class,load]") + 13).split(" ")[0])
                .filter(name -> name.matches("ac\\.cult\\.cultac\\.(cloud|platform\\.\\w+\\.cloud)\\..+"))
                .map(name -> name.split("\\$\\$Lambda")[0])
                .distinct()
                .toList();
        assertEquals(List.of(CloudModule.class.getName()), loaded);
    }

    /**
     * The cloud commands' registration, then one player's login, client ticks, an attack out of reach, which flags, a
     * held-slot change and a totem pop.
     */
    static final class Play {
        static final String DONE = "played without the cloud";

        public static void main(String[] args) {
            OfflineCultTestBootstrap.installConfig();
            CloudModule.configReloaded();
            CommandManager<Sender> commands =
                    new CommandManager<>(
                            ExecutionCoordinator.simpleCoordinator(),
                            CommandRegistrationHandler.nullCommandRegistrationHandler()) {
                        @Override
                        public boolean hasPermission(Sender sender, String permission) {
                            return true;
                        }
                    };
            new CultCloud().register(commands, null);
            new CultFindings().register(commands, null);
            new CultTop().register(commands, null);
            new CultLabel().register(commands, null);
            EmbeddedChannel channel = new EmbeddedChannel();
            CultConnection connection = new CultConnection(
                    OfflineCultTestBootstrap.platformConnection(),
                    channel,
                    CultAPI.INSTANCE.getNetworkManager().dispatcher(),
                    ignored -> null);
            for (PacketDirection direction : PacketDirection.values())
                connection.phase(direction, ConnectionPhase.PLAY);
            User user = new User(new User.Profile(UUID.randomUUID(), "Steve"), connection);
            PlatformPlayer platform = Mockito.mock(PlatformPlayer.class);
            PlatformWorld platformWorld = Mockito.mock(PlatformWorld.class);
            Mockito.when(platformWorld.getName()).thenReturn("world");
            Mockito.when(platform.getWorld()).thenReturn(platformWorld);
            user.bind(platform);
            CultAPI.INSTANCE.getPlayerDataManager().addUser(user);
            CultPlayer player = CultAPI.INSTANCE.getPlayerDataManager().getPlayer(user);

            ClientboundLogin login =
                    new ClientboundLogin(7, false, new PlayerSpawnInfo(0, "minecraft:overworld", GameMode.SURVIVAL));
            new PacketPlayerRespawn()
                    .onLogin(
                            new PacketSendEvent<>(user, ConnectionPhase.PLAY, ClientboundPackets.LOGIN, login, false),
                            player,
                            login);

            player.checkManager.allChecks.values().forEach(check -> check.setEnabled(false));
            PlayerReach reach = player.checkManager.getCheck(PlayerReach.class);
            reach.setEnabled(true);
            player.gamemode = GameMode.SURVIVAL;
            player.x = .5;
            player.y = 64;
            player.z = -1.5;
            player.registryState = new ClientComponentRegistries();
            player.getSetbackTeleportUtil().hasFullyLoaded = true;
            var world = player.compensatedWorld;
            var dimension = player.getWorldRegistries().dimension("minecraft:overworld");
            world.setLastClientboundDimension("minecraft:overworld", dimension.dimension());
            world.setDimension("minecraft:overworld", dimension);
            world.ensureValidationChunkLoaded(0, 0);
            world.ensureValidationChunkLoaded(0, -1);
            player.compensatedEntities.addEntity(100, EntityTypeIds.PLAYER, new Vec3(.5, 64, 2.3), 0, 0, 0);
            tickEnd(user, player);
            player.checkManager.dispatchReceiveHandlers(new PacketReceiveEvent<>(
                    user,
                    ConnectionPhase.PLAY,
                    ServerboundPackets.INTERACT,
                    new ServerboundInteract(100, InteractAction.ATTACK, Hand.MAIN_HAND, Optional.empty(), false)));
            tickEnd(user, player);
            tickEnd(user, player);
            player.checkManager.dispatchReceiveHandlers(new PacketReceiveEvent<>(
                    user, ConnectionPhase.PLAY, ServerboundPackets.SET_CARRIED_ITEM, new ServerboundSetCarriedItem(1)));
            player.checkManager.dispatchSendHandlers(new PacketSendEvent<>(
                    user,
                    ConnectionPhase.PLAY,
                    ClientboundPackets.ENTITY_EVENT,
                    new ClientboundEntityEvent(player.entityID, (byte) 35),
                    false));

            if (reach.violations <= 0) throw new AssertionError("the attack did not flag");
            if (player.observer != null) throw new AssertionError("an observer is set: " + player.observer);
            if (!reach.measuredAttacks().isEmpty()) throw new AssertionError("measured attacks are kept");
            System.out.println(DONE);
            System.exit(0);
        }

        private static void tickEnd(User user, CultPlayer player) {
            player.checkManager.dispatchReceiveHandlers(new PacketReceiveEvent<>(
                    user,
                    ConnectionPhase.PLAY,
                    ServerboundPackets.CLIENT_TICK_END,
                    ServerboundPackets.CLIENT_TICK_END.opaqueValue()));
        }
    }
}
