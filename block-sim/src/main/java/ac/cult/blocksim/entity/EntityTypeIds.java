package ac.cult.blocksim.entity;

/** Pre-resolved handles in the bundled 26.3 model; independent of host registry IDs. */
public final class EntityTypeIds {
    public static final int ARMOR_STAND = id("armor_stand");
    public static final int AXOLOTL = id("axolotl");
    public static final int BEE = id("bee");
    public static final int FOX = id("fox");
    public static final int BAT = id("bat");
    public static final int CAMEL = id("camel");
    public static final int CHICKEN = id("chicken");
    public static final int COD = id("cod");
    public static final int COW = id("cow");
    public static final int DOLPHIN = id("dolphin");
    public static final int DONKEY = id("donkey");
    public static final int ENDERMITE = id("endermite");
    public static final int END_CRYSTAL = id("end_crystal");
    public static final int EVOKER = id("evoker");
    public static final int FALLING_BLOCK = id("falling_block");
    public static final int TNT = id("tnt");
    public static final int FIREWORK_ROCKET = id("firework_rocket");
    public static final int FISHING_BOBBER = id("fishing_bobber");
    public static final int GOAT = id("goat");
    public static final int HAPPY_GHAST = id("happy_ghast");
    public static final int HOGLIN = id("hoglin");
    public static final int HORSE = id("horse");
    public static final int ILLUSIONER = id("illusioner");
    public static final int ITEM = id("item");
    public static final int LLAMA = id("llama");
    public static final int MAGMA_CUBE = id("magma_cube");
    public static final int MOOSHROOM = id("mooshroom");
    public static final int MULE = id("mule");
    public static final int OAK_BOAT = id("oak_boat");
    public static final int PARROT = id("parrot");
    public static final int PHANTOM = id("phantom");
    public static final int PIG = id("pig");
    public static final int PIGLIN = id("piglin");
    public static final int PILLAGER = id("pillager");
    public static final int PLAYER = id("player");
    public static final int PUFFERFISH = id("pufferfish");
    public static final int RAVAGER = id("ravager");
    public static final int SALMON = id("salmon");
    public static final int SHULKER = id("shulker");
    public static final int SILVERFISH = id("silverfish");
    public static final int SKELETON = id("skeleton");
    public static final int SKELETON_HORSE = id("skeleton_horse");
    public static final int SLIME = id("slime");
    public static final int SPIDER = id("spider");
    public static final int STRIDER = id("strider");
    public static final int TADPOLE = id("tadpole");
    public static final int TROPICAL_FISH = id("tropical_fish");
    public static final int TURTLE = id("turtle");
    public static final int VINDICATOR = id("vindicator");
    public static final int WITCH = id("witch");
    public static final int ZOGLIN = id("zoglin");
    public static final int ZOMBIE = id("zombie");
    public static final int ZOMBIE_HORSE = id("zombie_horse");
    public static final int ZOMBIFIED_PIGLIN = id("zombified_piglin");

    private EntityTypeIds() {}

    private static int id(String key) {
        var type = EntityTypes.defaults().byKey(key);
        if (type == null) throw new ExceptionInInitializerError("Missing entity type " + key);
        return type.id();
    }
}
