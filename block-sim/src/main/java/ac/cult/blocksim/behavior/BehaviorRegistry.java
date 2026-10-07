package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.DataTables;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntFunction;
import static ac.cult.blocksim.behavior.GrowingPlantBehavior.Kind.*;
import static ac.cult.blocksim.behavior.GrowingPlantBehavior.Part.*;

/** Dispatch is bound once to generated vanilla class ancestry, never to block names. */
public final class BehaviorRegistry implements IntFunction<BlockBehavior> {
    private final DataTables data;
    private final BlockBehavior[] byBlock;

    public BehaviorRegistry(DataTables data) {
        this(data, Map.of());
    }

    public BehaviorRegistry(DataTables data, Map<String, BlockBehavior> additionalFamilies) {
        this(data, new ac.cult.blocksim.data.ItemRegistry(data), additionalFamilies);
    }
    public BehaviorRegistry(DataTables data, ac.cult.blocksim.data.ItemRegistry items) {
        this(data, items, Map.of());
    }
    public BehaviorRegistry(DataTables data, ac.cult.blocksim.data.ItemRegistry items, ac.cult.blocksim.data.InteractionRegistries registries) {
        this(data, items, Map.of(), registries);
    }
    private BehaviorRegistry(DataTables data, ac.cult.blocksim.data.ItemRegistry items, Map<String, BlockBehavior> additionalFamilies) {
        this(data, items, additionalFamilies, ac.cult.blocksim.data.InteractionRegistries.defaults());
    }
    private BehaviorRegistry(DataTables data, ac.cult.blocksim.data.ItemRegistry items, Map<String, BlockBehavior> additionalFamilies, ac.cult.blocksim.data.InteractionRegistries registries) {
        this.data = data;
        this.byBlock = new BlockBehavior[data.registry().blocks().size()];
        var support = new SupportRules(data.tags().get("block:minecraft:unstable_bottom_center"));
        var families = new HashMap<String, BlockBehavior>();
        families.put("net.minecraft.world.level.block.Block", new BlockBehavior());
        families.put("net.minecraft.world.level.block.FlowerPotBlock", new FlowerPotBehavior(data, items));
        families.put("net.minecraft.world.level.block.ComposterBlock", new ComposterBehavior());
        families.put("net.minecraft.world.level.block.AbstractCauldronBlock", new CauldronBehavior(data));
        families.put("net.minecraft.world.level.block.BeehiveBlock", new BeehiveBehavior(items));
        families.put("net.minecraft.world.level.block.RespawnAnchorBlock", new RespawnAnchorBehavior());
        families.put("net.minecraft.world.level.block.JukeboxBlock", new JukeboxBehavior());
        families.put("net.minecraft.world.level.block.RedStoneOreBlock", new RedstoneOreBehavior());
        families.put("net.minecraft.world.level.block.CopperGolemStatueBlock", new CopperGolemStatueBehavior(data.tags().get("item:minecraft:axes"), data.tags().get("block:minecraft:copper_golem_statues")));
        families.put("net.minecraft.world.level.block.DecoratedPotBlock", new DecoratedPotBehavior(data));
        families.put("net.minecraft.world.level.block.piston.MovingPistonBlock", new PistonPartsBehavior(PistonPartsBehavior.Part.MOVING));
        families.put("net.minecraft.world.level.block.piston.PistonHeadBlock", new PistonPartsBehavior(PistonPartsBehavior.Part.HEAD));
        var candleCakes = new HashMap<String, Integer>();
        for (var block : data.registry().blocks()) {
            String candle = block.bindings().get("CandleCakeBlock.candleBlock");
            if (candle != null) candleCakes.put(data.registry().block(candle).bindings().get("asItem"), block.defaultState());
        }
        families.put("net.minecraft.world.level.block.CakeBlock", new CakeBehavior(data.tags().get("item:minecraft:candles"), candleCakes));
        families.put("net.minecraft.world.level.block.CandleCakeBlock", new CandleCakeBehavior());
        families.put("net.minecraft.world.level.block.FrogspawnBlock", new VegetationBehavior(data, VegetationBehavior.Kind.FROGSPAWN));
        families.put("net.minecraft.world.level.block.CactusFlowerBlock", new VegetationBehavior(data, VegetationBehavior.Kind.CACTUS_FLOWER));
        families.put("net.minecraft.world.level.block.GrindstoneBlock", new GrindstoneBehavior());
        families.put("net.minecraft.world.level.block.VegetationBlock", new VegetationBehavior(data.tags().get("block:minecraft:supports_vegetation")));
        families.put("net.minecraft.world.level.block.DoublePlantBlock", new DoublePlantBehavior(data.tags().get("block:minecraft:supports_vegetation"), data.tags().get("fluid:minecraft:water")));
        families.put("net.minecraft.world.level.block.SnowLayerBlock", new SnowLayerBehavior(data.tags().get("block:minecraft:cannot_support_snow_layer"), data.tags().get("block:minecraft:support_override_snow_layer")));
        families.put("net.minecraft.world.level.block.LilyPadBlock", new VegetationBehavior(data, VegetationBehavior.Kind.LILY_PAD));
        families.put("net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock", new FaceAttachedBehavior());
        families.put("net.minecraft.world.level.block.SugarCaneBlock", new SugarCaneBehavior(data.tags().get("block:minecraft:supports_sugar_cane"),
            data.tags().get("block:minecraft:supports_sugar_cane_adjacently"), data.tags().get("fluid:minecraft:supports_sugar_cane_adjacently")));
        families.put("net.minecraft.world.level.block.CactusBlock", new CactusBehavior(data.tags().get("block:minecraft:supports_cactus"), data.tags().get("fluid:minecraft:lava")));
        families.put("net.minecraft.world.level.block.PitcherCropBlock", new PitcherCropBehavior(data.tags().get("block:minecraft:supports_crops"), data.tags().get("fluid:minecraft:water")));
        families.put("net.minecraft.world.level.block.SnowyBlock", new SnowyBehavior(data.tags().get("block:minecraft:snow")));
        families.put("net.minecraft.world.level.block.ButtonBlock", new ButtonBehavior());
        families.put("net.minecraft.world.level.block.LeverBlock", new LeverBehavior());
        families.put("net.minecraft.world.level.block.ScaffoldingBlock", new ScaffoldingBehavior());
        families.put("net.minecraft.world.level.block.LanternBlock", new LanternBehavior(support));
        families.put("net.minecraft.world.level.block.LeafLitterBlock", new VegetationBehavior(data, VegetationBehavior.Kind.LEAF_LITTER));
        families.put("net.minecraft.world.level.block.FlowerBedBlock", new VegetationBehavior(data, VegetationBehavior.Kind.FLOWER_BED));
        families.put("net.minecraft.world.level.block.RepeaterBlock", new RepeaterBehavior());
        families.put("net.minecraft.world.level.block.DiodeBlock", new DiodeBehavior());
        families.put("net.minecraft.world.level.block.ComparatorBlock", new ComparatorBehavior(data, items, registries));
        families.put("net.minecraft.world.level.block.NetherPortalBlock", new NetherPortalBehavior(data));
        families.put("net.minecraft.world.level.block.RedstoneWireBlock", new RedstoneWireBehavior());
        families.put("net.minecraft.world.level.block.LecternBlock", new LecternBehavior(data.tags().get("item:minecraft:lectern_books")));
        families.put("net.minecraft.world.level.block.LightningRodBlock", new LightningRodBehavior());
        families.put("net.minecraft.world.level.block.ObserverBlock", new ObserverBehavior());
        families.put("net.minecraft.world.level.block.FarmlandBlock", new FarmlandBehavior(data.tags().get("block:minecraft:maintains_farmland")));
        families.put("net.minecraft.world.level.block.PathBlock", new PathBehavior());
        families.put("net.minecraft.world.level.block.SculkSensorBlock", new SculkSensorBehavior());
        families.put("net.minecraft.world.level.block.CalibratedSculkSensorBlock", new CalibratedSculkSensorBehavior());
        families.put("net.minecraft.world.level.block.TripWireHookBlock", new TripWireHookBehavior());
        families.put("net.minecraft.world.level.block.LiquidBlock", new BlockPickupBehavior(null, true));
        families.put("net.minecraft.world.level.block.PowderSnowBlock", new BlockPickupBehavior("minecraft:powder_snow_bucket", false));
        families.put("net.minecraft.world.level.block.BubbleColumnBlock", new BubbleColumnBehavior(data));
        families.put("net.minecraft.world.level.block.CampfireBlock", new CampfireBehavior(data.tags().get("item:minecraft:douses_campfires")));
        families.put("net.minecraft.world.level.block.CandleBlock", new CandleBehavior(support));
        families.put("net.minecraft.world.level.block.SlabBlock", new SlabBehavior());
        families.put("net.minecraft.world.level.block.TurtleEggBlock", new TurtleEggBehavior());
        families.put("net.minecraft.world.level.block.BarrierBlock", new BarrierBehavior());
        families.put("net.minecraft.world.level.block.SeagrassBlock", new VegetationBehavior(data, VegetationBehavior.Kind.SEAGRASS));
        families.put("net.minecraft.world.level.block.TallSeagrassBlock", new DoublePlantBehavior(data, DoublePlantBehavior.Kind.SEAGRASS));
        families.put("net.minecraft.world.level.block.SeaPickleBlock", new VegetationBehavior(data, VegetationBehavior.Kind.SEA_PICKLE));
        families.put("net.minecraft.world.level.block.MushroomBlock", new VegetationBehavior(data, VegetationBehavior.Kind.MUSHROOM));
        families.put("net.minecraft.world.level.block.ShelfMushroomBlock", new ShelfMushroomBehavior());
        families.put("net.minecraft.world.level.block.GrowingPlantHeadBlock", new GrowingPlantBehavior(data, HEAD, PLAIN));
        families.put("net.minecraft.world.level.block.GrowingPlantBodyBlock", new GrowingPlantBehavior(data, BODY, PLAIN));
        families.put("net.minecraft.world.level.block.KelpBlock", new GrowingPlantBehavior(data, HEAD, KELP));
        families.put("net.minecraft.world.level.block.KelpPlantBlock", new GrowingPlantBehavior(data, BODY, KELP));
        families.put("net.minecraft.world.level.block.CaveVinesBlock", new GrowingPlantBehavior(data, HEAD, CAVE_VINES));
        families.put("net.minecraft.world.level.block.CaveVinesPlantBlock", new GrowingPlantBehavior(data, BODY, CAVE_VINES));
        families.put("net.minecraft.world.level.block.BigDripleafBlock", new BigDripleafBehavior(data.tags().get("block:minecraft:supports_big_dripleaf")));
        families.put("net.minecraft.world.level.block.BigDripleafStemBlock", new BigDripleafStemBehavior(data.tags().get("block:minecraft:supports_big_dripleaf")));
        families.put("net.minecraft.world.level.block.SmallDripleafBlock", new DoublePlantBehavior(data, DoublePlantBehavior.Kind.DRIPLEAF));
        families.put("net.minecraft.world.level.block.MangrovePropaguleBlock", new MangrovePropaguleBehavior(
            data.tags().get("block:minecraft:supports_mangrove_propagule"), data.tags().get("block:minecraft:supports_hanging_mangrove_propagule")));
        families.put("net.minecraft.world.level.block.MultifaceBlock", new MultifaceBehavior());
        families.put("net.minecraft.world.level.block.DoorBlock", new DoorBehavior());
        families.put("net.minecraft.world.level.block.TrapDoorBlock", new TrapDoorBehavior());
        families.put("net.minecraft.world.level.block.SignBlock", new SignBehavior());
        families.put("net.minecraft.world.level.block.CeilingHangingSignBlock", new CeilingHangingSignBehavior(data.tags().get("block:minecraft:all_hanging_signs")));
        families.put("net.minecraft.world.level.block.WallHangingSignBlock", new WallHangingSignBehavior(data.tags().get("block:minecraft:wall_hanging_signs")));
        families.put("net.minecraft.world.level.block.CreakingHeartBlock", new CreakingHeartBehavior(data.tags().get("block:minecraft:pale_oak_logs")));
        families.put("net.minecraft.world.level.block.FireBlock", new FireBehavior(data.tags().get("block:minecraft:soul_fire_base_blocks"),
            data.registry().block("minecraft:fire").bindings().get("FireBlock.igniteOdds")));
        families.put("net.minecraft.world.level.block.SoulFireBlock", new SoulFireBehavior(data.tags().get("block:minecraft:soul_fire_base_blocks")));
        var connectionRules = new ConnectionRules(data);
        families.put("net.minecraft.world.level.block.FenceGateBlock", new FenceGateBehavior(connectionRules.walls));
        families.put("net.minecraft.world.level.block.IronBarsBlock", new HorizontalConnectionBehavior(connectionRules, HorizontalConnectionBehavior.Kind.BARS));
        families.put("net.minecraft.world.level.block.FenceBlock", new HorizontalConnectionBehavior(connectionRules, HorizontalConnectionBehavior.Kind.FENCE));
        families.put("net.minecraft.world.level.block.WallBlock", new WallBehavior(connectionRules));
        families.put("net.minecraft.world.level.block.StairBlock", new StairBehavior());
        families.put("net.minecraft.world.level.block.ChestBlock", new ChestBehavior());
        families.put("net.minecraft.world.level.block.TrappedChestBlock", new TrappedChestBehavior());
        families.put("net.minecraft.world.level.block.ShulkerBoxBlock", new ShulkerBoxBehavior());
        families.put("net.minecraft.world.level.block.CopperChestBlock", new CopperChestBehavior(data.tags().get("block:minecraft:copper_chests")));
        families.put("net.minecraft.world.level.block.NoteBlock", new NoteBehavior(data.tags().get("item:minecraft:noteblock_top_instruments")));
        families.put("net.minecraft.world.level.block.HugeMushroomBlock", new HugeMushroomBehavior());
        families.put("net.minecraft.world.level.block.ChorusPlantBlock", new ChorusPlantBehavior(data.tags().get("block:minecraft:supports_chorus_plant")));
        families.put("net.minecraft.world.level.block.ChorusFlowerBlock", new ChorusFlowerBehavior(data.tags().get("block:minecraft:supports_chorus_flower")));
        families.put("net.minecraft.world.level.block.CocoaBlock", new CocoaBehavior(data.tags().get("block:minecraft:supports_cocoa")));
        families.put("net.minecraft.world.level.block.VineBlock", new VineBehavior());
        families.put("net.minecraft.world.level.block.ConcretePowderBlock", new ConcretePowderBehavior(data.tags().get("fluid:minecraft:water")));
        families.put("net.minecraft.world.level.block.AbstractBedBlock", new BedBehavior());
        families.put("net.minecraft.world.level.block.HangingMossBlock", new HangingMossBehavior());
        families.put("net.minecraft.world.level.block.MossyCarpetBlock", new MossyCarpetBehavior());
        families.put("net.minecraft.world.level.block.PotentSulfurBlock", new PotentSulfurBehavior(
            data.tags().get("block:minecraft:causes_continuous_geyser_eruptions"), data.tags().get("block:minecraft:causes_periodic_geyser_eruptions")));
        families.put("net.minecraft.world.level.block.AttachedStemBlock", new AttachedStemBehavior(data));
        families.put("net.minecraft.world.level.block.TripWireBlock", new TripWireBehavior());
        families.put("net.minecraft.world.level.block.BambooStalkBlock", new BambooStalkBehavior(data.tags().get("block:minecraft:supports_bamboo")));
        families.put("net.minecraft.world.level.block.BambooSaplingBlock", new BambooSaplingBehavior(data.tags().get("block:minecraft:supports_bamboo")));
        families.put("net.minecraft.world.level.block.BellBlock", new BellBehavior(support));
        int leavesTemplate = data.registry().blocks().stream().filter(block -> block.bindings().get("classHierarchy").contains("net.minecraft.world.level.block.LeavesBlock"))
            .findFirst().orElseThrow().defaultState();
        families.put("net.minecraft.world.level.block.LeavesBlock", new LeavesBehavior(data.tags().get("block:minecraft:prevents_nearby_leaf_decay"), leavesTemplate));
        families.put("net.minecraft.world.level.block.SpeleothemBlock", new SpeleothemBehavior(data.tags().get("block:minecraft:speleothems")));
        families.putAll(BasicBlockRules.load(data));
        families.putAll(additionalFamilies);
        int blockIndex = 0;
        for (var block : data.registry().blocks()) {
            String hierarchy = block.bindings().get("classHierarchy");
            if (hierarchy == null) throw new IllegalArgumentException("Missing class ancestry for " + block.key());
            for (String type : hierarchy.split(",")) {
                BlockBehavior family = families.get(type);
                if (family != null) { byBlock[blockIndex] = family; break; }
            }
            if (byBlock[blockIndex] == null) throw new IllegalArgumentException("Missing base family for " + block.key());
            blockIndex++;
        }
    }

    @Override public BlockBehavior apply(int state) { return byBlock[data.registry().blockIndex(state)]; }
}
