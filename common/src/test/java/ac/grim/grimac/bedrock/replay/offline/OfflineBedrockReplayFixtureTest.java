package ac.grim.grimac.bedrock.replay.offline;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.latency.CompensatedWorld;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class OfflineBedrockReplayFixtureTest {
    private static final Path SCENARIOS = Path.of("/home/hunter/Downloads/CultAC/bedrock-smoketest-scenarios");
    private static final UUID PLAYER_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    public void jumpScenarioLoadsSourceFixtureAndAuthInputs() throws Exception {
        OfflineBedrockReplayScenario scenario = OfflineBedrockReplayScenario.load(
                SCENARIOS.resolve("small-scenarios/jump"));
        assertEquals("jump", scenario.name());

        List<?> frames = OfflineBedrockAuthInputCapture.read(scenario.packetsPath(), PLAYER_UUID);
        assertFalse(frames.isEmpty());

        SpongeSchematicCompensatedWorldLoader.bootstrapMinecraft();
        GrimPlayer player = Mockito.mock(GrimPlayer.class);
        CompensatedWorld world = new CompensatedWorld(player);
        SpongeSchematicCompensatedWorldLoader.LoadedSchematic loaded =
                SpongeSchematicCompensatedWorldLoader.load(scenario.schematicPath(), world);

        assertTrue(loaded.nonAirBlocks() > 0);
        assertFalse(world.chunks.isEmpty());
        assertTrue(hasNonAirBlockInLoadedBounds(world, loaded));
    }

    private static boolean hasNonAirBlockInLoadedBounds(
            CompensatedWorld world,
            SpongeSchematicCompensatedWorldLoader.LoadedSchematic loaded
    ) {
        for (int y = 0; y < loaded.height(); y++) {
            for (int z = 0; z < loaded.length(); z++) {
                for (int x = 0; x < loaded.width(); x++) {
                    if (!world.getBlockDataAt(
                            loaded.originX() + x,
                            loaded.originY() + y,
                            loaded.originZ() + z).getMaterial().isAir()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
