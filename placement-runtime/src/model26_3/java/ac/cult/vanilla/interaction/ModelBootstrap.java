package ac.cult.vanilla.interaction;

import java.util.Objects;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;

/** API differences of the original official 26.3 model. */
final class ModelBootstrap {
    private ModelBootstrap() {}

    static void checkVersion() {
        if (SharedConstants.getProtocolVersion() != 777
                || !SharedConstants.getCurrentVersion().id().equals("26.3"))
            throw new IllegalStateException("Expected official Java 26.3");
    }

    static PackResources resources() {
        return ServerPacksSource.createVanillaPackSource().fullResources();
    }

    static RegistryAccess.Frozen loadRegistries(ResourceManager resources, RegistryAccess base, boolean narrowed) {
        return RegistryDataLoader.load(
                        resources,
                        base.listRegistries().toList(),
                        RegistryDataLoader.SYNCHRONIZED_REGISTRIES.stream()
                                .filter(registry -> !narrowed
                                        || InteractionBindings.MODEL_REGISTRIES.contains(
                                                registry.key().identifier().toString()))
                                .toList(),
                        Runnable::run)
                .join();
    }

    static void initializeComponents(RegistryAccess registries) {
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries).forEach(pending -> pending.apply());
    }

    static net.minecraft.core.component.DataComponentPatch decodeComponents(
            com.google.gson.JsonElement input, RegistryAccess registries) {
        return TransformerComponents.decode(input, registries);
    }

    static com.google.gson.JsonElement encodeComponents(
            net.minecraft.core.component.DataComponentPatch patch, RegistryAccess registries) {
        return TransformerComponents.encode(patch, registries);
    }

    static void afterUseOn(InteractionPlayer player, InteractionHand hand, ItemStack before, InteractionResult result) {
        if (result instanceof InteractionResult.Success success) {
            var after =
                    Objects.requireNonNullElseGet(success.heldItemTransformedTo(), () -> player.getItemInHand(hand));
            if (after != before) player.setItemInHand(hand, after);
        }
    }
}
