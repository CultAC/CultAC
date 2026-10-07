package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.packet.RegistryTags;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;
import ac.cult.cultac.utils.blockplace.ClientBlockActions;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OlderClientActionsTest {
    @Test
    void supportedClientsPlaceInTheBundledModelStateSpace() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var player = fixture.player;
            var version = player.getClass().getDeclaredField("resolvedClientVersion");
            version.setAccessible(true);
            player.gamemode = GameMode.SURVIVAL;
            player.x = .5;
            player.y = 64;
            player.z = -2;
            var support = new BlockPos(1, 63, 1);
            var world = player.compensatedWorld;
            world.ensureValidationChunkLoaded(0, 0);
            int stone =
                    DataTables.defaults().registry().block("minecraft:stone").defaultState();
            for (int protocol = 768; protocol <= 777; protocol++) {
                version.set(player, ClientVersion.fromProtocolVersion(protocol));
                player.registryState = new ClientComponentRegistries();
                world.updateBlock(support, stone);
                world.updateBlock(support.above(), 0);
                var held = OfflineCultTestBootstrap.item("minecraft:stone", 2);
                player.getInventory().inventory.setHeldItem(held);
                var place = new BlockPlace(player, Hand.MAIN_HAND, support, Direction.UP, held, null);
                place.setCursor(new Vec3(.5, 1, .5));
                ClientBlockActions.useOn(player, place);
                assertEquals(stone, world.getBlockStateIdAt(support.above()), "Client protocol " + protocol);
                assertEquals(1, player.getInventory().getHeldItem().getCount());
                assertEquals(2, held.getCount());
                var binding = player.registryState.blockSimulatorActions(
                        ProtocolVersion.V26_3,
                        ProtocolVersion.of(protocol),
                        world.clientFeatures(),
                        DataTables.defaults());
                assertEquals(ProtocolVersion.V26_3, binding.states().target());
                assertEquals(stone, binding.states().toModel(stone));
            }
        }
    }

    @Test
    void anOlderClientKeepsItsOwnReceivedTagGeneration() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var version = fixture.player.getClass().getDeclaredField("resolvedClientVersion");
            version.setAccessible(true);
            version.set(fixture.player, ClientVersion.fromProtocolVersion(774));
            var data = DataTables.defaults();
            var state = new ClientComponentRegistries();
            int stone = data.registry()
                    .blockIndex(data.registry().block("minecraft:stone").defaultState());
            state.appendTags(new RegistryTags(
                    Map.of("minecraft:block", new RegistryTags.Payload(Map.of("test:received", List.of(stone))))));
            var first = state.blockSimulatorActions(
                    ProtocolVersion.V26_3,
                    ProtocolVersion.V1_21_11,
                    fixture.player.compensatedWorld.clientFeatures(),
                    data);
            assertEquals(
                    java.util.Set.of("minecraft:stone"), first.tags().tags().get("block:test:received"));
            assertSame(
                    first,
                    state.blockSimulatorActions(
                            ProtocolVersion.V26_3,
                            ProtocolVersion.V1_21_11,
                            fixture.player.compensatedWorld.clientFeatures(),
                            data));
            state.appendTags(new RegistryTags(Map.of("minecraft:block", RegistryTags.Payload.EMPTY)));
            var next = state.blockSimulatorActions(
                    ProtocolVersion.V26_3,
                    ProtocolVersion.V1_21_11,
                    fixture.player.compensatedWorld.clientFeatures(),
                    data);
            assertFalse(next.tags().tags().containsKey("block:test:received"));
            assertEquals(
                    java.util.Set.of("minecraft:stone"), first.tags().tags().get("block:test:received"));
            assertNotSame(first, next);
        }
    }
}
