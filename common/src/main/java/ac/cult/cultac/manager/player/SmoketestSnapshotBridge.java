package ac.cult.cultac.manager.player;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.network.protocol.util.SpigotConversionUtil;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.inventory.InventoryStorage;
import ac.cult.cultac.utils.nmsutil.NmsIdentifierUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class SmoketestSnapshotBridge {
    private static final String CHANNEL = "cult:smoketest_snapshot";
    private static final int MAGIC = 0x47534D31; // GSM1
    private static final int VERSION = 4;

    private SmoketestSnapshotBridge() {
    }

    static boolean handle(CultPlayer player, String channel, byte[] data) {
        if (!CHANNEL.equals(channel)) {
            return false;
        }
        if (data.length == 0) {
            return true;
        }

        Snapshot snapshot;
        try {
            snapshot = readSnapshot(data);
        } catch (IOException | RuntimeException exception) {
            LogUtil.warn("Cult smoketest snapshot failed player=" + player.getName()
                    + " reason=decode-error error=" + exception.getClass().getSimpleName()
                    + ":" + exception.getMessage());
            return true;
        }

        compare(player, snapshot);
        return true;
    }

    private static Snapshot readSnapshot(byte[] data) throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(data));
        int magic = input.readInt();
        if (magic != MAGIC) {
            throw new IOException("invalid magic " + magic);
        }

        int version = input.readInt();
        if (version < 1 || version > VERSION) {
            throw new IOException("unsupported version " + version);
        }

        int tick = input.readInt();
        String scenario = input.readUTF();
        int selectedSlot = input.readInt();
        if (version >= 3) {
            input.readBoolean(); // expectNoInventoryResync
        }

        int blockCount = checkedCount(input.readInt(), 2048, "blocks");
        List<BlockSnapshot> blocks = new ArrayList<>(blockCount);
        for (int i = 0; i < blockCount; i++) {
            blocks.add(new BlockSnapshot(
                    input.readInt(),
                    input.readInt(),
                    input.readInt(),
                    input.readInt(),
                    input.readUTF()
            ));
        }

        List<ItemSnapshot> inventory = version >= 4 ? readItems(input, "inventory") : List.of();
        List<ItemSnapshot> menu = version >= 4 ? readItems(input, "menu") : List.of();
        ItemSnapshot carried = version >= 4 ? readItem(input) : ItemSnapshot.UNCHANGED;
        return new Snapshot(tick, scenario, selectedSlot, blocks, inventory, menu, carried);
    }

    private static List<ItemSnapshot> readItems(DataInputStream input, String name) throws IOException {
        int count = checkedCount(input.readInt(), 256, name);
        List<ItemSnapshot> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) items.add(readItem(input));
        return items;
    }

    private static ItemSnapshot readItem(DataInputStream input) throws IOException {
        return new ItemSnapshot(input.readInt(), input.readUTF(), input.readInt(), input.readUTF());
    }

    private static int checkedCount(int count, int max, String name) throws IOException {
        if (count < 0 || count > max) {
            throw new IOException(name + " count out of range: " + count);
        }
        return count;
    }

    private static void compare(CultPlayer player, Snapshot snapshot) {
        List<String> mismatches = new ArrayList<>();
        for (BlockSnapshot block : snapshot.blocks()) {
            var compensatedState = player.compensatedWorld.getBlockStateAt(new BlockPos(block.x(), block.y(), block.z()));
            int cultStateId = Block.getId(compensatedState);
            String cultState = String.valueOf(compensatedState);
            // Numeric state IDs belong to each protocol's registry and are translated by ViaVersion.
            // The namespaced block plus its complete property set is the stable semantic identity.
            if (!block.state().equals(cultState)) {
                mismatches.add("block " + block.x() + "," + block.y() + "," + block.z()
                        + " expectedId=" + block.stateId()
                        + " cultId=" + cultStateId
                        + " expected=" + block.state()
                        + " cult=" + cultState);
            }
        }
        if (snapshot.selectedSlot() >= 0 && player.packetStateData.lastSlotSelected != snapshot.selectedSlot()) {
            mismatches.add("selected-slot expected=" + snapshot.selectedSlot()
                    + " cult=" + player.packetStateData.lastSlotSelected);
        }

        InventoryStorage storage = player.getInventory().inventory.getInventoryStorage();
        for (ItemSnapshot item : snapshot.inventory()) {
            compareItem(mismatches, "inventory", item, storage.getItem(item.slot()));
        }
        for (ItemSnapshot item : snapshot.menu()) {
            var slot = player.getInventory().menu.getSlot(item.slot());
            compareItem(mismatches, "menu", item, slot == null ? null : slot.getItem());
        }
        if (snapshot.carried().slot() == -1) {
            compareItem(mismatches, "carried", snapshot.carried(), player.getInventory().menu.getCarried());
        }

        if (mismatches.isEmpty()) {
            if (snapshot.tick() != 0
                    && snapshot.blocks().isEmpty()
                    && !snapshot.scenario().startsWith("inventory")) {
                return;
            }
            LogUtil.info("Cult smoketest snapshot passed player=" + player.getName()
                    + " tick=" + snapshot.tick()
                    + " scenario=" + snapshot.scenario()
                    + " blocks=" + snapshot.blocks().size()
                    + " items=" + (snapshot.inventory().size() + snapshot.menu().size()));
            return;
        }

        LogUtil.warn("Cult smoketest snapshot failed player=" + player.getName()
                + " tick=" + snapshot.tick()
                + " scenario=" + snapshot.scenario()
                + " mismatches=" + mismatches.size()
                + " details=" + String.join(" | ", mismatches.subList(0, Math.min(12, mismatches.size()))));
    }

    private static void compareItem(List<String> mismatches, String owner, ItemSnapshot expected, org.bukkit.inventory.ItemStack item) {
        net.minecraft.world.item.ItemStack cult = SpigotConversionUtil.toNmsItemStack(item);
        String key = cult.isEmpty() ? "minecraft:air" : NmsIdentifierUtil.registryKey(BuiltInRegistries.ITEM, cult.getItem());
        int count = cult.isEmpty() ? 0 : cult.getCount();
        String components = cult.isEmpty() ? "" : String.valueOf(cult.getComponentsPatch());
        if (!expected.key().equals(key)
                || expected.count() != count
                || !canonicalComponents(expected.components()).equals(canonicalComponents(components))) {
            mismatches.add(owner + " slot=" + expected.slot()
                    + " expected=" + expected.key() + "x" + expected.count() + expected.components()
                    + " cult=" + key + "x" + count + components);
        }
    }

    static String canonicalComponents(String value) {
        if (value == null || value.indexOf('{') < 0) {
            return value == null ? "" : value;
        }

        StringBuilder result = new StringBuilder(value.length());
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '"' && !escaped) quoted = !quoted;
            if (current != '{' || quoted) {
                result.append(current);
                escaped = current == '\\' && !escaped;
                continue;
            }

            int end = matchingBrace(value, index);
            if (end < 0) {
                return value;
            }
            String inner = canonicalComponents(value.substring(index + 1, end));
            List<String> entries = splitTopLevel(inner);
            entries.sort(String::compareTo);
            result.append('{').append(String.join(", ", entries)).append('}');
            index = end;
            escaped = false;
        }
        return result.toString();
    }

    private static int matchingBrace(String value, int start) {
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int index = start; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '"' && !escaped) quoted = !quoted;
            if (!quoted && current == '{') depth++;
            if (!quoted && current == '}' && --depth == 0) return index;
            escaped = current == '\\' && !escaped;
        }
        return -1;
    }

    private static List<String> splitTopLevel(String value) {
        List<String> entries = new ArrayList<>();
        int start = 0;
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < value.length() - 1; index++) {
            char current = value.charAt(index);
            if (current == '"' && !escaped) quoted = !quoted;
            if (!quoted && (current == '{' || current == '[' || current == '(')) depth++;
            if (!quoted && (current == '}' || current == ']' || current == ')')) depth--;
            if (!quoted && depth == 0 && current == ',' && value.charAt(index + 1) == ' ') {
                entries.add(value.substring(start, index));
                start = index + 2;
            }
            escaped = current == '\\' && !escaped;
        }
        entries.add(value.substring(start));
        return entries;
    }

    private record Snapshot(
            int tick,
            String scenario,
            int selectedSlot,
            List<BlockSnapshot> blocks,
            List<ItemSnapshot> inventory,
            List<ItemSnapshot> menu,
            ItemSnapshot carried
    ) {
    }

    private record BlockSnapshot(int x, int y, int z, int stateId, String state) {
    }

    private record ItemSnapshot(int slot, String key, int count, String components) {
        private static final ItemSnapshot UNCHANGED = new ItemSnapshot(-2, "minecraft:air", 0, "");
    }
}
