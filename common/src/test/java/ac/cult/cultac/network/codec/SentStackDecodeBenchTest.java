package ac.cult.cultac.network.codec;

import ac.cult.blocksim.data.nbt.BinaryNbt;
import ac.cult.blocksim.data.nbt.CanonicalSnbt;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.protocol.ProtocolCodecs;
import ac.cult.cultac.protocol.ProtocolVersion;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Cost of one sent stack the connection's cache does not hold: decode, conversion, retained size and the held copy.
 * Runs only with CULT_ITEMS_BENCH set; CULT_BENCH_OUT names an output file.
 */
class SentStackDecodeBenchTest {
    private static final ProtocolVersion VERSION = ProtocolVersion.of(774);
    private static final int LOOPS = 40, WARMUP = 10, REPS = 11;

    private final WireRegistryState names = SentStackDeferralTest.registries();
    private final ModelItemValues plain =
            new ModelItemValues(ProtocolCodecs.decoder(), VERSION, names, ItemUtil.modelItems());

    private static SimItemStack stack(String item, int count, String... components) {
        var stack = ItemUtil.modelItems().stack(item, count);
        for (int index = 0; index < components.length; index += 2)
            stack.setComponent(components[index], CanonicalSnbt.parse(components[index + 1]));
        return stack;
    }

    private byte[] bytes(SimItemStack stack) {
        ByteBuf output = Unpooled.buffer();
        plain.write(output, stack);
        return ByteBufUtil.getBytes(output);
    }

    /** {@code stack}'s wire bytes with a custom name and lore lines appended, as servers send menu entries. */
    private byte[] named(SimItemStack stack, String name, List<String> lore) {
        var input = Unpooled.wrappedBuffer(bytes(stack));
        int count = Wire.readVarInt(input), id = Wire.readVarInt(input);
        int added = Wire.readVarInt(input), removed = Wire.readVarInt(input);
        if (removed != 0) throw new IllegalStateException("removed components");
        ByteBuf output = Unpooled.buffer();
        Wire.writeVarInt(output, count);
        Wire.writeVarInt(output, id);
        Wire.writeVarInt(output, added + 1 + (lore.isEmpty() ? 0 : 1));
        Wire.writeVarInt(output, 0);
        output.writeBytes(input);
        Wire.writeVarInt(output, names.id("minecraft:data_component_type", "minecraft:custom_name"));
        output.writeBytes(BinaryNbt.write(CanonicalSnbt.parse(name)));
        if (!lore.isEmpty()) {
            Wire.writeVarInt(output, names.id("minecraft:data_component_type", "minecraft:lore"));
            Wire.writeVarInt(output, lore.size());
            for (String line : lore) output.writeBytes(BinaryNbt.write(CanonicalSnbt.parse(line)));
        }
        return ByteBufUtil.getBytes(output);
    }

    private static String line(String label, String value) {
        return "{text:'',italic:0b,extra:[{text:'" + label + " ',color:'gray'},{text:'" + value + "',color:'yellow'}]}";
    }

