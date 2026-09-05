package ac.cult.cultac.bedrock.replay.offline;

import java.nio.file.Path;
import org.junit.Assume;
import org.junit.Test;

public final class OfflineBedrockReplayFixtureExportToolTest {
    private static final Path REGION_DIR = Path.of(
            "/home/hunter/Downloads/actest/world/dimensions/minecraft/overworld/region");
    private static final Path SCENARIOS = Path.of(
            "/home/hunter/Downloads/CultAC/bedrock-smoketest-scenarios/small-scenarios");

    @Test
    public void exportMissingFixturesFromActestWorld() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("exportBedrockFixtures"));
        export("elytrahoney", 274, 78, -101, 293, 93, -84);
        export("ladder2", 272, 78, -101, 289, 92, -84);
        export("ladder3", 272, 78, -101, 289, 91, -78);
        export("stairs", 259, 78, -99, 282, 94, -83);
        export("waterlava", 276, 78, -108, 299, 90, -92);
        export("waterwithelytraon", 295, 79, -108, 312, 92, -91);
        export("bubblecolumncombined", 221, 76, -82, 236, 98, -63);
        export("bubblecolumndown1", 221, 76, -82, 236, 98, -63);
        export("bubblecolumndown2", 221, 76, -82, 236, 98, -63);
        export("bubblecolumnup1", 221, 76, -82, 236, 98, -63);
        export("bubblecolumnup2", 221, 76, -82, 236, 98, -63);
        export("candlestepping3", 261, 76, -90, 278, 91, -70);
    }

    @Test
    public void exportAdditionalRequiredFixturesFromActestWorld() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("exportBedrockFixtures"));
        export("stairsjumpspam", 229, 78, -77, 244, 95, -59);
    }

    @Test
    public void exportScaffolding2FixtureFromActestWorld() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("exportBedrockFixtures"));
        export("scaffolding2", 291, 76, -77, 307, 98, -63);
    }

    @Test
    public void exportScaffolding3FixtureFromActestWorld() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("exportBedrockFixtures"));
        export("scaffolding3", 291, 76, -77, 307, 98, -63);
    }

    @Test
    public void exportJumpingIntoWaterTowerFixtureFromActestWorld() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("exportBedrockFixtures"));
        export("jumpingintowatertower", 265, 75, -72, 279, 92, -52);
    }

    @Test
    public void exportFallingPowderSnowFixtureFromActestWorld() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("exportBedrockFixtures"));
        export("fallingpowdersnow", 269, 76, -58, 282, 95, -44);
    }

    private static void export(
            String scenario,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ
    ) throws Exception {
        OfflineBedrockReplayFixtureExporter.export(
                REGION_DIR,
                SCENARIOS.resolve(scenario),
                minX,
                minY,
                minZ,
                maxX,
                maxY,
                maxZ);
    }
}
