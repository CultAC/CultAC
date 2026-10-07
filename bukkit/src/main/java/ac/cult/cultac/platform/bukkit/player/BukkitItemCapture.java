package ac.cult.cultac.platform.bukkit.player;

import ac.cult.blocksim.data.ComponentPatch;
import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.network.codec.ModelItemValues;
import ac.cult.cultac.platform.bukkit.BukkitRegistryNames;
import ac.cult.cultac.platform.bukkit.NmsIdentifierUtil;
import ac.cult.cultac.protocol.ProtocolCodecs;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WireValueDecoder;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.inventory.ItemUtil;
import io.netty.buffer.Unpooled;
import java.util.HashSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Host inventory values enter the owned packet decoder at the Bukkit boundary. */
public final class BukkitItemCapture {
    private final RegistryAccess access;
    private final ProtocolVersion version;
    private final WireValueDecoder decoder = ProtocolCodecs.decoder();
    private final WireValueDecoder.Registries names;

    public BukkitItemCapture(RegistryAccess access, ProtocolVersion version) {
        this.access = access;
        this.version = version;
        var snapshot = BukkitRegistryNames.read(access);
        names = new WireValueDecoder.Registries() {
            @Override
            public String name(String registry, int id) {
                return snapshot.name(registry, id);
            }

            @Override
            public int id(String registry, String name) {
                return snapshot.id(registry, name);
            }
        };
    }

    public SimItemStack stack(ItemStack stack) {
        var items = ItemUtil.modelItems();
        if (stack.isEmpty()) return items.empty();
        String key = NmsIdentifierUtil.registryKey(BuiltInRegistries.ITEM, stack.getItem());
        var split = stack.getComponentsPatch().split();
        var removed = new HashSet<String>();
        split.removed()
                .forEach(type ->
                        removed.add(NmsIdentifierUtil.registryKey(BuiltInRegistries.DATA_COMPONENT_TYPE, type)));
        // A stack keeps the prototype captured when it was constructed. The holder's
        // current defaults can differ; expose the captured map through a private copy.
        var prototype = ((PatchedDataComponentMap) stack.getComponents()).copy();
        prototype.clearPatch();
        return new SimItemStack(
                items.item(key),
                stack.getCount(),
                capture(prototype),
                new ComponentPatch(capture(split.added()), removed),
                () -> items.defaults(key));
    }

    private Components capture(DataComponentMap components) {
        if (components.isEmpty()) return Components.EMPTY;
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), access);
        try {
            Wire.writeVarInt(buffer, 1);
            Wire.writeVarInt(buffer, BuiltInRegistries.ITEM.getId(Items.STONE));
            DataComponentPatch.STREAM_CODEC.encode(
                    buffer, DataComponentPatch.builder().set(components).build());
            var value = decoder.item(version, buffer, false, names);
            if (buffer.isReadable()) throw new IllegalStateException("Unread host component bytes");
            return ModelItemValues.patch(value).added();
        } finally {
            buffer.release();
        }
    }
}
