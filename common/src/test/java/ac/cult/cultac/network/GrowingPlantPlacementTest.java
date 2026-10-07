package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.SimAction;
import ac.cult.blocksim.data.BlockTags;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.Vec3;
import ac.cult.blocksim.interaction.BlockHit;
import ac.cult.blocksim.interaction.Hand;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.blockplace.ClientBlockActions;
import org.junit.jupiter.api.Test;

class GrowingPlantPlacementTest {
    private static final BlockPos PLACED = new BlockPos(0, 64, 0);

    @Test
    void bothVinesPredictSupportedPlacementAndInventoryConsumption() {
        for (boolean upward : new boolean[] {false, true}) {
            try (var fixture = fixture(upward)) {
                var support = upward ? PLACED.below() : PLACED.above();
                fixture.player.compensatedWorld.updateBlock(support, state("minecraft:stone"));
                var prediction = predict(fixture, upward);
                assertTrue(prediction.result().interaction().consumesAction());
                assertEquals(7, prediction.result().player().inventory().get(0).count());
                int head = prediction.writes().stream()
                        .filter(write -> write.getKey().equals(PLACED))
                        .findFirst()
                        .orElseThrow()
                        .getValue();
                var registry = DataTables.defaults().registry();
                assertEquals(
                        upward ? "minecraft:twisting_vines" : "minecraft:weeping_vines",
                        registry.block(head).key());
                int age = Integer.parseInt(registry.value(head, "age"));
                assertTrue(age >= 0 && age < 25);
            }
        }
    }

    @Test
    void unsupportedVinesStillRejectPlacement() {
        for (boolean upward : new boolean[] {false, true}) {
            try (var fixture = fixture(upward)) {
                var prediction = predict(fixture, upward);
                assertFalse(prediction.result().interaction().consumesAction());
                assertTrue(prediction.writes().isEmpty());
                assertTrue(prediction.inventory().isEmpty());
            }
        }
    }

    @Test
    void extendingVinesConvertsPreviousHeadToBody() {
        for (boolean upward : new boolean[] {false, true}) {
            try (var fixture = fixture(upward)) {
                var registry = DataTables.defaults().registry();
                String head = upward ? "minecraft:twisting_vines" : "minecraft:weeping_vines";
                String body = head + "_plant";
                var previous = upward ? PLACED.below() : PLACED.above();
                fixture.player.compensatedWorld.updateBlock(previous, registry.with(state(head), "age", "12"));
                fixture.player.compensatedWorld.updateBlock(
                        upward ? previous.below() : previous.above(), state("minecraft:stone"));
                var prediction = predict(fixture, upward);
                assertTrue(prediction.result().interaction().consumesAction());
                assertTrue(prediction.writes().stream()
                        .anyMatch(write -> write.getKey().equals(previous)
                                && registry.block(write.getValue()).key().equals(body)));
                assertTrue(prediction.writes().stream()
                        .anyMatch(write -> write.getKey().equals(PLACED)
                                && registry.block(write.getValue()).key().equals(head)));
            }
        }
    }

    @Test
    void randomInitialAgesHaveIdenticalVineGeometryAndClimbability() {
        var registry = DataTables.defaults().registry();
        for (String key : new String[] {"minecraft:weeping_vines", "minecraft:twisting_vines"}) {
            int base = state(key);
            for (int age = 0; age < 25; age++) {
                int state = registry.with(base, "age", Integer.toString(age));
                assertSame(registry.shapes().outline(base), registry.shapes().outline(state));
                assertSame(registry.shapes().collision(base), registry.shapes().collision(state));
                assertTrue(BlockTags.CLIMBABLE.test(state));
            }
        }
    }

    private static int state(String key) {
        return DataTables.defaults().registry().block(key).defaultState();
    }

    private static RecordReceiveFixture fixture(boolean upward) {
        var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3);
        var player = fixture.player;
        player.x = .5;
        player.y = 64;
        player.z = -3;
        player.gamemode = ac.cult.cultac.protocol.value.GameMode.SURVIVAL;
        player.boundingBox = ac.cult.cultac.utils.nmsutil.GetBoundingBox.getCollisionBoxForPlayer(player, .5, 64, -3);
        player.getInventory()
                .inventory
                .setHeldItem(OfflineCultTestBootstrap.item(
                        upward ? "minecraft:twisting_vines" : "minecraft:weeping_vines", 8));
        for (int x = -1; x <= 0; x++)
            for (int z = -1; z <= 0; z++) player.compensatedWorld.ensureValidationChunkLoaded(x, z);
        return fixture;
    }

    private static ClientBlockActions.Prediction predict(RecordReceiveFixture fixture, boolean upward) {
        var clicked = new ac.cult.blocksim.engine.BlockPos(0, upward ? 63 : 65, 0);
        var hit = new BlockHit(
                clicked, upward ? Direction.UP : Direction.DOWN, new Vec3(.5, upward ? 64 : 65, .5), false);
        return ClientBlockActions.predict(fixture.player, new SimAction.UseOn(Hand.MAIN_HAND, hit), 0, 0);
    }
}
