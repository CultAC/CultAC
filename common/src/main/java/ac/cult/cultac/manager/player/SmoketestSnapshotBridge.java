package ac.cult.cultac.manager.player;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.inventory.InventoryStorage;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class SmoketestSnapshotBridge {
    private static final String CHANNEL = "cult:smoketest_snapshot";
    private static final int MAGIC = 0x47534D31; // GSM1
    private static final int VERSION = 5;
    private static final Pattern RANDOM_INITIAL_AGE =
            Pattern.compile("(?<=[\\[,])age=(?:[0-9]|1[0-9]|2[0-4])(?=[,\\]])");

    private SmoketestSnapshotBridge() {}

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
        // RC1 transformer components can make an unchanged snapshot exceed the
        // vanilla custom-payload limit. Compression preserves every comparison.
        if (data.length >= 2 && data[0] == (byte) 0x1f && data[1] == (byte) 0x8b) {
            try (var compressed = new java.util.zip.GZIPInputStream(new ByteArrayInputStream(data))) {
                data = compressed.readNBytes(1024 * 1024 + 1);
                if (data.length > 1024 * 1024) throw new IOException("snapshot exceeds 1 MiB");
            }
        }
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(data));
        int magic = input.readInt();
        if (magic != MAGIC) {
            throw new IOException("invalid magic " + magic);
        }

        int version = input.readInt();
        if (version != VERSION) {
            throw new IOException("unsupported version " + version);
        }

        int tick = input.readInt();
        String scenario = input.readUTF();
        int selectedSlot = input.readInt();
        input.readBoolean(); // expectNoInventoryResync

        // Declared fixture regions may exceed one chunk section. Each block needs
        // four ints and a UTF length, so the bounded payload limits allocation.
        int blockCount = checkedCount(input.readInt(), input.available() / 18, "blocks");
        List<BlockSnapshot> blocks = new ArrayList<>(blockCount);
        for (int i = 0; i < blockCount; i++) {
            blocks.add(new BlockSnapshot(
                    input.readInt(), input.readInt(), input.readInt(), input.readInt(), input.readUTF()));
        }

        List<ItemSnapshot> inventory = readItems(input, "inventory");
        List<ItemSnapshot> menu = readItems(input, "menu");
        ItemSnapshot carried = readItem(input);
        return new Snapshot(tick, scenario, selectedSlot, blocks, inventory, menu, carried);
    }

    private static List<ItemSnapshot> readItems(DataInputStream input, String name) throws IOException {
        int count = checkedCount(input.readInt(), 256, name);
        List<ItemSnapshot> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) items.add(readItem(input));
        return items;
    }

    private static ItemSnapshot readItem(DataInputStream input) throws IOException {
        int slot = input.readInt();
        String key = input.readUTF();
        int count = input.readInt();
        int length = checkedCount(input.readInt(), input.available(), "item bytes");
        return new ItemSnapshot(slot, key, count, input.readNBytes(length));
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
            int cultStateId = player.compensatedWorld.getBlockStateIdAt(new BlockPos(block.x(), block.y(), block.z()));
            String cultState = DataTables.defaults().registry().debugString(cultStateId);
            // Numeric state IDs belong to each protocol's registry and are translated by ViaVersion.
            // Growing heads choose age 0–24 using private client RNG. These ages
            // have identical client behavior; age 25 and all other properties remain exact.
            if (!blockStatesMatch(block.state(), cultStateId)) {
                mismatches.add("block " + block.x() + "," + block.y() + "," + block.z()
                        + " expectedId=" + block.stateId()
                        + " cultId=" + cultStateId
                        + " expected=" + block.state()
                        + " cult=" + cultState);
            }
        }
        if (snapshot.selectedSlot() >= 0 && player.packetStateData.lastSlotSelected != snapshot.selectedSlot()) {
            mismatches.add("selected-slot expected=" + snapshot.selectedSlot() + " cult="
                    + player.packetStateData.lastSlotSelected);
        }

        InventoryStorage storage = player.getInventory().inventory.getInventoryStorage();
        var names = player.user.getCultConnection().require(ac.cult.cultac.protocol.data.RegistryNames.class);
        var modelNames = ac.cult.cultac.network.codec.ModelRegistryNamesState.defaults();
        var builtin =
                ac.cult.blocksim.environment.ClientWorldDefaults.defaults().builtinRegistries();
        var values = new ac.cult.cultac.network.codec.ModelItemValues(
                ac.cult.cultac.protocol.ProtocolCodecs.decoder(),
                ac.cult.cultac.protocol.ProtocolVersion.V26_3,
                new ac.cult.cultac.protocol.WireValueDecoder.Registries() {
                    // The validation client writes the model schema. Host static
                    // IDs may belong to an older backend; dynamic IDs are received.
                    @Override
                    public String name(String registry, int id) {
                        return (builtin.contains(registry) ? modelNames : names).name(registry, id);
                    }

                    @Override
                    public int id(String registry, String name) {
                        return (builtin.contains(registry) ? modelNames : names).id(registry, name);
                    }
                },
                ac.cult.cultac.utils.inventory.ItemUtil.modelItems());
        for (ItemSnapshot item : snapshot.inventory()) {
            compareItem(values, mismatches, "inventory", item, storage.getItem(item.slot()));
        }
        for (ItemSnapshot item : snapshot.menu()) {
            var slot = player.getInventory().menu.getSlot(item.slot());
            compareItem(values, mismatches, "menu", item, slot == null ? null : slot.getItem());
        }
        if (snapshot.carried().slot() == -1) {
            compareItem(
                    values,
                    mismatches,
                    "carried",
                    snapshot.carried(),
                    player.getInventory().menu.getCarried());
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

    static boolean blockStatesMatch(String expected, int modeled) {
        var registry = DataTables.defaults().registry();
        if (expected.equals(registry.debugString(modeled))) return true;
        if (!registry.block(modeled)
                        .bindings()
                        .get("classHierarchy")
                        .contains("net.minecraft.world.level.block.GrowingPlantHeadBlock")
                || Integer.parseInt(registry.value(modeled, "age")) >= 25) return false;
        var age = RANDOM_INITIAL_AGE.matcher(expected);
        return age.find() && age.replaceFirst("age=0").equals(registry.debugString(registry.with(modeled, "age", "0")));
    }

    private static void compareItem(
            ac.cult.cultac.network.codec.ModelItemValues values,
            List<String> mismatches,
            String owner,
            ItemSnapshot expected,
            ac.cult.blocksim.engine.SimItemStack item) {
        var cult = item == null ? ac.cult.blocksim.engine.SimItemStack.EMPTY : item;
        var client = ac.cult.blocksim.engine.SimItemStack.EMPTY;
        if (expected.components().length > 0) {
            var input = io.netty.buffer.Unpooled.wrappedBuffer(expected.components());
            try {
                client = values.item(input, false);
                if (input.isReadable()) throw new IllegalStateException("Unread client snapshot item bytes");
            } finally {
                input.release();
            }
        }
        if (!expected.key().equals(client.itemKey()) || expected.count() != client.count())
            throw new IllegalStateException("Inconsistent client snapshot item");
        if (!cult.matches(client)) {
            mismatches.add(owner + " slot=" + expected.slot() + " expected=" + client + " cult=" + cult);
        }
    }

    private record Snapshot(
            int tick,
            String scenario,
            int selectedSlot,
            List<BlockSnapshot> blocks,
            List<ItemSnapshot> inventory,
            List<ItemSnapshot> menu,
            ItemSnapshot carried) {}

    private record BlockSnapshot(int x, int y, int z, int stateId, String state) {}

    private record ItemSnapshot(int slot, String key, int count, byte[] components) {}
}
