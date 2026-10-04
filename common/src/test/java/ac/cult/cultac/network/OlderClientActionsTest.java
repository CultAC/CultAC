package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.network.packet.RegistryTags;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;
import ac.cult.cultac.utils.blockplace.VanillaBlockActions;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.minecraft.IsolatedMinecraft;
import ac.cult.runtime.RuntimeModel;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class OlderClientActionsTest {
    @Test
    void originalActionFamilyDoesNotInheritTheOlderGeometryUpperBound() throws Exception {
        try (var vanilla = new VanillaActionFixture();
                var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var primary = IsolatedMinecraft.class.getDeclaredField("primary");
            var acquired = IsolatedMinecraft.class.getDeclaredField("acquiredBindings");
            primary.setAccessible(true);
            acquired.setAccessible(true);
            Object original = primary.get(null);
            var bindings = (Map<?, ?>) acquired.get(null);
            var version = fixture.player.getClass().getDeclaredField("resolvedClientVersion");
            version.setAccessible(true);
            try {
                primary.set(null, bindings.get(RuntimeModel.JAVA_1_21_11));
                for (int protocol = 768; protocol <= 777; protocol++) {
                    version.set(fixture.player, ClientVersion.fromProtocolVersion(protocol));
                    assertEquals(
                            protocol <= 774 ? RuntimeModel.JAVA_1_21_11 : RuntimeModel.JAVA_26_3,
                            IsolatedMinecraft.actionsFor(fixture.player)
                                    .runtime()
                                    .model());
                    if (protocol > 774) assertNull(IsolatedMinecraft.forPlayer(fixture.player));
                    else
                        assertEquals(
                                RuntimeModel.JAVA_1_21_11,
                                IsolatedMinecraft.forPlayer(fixture.player).model());
                }
            } finally {
                primary.set(null, original);
            }
        }
    }

    @Test
    void oldActionsUseTheOriginalOlderModelAndTheirOwnReceivedTagGeneration() throws Exception {
        try (var vanilla = new VanillaActionFixture();
                var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var player = fixture.player;
            var version = player.getClass().getDeclaredField("resolvedClientVersion");
            version.setAccessible(true);
            version.set(player, ClientVersion.fromProtocolVersion(774));
            assertEquals(
                    RuntimeModel.JAVA_1_21_11,
                    IsolatedMinecraft.actionsFor(player).runtime().model());
            assertEquals(
                    RuntimeModel.JAVA_26_3, IsolatedMinecraft.forPlayer(player).model());
            player.gamemode = GameMode.SURVIVAL;
            player.x = .5;
            player.y = 64;
            player.z = -2;
            var support = new BlockPos(1, 63, 1);
            var placed = support.above();
            var world = player.compensatedWorld;
            world.ensureValidationChunkLoaded(0, 0);
            // Use the real newer dimension schema, including its ambient-light
            // environment attribute. Passing it unchanged to 1.21.11 fails decoding.
            world.setDimension(
                    "minecraft:overworld",
                    ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap.vanillaRegistries()
                            .lookupOrThrow(Registries.DIMENSION_TYPE)
                            .get(Identifier.parse("minecraft:overworld"))
                            .orElseThrow()
                            .value());
            world.updateBlock(support, Blocks.GRASS_BLOCK.defaultBlockState());
            player.registryState = new ClientComponentRegistries();
            player.registryState.appendTags(new RegistryTags(Map.of(
                    Registries.BLOCK,
                    new TagNetworkSerialization.NetworkPayload(Map.of(
                            Identifier.parse("minecraft:dirt"),
                            it.unimi.dsi.fastutil.ints.IntList.of(),
                            // The complete dimension references this named set; retain
                            // its definition with empty membership in this fixture.
                            Identifier.parse("minecraft:infiniburn_overworld"),
                            it.unimi.dsi.fastutil.ints.IntList.of(),
                            Identifier.parse("minecraft:supports_vegetation"),
                            it.unimi.dsi.fastutil.ints.IntList.of(
                                    BuiltInRegistries.BLOCK.getId(Blocks.GRASS_BLOCK)))))));

            var geometryTags = IsolatedMinecraft.tagsFor(player);
            var actionTags = IsolatedMinecraft.actionsFor(player).tagsFor(player);
            assertSame(geometryTags, IsolatedMinecraft.tagsFor(player));
            assertSame(actionTags, IsolatedMinecraft.actionsFor(player).tagsFor(player));

            place(fixture, support);
            assertTrue(world.getBlockStateAt(placed).isAir());
            assertTrue(world.getBlockStateAt(placed.above()).isAir());
            assertEquals(1, player.getInventory().getHeldItem().getCount());

            // The native backing model would consume and place this flower. Changing
            // the client's negotiated version selects that model, using the same tags.
            version.set(player, ClientVersion.fromProtocolVersion(777));
            assertEquals(
                    RuntimeModel.JAVA_26_3,
                    IsolatedMinecraft.actionsFor(player).runtime().model());
            place(fixture, support);
            assertEquals(Blocks.SUNFLOWER, world.getBlockStateAt(placed).getBlock());
            assertEquals(Blocks.SUNFLOWER, world.getBlockStateAt(placed.above()).getBlock());
            assertTrue(player.getInventory().getHeldItem().isEmpty());
        }
    }

    private static void place(RecordReceiveFixture fixture, BlockPos support) {
        var player = fixture.player;
        var stack = new ItemStack(Items.SUNFLOWER);
        player.getInventory().inventory.setHeldItem(stack);
        var place = new BlockPlace(player, InteractionHand.MAIN_HAND, support, Direction.UP, stack, null);
        place.setCursor(new Vec3(.5, 1, .5));
        VanillaBlockActions.useOn(player, place);
    }
}
