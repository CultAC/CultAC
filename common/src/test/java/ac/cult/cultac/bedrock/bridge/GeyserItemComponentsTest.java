package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import com.google.common.hash.Hashing;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.NbtOps;
import org.geysermc.geyser.item.hashing.DataComponentHashers;
import org.geysermc.geyser.item.hashing.MinecraftHashEncoder;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.UseEffects;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class GeyserItemComponentsTest {
    @BeforeClass public static void bootstrap() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        var geyser = org.mockito.Mockito.mock(org.geysermc.geyser.GeyserImpl.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        org.mockito.Mockito.when(geyser.packDirectory()).thenReturn(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")));
        var instance = org.geysermc.geyser.GeyserImpl.class.getDeclaredField("instance");
        instance.setAccessible(true);
        Object previous = instance.get(null);
        instance.set(null, geyser);
        try { Class.forName("org.geysermc.geyser.item.hashing.DataComponentHashers"); }
        finally { instance.set(null, previous); }
    }

    @Test public void scalarHashRetainsVanillaTypeAndByteOrder() {
        var encoder = new MinecraftHashEncoder(null);
        byte[] expectedBytes = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).put((byte) 8).putInt(123456).array();
        assertEquals(Hashing.crc32c().hashBytes(expectedBytes), encoder.number(123456));
    }

    @Test public void componentSchemaPreservesUseEffectsInsteadOfOnlyItemTypeAndCount() {
        var components = new org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents(new java.util.HashMap<>());
        components.put(DataComponentTypes.USE_EFFECTS, new UseEffects(true, false, 0.35f));
        var component = components.getDataComponents().get(DataComponentTypes.USE_EFFECTS);
        Object value = GeyserComponentValues.serialize(null, component);
        var parsed = DataComponents.USE_EFFECTS.codec().parse(NbtOps.INSTANCE, GeyserItemStacks.tag(value)).getOrThrow();
        assertTrue(parsed.canSprint());
        assertFalse(parsed.interactVibrations());
        assertEquals(0.35f, parsed.speedMultiplier(), 0);
        var hash = DataComponents.USE_EFFECTS.codec().encodeStart(net.minecraft.util.HashOps.CRC32C_INSTANCE, parsed).getOrThrow();
        assertEquals(DataComponentHashers.hash(null, component), hash);
    }

    @Test public void capturedArraysAndNestedValuesAreDetached() {
        var encoder = new GeyserComponentValues(null);
        int[] values = {4, 8};
        var map = encoder.map(Map.of(encoder.string("indices"), encoder.intArray(values)));
        values[0] = 99;
        var tag = (net.minecraft.nbt.CompoundTag) GeyserItemStacks.tag(encoder.value(map));
        assertArrayEquals(new int[]{4, 8}, tag.getIntArray("indices").orElseThrow());
    }
}
