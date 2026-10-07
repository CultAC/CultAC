package ac.cult.blocksim;

import ac.cult.blocksim.behavior.*;
import ac.cult.blocksim.data.*;
import ac.cult.blocksim.engine.*;
import ac.cult.blocksim.interaction.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source-derived regressions for placement-only support and client sign results. */
class SignAndSoilTest {
    private static final DataTables DATA;
    private static final BlockPos POS = new BlockPos(0, 64, 0);
    static { try { DATA = DataTables.load("26.3"); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    private static int state(String name) { return DATA.registry().block("minecraft:" + name).defaultState(); }
    private static int with(int state, String key, String value) { return DATA.registry().with(state, key, value); }

    @Test void soilUsesDirtUnderSolidCeilingsButFenceGatesMaintainIt() {
        var level = level(Map.of(POS.relative(Direction.UP), state("stone"), POS.relative(Direction.DOWN), state("stone")), Map.of());
        for (String block : new String[]{"farmland", "dirt_path"}) {
            var context = context(level, block, Direction.UP, 0, false);
            assertFalse(level.behavior(state(block)).canSurvive(level, state(block), POS));
            assertEquals(state("dirt"), level.behavior(state(block)).placementState(DATA.registry().block("minecraft:" + block), context));
            assertEquals(state(block), level.behavior(state(block)).updateShape(level, state(block), POS, Direction.UP, POS.relative(Direction.UP), state("stone")));
        }
        level = level(Map.of(POS.relative(Direction.UP), state("oak_fence_gate"), POS.relative(Direction.DOWN), state("stone")), Map.of());
        assertTrue(level.behavior(state("farmland")).canSurvive(level, state("farmland"), POS));
        assertTrue(level.behavior(state("dirt_path")).canSurvive(level, state("dirt_path"), POS));
    }

    @Test void standingSignsAndBannersAcceptLegacySolidFenceSupport() {
        var level = level(Map.of(POS.relative(Direction.DOWN), state("oak_fence")), Map.of());
        assertTrue(level.behavior(state("oak_sign")).canSurvive(level, state("oak_sign"), POS));
        assertTrue(level.behavior(state("white_banner")).canSurvive(level, state("white_banner"), POS));
    }

    @Test void wallHangingSignSupportIsPlacementOnly() {
        var level = level(Map.of(), Map.of()); int sign = state("oak_wall_hanging_sign");
        var behavior = (WallHangingSignBehavior) level.behavior(sign);
        assertFalse(behavior.canPlace(level, sign, POS));
        assertTrue(behavior.canSurvive(level, sign, POS));
        assertEquals(sign, behavior.updateShape(level, sign, POS, Direction.EAST, POS.relative(Direction.EAST), state("air")));
    }

    @Test void ceilingHangingSignSnapsOnFullCeilingAndKeepsYawWhenSneaking() {
        var level = level(Map.of(POS.relative(Direction.UP), state("stone")), Map.of());
        var block = DATA.registry().block("minecraft:oak_hanging_sign"); var behavior = level.behavior(block.defaultState());
        int snapped = behavior.placementState(block, context(level, "oak_hanging_sign", Direction.DOWN, 22.5F, false));
        assertEquals("false", DATA.registry().value(snapped, "attached"));
        assertEquals("8", DATA.registry().value(snapped, "rotation"));
        int middle = behavior.placementState(block, context(level, "oak_hanging_sign", Direction.DOWN, 22.5F, true));
        assertEquals("true", DATA.registry().value(middle, "attached"));
        assertEquals("9", DATA.registry().value(middle, "rotation"));
    }

    @Test void clientSignUseConsumesWithoutWorldOrInventoryChange() {
        var level = level(Map.of(POS, state("oak_sign")), Map.of(POS, new BlockEntityData("minecraft:sign", Components.EMPTY)));
        var context = context(level, "stone", Direction.UP, 0, false);
        var use = new UseContext(level, context.player(), Hand.MAIN_HAND, context.stack(), new BlockHit(POS, Direction.UP, new Vec3(0.5, 65, 0.5), false));
        assertEquals(SimInteraction.CONSUME, level.behavior(state("oak_sign")).useWithoutItem(state("oak_sign"), use));
        assertEquals(SimInteraction.CONSUME, level.behavior(state("oak_sign")).useItemOn(state("oak_sign"), use));
        assertEquals(4, context.stack().count()); assertTrue(level.writes().isEmpty());
        var items = new ItemRegistry(DATA); var dye = items.stack("minecraft:red_dye", 4);
        assertEquals(SimInteraction.SUCCESS, level.behavior(state("oak_sign")).useItemOn(state("oak_sign"), new UseContext(level, context.player(), Hand.MAIN_HAND, dye, use.hit())));
        assertEquals(4, dye.count()); assertTrue(level.writes().isEmpty());
    }

    @Test void signPropertyChangesKeepEntityAndDifferentBlockCreatesFreshEntity() {
        var waxed = new BlockEntityData("minecraft:sign", Components.parse("{\"is_waxed\":true}"));
        var level = level(Map.of(POS, state("oak_sign")), Map.of(POS, waxed));
        assertTrue(level.setBlock(POS, with(state("oak_sign"), "rotation", "7"), 2));
        assertEquals(waxed, level.blockEntityAt(POS));
        assertTrue(level.setBlock(POS, state("spruce_sign"), 2));
        assertEquals("minecraft:sign", level.blockEntityAt(POS).type());
        assertNotSame(waxed, level.blockEntityAt(POS));
        assertFalse(level.blockEntityAt(POS).data().get("is_waxed").getAsBoolean());
        assertNotNull(level.blockEntityAt(POS).savedData());
        assertTrue(level.setBlock(POS, state("air"), 2)); assertNull(level.blockEntityAt(POS));
    }

    private static PlacementContext context(SimLevel level, String item, Direction face, float yaw, boolean secondary) {
        var items = new ItemRegistry(DATA); var stack = items.stack("minecraft:" + item, 4);
        var player = new SimPlayer(new SimPlayer.State(new Vec3(0.5, 64, 0.5), yaw, 0, secondary, false, true, false, SimPlayer.GameMode.SURVIVAL), stack, items.empty());
        var clicked = POS.relative(face.opposite());
        return new PlacementContext(level, player, Hand.MAIN_HAND, stack, new BlockHit(clicked, face, new Vec3(0.5, face == Direction.DOWN ? 65 : 64, 0.5), false));
    }
    private static SimLevel level(Map<BlockPos, Integer> states, Map<BlockPos, BlockEntityData> entities) {
        SimWorldView view = new SimWorldView() {
            public int stateAt(BlockPos pos) { return states.getOrDefault(pos, state("air")); }
            public boolean isLoaded(BlockPos pos) { return true; }
            public boolean isSectionEmpty(BlockPos pos) { return false; }
            public int minY() { return -64; } public int height() { return 384; }
            public boolean isWithinBorder(BlockPos pos) { return true; }
            public boolean creakingActiveAt(BlockPos pos) { return false; }
            public boolean waterEvaporatesAt(BlockPos pos) { return false; }
            public BlockEntityData blockEntityAt(BlockPos pos) { return entities.get(pos); }
        };
        return new SimLevel(view, DATA.registry(), new BehaviorRegistry(DATA));
    }
}
