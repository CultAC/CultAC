package ac.cult.cultac.utils.inventory;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.ItemDefinition;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ModelRegistryData;

/** Pre-resolved item handles in the fixed model ID space. */
public final class ItemTypes {
    private static final java.util.List<ItemDefinition> ITEMS =
            DataTables.defaults().items();
    private static final ac.cult.cultac.protocol.data.IdTable NAMES =
            ModelRegistryData.load(ProtocolVersion.V26_3).registry("minecraft:item");

    private ItemTypes() {}

    private static ItemDefinition item(String key) {
        return ITEMS.get(NAMES.id(key));
    }

    public static final ItemDefinition AIR = item("minecraft:air");
    public static final ItemDefinition AXOLOTL_BUCKET = item("minecraft:axolotl_bucket");
    public static final ItemDefinition BOW = item("minecraft:bow");
    public static final ItemDefinition BUCKET = item("minecraft:bucket");
    public static final ItemDefinition CARROT_ON_A_STICK = item("minecraft:carrot_on_a_stick");
    public static final ItemDefinition COD_BUCKET = item("minecraft:cod_bucket");
    public static final ItemDefinition CROSSBOW = item("minecraft:crossbow");
    public static final ItemDefinition ELYTRA = item("minecraft:elytra");
    public static final ItemDefinition END_CRYSTAL = item("minecraft:end_crystal");
    public static final ItemDefinition FIRE_CHARGE = item("minecraft:fire_charge");
    public static final ItemDefinition GOAT_HORN = item("minecraft:goat_horn");
    public static final ItemDefinition LEATHER_BOOTS = item("minecraft:leather_boots");
    public static final ItemDefinition LIGHT = item("minecraft:light");
    public static final ItemDefinition MILK_BUCKET = item("minecraft:milk_bucket");
    public static final ItemDefinition POWDER_SNOW_BUCKET = item("minecraft:powder_snow_bucket");
    public static final ItemDefinition PUFFERFISH_BUCKET = item("minecraft:pufferfish_bucket");
    public static final ItemDefinition SALMON_BUCKET = item("minecraft:salmon_bucket");
    public static final ItemDefinition SHIELD = item("minecraft:shield");
    public static final ItemDefinition SPYGLASS = item("minecraft:spyglass");
    public static final ItemDefinition STONE = item("minecraft:stone");
    public static final ItemDefinition TADPOLE_BUCKET = item("minecraft:tadpole_bucket");
    public static final ItemDefinition TRIDENT = item("minecraft:trident");
    public static final ItemDefinition TROPICAL_FISH_BUCKET = item("minecraft:tropical_fish_bucket");
    public static final ItemDefinition WARPED_FUNGUS_ON_A_STICK = item("minecraft:warped_fungus_on_a_stick");
    public static final ItemDefinition WATER_BUCKET = item("minecraft:water_bucket");
    public static final ItemDefinition WRITABLE_BOOK = item("minecraft:writable_book");
    public static final ItemDefinition WRITTEN_BOOK = item("minecraft:written_book");
}
