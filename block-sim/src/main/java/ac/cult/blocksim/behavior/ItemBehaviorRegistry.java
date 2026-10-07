package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.ItemDefinition;
import ac.cult.blocksim.data.ItemRegistry;
import ac.cult.blocksim.engine.BlockEntityComponentWriter;
import ac.cult.blocksim.interaction.PlacementObstruction;
import java.util.Map;
import java.util.HashMap;

/** Item ancestry is generated; family dispatch is resolved once for each item definition. */
public final class ItemBehaviorRegistry {
    private final Map<ItemDefinition, ItemBehavior> byItem = new java.util.IdentityHashMap<>();
    private final ItemDefinition air;
    public ItemBehaviorRegistry(DataTables data, ItemBehavior generalItem, PlacementObstruction obstruction, BlockEntityComponentWriter components) {
        this(data, generalItem, obstruction, components, Map.of());
    }
    public ItemBehaviorRegistry(DataTables data, ItemBehavior generalItem, PlacementObstruction obstruction, BlockEntityComponentWriter components,
                                Map<String, ItemBehavior> additionalFamilies) {
        this(data, new ItemRegistry(data), generalItem, obstruction, components, additionalFamilies);
    }
    public ItemBehaviorRegistry(DataTables data, ItemRegistry items, ItemBehavior generalItem, PlacementObstruction obstruction, BlockEntityComponentWriter components) {
        this(data, items, generalItem, obstruction, components, Map.of());
    }
    public ItemBehaviorRegistry(DataTables data, ItemRegistry items, ItemBehavior generalItem, PlacementObstruction obstruction, BlockEntityComponentWriter components,
                                 Map<String, ItemBehavior> additionalFamilies) {
        air = data.items().stream().filter(item -> item.key().equals("minecraft:air")).findFirst().orElseThrow();
        var families = new HashMap<String, ItemBehavior>();
        families.put("net.minecraft.world.item.Item", generalItem);
        var thrown = new SimpleUseItemBehavior(generalItem, SimpleUseItemBehavior.Kind.THROW);
        for (String family : new String[]{"EggItem", "EnderpearlItem", "ExperienceBottleItem", "SnowballItem", "WindChargeItem"})
            families.put("net.minecraft.world.item." + family, thrown);
        var acknowledge = new SimpleUseItemBehavior(generalItem, SimpleUseItemBehavior.Kind.ACKNOWLEDGE);
        for (String family : new String[]{"EmptyMapItem", "FishingRodItem", "WritableBookItem", "WrittenBookItem"})
            families.put("net.minecraft.world.item." + family, acknowledge);
        families.put("net.minecraft.world.item.FoodOnAStickItem", new SimpleUseItemBehavior(generalItem, SimpleUseItemBehavior.Kind.PASS));
        families.put("net.minecraft.world.item.BundleItem", new SimpleUseItemBehavior(generalItem, SimpleUseItemBehavior.Kind.BUNDLE));
        families.put("net.minecraft.world.item.SpyglassItem", new SimpleUseItemBehavior(generalItem, SimpleUseItemBehavior.Kind.SPYGLASS));
        families.put("net.minecraft.world.item.KnowledgeBookItem", new SimpleUseItemBehavior(generalItem, SimpleUseItemBehavior.Kind.KNOWLEDGE_BOOK));
        families.put("net.minecraft.world.item.BowItem", new ProjectileWeaponBehavior(data, generalItem, false));
        families.put("net.minecraft.world.item.CrossbowItem", new ProjectileWeaponBehavior(data, generalItem, true));
        families.put("net.minecraft.world.item.TridentItem", new TridentItemBehavior(generalItem));
        families.put("net.minecraft.world.item.InstrumentItem", new InstrumentItemBehavior(generalItem, ac.cult.blocksim.data.InteractionRegistries.defaults()));
        families.put("net.minecraft.world.item.ShearsItem", new ShearsItemBehavior(generalItem));
        families.put("net.minecraft.world.item.BoneMealItem", new BoneMealItemBehavior(data, generalItem));
        families.put("net.minecraft.world.item.BrushItem", new BrushItemBehavior(generalItem));
        families.put("net.minecraft.world.item.CompassItem", new CompassItemBehavior(generalItem, items));
        families.put("net.minecraft.world.item.PotionItem", new PotionItemBehavior(data, generalItem, items));
        families.put("net.minecraft.world.item.ThrowablePotionItem", new SimpleUseItemBehavior(
            families.get("net.minecraft.world.item.PotionItem"), SimpleUseItemBehavior.Kind.THROW));
        families.put("net.minecraft.world.item.DebugStickItem", new DebugStickItemBehavior(generalItem));
        families.put("net.minecraft.world.item.LeadItem", new ContextualItemBehavior(data, generalItem, ContextualItemBehavior.Kind.LEAD));
        families.put("net.minecraft.world.item.MapItem", new ContextualItemBehavior(data, generalItem, ContextualItemBehavior.Kind.FILLED_MAP));
        families.put("net.minecraft.world.item.EnderEyeItem", new ContextualItemBehavior(data, generalItem, ContextualItemBehavior.Kind.ENDER_EYE));
        families.put("net.minecraft.world.item.FireworkRocketItem", new ContextualItemBehavior(data, generalItem, ContextualItemBehavior.Kind.FIREWORK));
        var entityTypes = ac.cult.blocksim.entity.EntityTypes.defaults();
        families.put("net.minecraft.world.item.BoatItem", new BoatItemBehavior(generalItem, entityTypes));
        families.put("net.minecraft.world.item.MinecartItem", new MinecartItemBehavior(data, generalItem, entityTypes));
        families.put("net.minecraft.world.item.HangingEntityItem", new HangingItemBehavior(data, generalItem));
        families.put("net.minecraft.world.item.SpawnEggItem", new EntityItemBehavior(generalItem, EntityItemBehavior.Kind.SPAWN_EGG, entityTypes));
        families.put("net.minecraft.world.item.ArmorStandItem", new EntityItemBehavior(generalItem, EntityItemBehavior.Kind.ARMOR_STAND, entityTypes));
        families.put("net.minecraft.world.item.EndCrystalItem", new EntityItemBehavior(generalItem, EntityItemBehavior.Kind.END_CRYSTAL, entityTypes));
        families.put("net.minecraft.world.item.BottleItem", new BottleItemBehavior(data, generalItem, items));
        families.put("net.minecraft.world.item.CushionItem", new CushionItemBehavior(data, generalItem, entityTypes));
        families.put("net.minecraft.world.item.FlintAndSteelItem", new BlockUseItemBehavior(data, generalItem, BlockUseItemBehavior.Kind.FLINT_AND_STEEL));
        families.put("net.minecraft.world.item.FireChargeItem", new BlockUseItemBehavior(data, generalItem, BlockUseItemBehavior.Kind.FIRE_CHARGE));
        families.put("net.minecraft.world.item.HoneycombItem", new BlockUseItemBehavior(data, generalItem, BlockUseItemBehavior.Kind.HONEYCOMB));
        families.put("net.minecraft.world.item.BucketItem", new BucketItemBehavior(generalItem, items, new ac.cult.blocksim.interaction.AdventurePredicates(data)));
        families.put("net.minecraft.world.item.BlockItem", new BlockItemBehavior(data, items, generalItem, obstruction, components, BlockItemBehavior.Kind.BLOCK));
        families.put("net.minecraft.world.item.DoubleHighBlockItem", new BlockItemBehavior(data, items, generalItem, obstruction, components, BlockItemBehavior.Kind.DOUBLE_HIGH));
        families.put("net.minecraft.world.item.GameMasterBlockItem", new BlockItemBehavior(data, items, generalItem, obstruction, components, BlockItemBehavior.Kind.GAME_MASTER));
        families.put("net.minecraft.world.item.StandingAndWallBlockItem", new BlockItemBehavior(data, items, generalItem, obstruction, components, BlockItemBehavior.Kind.STANDING_WALL));
        families.put("net.minecraft.world.item.ScaffoldingBlockItem", new BlockItemBehavior(data, items, generalItem, obstruction, components, BlockItemBehavior.Kind.SCAFFOLDING));
        families.put("net.minecraft.world.item.HangingSignItem", new BlockItemBehavior(data, items, generalItem, obstruction, components, BlockItemBehavior.Kind.HANGING_SIGN));
        families.put("net.minecraft.world.item.SolidBucketItem", new BlockItemBehavior(data, items, generalItem, obstruction, components, BlockItemBehavior.Kind.SOLID_BUCKET));
        families.put("net.minecraft.world.item.PlaceOnWaterBlockItem", new BlockItemBehavior(data, items, generalItem, obstruction, components, BlockItemBehavior.Kind.WATER_SURFACE));
        families.putAll(additionalFamilies);
        for (var item : data.items()) {
            for (String type : item.bindings().get("classHierarchy").split(",")) {
                ItemBehavior behavior = families.get(type);
                if (behavior != null) { byItem.put(item, behavior); break; }
            }
            if (!byItem.containsKey(item)) throw new IllegalArgumentException("Missing item family " + item.key());
        }
    }
    public ItemBehavior behavior(ItemDefinition item) { return byItem.get(item); }
    public boolean canDestroyBlock(ac.cult.blocksim.engine.SimItemStack stack, ac.cult.blocksim.engine.SimPlayer player) {
        var item = itemForStack(stack);
        // DebugStickItem's client branch has no interaction side effect and always returns false.
        return !item.vanillaClass().equals("net.minecraft.world.item.DebugStickItem") && behavior(item).canDestroyBlock(stack, player);
    }
    public ItemDefinition itemForStack(ac.cult.blocksim.engine.SimItemStack stack) { return typeHolder(stack); }
    private ItemDefinition typeHolder(ac.cult.blocksim.engine.SimItemStack stack) { return stack.isEmpty() ? air : stack.definition(); }
}
