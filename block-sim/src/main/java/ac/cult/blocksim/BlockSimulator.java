package ac.cult.blocksim;

import ac.cult.blocksim.behavior.*;
import ac.cult.blocksim.data.*;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.interaction.*;
import java.util.List;
import java.util.Map;

/** One portable entry point for use, placement and tick-resolved breaking. */
public final class BlockSimulator {
    private final DataTables data;
    private final BehaviorRegistry blocks;
    private final ItemBehaviorRegistry items;
    private final ItemTemplates templates;
    private final AdventurePredicates adventure;
    private final BreakDriver breaks;
    private final MiningSpeed mining;
    private final HolderSets.Overlay tags;
    private final java.util.function.Function<HolderSets.Overlay, BlockSimulator> snapshots;

    public BlockSimulator(DataTables data, ItemBehaviorRegistry items, ItemTemplates templates) {
        this(data, new BehaviorRegistry(data), items, templates);
    }
    private BlockSimulator(DataTables data, BehaviorRegistry blocks, ItemBehaviorRegistry items, ItemTemplates templates) {
        this(data, blocks, items, templates, HolderSets.Overlay.EMPTY, null);
    }
    private BlockSimulator(DataTables data, BehaviorRegistry blocks, ItemBehaviorRegistry items, ItemTemplates templates,
                           HolderSets.Overlay tags, java.util.function.Function<HolderSets.Overlay, BlockSimulator> snapshots) {
        this.data = java.util.Objects.requireNonNull(data);
        this.blocks = blocks; this.items = java.util.Objects.requireNonNull(items);
        this.templates = java.util.Objects.requireNonNull(templates);
        this.tags = tags; this.snapshots = snapshots;
        adventure = new AdventurePredicates(data); breaks = new BreakDriver(items, adventure); mining = new MiningSpeed(data);
    }

    /** One setup per received registry snapshot; every created stack uses the same client defaults. */
    public static BlockSimulator create(DataTables data, ItemRegistry items, InteractionRegistries registries,
                                        PlacementObstruction obstruction, ConsumableBehavior consumables) {
        return create(data, items, registries, obstruction, consumables, HolderSets.Overlay.EMPTY);
    }
    private static BlockSimulator create(DataTables defaults, ItemRegistry items, InteractionRegistries registries,
                                         PlacementObstruction obstruction, ConsumableBehavior consumables, HolderSets.Overlay tags) {
        var data = tags.apply(defaults);
        var templates = new ItemTemplates(items);
        var transforms = new BlockTransformerBehavior(data, registries);
        var equipment = new EquipmentBehavior(data, registries);
        var general = new GeneralItemBehavior(consumables, transforms::useOn, equipment::swap);
        var itemBehaviors = new ItemBehaviorRegistry(data, items, general, obstruction, new ac.cult.blocksim.entity.BlockEntityComponentApplication(templates),
            Map.of("net.minecraft.world.item.InstrumentItem", new InstrumentItemBehavior(general, registries)));
        return new BlockSimulator(data, new BehaviorRegistry(data, items, registries), itemBehaviors, templates, tags,
            received -> create(defaults, items, registries, obstruction, consumables, received));
    }

    /** Cache the returned immutable simulator at the connection's received-tag boundary. */
    public BlockSimulator withTags(HolderSets.Overlay received) {
        if (tags.equals(received)) return this;
        if (snapshots == null) throw new IllegalStateException("Custom behavior bindings cannot be rebound to received tags");
        return snapshots.apply(received);
    }

    public SimResult simulate(SimAction action, SimInput input) {
        if (!tags.equals(input.tags())) return withTags(input.tags()).simulate(action, input);
        var fork = input.fork();
        var level = new SimLevel(fork.world(), data.registry(), blocks);
        var stacks = new ItemStackActions(items, adventure, fork.cooldowns(), templates);
        var use = new InteractionDriver(data.enabledFeatures(), stacks, fork.cooldowns());
        var session = new BreakSession(breaks, mining, fork.breaking());
        SimInteraction interaction = null;
        BreakSession.Result breaking = null;
        boolean accepted = true;
        try {
            switch (action) {
                case SimAction.UseOn a -> interaction = use.useItemOn(level, fork.player(), a.hand(), a.hit());
                case SimAction.Use a -> interaction = use.useItem(level, fork.player(), a.hand());
                case SimAction.DestroyBlock a -> accepted = breaks.destroyBlock(level, fork.player(), a.pos());
                case SimAction.StartBreak a -> breaking = session.start(level, fork.player(), fork.mining(), a.pos(), a.face());
                case SimAction.ContinueBreak a -> breaking = session.continueBreaking(level, fork.player(), fork.mining(), a.pos(), a.face());
                case SimAction.AbortBreak a -> breaking = session.abort();
            }
        } catch (SimLevel.UnloadedWorldException unloaded) {
            return new SimResult(null, false, List.of(), Map.of(), Map.of(), input.player(), input.cooldowns(), input.breaking(), List.of(), Decline.UNLOADED);
        }
        return new SimResult(interaction, breaking == null ? accepted : breaking.accepted(), level.writes(), level.retainedPossibilities(), level.blockEntityChanges(),
            fork.player(), fork.cooldowns(), breaking == null ? fork.breaking() : breaking.state(), breaking == null ? List.of() : breaking.packets(), null);
    }
}
