package ac.cult.blocksim.engine;

import ac.cult.blocksim.data.*;
import com.google.gson.JsonElement;
import java.util.IdentityHashMap;
import java.util.Map;

/** Client comparator inputs, bound once to generated block families. No redstone ticks run here. */
public final class AnalogSignals {
    private enum Kind {
        CONTAINER, CHEST, CAKE, CANDLE_CAKE, LEVEL, HONEY, EYE, BULB, STATUE, ANCHOR,
        COMMAND, CRAFTER, JUKEBOX, LECTERN, SENSOR, DETECTOR_RAIL, CLIENT_ZERO, LAVA_CAULDRON
    }
    private final Map<BlockDefinition, Kind> kinds = new IdentityHashMap<>();
    private final ItemTemplates templates;
    private final InteractionRegistries registries;

    public AnalogSignals(DataTables data, ItemRegistry items, InteractionRegistries registries) {
        templates = new ItemTemplates(items); this.registries = registries;
        var families = new java.util.HashMap<String, Kind>();
        for (String name : new String[]{"AbstractFurnaceBlock", "BarrelBlock", "BrewingStandBlock", "DecoratedPotBlock", "DispenserBlock", "HopperBlock", "ShulkerBoxBlock"})
            families.put(name, Kind.CONTAINER);
        families.put("ChestBlock", Kind.CHEST); families.put("CakeBlock", Kind.CAKE);
        families.put("CandleCakeBlock", Kind.CANDLE_CAKE); families.put("ComposterBlock", Kind.LEVEL);
        families.put("LayeredCauldronBlock", Kind.LEVEL); families.put("BeehiveBlock", Kind.HONEY);
        families.put("EndPortalFrameBlock", Kind.EYE); families.put("CopperBulbBlock", Kind.BULB);
        families.put("CopperGolemStatueBlock", Kind.STATUE); families.put("RespawnAnchorBlock", Kind.ANCHOR);
        families.put("CommandBlock", Kind.COMMAND); families.put("CrafterBlock", Kind.CRAFTER);
        families.put("JukeboxBlock", Kind.JUKEBOX); families.put("LecternBlock", Kind.LECTERN);
        families.put("SculkSensorBlock", Kind.SENSOR); families.put("DetectorRailBlock", Kind.DETECTOR_RAIL);
        families.put("ChiseledBookShelfBlock", Kind.CLIENT_ZERO); families.put("ShelfBlock", Kind.CLIENT_ZERO);
        families.put("AbstractCauldronBlock", Kind.CLIENT_ZERO);
        families.put("CreakingHeartBlock", Kind.CLIENT_ZERO); families.put("LavaCauldronBlock", Kind.LAVA_CAULDRON);
        for (var block : data.registry().blocks()) for (String family : block.bindings().get("classHierarchy").split(",")) {
            Kind kind = families.get(family.substring(family.lastIndexOf('.') + 1));
            if (kind != null) { kinds.put(block, kind); break; }
        }
    }

    public boolean hasOutput(SimLevel level, int state) { return kinds.containsKey(level.registry().block(state)); }
    public int output(SimLevel level, int state, BlockPos pos, Direction direction) {
        Kind kind = kinds.get(level.registry().block(state));
        if (kind == null) return 0;
        return switch (kind) {
            case CLIENT_ZERO -> 0; // Shelf/bookcase explicitly reject client reads; heart output is set only by server ticks.
            case CAKE -> (7 - number(level, state, "bites")) * 2;
            case CANDLE_CAKE -> 14;
            case LEVEL -> number(level, state, "level");
            case HONEY -> number(level, state, "honey_level");
            case EYE -> bool(level, state, "eye") ? 15 : 0;
            case BULB -> bool(level, level.stateAt(pos), "lit") ? 15 : 0;
            case STATUE -> switch (level.registry().value(state, "copper_golem_pose")) { case "standing" -> 1; case "sitting" -> 2; case "running" -> 3; case "star" -> 4; default -> throw new IllegalArgumentException("Unknown statue pose"); };
            case ANCHOR -> (int) Math.floor(number(level, state, "charges") / 4.0F * 15);
            case LAVA_CAULDRON -> 3;
            case DETECTOR_RAIL -> bool(level, state, "powered") ? level.detectorRailOutputAt(pos) : 0;
            case CHEST -> chestOutput(level, state, pos);
            default -> entityOutput(level, state, pos, kind);
        };
    }

