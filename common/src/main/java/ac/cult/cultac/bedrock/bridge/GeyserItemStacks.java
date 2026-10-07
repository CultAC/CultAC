package ac.cult.cultac.bedrock.bridge;

import ac.cult.blocksim.data.ItemRegistry;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.network.codec.ModelItemValues;
import ac.cult.cultac.protocol.ProtocolCodecs;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WireValueDecoder;
import ac.cult.cultac.protocol.data.IdTable;
import ac.cult.cultac.protocol.data.ModelRegistryData;
import io.netty.buffer.Unpooled;
import java.util.HashMap;
import java.util.Map;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.session.cache.RegistryCache;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

/** Stock Geyser values enter the same owned item decoder as Java inventory packets. */
final class GeyserItemStacks {
    private GeyserItemStacks() {}

    static SimItemStack toModelItem(GeyserSession session, ItemStack source, ItemRegistry items) {
        if (source == null || source.getAmount() <= 0) return SimItemStack.EMPTY;
        var mapping = session.getItemMappings().getMapping(source.getId()).getJavaItem();
        if (mapping.javaId() != source.getId()) throw new IllegalArgumentException("Unknown item " + source.getId());
        var result = decode(source, names(session), items);
        if (!result.itemKey().equals(mapping.javaIdentifier()))
            throw new IllegalArgumentException("Geyser item mapping differs from its protocol " + version() + ": "
                    + source.getId() + " " + mapping.javaIdentifier() + " != " + result.itemKey());
        return result;
    }

    static ProtocolVersion version() {
        return ProtocolVersion.of(MinecraftCodec.CODEC.getProtocolVersion());
    }

    static SimItemStack decode(ItemStack source, WireValueDecoder.Registries names, ItemRegistry items) {
        var buffer = Unpooled.buffer();
        try {
            // MCProtocolLib writes its own schema, including nested templates and removals.
            // Via's versioned reader resolves that schema into the fixed 26.3 model.
            MinecraftTypes.writeOptionalItemStack(buffer, source);
            var result = new ModelItemValues(ProtocolCodecs.decoder(), version(), names, items).item(buffer, false);
            if (buffer.isReadable()) throw new IllegalStateException("Geyser item capture left unread bytes");
            return result;
        } finally {
            buffer.release();
        }
    }

    private static WireValueDecoder.Registries names(GeyserSession session) {
        var defaults = ModelRegistryData.load(version());
        var received = new HashMap<String, IdTable>();
        for (var key : RegistryCache.READERS.keySet()) {
            // Geyser-Spigot relocates Adventure; only strings cross that boundary.
            String name;
            try {
                name = key.getClass().getMethod("registryKey").invoke(key).toString();
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot read Geyser registry identifier", failure);
            }
            var entries = session.getRegistryCache().registry(key).keys();
            received.put(
                    name,
                    new IdTable(name, entries.stream().map(Object::toString).toList()));
        }
        var snapshot = Map.copyOf(received);
        return new WireValueDecoder.Registries() {
            private IdTable table(String name) {
                var table = snapshot.get(name);
                return table == null ? defaults.registry(name) : table;
            }

            @Override
            public String name(String registry, int id) {
                return table(registry).name(id);
            }

            @Override
            public int id(String registry, String name) {
                return table(registry).id(name);
            }

            @Override
            public boolean preserveIdentifiers() {
                return true;
            }
        };
    }
}
