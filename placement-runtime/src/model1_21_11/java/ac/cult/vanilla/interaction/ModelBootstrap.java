package ac.cult.vanilla.interaction;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;

/** API differences of Mojang's original 1.21.11_unobfuscated artifact. */
final class ModelBootstrap {
    private ModelBootstrap() {}

    static void checkVersion() {
        if (SharedConstants.getProtocolVersion() != 774
                || !SharedConstants.getCurrentVersion().id().equals("1.21.11_unobfuscated"))
            throw new IllegalStateException("Expected official Java 1.21.11_unobfuscated");
    }

    static PackResources resources() {
        return ServerPacksSource.createVanillaPackSource();
    }

    static RegistryAccess.Frozen loadRegistries(ResourceManager resources, RegistryAccess base, boolean narrowed) {
        if (narrowed) throw new IllegalArgumentException("1.21.11 does not use the 26.3 narrowing profile");
        return RegistryDataLoader.load(
                resources, base.listRegistries().toList(), RegistryDataLoader.SYNCHRONIZED_REGISTRIES);
    }

    static void initializeComponents(RegistryAccess registries) {
        // 1.21.11 builds item defaults during BuiltInRegistries.bootstrap, before data registries load.
    }

    static net.minecraft.core.component.DataComponentPatch decodeComponents(
            com.google.gson.JsonElement input, RegistryAccess registries) {
        return InteractionItems.nativeDecode(input, registries);
    }

    static com.google.gson.JsonElement encodeComponents(
            net.minecraft.core.component.DataComponentPatch patch, RegistryAccess registries) {
        return InteractionItems.nativeEncode(patch, registries);
    }

    static void afterUseOn(InteractionPlayer player, InteractionHand hand, ItemStack before, InteractionResult result) {
        // 1.21.11 MultiPlayerGameMode.performUseItemOn returns the result directly.
    }
}
