package ac.cult.blocksim.entity;

import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.data.ItemTemplates;
import ac.cult.blocksim.data.nbt.NbtJson;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.blocksim.engine.BlockEntityComponentWriter;
import ac.cult.blocksim.interaction.PlacementContext;
import java.util.List;

/** Applies only item components that change predicted interaction or signal inputs. */
public final class BlockEntityComponentApplication implements BlockEntityComponentWriter {
    private static final String ENTITY_PACKAGE = "net.minecraft.world.level.block.entity.";
    private final ContainerEntityComponents containers;
    public BlockEntityComponentApplication(ItemTemplates templates) { containers = new ContainerEntityComponents(templates); }

    @Override
    public void apply(PlacementContext context) {
        var entity = context.level().blockEntityAt(context.clickedPos());
        if (entity == null) return;
        var level = context.level(); int state = level.stateAt(context.clickedPos());
        var bindings = level.registry().block(state).bindings();
        var hierarchy = List.of(bindings.get("blockEntity.classHierarchy").split(","));
        boolean container = hierarchy.contains(ENTITY_PACKAGE + "BaseContainerBlockEntity");
        boolean pot = entity.type().equals("minecraft:decorated_pot");
        if (!container && !pot && !entity.isSign()) return;
        var stack = context.stack();
        var input = new ComponentInput(stack.components(), entity.savedData());
        for (String key : entity.data().keys()) if (!entity.savedData().values().containsKey(key)) input.internalFields.put(key, entity.data().get(key));
        if (container) {
            int size = Integer.parseInt(bindings.get("blockEntity.containerSize"));
            containers.baseContainer(input, size, entity.type().equals("minecraft:shulker_box"));
            if (hierarchy.contains(ENTITY_PACKAGE + "RandomizableContainerBlockEntity")) containers.randomizable(input);
        }
        if (pot) containers.pot(input);
        if (entity.isSign()) SignEntityComponents.apply(input);
        var saved = new NbtValue.Compound(input.fields);
        var modeled = NbtJson.encode(saved).getAsJsonObject();
        input.internalFields.forEach(modeled::add);
        level.blockEntityAt(context.clickedPos(), new ac.cult.blocksim.engine.BlockEntityData(entity.type(), new Components(modeled.asMap()), saved));
    }
}
