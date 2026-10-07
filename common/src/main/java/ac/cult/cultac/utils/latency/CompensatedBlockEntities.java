package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.behavior.BehaviorRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.engine.BlockEntityData;
import ac.cult.blocksim.engine.BlockEntityPrototypes;
import ac.cult.cultac.network.packet.WorldPackets.BlockEntityUpdate;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ModelBlockStates;
import ac.cult.cultac.protocol.value.BlockPos;
import java.util.HashMap;
import java.util.Map;
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

    private final Map<Long, Entry> entries = new HashMap<>();
    private final Supplier<ac.cult.blocksim.data.InteractionRegistries> registries;
    private final ModelBlockStates states;

    public CompensatedBlockEntities(ac.cult.cultac.player.CultPlayer player) {
        this(() -> player.registryState == null
                ? ac.cult.blocksim.data.InteractionRegistries.defaults()
                : player.registryState.blockSimulatorRegistries(Rules.DATA).interactions());
    }

    public CompensatedBlockEntities(Supplier<ac.cult.blocksim.data.InteractionRegistries> registries) {
        this(registries, Rules.STATES);
    }

    public CompensatedBlockEntities(
            Supplier<ac.cult.blocksim.data.InteractionRegistries> registries, ModelBlockStates states) {
        this.registries = registries;
        this.states = java.util.Objects.requireNonNull(states);
        java.util.Objects.requireNonNull(Rules.DATA); // Load static facts before any prediction action.
    }

    private Entry entry(BlockPos pos, int state) {
        Entry entry = entries.get(pos.asLong());
        var block = Rules.DATA.registry().block(state);
        String type = block.bindings().get("blockEntity.type");
        if (entry != null && !entry.data().type().equals(type)) {
            entries.remove(pos.asLong());
            entry = null;
        }
        if (entry == null) {
            var data = BlockEntityPrototypes.create(block, state);
            if (data != null) {
                entry = new Entry(data, state);
                if (!data.data().keys().isEmpty()) entries.put(pos.asLong(), entry);
            }
        }
        if (entry != null && entry.state() != state) {
            entry = new Entry(entry.data(), state);
            entries.put(pos.asLong(), entry);
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
                : ClientBlockEntityFields.load(packet.type(), tag, states.toModel(stateId));
        if (loaded.data().keys().isEmpty()) return;
        entries.put(
                packet.position().asLong(),
                new Entry(retainShulkerAnimation(loaded, entry.data()), states.toModel(stateId)));
    }

    private static BlockEntityData retainShulkerAnimation(BlockEntityData data, BlockEntityData previous) {
        if (data.type().equals("minecraft:shulker_box")) {
            // Inventory/component packets do not reset the running client animation.
            for (String key : java.util.List.of("animation_status", "progress", "progress_old"))
                if (previous.data().has(key))
                    data = new BlockEntityData(
                            data.type(), data.data().with(key, previous.data().get(key)), data.savedData());
        }
        return data;
    }

    public void blockEvent(BlockPos pos, int state, int action, int parameter) {
        var entry = entry(pos, states.toModel(state));
        if (entry != null)
            entries.put(
                    pos.asLong(),
                    new Entry(
                            ac.cult.blocksim.engine.ShulkerAnimation.event(entry.data(), action, parameter),
                            entry.state()));
    }

    public void tick() {
        entries.replaceAll((pos, entry) -> {
            var next = ac.cult.blocksim.engine.ShulkerAnimation.tick(entry.data());
            if (next.type().equals("minecraft:jukebox")
                    && Rules.DATA.registry().value(entry.state(), "has_record").equals("true"))
                next = ac.cult.blocksim.engine.JukeboxPlayback.tick(next);
            return next == entry.data() ? entry : new Entry(next, entry.state());
        });
    }

    /** Preserve same-block and copper-family entities exactly as LevelChunk does. */
    public void stateChanged(BlockPos pos, int oldState, int newState) {
        int oldId = states.toModel(oldState), newId = states.toModel(newState);
        if (!Rules.DATA.registry().sameBlock(oldId, newId)
                && !Rules.BEHAVIORS.apply(newId).shouldKeepBlockEntity(Rules.DATA.registry(), newId, oldId))
            entries.remove(pos.asLong());
        entry(pos, newId);
    }

    public BlockEntityData snapshot(BlockPos pos, int state) {
        var entry = entry(pos, states.toModel(state));
        return entry == null ? null : entry.data();
    }

    /** Local action overlay after its ordered block writes, without running native callbacks. */
    public void predict(BlockPos pos, int state, BlockEntityData data) {
        if (data == null) {
            entries.remove(pos.asLong());
            return;
        }
        if (data.data().keys().isEmpty()) {
            entries.remove(pos.asLong());
            return;
        }
        int model = states.toModel(state);
        String expectedType = Rules.DATA.registry().block(model).bindings().get("blockEntity.type");
        if (expectedType == null || expectedType.equals("none") || !expectedType.equals(data.type()))
            throw new IllegalArgumentException("Predicted block entity does not match client state at " + pos);
        entries.put(pos.asLong(), new Entry(data, model));
    }

    public void forgetChunk(int x, int z) {
        entries.keySet().removeIf(key -> {
            var pos = BlockPos.of(key);
            return pos.getX() >> 4 == x && pos.getZ() >> 4 == z;
        });
    }

    public void clear() {
        entries.clear();
    }
}
