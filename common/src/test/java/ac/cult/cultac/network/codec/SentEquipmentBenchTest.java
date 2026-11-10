package ac.cult.cultac.network.codec;

import ac.cult.blocksim.data.nbt.CanonicalSnbt;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.protocol.ProtocolCodecs;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.EquipmentSlot;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.inventory.ItemUtil;
import com.sun.management.ThreadMXBean;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Equipment decode cost per packet, stacks converted at decode versus on read. Runs only with CULT_ITEMS_BENCH set;
 * CULT_BENCH_OUT names an output file. Each packet carries six stacks: half from a small kit set the cache keeps, half
 * from a rotation of distinct worn pieces it cannot keep, near the hit rate measured on live servers.
 */
class SentEquipmentBenchTest {
    private static final ProtocolVersion VERSION = ProtocolVersion.of(774);
    private static final int PACKETS = 4_000, LOOPS = 10, WARMUP = 8, REPS = 11;

    private final WireRegistryState names = SentStackDeferralTest.registries();
    private final ModelItemValues plain =
            new ModelItemValues(ProtocolCodecs.decoder(), VERSION, names, ItemUtil.modelItems());

    private byte[] bytes(SimItemStack stack) {
        ByteBuf output = Unpooled.buffer();
        plain.write(output, stack);
        return ByteBufUtil.getBytes(output);
    }

    private static SimItemStack stack(String item, String... components) {
        var stack = ItemUtil.modelItems().stack(item, 1);
        for (int index = 0; index < components.length; index += 2)
            stack.setComponent(components[index], CanonicalSnbt.parse(components[index + 1]));
        return stack;
    }

    private List<byte[]> packets() {
        String[] kit = {
            "minecraft:diamond_sword", "minecraft:shield", "minecraft:diamond_boots",
            "minecraft:diamond_leggings", "minecraft:diamond_chestplate", "minecraft:diamond_helmet"
        };
        var kept = new ArrayList<byte[]>();
        for (String item : kit)
            kept.add(bytes(stack(item, "minecraft:enchantments", "{'minecraft:unbreaking':3}")));
        var worn = new ArrayList<byte[]>();
        for (int piece = 0; piece < 600; piece++) {
            String item = kit[piece % kit.length];
            worn.add(bytes(stack(
                    item,
                    "minecraft:damage", Integer.toString(1 + piece),
                    "minecraft:enchantments",
                    "{'minecraft:protection':" + (1 + piece % 4) + ",'minecraft:unbreaking':3}",
                    "minecraft:repair_cost", "5",
                    "minecraft:custom_data", "{kit:'pvp',owner:" + piece + "}")));
        }
        var packets = new ArrayList<byte[]>();
        for (int packet = 0; packet < PACKETS; packet++) {
            ByteBuf output = Unpooled.buffer();
            Wire.writeVarInt(output, 100 + packet % 40);
            for (int slot = 0; slot < 6; slot++) {
                output.writeByte(slot | (slot < 5 ? 128 : 0));
                output.writeBytes(slot % 2 == 0 ? kept.get(slot) : worn.get((packet * 3 + slot / 2) % worn.size()));
            }
            packets.add(ByteBufUtil.getBytes(output));
        }
        return packets;
    }

    /** The decode as converted at decode: one stack per entry, entries copied into the packet. */
    private static InventoryPackets.Equipment eager(ModelItemValues values, ByteBuf input) {
        int id = Wire.readVarInt(input), slot;
        var entries = new ArrayList<InventoryPackets.EquipmentEntry>();
        do {
            slot = input.readUnsignedByte();
            entries.add(new InventoryPackets.EquipmentEntry(
                    EquipmentSlot.VALUES.get(slot & 127), values.sentItem(input)));
        } while ((slot & 128) != 0);
        return new InventoryPackets.Equipment(id, entries);
    }

    private interface Pass {
        long run(List<byte[]> packets);
    }