    private Map<String, List<byte[]>> groups() {
        var groups = new LinkedHashMap<String, List<byte[]>>();
        var menu = new ArrayList<byte[]>();
        String[] items = {"minecraft:diamond_sword", "minecraft:emerald", "minecraft:book", "minecraft:clock",
            "minecraft:chest", "minecraft:beacon", "minecraft:paper", "minecraft:anvil"};
        for (int entry = 0; entry < 48; entry++) {
            var lore = new ArrayList<String>();
            lore.add("{text:'A menu entry the server describes',color:'dark_gray',italic:0b}");
            for (int property = 0; property < 2 + entry % 6; property++)
                lore.add(line("Property " + property + ":", "value " + (entry * 7 + property)));
            lore.add("{text:'',color:'white',italic:0b}");
            lore.add("{text:'Click to open',color:'green',bold:1b,italic:0b}");
            var base = entry % 3 == 0
                    ? stack(items[entry % items.length], 1, "minecraft:custom_data", "{menu:'main',entry:" + entry + "}",
                            "minecraft:enchantment_glint_override", "1b")
                    : stack(items[entry % items.length], 1, "minecraft:custom_data", "{menu:'main',entry:" + entry + "}");
            menu.add(named(base, "{text:'Entry " + entry + "',color:'gold',bold:1b,italic:0b}", lore));
        }
        groups.put("menu entries (name, 4-9 lore lines, custom data)", menu);
        var heads = new ArrayList<byte[]>();
        for (int head = 0; head < 24; head++)
            heads.add(named(
                    stack("minecraft:player_head", 1, "minecraft:custom_data",
                            "{skin:'eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUv"
                                    + head + "'}"),
                    "{text:'Player" + head + "',color:'aqua',italic:0b}",
                    List.of(line("Rank:", "Member"), line("Wins:", Integer.toString(head * 13)))));
        groups.put("heads (name, 2 lore lines, skin data)", heads);
        var panes = new ArrayList<byte[]>();
        for (int pane = 0; pane < 16; pane++)
            panes.add(named(stack(pane % 2 == 0 ? "minecraft:black_stained_glass_pane" : "minecraft:gray_stained_glass_pane",
                    1 + pane), "{text:' ',italic:0b}", List.of()));
        groups.put("fillers (blank name)", panes);
        var gear = new ArrayList<byte[]>();
        for (int piece = 0; piece < 48; piece++) {
            String[] armor = {"minecraft:diamond_boots", "minecraft:diamond_leggings", "minecraft:diamond_chestplate",
                "minecraft:diamond_helmet", "minecraft:diamond_sword", "minecraft:bow"};
            gear.add(bytes(stack(armor[piece % armor.length], 1,
                    "minecraft:damage", Integer.toString(1 + piece),
                    "minecraft:enchantments", "{'minecraft:protection':" + (1 + piece % 4) + ",'minecraft:unbreaking':3}",
                        "minecraft:repair_cost", "3")));
        }
        groups.put("gear (damage, enchantments, repair cost)", gear);
        var kit = new ArrayList<byte[]>();
        for (var stack : SentStackDeferralTest.stacks()) kit.add(bytes(stack));
        groups.put("kit stacks (deferral fixtures)", kit);
        return groups;
    }

    @Test
    void missedStacks() throws IOException {
        Assumptions.assumeTrue(System.getenv("CULT_ITEMS_BENCH") != null);
        var threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        var groups = groups();
        var cache = new ItemStackCache();
        var values = new ModelItemValues(
                ProtocolCodecs.decoder(), VERSION, names, ItemUtil.modelItems(), cache, names::generation);
        var labels = new ArrayList<>(groups.keySet());
        long[][] bytes = new long[labels.size()][REPS], nanos = new long[labels.size()][REPS];
        long sink = 0;
        for (int rep = -WARMUP; rep < REPS; rep++) {
            for (int index = 0; index < labels.size(); index++) {
                int group = (index + Math.max(rep, 0)) % labels.size();
                var stacks = groups.get(labels.get(group));
                long allocated = threads.getCurrentThreadAllocatedBytes(), cpu = threads.getCurrentThreadCpuTime();
                for (int loop = 0; loop < LOOPS; loop++)
                    for (byte[] stack : stacks) {
                        cache.clear();
                        sink += values.sentItem(Unpooled.wrappedBuffer(stack)).count();
                    }
                long cpuAfter = threads.getCurrentThreadCpuTime(), allocatedAfter = threads.getCurrentThreadAllocatedBytes();
                if (rep >= 0) {
                    bytes[group][rep] = allocatedAfter - allocated;
                    nanos[group][rep] = cpuAfter - cpu;
                }
            }
        }
        var out = new StringBuilder(String.format("missed sent stacks, %d loops per pass, sink %d%n", LOOPS, sink));
        for (int group = 0; group < labels.size(); group++) {
            var stacks = groups.get(labels.get(group));
            double per = LOOPS * (double) stacks.size();
            long[] b = bytes[group].clone(), n = nanos[group].clone();
            Arrays.sort(b);
            Arrays.sort(n);
            out.append(String.format(
                    "%-52s wire %6.1f B  bytes/stack median %8.1f (min %8.1f max %8.1f)  ns/stack median %8.1f (min %8.1f max %8.1f)%n",
                    labels.get(group), stacks.stream().mapToInt(s -> s.length).average().orElse(0),
                    b[REPS / 2] / per, b[0] / per, b[REPS - 1] / per, n[REPS / 2] / per, n[0] / per, n[REPS - 1] / per));
        }
        System.out.print(out);
        String file = System.getenv("CULT_BENCH_OUT");
        if (file != null) Files.writeString(Path.of(file), out);
    }
}
