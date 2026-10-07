package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.engine.*;
import ac.cult.blocksim.interaction.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Shared state assignments, support tags and constant client results.
 * Source citations and hashes for each binding live in the version manifest. */
public final class BasicBlockRules extends BlockBehavior {
    private enum ClientUse { PASS, SUCCESS, SIGN, GAME_MASTER, CONSUME, DAYLIGHT, BERRIES, PUMPKIN, TNT, VAULT, SHELF, BOOKSHELF }
    private final DataTables data;
    private final Map<String, String> placement;
    private final String support, signal;
    private final ClientUse useRule;
    private final SupportRules attachments;
    private final Direction updateDirection, directSignalDirection;
    private final boolean updateBehind, updateAny;
    private final SignBehavior sign;
    private final SlotContainerUse slots;

    private BasicBlockRules(DataTables data, String[] row) {
        this.data = data;
        placement = new LinkedHashMap<>();
        if (!row[1].equals("-")) for (String assignment : row[1].split(",")) {
            String[] pair = assignment.split("=", 2);
            placement.put(pair[0], pair[1]);
        }
        support = row[2];
        useRule = ClientUse.valueOf(row[3]);
        sign = useRule == ClientUse.SIGN ? new SignBehavior() : null;
        slots = useRule == ClientUse.SHELF || useRule == ClientUse.BOOKSHELF ? new SlotContainerUse(data) : null;
        signal = row[4];
        attachments = new SupportRules(data.tags().get("block:minecraft:unstable_bottom_center"));
        updateBehind = row[5].equals("BEHIND"); updateAny = row[5].equals("ALL");
        updateDirection = row[5].equals("UP") ? Direction.UP : row[5].equals("DOWN") ? Direction.DOWN : null;
        directSignalDirection = row[6].equals("NONE") ? null : Direction.valueOf(row[6]);
    }