    private int entityOutput(SimLevel level, int state, BlockPos pos, Kind kind) {
        var entity = level.blockEntityAt(pos);
        if (entity == null) return 0;
        var fields = entity.data();
        return switch (kind) {
            case CONTAINER -> containerOutput(entity);
            case COMMAND -> fields.integer("SuccessCount", 0);
            case SENSOR -> level.registry().value(state, "sculk_sensor_phase").equals("active") ? fields.integer("last_vibration_frequency", 0) : 0;
            case CRAFTER -> {
                var occupied = new java.util.HashSet<Integer>();
                var disabled = fields.get("disabled_slots");
                if (disabled != null) for (var slot : disabled.getAsJsonArray()) occupied.add(slot.getAsInt());
                var items = fields.get("Items");
                if (items != null) for (var item : items.getAsJsonArray()) if (!templates.decode(item).isEmpty()) occupied.add(item.getAsJsonObject().get("Slot").getAsInt());
                yield occupied.size();
            }
            case JUKEBOX -> {
                var item = fields.get("RecordItem");
                var playable = item == null ? null : templates.decode(item).components().get("minecraft:jukebox_playable");
                yield playable == null ? 0 : registries.resolve("jukebox_song", playable).getAsJsonObject().get("comparator_output").getAsInt();
            }
            case LECTERN -> {
                if (!bool(level, state, "has_book")) yield 0;
                int pages = fields.integer("page_count", 0);
                float progress = pages > 1 ? fields.integer("Page", 0) / (pages - 1.0F) : 1.0F;
                yield (int) Math.floor(progress * 14) + fields.integer("has_book_content", 0);
            }
            default -> throw new IllegalStateException("Unexpected entity input " + kind);
        };
    }

    private int chestOutput(SimLevel level, int state, BlockPos pos) {
        if (chestBlocked(level, pos)) return 0;
        var first = level.blockEntityAt(pos);
        if (first == null) return 0;
        String type = level.registry().value(state, "type");
        if (type.equals("single")) return containerOutput(first);
        Direction facing = Direction.valueOf(level.registry().value(state, "facing").toUpperCase(java.util.Locale.ROOT));
        BlockPos otherPos = pos.relative(type.equals("left") ? facing.clockwise() : facing.counterClockwise());
        int other = level.stateAt(otherPos);
        if (!level.registry().sameBlock(state, other) || level.registry().value(other, "type").equals("single")
                || level.registry().value(other, "type").equals(type) || !level.registry().value(other, "facing").equals(level.registry().value(state, "facing")))
            return containerOutput(first);
        var second = level.blockEntityAt(otherPos);
        if (second == null) return containerOutput(first);
        if (chestBlocked(level, otherPos)) return 0;
        // DoubleBlockCombiner orders RIGHT first; preserve float summation in container slot order.
        float fill = type.equals("right") ? fill(second, fill(first)) : fill(first, fill(second));
        return discrete(fill / 54);
    }
    private boolean chestBlocked(SimLevel level, BlockPos pos) {
        BlockPos above = pos.relative(Direction.UP);
        return level.isRedstoneConductor(level.stateAt(above), above) || level.hasSittingCatAt(pos);
    }
    private int containerOutput(BlockEntityData entity) {
        int size = switch (entity.type()) {
            case "minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker" -> 3;
            case "minecraft:brewing_stand", "minecraft:hopper" -> 5;
            case "minecraft:dispenser", "minecraft:dropper" -> 9;
            case "minecraft:decorated_pot" -> 1;
            default -> 27;
        };
        return discrete(fill(entity) / size);
    }
    private float fill(BlockEntityData entity) {
        return fill(entity, 0);
    }
    private float fill(BlockEntityData entity, float total) {
        var fields = entity.data();
        if (entity.type().equals("minecraft:decorated_pot")) return total + stackFill(fields.get("item"));
        var items = fields.get("Items");
        if (items != null) {
            var slots = new java.util.TreeMap<Integer, JsonElement>();
            for (var item : items.getAsJsonArray()) slots.put(item.getAsJsonObject().get("Slot").getAsInt(), item);
            for (var item : slots.values()) total += stackFill(item);
        }
        return total;
    }
    private float stackFill(JsonElement item) {
        if (item == null) return 0;
        var stack = templates.decode(item);
        return stack.isEmpty() ? 0 : (float) stack.count() / Math.min(99, stack.maxStackSize());
    }
    private static int discrete(float fill) { return (int) Math.floor(fill * 14) + (fill > 0 ? 1 : 0); }
    private static int number(SimLevel level, int state, String property) { return Integer.parseInt(level.registry().value(state, property)); }
    private static boolean bool(SimLevel level, int state, String property) { return Boolean.parseBoolean(level.registry().value(state, property)); }
}
