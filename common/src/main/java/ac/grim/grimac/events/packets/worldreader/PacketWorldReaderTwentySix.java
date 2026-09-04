package ac.grim.grimac.events.packets.worldreader;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.network.event.PacketSendEvent;
import ac.grim.grimac.utils.latency.CompensatedWorld.ClientboundDimensionData;
import ac.grim.grimac.utils.latency.CompensatedWorld.CachedSection;
import ac.grim.grimac.utils.latency.CompensatedGeysers;
import ac.grim.grimac.utils.latency.CompensatedWorld.CachedChunk;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftServer;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class PacketWorldReaderTwentySix extends BasePacketWorldReader {
    // Mojang includes lighting with the chunk packet. Decode only the chunk payload and ignore the light payload here.
    @Override
    public void handleMapChunk(GrimPlayer player, PacketSendEvent event, ClientboundLevelChunkWithLightPacket packet) {
        ClientboundDimensionData dimensionData = player.compensatedWorld.getLastClientboundDimension();
        FriendlyByteBuf chunkData = packet.getChunkData().getReadBuffer();

        CachedSection[] chunks = new CachedSection[dimensionData.sectionCount()];
        try {
            for (int i = 0; i < chunks.length; i++) {
                LevelChunkSection section = createSection();
                section.read(chunkData);
                chunks[i] = new CachedSection(section.getStates().copy());
            }
        } finally {
            chunkData.release();
        }

        List<BlockPos> geyserTickers = new ArrayList<>();
        packet.getChunkData().getBlockEntitiesTagsConsumer(packet.getX(), packet.getZ()).accept((position, type, tag) -> {
            if ("potent_sulfur".equals(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type).getPath())
                    && hasGeyserTicker(chunks, dimensionData.minHeight(), position)) {
                geyserTickers.add(position.immutable());
            }
        });

        addChunkToCache(event, player, chunks, true, dimensionData.dimension(), packet.getX(), packet.getZ(), geyserTickers);
    }

    private static boolean hasGeyserTicker(CachedSection[] sections, int minHeight, BlockPos position) {
        int offsetY = position.getY() - minHeight;
        int sectionIndex = offsetY >> 4;
        if (offsetY < 0 || sectionIndex >= sections.length || sections[sectionIndex] == null) {
            return false;
        }
        return CompensatedGeysers.hasTicker(sections[sectionIndex].getState(
                CachedChunk.index(position.getX() & 0xF, offsetY & 0xF, position.getZ() & 0xF)));
    }

    private static LevelChunkSection createSection() {
        RegistryAccess access = ((CraftServer) Bukkit.getServer()).getServer().registryAccess();
        try {
            Class<?> factoryClass = Class.forName("net.minecraft.world.level.chunk.PalettedContainerFactory");
            Object factory = factoryClass.getMethod("create", RegistryAccess.class).invoke(null, access);
            Constructor<LevelChunkSection> constructor = LevelChunkSection.class.getConstructor(factoryClass);
            return constructor.newInstance(factory);
        } catch (ClassNotFoundException ignored) {
            Object biomeRegistry = lookupRegistry(access, Registries.BIOME);
            try {
                Constructor<?> constructor = LevelChunkSection.class.getConstructor(net.minecraft.core.Registry.class);
                return (LevelChunkSection) constructor.newInstance(biomeRegistry);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Unable to construct legacy chunk section", unwrap(exception));
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to construct chunk section", unwrap(exception));
        }
    }

    private static Object lookupRegistry(RegistryAccess access, Object key) {
        for (String methodName : new String[]{"registryOrThrow", "lookupOrThrow"}) {
            for (Method method : RegistryAccess.class.getMethods()) {
                if (!method.getName().equals(methodName)
                        || method.getParameterCount() != 1
                        || !net.minecraft.core.Registry.class.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
                try {
                    return method.invoke(access, key);
                } catch (ReflectiveOperationException exception) {
                    throw new IllegalStateException("Unable to resolve biome registry", unwrap(exception));
                }
            }
        }
        throw new IllegalStateException("No supported registry lookup accessor");
    }

    private static Throwable unwrap(ReflectiveOperationException exception) {
        return exception instanceof InvocationTargetException invocationTargetException
                ? invocationTargetException.getTargetException()
                : exception;
    }
}