    public static Map<String, BlockBehavior> load(DataTables data) {
        String resource = "/block-sim/" + data.version() + "/basic-rules.tsv";
        var stream = BasicBlockRules.class.getResourceAsStream(resource);
        if (stream == null) throw new IllegalStateException("Missing block rules " + resource);
        var rules = new LinkedHashMap<String, BlockBehavior>();
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            for (String line : reader.lines().filter(line -> !line.isBlank() && !line.startsWith("#")).toList()) {
                String[] row = line.split("\t", -1);
                if (row.length != 7) throw new IllegalArgumentException("Invalid block rule: " + line);
                rules.put("net.minecraft.world.level.block." + row[0], new BasicBlockRules(data, row));
            }
        } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        return rules;
    }

    @Override public int placementState(BlockDefinition block, PlacementContext context) {
        if (placement.containsValue("ladder_wall") && !context.replacingClicked()) {
            int attached = context.level().stateAt(context.clickedPos().relative(context.clickedFace().opposite()));
            if (context.level().registry().block(attached) == block && facing(context.level(), attached) == context.clickedFace()) return -1;
        }
        int state = block.defaultState();
        for (var assignment : placement.entrySet()) {
            if (assignment.getValue().equals("block_state")) {
                String value = context.stack().components().stringMap("minecraft:block_state").get(assignment.getKey());
                if (value != null) state = context.level().registry().withIfValid(state, assignment.getKey(), value);
                continue;
            }
            if (assignment.getValue().equals("wall_support") || assignment.getValue().equals("ladder_wall") || assignment.getValue().equals("wall_blocking")) {
                state = wallPlacementState(state, context, assignment.getValue().equals("wall_blocking"));
                if (state < 0) return -1;
                continue;
            }
            String value = switch (assignment.getValue()) {
                case "horizontal_opposite" -> context.horizontalDirection().opposite().name().toLowerCase(Locale.ROOT);
                case "horizontal_clockwise" -> context.horizontalDirection().clockwise().name().toLowerCase(Locale.ROOT);
                case "look_opposite" -> context.nearestLookingDirection().opposite().name().toLowerCase(Locale.ROOT);
                case "clicked_face" -> context.clickedFace().name().toLowerCase(Locale.ROOT);
                case "end_rod" -> endRodDirection(block, context).name().toLowerCase(Locale.ROOT);
                case "clicked_orientation", "look_orientation" -> orientation(context, assignment.getValue().equals("clicked_orientation"));
                case "hopper_facing" -> (context.clickedFace().horizontal() ? context.clickedFace().opposite() : Direction.DOWN).name().toLowerCase(Locale.ROOT);
                case "clicked_axis" -> context.clickedFace().axisName();
                case "rail_shape" -> context.horizontalDirection().axisName().equals("x") ? "east_west" : "north_south";
                case "water" -> Boolean.toString(context.level().fluidAt(context.clickedPos()).is("minecraft:water"));
                case "full_water" -> Boolean.toString(data.tags().get("fluid:minecraft:water").contains(context.level().fluidAt(context.clickedPos()).type())
                    && context.level().fluidAt(context.clickedPos()).amount() == 8);
                case "powered" -> Boolean.toString(new Signals(context.level()).hasNeighborSignal(context.clickedPos()));
                case "yaw" -> Integer.toString(Rotation16.fromDegrees(context.rotation()));
                case "yaw_plus_180" -> Integer.toString(Rotation16.fromDegrees(context.rotation() + 180.0F));
                default -> assignment.getValue();
            };
            state = context.level().registry().with(state, assignment.getKey(), value);
        }
        return state;
    }

    @Override public boolean canSurvive(SimLevel level, int state, BlockPos pos) {
        if (support.equals("-")) return true;
        if (support.startsWith("floor_") || support.startsWith("wall_") || support.startsWith("ceiling_"))
            return attachments.testAttachment(support, level, state, pos, data.tags().get("fluid:minecraft:water"));
        int below = level.stateAt(pos.relative(Direction.DOWN));
        if (support.equals("non_air")) return !level.registry().facts(below).has(StateFacts.AIR);
        String tag = support.startsWith("@") ? level.registry().block(state).bindings().get(support.substring(1)) : "minecraft:" + support;
        return data.tags().get("block:" + tag).contains(level.registry().block(below).key());
    }
    @Override public ac.cult.blocksim.engine.shapes.VoxelShape outlineShape(SimLevel level, int state, BlockPos pos, ac.cult.blocksim.engine.EntityCollisionContext context) {
        if (useRule != ClientUse.CONSUME) return super.outlineShape(level, state, pos, context);
        return context.isHoldingItem("minecraft:light")
            ? ac.cult.blocksim.engine.shapes.Shapes.block() : ac.cult.blocksim.engine.shapes.Shapes.empty();
    }

    @Override public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        boolean check = updateAny || direction == updateDirection || updateBehind && direction == facing(level, state).opposite();
        return check && !canSurvive(level, state, pos) ? level.registry().block("minecraft:air").defaultState() : state;
    }

    @Override public SimInteraction useWithoutItem(int state, UseContext context) {
        return switch (useRule) {
            case SUCCESS -> SimInteraction.SUCCESS;
            case CONSUME -> SimInteraction.CONSUME;
            case DAYLIGHT -> context.player().state().mayBuild() ? SimInteraction.SUCCESS : SimInteraction.PASS;
            case BERRIES -> number(context.level(), state, "age") > 1 ? SimInteraction.SUCCESS : SimInteraction.PASS;
            case SIGN -> sign.useWithoutItem(state, context);
            case BOOKSHELF -> slots.use(state, context, true, false);
            case GAME_MASTER -> {
                var entity = context.level().blockEntityAt(context.clickedPos());
                yield entity != null && entity.type().equals(context.level().registry().block(state).bindings().get("blockEntity.type"))
                    && context.player().state().gameMaster() ? SimInteraction.SUCCESS : SimInteraction.PASS;
            }
            default -> SimInteraction.PASS;
        };
    }
    @Override public SimInteraction useItemOn(int state, UseContext context) {
        var stack = context.stack();
        return switch (useRule) {
            case SIGN -> sign.useItemOn(state, context);
            case SHELF, BOOKSHELF -> slots.use(state, context, useRule == ClientUse.BOOKSHELF, true);
            case PUMPKIN -> stack.is("minecraft:shears") ? SimInteraction.SUCCESS : SimInteraction.TRY_WITH_EMPTY_HAND;
            case TNT -> stack.is("minecraft:flint_and_steel") || stack.is("minecraft:fire_charge") ? SimInteraction.SUCCESS : SimInteraction.TRY_WITH_EMPTY_HAND;
            case VAULT -> !stack.isEmpty() && context.level().registry().value(state, "vault_state").equals("active") ? SimInteraction.SUCCESS_SERVER : SimInteraction.TRY_WITH_EMPTY_HAND;
            case BERRIES -> number(context.level(), state, "age") < 3 && stack.is("minecraft:bone_meal") ? SimInteraction.PASS : SimInteraction.TRY_WITH_EMPTY_HAND;
            default -> SimInteraction.TRY_WITH_EMPTY_HAND;
        };
    }
    @Override public int ownSignal(SimLevel level, int state, BlockPos pos) {
        return switch (signal) {
            case "power" -> number(level, state, "power");
            case "powered" -> bool(level, state, "powered") ? 15 : 0;
            case "lit_except_up", "lit_except_facing" -> bool(level, state, "lit") ? 15 : 0;
            case "test_powered" -> {
                var entity = level.blockEntityAt(pos);
                yield level.registry().value(state, "mode").equals("start") && entity != null
                    && entity.type().equals("minecraft:test_block") && entity.data().integer("powered", 0) != 0 ? 15 : 0;
            }
            default -> Integer.parseInt(signal);
        };
    }
    @Override public int signal(SimLevel level, int state, BlockPos pos, Direction direction) {
        if (signal.equals("lit_except_up") && direction == Direction.UP
            || signal.equals("lit_except_facing") && direction == facing(level, state)) return 0;
        return ownSignal(level, state, pos);
    }
    @Override public int directSignal(SimLevel level, int state, BlockPos pos, Direction direction) {
        return direction == directSignalDirection ? ownSignal(level, state, pos) : 0;
    }

    private static String orientation(PlacementContext context, boolean clicked) {
        Direction front = clicked ? context.clickedFace() : context.nearestLookingDirection().opposite();
        Direction top = front.horizontal() ? Direction.UP
            : !clicked && front == Direction.UP ? context.horizontalDirection() : context.horizontalDirection().opposite();
        return (front.name() + "_" + top.name()).toLowerCase(Locale.ROOT);
    }

    private static Direction endRodDirection(BlockDefinition block, PlacementContext context) {
        var level = context.level(); var face = context.clickedFace();
        int attached = level.stateAt(context.clickedPos().relative(face.opposite()));
        return level.registry().block(attached) == block && facing(level, attached) == face ? face.opposite() : face;
    }
    public static int wallPlacementState(BlockDefinition block, PlacementContext context) {
        return wallPlacementState(block.defaultState(), context, false);
    }
    private static int wallPlacementState(int base, PlacementContext context, boolean blocking) {
        var level = context.level();
        for (Direction direction : context.nearestLookingDirections()) {
            if (!direction.horizontal()) continue;
            int state = level.registry().with(base, "facing", direction.opposite().name().toLowerCase(Locale.ROOT));
            if (blocking) {
                int neighbor = level.stateAt(context.clickedPos().relative(direction));
                if (!level.behavior(neighbor).canBeReplaced(level, neighbor, context)) return state;
            } else if (level.behavior(state).canSurvive(level, state, context.clickedPos())) return state;
        }
        return -1;
    }
}
