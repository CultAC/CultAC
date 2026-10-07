package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.interaction.PlacementContext;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class FireBehavior extends BaseFireBehavior {
    private final Map<String, Integer> igniteOdds;
    public FireBehavior(Set<String> soulFireBases, String generatedIgniteOdds) {
        super(soulFireBases);
        var odds = new HashMap<String, Integer>();
        JsonParser.parseString(generatedIgniteOdds).getAsJsonObject().entrySet().forEach(entry -> odds.put(entry.getKey(), entry.getValue().getAsInt()));
        igniteOdds = Map.copyOf(odds);
    }

    @Override
    public int placementState(BlockDefinition block, PlacementContext context) { return stateForPlacement(context.level(), block.defaultState(), context.clickedPos()); }

    public int stateForPlacement(SimLevel level, int defaultState, BlockPos pos) {
        BlockPos below = pos.relative(Direction.DOWN); int belowState = level.stateAt(below);
        if (canBurn(level, belowState) || level.isFaceSturdy(belowState, below, Direction.UP)) return defaultState;
        int result = defaultState;
        for (Direction direction : Direction.values()) {
            if (direction != Direction.DOWN) result = level.registry().with(result, direction.name().toLowerCase(Locale.ROOT),
                Boolean.toString(canBurn(level, level.stateAt(pos.relative(direction)))));
        }
        return result;
    }

    private int igniteOdds(SimLevel level, int state) {
        return level.registry().hasProperty(state, "waterlogged") && bool(level, state, "waterlogged") ? 0
            : igniteOdds.getOrDefault(level.registry().block(state).key(), 0);
    }

    private boolean canBurn(SimLevel level, int state) { return igniteOdds(level, state) > 0; }

    private boolean isValidFireLocation(SimLevel level, BlockPos pos) {
        for (Direction direction : Direction.values()) if (canBurn(level, level.stateAt(pos.relative(direction)))) return true;
        return false;
    }

    @Override
    public boolean canSurvive(SimLevel level, int state, BlockPos pos) {
        BlockPos below = pos.relative(Direction.DOWN);
        return level.isFaceSturdy(level.stateAt(below), below, Direction.UP) || isValidFireLocation(level, pos);
    }

    private int stateWithAge(SimLevel level, BlockPos pos, int age) {
        int placement = fireState(level, pos);
        return level.registry().block(placement).key().equals("minecraft:fire") ? level.registry().with(placement, "age", Integer.toString(age)) : placement;
    }

    @Override
    public int updateShape(SimLevel level, int state, BlockPos pos, Direction direction, BlockPos neighborPos, int neighborState) {
        return canSurvive(level, state, pos) ? stateWithAge(level, pos, number(level, state, "age")) : level.registry().block("minecraft:air").defaultState();
    }
}
