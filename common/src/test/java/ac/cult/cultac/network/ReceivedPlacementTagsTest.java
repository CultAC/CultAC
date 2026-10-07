package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.packet.RegistryTags;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;
import ac.cult.cultac.utils.blockplace.ClientBlockActions;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.math.Vec3;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReceivedPlacementTagsTest {
    @Test
    void placementUsesEachPlayersReceivedTagsAndReplacesOldMembership() throws Exception {
        // ClientPacketListener#handleUpdateTags applies the received bindings before
        // VegetationBlock#mayPlaceOn reads SUPPORTS_VEGETATION during item use.
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var player = fixture.player;
            player.gamemode = GameMode.SURVIVAL;
            player.x = .5;
            player.y = 64;
            player.z = -2;
            var support = new BlockPos(1, 63, 1);
            var placed = support.above();
            var world = player.compensatedWorld;
            world.ensureValidationChunkLoaded(0, 0);
            world.updateBlock(
                    support,
                    DataTables.defaults().registry().block("minecraft:stone").defaultState());
            assertFalse(DataTables.defaults()
                    .tags()
                    .get("block:minecraft:supports_vegetation")
                    .contains("minecraft:stone"));
            player.registryState = new ClientComponentRegistries();
            player.registryState.appendTags(new RegistryTags(Map.of(
                    "minecraft:block",
                    new RegistryTags.Payload(Map.of(
                            "minecraft:supports_vegetation",
                            java.util.List.of(DataTables.defaults()
                                    .registry()
                                    .blockIndex(ac.cult.blocksim.data.BlockIds.STONE.defaultState())))))));

            place(fixture, support);
            assertEquals(
                    DataTables.defaults().registry().block("minecraft:sunflower"),
                    DataTables.defaults().registry().block(world.getBlockStateIdAt(placed)));
            assertEquals(
                    DataTables.defaults().registry().block("minecraft:sunflower"),
                    DataTables.defaults().registry().block(world.getBlockStateIdAt(placed.above())));
            assertTrue(player.getInventory().getHeldItem().isEmpty());
            assertFalse(
                    DataTables.defaults()
                            .tags()
                            .get("block:minecraft:supports_vegetation")
                            .contains("minecraft:stone"),
                    "Client tag updates must not mutate host registry bindings");

            world.updateBlock(
                    placed,
                    DataTables.defaults().registry().block("minecraft:air").defaultState());
            world.updateBlock(
                    placed.above(),
                    DataTables.defaults().registry().block("minecraft:air").defaultState());
            player.registryState.appendTags(new RegistryTags(Map.of("minecraft:block", RegistryTags.Payload.EMPTY)));
            place(fixture, support);
            assertTrue(DataTables.defaults()
                    .registry()
                    .facts(world.getBlockStateIdAt(placed))
                    .has(ac.cult.blocksim.data.StateFacts.AIR));
            assertTrue(DataTables.defaults()
                    .registry()
                    .facts(world.getBlockStateIdAt(placed.above()))
                    .has(ac.cult.blocksim.data.StateFacts.AIR));
            assertEquals(1, player.getInventory().getHeldItem().getCount());

            player.registryState = null;
            place(fixture, support);
            assertTrue(
                    DataTables.defaults()
                            .registry()
                            .facts(world.getBlockStateIdAt(placed))
                            .has(ac.cult.blocksim.data.StateFacts.AIR),
                    "Received tags must not leak to the default client view");
            assertEquals(1, player.getInventory().getHeldItem().getCount());
        }
    }

    private static void place(RecordReceiveFixture fixture, BlockPos support) {
        var player = fixture.player;
        var stack = OfflineCultTestBootstrap.item("minecraft:sunflower");
        player.getInventory().inventory.setHeldItem(stack);
        var place = new BlockPlace(player, Hand.MAIN_HAND, support, Direction.UP, stack, null);
        place.setCursor(new Vec3(.5, 1, .5));
        ClientBlockActions.useOn(player, place);
    }
}
