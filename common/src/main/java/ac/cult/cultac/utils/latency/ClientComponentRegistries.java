package ac.cult.cultac.utils.latency;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.core.component.BlockTransformer;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Received component definitions; tag membership deliberately uses the server registry. */
public final class ClientComponentRegistries {
    private static final DataComponentType<Holder<BlockTransformer>> BLOCK_TRANSFORMER_COMPONENT = blockTransformerComponent();

    private final Map<ResourceKey<? extends Registry<?>>, List<RegistrySynchronization.PackedRegistryEntry>> entries = new HashMap<>();
    private RegistryAccess.Frozen received;

    public void append(ClientboundRegistryDataPacket packet) {
        if (isRelevant(packet.registry())) {
            entries.computeIfAbsent(packet.registry(), ignored -> new ArrayList<>()).addAll(packet.entries());
        }
    }

    public void finish() {
        if (entries.isEmpty()) return;
        Map<ResourceKey<? extends Registry<?>>, RegistryDataLoader.NetworkedRegistryData> network = new HashMap<>();
        entries.forEach((key, values) -> network.put(key, new RegistryDataLoader.NetworkedRegistryData(
                List.copyOf(values), TagNetworkSerialization.NetworkPayload.EMPTY)));
        var server = MinecraftServer.getServer();
        received = RegistryDataLoader.load(network, server.getResourceManager(),
                server.registryAccess().listRegistries().filter(lookup -> !entries.containsKey(lookup.key())).toList(),
                RegistryDataLoader.SYNCHRONIZED_REGISTRIES.stream().filter(data -> entries.containsKey(data.key())).toList(),
                Runnable::run).join();
        entries.clear();
    }

    public Holder<BlockTransformer> transformer(ItemStack stack) {
        if (BLOCK_TRANSFORMER_COMPONENT == null) return null;
        Holder<BlockTransformer> component = stack.get(BLOCK_TRANSFORMER_COMPONENT);
        if (component == null || received == null) return component;
        var registry = received.lookup(Registries.BLOCK_TRANSFORMER);
        if (registry.isEmpty()) return component;
        if (stack.getComponentsPatch().split().added().has(BLOCK_TRANSFORMER_COMPONENT)) {
            // Explicit item components are serialized by the connection registry ID.
            int id = MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.BLOCK_TRANSFORMER).getId(component.value());
            return registry.get().get(id).orElse(null);
        }
        // Default item components are initialized by key after configuration.
        return component.unwrapKey().flatMap(registry.get()::get).orElse(null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static DataComponentType<Holder<BlockTransformer>> blockTransformerComponent() {
        try {
            return (DataComponentType) DataComponents.class.getField("BLOCK_TRANSFORMER").get(null);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static boolean isRelevant(ResourceKey<? extends Registry<?>> key) {
        return key.equals(Registries.BLOCK_TRANSFORMER) || key.equals(Registries.BLOCK_STATE_PROVIDER);
    }
}
