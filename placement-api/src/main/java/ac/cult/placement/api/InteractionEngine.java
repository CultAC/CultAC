package ac.cult.placement.api;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Client interactions over compensated state. Minecraft objects never cross this boundary. */
public interface InteractionEngine extends AutoCloseable {
    enum Operation {
        USE_ON,
        USE,
        BREAK
    }

    /**
     * The only item components that change a client action's result on these paths (block
     * writes, consumption, inventory counts, cooldowns): the placed state and its block entity
     * data (lectern and jukebox state), adventure predicates, potion contents (water on mud and
     * cauldrons), dye (whether cauldron washing consumes the click), the tool (creative
     * breaking), use cooldowns, equipment slots and the 26.3 block transformer. Every other component takes the item's
     * default; the components vanilla reads only server-side (such as the debug stick's
     * selection) or only for display never change a client result.
     */
    Set<String> TRANSFERRED_COMPONENTS = Set.of(
            "minecraft:block_state",
            "minecraft:block_entity_data",
            "minecraft:can_place_on",
            "minecraft:can_break",
            "minecraft:potion_contents",
            "minecraft:dyed_color",
            "minecraft:tool",
            "minecraft:use_cooldown",
            "minecraft:equippable",
            "minecraft:block_transformer");

    /**
     * An item type and count, plus {@code components}: the stack's patch over its type's
     * defaults restricted to {@link #TRANSFERRED_COMPONENTS}, encoded by vanilla's component
     * patch codec, or {@code null} when it changes none of them. The internal 26.3
     * action view resolves a supplied block-transformer holder with its original direct
     * codec; this representation is not a native network component patch.
     */
    record Stack(String item, int count, String components) {
        public static final Stack EMPTY = new Stack("minecraft:air", 0, null);

        public static Stack vanilla(String item, int count) {
            return new Stack(item, count, null);
        }
    }

    record Actor(
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            String pose,
            String gameMode,
            boolean secondaryUse,
            int food,
            boolean gameMaster,
            int selectedSlot,
            List<Stack> inventory,
            boolean cooldown,
            double scale,
            double blockRange,
            boolean instabuild) {
        public Actor {
            inventory = List.copyOf(inventory);
        }

        public Actor(
                double x,
                double y,
                double z,
                float yaw,
                float pitch,
                String pose,
                String gameMode,
                boolean secondaryUse,
                int food,
                boolean gameMaster,
                int selectedSlot,
                List<Stack> inventory,
                boolean cooldown,
                double scale,
                double blockRange) {
            this(
                    x,
                    y,
                    z,
                    yaw,
                    pitch,
                    pose,
                    gameMode,
                    secondaryUse,
                    food,
                    gameMaster,
                    selectedSlot,
                    inventory,
                    cooldown,
                    scale,
                    blockRange,
                    gameMode.equals("CREATIVE"));
        }
    }

    /**
     * One client action. {@code dimensionType} is the client's dimension type, encoded by vanilla's
     * direct codec, or {@code null} for the vanilla type of {@code dimension}. Block, item and fluid
     * tags come from {@code world.tags()} at the client's compensated packet boundary,
     * or the startup bindings when no per-client snapshot is supplied.
     */
    record Request(
            Operation operation,
            PlacementEngine.World world,
            Actor actor,
            String hand,
            PlacementEngine.Pos clicked,
            String face,
            double hitX,
            double hitY,
            double hitZ,
            boolean inside,
            String dimension,
            String dimensionType) {}

    record Cooldown(String group, int ticks) {}

    record Result(
            boolean consumes,
            boolean success,
            List<PlacementEngine.Write> writes,
            Map<Integer, Stack> inventory,
            List<Cooldown> cooldowns,
            boolean usingItem,
            String usedHand,
            int food) {
        public Result {
            writes = List.copyOf(writes);
            inventory = Map.copyOf(inventory);
            cooldowns = List.copyOf(cooldowns);
        }
    }

    Result interact(Request request);

    @Override
    void close();
}
