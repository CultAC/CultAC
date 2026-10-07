package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.packet.RegistryTags;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.nmsutil.BlockBreakSpeed;
import org.junit.jupiter.api.Test;

class ReceivedMiningTagsTest {
    @Test
    void miningUsesReceivedTagMembershipWithoutChangingServerBindings() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var player = fixture.player;
            player.packetStateData.packetPlayerOnGround = true;
            var shovel = OfflineCultTestBootstrap.item("minecraft:iron_shovel");
            int stone = ac.cult.blocksim.data.BlockIds.STONE.defaultState();
            assertEquals(1.0F / 1.5F / 100, BlockBreakSpeed.getBlockDamage(player, shovel, stone), 1e-8);
            player.registryState = new ClientComponentRegistries();
            player.registryState.appendTags(new RegistryTags(java.util.Map.of(
                    "minecraft:block",
                    new RegistryTags.Payload(java.util.Map.of(
                            "minecraft:mineable/shovel",
                            java.util.List.of(DataTables.defaults()
                                    .registry()
                                    .blockIndex(ac.cult.blocksim.data.BlockIds.STONE.defaultState())))))));
            assertEquals(6.0F / 1.5F / 30, BlockBreakSpeed.getBlockDamage(player, shovel, stone), 1e-8);
            assertFalse(DataTables.defaults()
                    .tags()
                    .get("block:minecraft:mineable/shovel")
                    .contains("minecraft:stone"));
            player.registryState.appendTags(
                    new RegistryTags(java.util.Map.of("minecraft:block", RegistryTags.Payload.EMPTY)));
            assertEquals(1.0F / 1.5F / 100, BlockBreakSpeed.getBlockDamage(player, shovel, stone), 1e-8);
        }
    }
}
