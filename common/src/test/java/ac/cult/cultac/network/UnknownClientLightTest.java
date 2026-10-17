package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.SimAction;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.Vec3;
import ac.cult.blocksim.interaction.BlockHit;
import ac.cult.blocksim.interaction.Hand;
import ac.cult.blocksim.interaction.InteractionKind;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.blockplace.ClientBlockActions;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import org.junit.jupiter.api.Test;

class UnknownClientLightTest {
    private static final BlockPos CLICKED = new BlockPos(0, 63, 0);

    @Test
    void mushroomPlacesOnAnySolidBlock() {
        for (String mushroom : new String[] {"minecraft:brown_mushroom", "minecraft:red_mushroom"}) {
            try (var fixture = fixture(mushroom, "minecraft:stone")) {
                var prediction = predict(fixture);
                assertTrue(prediction.result().interaction().consumesAction(), mushroom);
                int placed = state(mushroom);
                assertTrue(prediction.writes().stream()
                        .anyMatch(write -> write.getKey().equals(CLICKED.above()) && write.getValue() == placed));
            }
        }
    }

    @Test
    void pitcherCropAcceptsBoneMeal() {
        try (var fixture = fixture("minecraft:bone_meal", "minecraft:pitcher_crop")) {
            var interaction = predict(fixture).result().interaction();
            assertEquals(InteractionKind.SUCCESS, interaction.kind());
        }
    }

    private static int state(String key) {
        return DataTables.defaults().registry().block(key).defaultState();
    }

    private static RecordReceiveFixture fixture(String held, String clicked) {
        var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3);
        var player = fixture.player;
        player.x = .5;
        player.y = 64;
        player.z = -3;
        player.gamemode = GameMode.SURVIVAL;
        player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, .5, 64, -3);
        player.getInventory().inventory.setHeldItem(OfflineCultTestBootstrap.item(held, 8));
        for (int x = -1; x <= 0; x++)
            for (int z = -1; z <= 0; z++) player.compensatedWorld.ensureValidationChunkLoaded(x, z);
        player.compensatedWorld.updateBlock(CLICKED, state(clicked));
        return fixture;
    }

    private static ClientBlockActions.Prediction predict(RecordReceiveFixture fixture) {
        var hit = new BlockHit(
                new ac.cult.blocksim.engine.BlockPos(CLICKED.x(), CLICKED.y(), CLICKED.z()),
                Direction.UP,
                new Vec3(.5, 64, .5),
                false);
        return ClientBlockActions.predict(fixture.player, new SimAction.UseOn(Hand.MAIN_HAND, hit), 0, 0);
    }
}