    @Test
    void equipment() throws IOException {
        Assumptions.assumeTrue(System.getenv("CULT_ITEMS_BENCH") != null);
        var threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        var packets = packets();
        var eagerCache = new ItemStackCache();
        var eagerValues = new ModelItemValues(
                ProtocolCodecs.decoder(), VERSION, names, ItemUtil.modelItems(), eagerCache, names::generation);
        var laterValues = new ModelInventoryValues(
                ProtocolCodecs.decoder(), VERSION, names, new ItemStackCache(), names::generation);
        Pass eager = list -> {
            long sink = 0;
            for (byte[] bytes : list) sink += eager(eagerValues, Unpooled.wrappedBuffer(bytes)).slots().size();
            return sink;
        };
        Pass unread = list -> {
            long sink = 0;
            for (byte[] bytes : list) {
                var packet = (InventoryPackets.Equipment)
                        laterValues.read(Unpooled.wrappedBuffer(bytes), "minecraft:set_equipment");
                sink += packet.slots().size();
            }
            return sink;
        };
        Pass read = list -> {
            long sink = 0;
            for (byte[] bytes : list) {
                var packet = (InventoryPackets.Equipment)
                        laterValues.read(Unpooled.wrappedBuffer(bytes), "minecraft:set_equipment");
                for (var entry : packet.slots()) sink += entry.item().count();
            }
            return sink;
        };
        String[] labels = {"eager", "deferred-unread", "deferred-all-read"};
        Pass[] passes = {eager, unread, read};
        long[][] bytes = new long[passes.length][REPS], nanos = new long[passes.length][REPS];
        long sink = 0;
        for (int rep = -WARMUP; rep < REPS; rep++) {
            for (int index = 0; index < passes.length; index++) {
                int pass = (index + Math.max(rep, 0)) % passes.length;
                long allocated = threads.getCurrentThreadAllocatedBytes();
                long cpu = threads.getCurrentThreadCpuTime();
                for (int loop = 0; loop < LOOPS; loop++) sink += passes[pass].run(packets);
                long cpuAfter = threads.getCurrentThreadCpuTime();
                long allocatedAfter = threads.getCurrentThreadAllocatedBytes();
                if (rep >= 0) {
                    bytes[pass][rep] = allocatedAfter - allocated;
                    nanos[pass][rep] = cpuAfter - cpu;
                }
            }
        }
        int hits = 0;
        var probeCache = new ItemStackCache();
        var probe = new ModelItemValues(
                ProtocolCodecs.decoder(), VERSION, names, ItemUtil.modelItems(), probeCache, names::generation);
        for (byte[] packet : packets) {
            var input = Unpooled.wrappedBuffer(packet);
            Wire.readVarInt(input);
            int slot;
            do {
                slot = input.readUnsignedByte();
                int start = input.readerIndex();
                while ((input.getUnsignedByte(start++) & 0x80) != 0) {}
                if (probeCache.find(input, start) >= 0) hits++;
                probe.sentItem(input);
            } while ((slot & 128) != 0);
        }
        var out = new StringBuilder();
        out.append(String.format("sent equipment, %d packets x 6 stacks per pass, cache hit rate %.3f, sink %d%n",
                PACKETS, hits / (PACKETS * 6.0), sink));
        for (int pass = 0; pass < passes.length; pass++) {
            long[] b = bytes[pass].clone(), n = nanos[pass].clone();
            Arrays.sort(b);
            Arrays.sort(n);
            out.append(String.format(
                    "%-18s bytes/packet median %8.1f (min %8.1f max %8.1f)"
                            + "  ns/packet median %8.1f (min %8.1f max %8.1f)%n",
                    labels[pass],
                    b[REPS / 2] / (double) (PACKETS * LOOPS), b[0] / (double) (PACKETS * LOOPS),
                    b[REPS - 1] / (double) (PACKETS * LOOPS), n[REPS / 2] / (double) (PACKETS * LOOPS),
                    n[0] / (double) (PACKETS * LOOPS), n[REPS - 1] / (double) (PACKETS * LOOPS)));
        }
        System.out.print(out);
        String file = System.getenv("CULT_BENCH_OUT");
        if (file != null) Files.writeString(Path.of(file), out);
    }
}
