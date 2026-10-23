package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.behavior.BehaviorRegistry;
import ac.cult.blocksim.data.Box;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.InteractionRegistries;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.engine.BlockEntityData;
import ac.cult.blocksim.engine.BlockEntityPrototypes;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.JukeboxPlayback;
import ac.cult.blocksim.engine.ShulkerAnimation;
import ac.cult.blocksim.engine.ShulkerGeometry;
import ac.cult.blocksim.engine.Vec3;
import ac.cult.cultac.network.packet.WorldPackets.BlockEntityUpdate;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ModelBlockStates;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrays;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/** Sparse packet inputs applied at the existing compensated transaction boundary. */
public final class CompensatedBlockEntities {
    private record Entry(BlockEntityData data, int state) {}

    private static final class Rules {
        static final DataTables DATA = DataTables.defaults();
        static final BehaviorRegistry BEHAVIORS;
        static final ModelBlockStates STATES = ModelBlockStates.project(ProtocolVersion.V26_3, ProtocolVersion.V26_3);

        static {
            BEHAVIORS = new BehaviorRegistry(DATA);
        }
    }

    // Ordinary block writes need lookups but hold no block entity. Allocate storage on the first retained entry.
    private Long2ObjectOpenHashMap<Entry> entries;
    // Every key whose entry the next tick may change; the rest are fixed points of tick.
    private LongOpenHashSet ticking;
    // Level#blockEntityTickers order: LevelChunk#updateBlockEntityTicker appends a new block entity's ticker and
    // rebinds a replaced one in place, and LevelChunk#removeBlockEntityTicker drops it.
    private Long2LongOpenHashMap added;
    private long nextAdded;
    private final List<ShulkerShove> lastTickShoves = new ArrayList<>(0);
    private final Supplier<InteractionRegistries> registries;
    private final ModelBlockStates states;

    public CompensatedBlockEntities(CultPlayer player) {
        this(() -> player.registryState == null
                ? InteractionRegistries.defaults()
                : player.registryState.blockSimulatorRegistries(Rules.DATA).interactions());
    }

    public CompensatedBlockEntities(Supplier<InteractionRegistries> registries) {
        this(registries, Rules.STATES);
    }

    public CompensatedBlockEntities(Supplier<InteractionRegistries> registries, ModelBlockStates states) {
        this.registries = registries;
        this.states = Objects.requireNonNull(states);
        Objects.requireNonNull(Rules.DATA); // Load static facts before any prediction action.
    }

    private Entry entry(BlockPos pos, int state) {
        Entry entry = get(pos.asLong());
        var block = Rules.DATA.registry().block(state);
        String type = block.bindings().get("blockEntity.type");
        if (entry != null && !entry.data().type().equals(type)) {
            remove(pos.asLong());
            entry = null;
        }
        if (entry == null) {
            var data = BlockEntityPrototypes.create(block, state);
            if (data != null) {
                entry = new Entry(data, state);
                if (!data.data().keys().isEmpty()) put(pos.asLong(), entry);
            }
        }
        if (entry != null && entry.state() != state) {
            entry = new Entry(entry.data(), state);
            put(pos.asLong(), entry);
        }
        return entry;
    }

    /** ClientPacketListener.handleBlockEntityData / LevelChunk.replaceWithPacketData. */
    public void receive(BlockEntityUpdate packet, int stateId) {
        var entry = entry(packet.position(), states.toModel(stateId));
        var tag = packet.tag();
        if (entry == null || tag == null || !packet.type().equals(entry.data().type())) return;
        if (packet.type().equals("minecraft:potent_sulfur")
                && !(tag.values().get("countdown") instanceof NbtValue.Numeric)) {
            // PotentSulfurBlockEntity.loadAdditional only changes a present numeric
            // countdown. Retain it across packet loads and local prediction overlays.
            var values = new HashMap<>(tag.values());
            values.put(
                    "countdown",
                    entry.data()
                            .savedData()
                            .values()
                            .getOrDefault("countdown", new NbtValue.Numeric(NbtValue.Kind.INT, -1)));
            tag = new NbtValue.Compound(values);
        }
        var loaded = packet.type().equals("minecraft:jukebox")
                ? ClientJukeboxFields.load(tag, entry.data(), registries.get())
                : ClientBlockEntityFields.load(packet.type(), tag);
        if (loaded.data().keys().isEmpty()) return;
        put(
                packet.position().asLong(),
                new Entry(retainShulkerAnimation(loaded, entry.data()), states.toModel(stateId)));
    }

    private static BlockEntityData retainShulkerAnimation(BlockEntityData data, BlockEntityData previous) {
        if (data.type().equals("minecraft:shulker_box")) {
            // Inventory/component packets do not reset the running client animation.
            for (String key : List.of("animation_status", "progress", "progress_old"))
                if (previous.data().has(key))
                    data = new BlockEntityData(
                            data.type(), data.data().with(key, previous.data().get(key)), data.savedData());
        }
        return data;
    }

    public void blockEvent(BlockPos pos, int state, int action, int parameter) {
        var entry = entry(pos, states.toModel(state));
        if (entry != null)
            put(pos.asLong(), new Entry(ShulkerAnimation.event(entry.data(), action, parameter), entry.state()));
    }

    /** Level#tickBlockEntities, which records each ShulkerBoxBlockEntity#moveCollidedEntities call it makes. */
    public void tick() {
        lastTickShoves.clear();
        if (ticking == null || ticking.isEmpty()) return;
        for (long key : tickerOrder()) {
            var entry = entries.get(key);
            if (entry == null) {
                ticking.remove(key);
                continue;
            }
            var next = ShulkerAnimation.tick(entry.data());
            if (opening(entry.data())) lastTickShoves.add(shove(key, entry.state(), next));
            if (next.type().equals("minecraft:jukebox")
                    && Rules.DATA.registry().value(entry.state(), "has_record").equals("true"))
                next = JukeboxPlayback.tick(next);
            if (next != entry.data()) entries.put(key, new Entry(next, entry.state()));
            else ticking.remove(key);
        }
    }

    /** The {@code ticking} keys in Level#tickBlockEntities order. */
    private long[] tickerOrder() {
        long[] keys = ticking.toLongArray();
        if (keys.length > 1) LongArrays.quickSort(keys, (a, b) -> Long.compare(added.get(a), added.get(b)));
        return keys;
    }

    /**
     * A ShulkerBoxBlockEntity#moveCollidedEntities call: the progress step's Shulker#getProgressDeltaAabb, and the
     * movement it gives every entity that box intersects.
     */
    public record ShulkerShove(SimpleCollisionBox area, double x, double y, double z) {}

    /** A client tick whose TickRateManager#runsNormally is false: Level#tickBlockEntities ticks no block entity. */
    public void skipTick() {
        lastTickShoves.clear();
    }

    /**
     * Adds the ShulkerBoxBlockEntity#moveCollidedEntities calls of the client tick that ended last, or with
     * {@code next} those of the client tick that runs next, whose block entities tick when {@code runsNormally}.
     */
    public void shulkerShoves(boolean next, boolean runsNormally, List<ShulkerShove> shoves) {
        if (!next) {
            shoves.addAll(lastTickShoves);
            return;
        }
        if (ticking == null || ticking.isEmpty() || !runsNormally) return;
        for (long key : tickerOrder()) {
            var entry = entries.get(key);
            if (entry != null && opening(entry.data()))
                shoves.add(shove(key, entry.state(), ShulkerAnimation.tick(entry.data())));
        }
    }

    /** ShulkerBoxBlockEntity#updateAnimation calls moveCollidedEntities in every OPENING update. */
    private static boolean opening(BlockEntityData data) {
        if (!data.type().equals("minecraft:shulker_box")) return false;
        var status = data.data().get("animation_status");
        return status != null && status.getAsString().equals("OPENING");
    }

    private static ShulkerShove shove(long key, int state, BlockEntityData updated) {
        var fields = updated.data();
        float progressOld = fields.get("progress_old").getAsFloat();
        float progress = fields.get("progress").getAsFloat();
        Direction facing =
                Direction.valueOf(Rules.DATA.registry().value(state, "facing").toUpperCase(Locale.ROOT));
        BlockPos pos = BlockPos.of(key);
        Box area = ShulkerGeometry.progressDeltaBounds(
                1.0F, facing, progressOld, progress, new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5));
        return new ShulkerShove(
                new SimpleCollisionBox(area.minX(), area.minY(), area.minZ(), area.maxX(), area.maxY(), area.maxZ()),
                (area.maxX() - area.minX() + 0.01) * facing.x(),
                (area.maxY() - area.minY() + 0.01) * facing.y(),
                (area.maxZ() - area.minZ() + 0.01) * facing.z());
    }

    /** Preserve same-block and copper-family entities exactly as LevelChunk does. */
    public void stateChanged(BlockPos pos, int oldState, int newState) {
        int oldId = states.toModel(oldState), newId = states.toModel(newState);
        if (!Rules.DATA.registry().sameBlock(oldId, newId)
                && !Rules.BEHAVIORS.apply(newId).shouldKeepBlockEntity(Rules.DATA.registry(), newId, oldId))
            remove(pos.asLong());
        entry(pos, newId);
    }

    public BlockEntityData snapshot(BlockPos pos, int state) {
        var entry = entry(pos, states.toModel(state));
        return entry == null ? null : entry.data();
    }

    /** Local action overlay after its ordered block writes, without running native callbacks. */
    public void predict(BlockPos pos, int state, BlockEntityData data) {
        if (data == null) {
            remove(pos.asLong());
            return;
        }
        if (data.data().keys().isEmpty()) {
            remove(pos.asLong());
            return;
        }
        int model = states.toModel(state);
        String expectedType = Rules.DATA.registry().block(model).bindings().get("blockEntity.type");
        if (expectedType == null || expectedType.equals("none") || !expectedType.equals(data.type()))
            throw new IllegalArgumentException("Predicted block entity does not match client state at " + pos);
        put(pos.asLong(), new Entry(data, model));
    }

    public void forgetChunk(int x, int z) {
        if (entries == null) return;
        var iterator = entries.long2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            long key = iterator.next().getLongKey();
            if (((int) (key >> 38) >> 4) == x && ((int) (key << 26 >> 38) >> 4) == z) {
                iterator.remove();
                ticking.remove(key);
                added.remove(key);
            }
        }
    }

    public void clear() {
        entries = null;
        ticking = null;
        added = null;
    }

    private Entry get(long key) {
        return entries == null ? null : entries.get(key);
    }

    private void remove(long key) {
        if (entries != null) {
            entries.remove(key);
            ticking.remove(key);
            added.remove(key);
        }
    }

    private void put(long key, Entry entry) {
        if (entries == null) {
            entries = new Long2ObjectOpenHashMap<>(2);
            ticking = new LongOpenHashSet(2);
            added = new Long2LongOpenHashMap(2);
        }
        if (entries.put(key, entry) == null) added.put(key, nextAdded++);
        ticking.add(key);
    }
}
