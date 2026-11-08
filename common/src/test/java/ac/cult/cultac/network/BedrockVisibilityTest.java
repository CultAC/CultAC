package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.bedrock.player.BedrockPlayerState;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.protocol.BedrockProtocolVersion;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.combat.BedrockEntityReach;
import ac.cult.cultac.checks.impl.visibility.BedrockAttackMobVisibility;
import ac.cult.cultac.checks.impl.visibility.BedrockBreakVisibility;
import ac.cult.cultac.checks.impl.visibility.BedrockPlaceVisibility;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.visibility.BedrockPickBlocks;
import org.junit.jupiter.api.Test;

/**
 * A Bedrock client's picks through blocks. The client stands at 0.5, 64, -1.5 without a modelled camera, so it may
 * have any eye height from 0.4 to 1.62001; a zombie stands at 0.5, 64, 0.75, the front of its box grown by the pick
 * margin at z 0.35, and a wall, when built, fills x -1 to 2 and y 64 to 66 at z -1.
 */
class BedrockVisibilityTest {
    private static final Vec3 ZOMBIE_HIT = new Vec3(0.5, 65, 0.35);
    private static final Vec3 LOW_ZOMBIE_HIT = new Vec3(0.5, 64.6, 0.35);
    private static final BlockPos TARGET = new BlockPos(0, 64, 2);
    private static final int NORTH = 2;

    @Test
    void anAttackThroughAWallIsFlaggedAndOneInTheOpenIsNot() throws Exception {
        try (var setup = new Setup()) {
            var visibility = setup.check(BedrockAttackMobVisibility.class);
            var reach = setup.check(BedrockEntityReach.class);
            setup.zombie();

            reach.onBedrockAttack(42, ZOMBIE_HIT, 1.0E-5);
            assertEquals(0, visibility.violations, "nothing stands between the client and the zombie");

            setup.wall("stone");
            reach.onBedrockAttack(42, ZOMBIE_HIT, 1.0E-5);
            assertEquals(1, visibility.violations, "the wall hides the zombie from every eye the client may have");
        }
    }

    @Test
    void aStairBlocksOnlyWithItsSlabHalf() throws Exception {
        try (var setup = new Setup()) {
            var visibility = setup.check(BedrockAttackMobVisibility.class);
            var reach = setup.check(BedrockEntityReach.class);
            setup.zombie();
            // Only a stair's slab half is certain to block a Bedrock pick.
            setup.row(64, "oak_stairs[facing=south,half=bottom]");
            reach.onBedrockAttack(42, LOW_ZOMBIE_HIT, 1.0E-5);
            assertEquals(0, visibility.violations, "a standing eye sees the hit over the stair's slab half");

            setup.row(64, "stone");
            reach.onBedrockAttack(42, LOW_ZOMBIE_HIT, 1.0E-5);
            assertEquals(1, visibility.violations, "a full block row hides the low hit from every eye");
        }
    }

    @Test
    void aClosedDoorBlocksWithItsThinnerBedrockPlateAndAnOpenOneDoesNot() throws Exception {
        try (var setup = new Setup()) {
            var visibility = setup.check(BedrockAttackMobVisibility.class);
            var reach = setup.check(BedrockEntityReach.class);
            setup.zombie();
            setup.row(64, "oak_door[facing=north,half=lower,open=false,hinge=left]");
            setup.row(65, "oak_door[facing=north,half=upper,open=false,hinge=left]");
            reach.onBedrockAttack(42, ZOMBIE_HIT, 1.0E-5);
            assertEquals(1, visibility.violations, "a closed door's plate fills the opening");

            setup.row(64, "oak_door[facing=north,half=lower,open=true,hinge=left]");
            setup.row(65, "oak_door[facing=north,half=upper,open=true,hinge=left]");
            reach.onBedrockAttack(42, ZOMBIE_HIT, 1.0E-5);
            assertEquals(1, visibility.violations, "an open door stands aside");
        }
    }

    @Test
    void blocksTheClientMayNotHoldDoNotBlock() throws Exception {
        try (var setup = new Setup()) {
            var visibility = setup.check(BedrockAttackMobVisibility.class);
            var reach = setup.check(BedrockEntityReach.class);
            setup.zombie();
            var world = setup.player().compensatedWorld;
            // The client's own placement, which Geyser may have refused and restored on the client.
            world.startPredicting();
            for (int x = -1; x <= 1; x++) {
                world.updateBlock(x, 64, -1, Setup.state("stone"));
                world.updateBlock(x, 65, -1, Setup.state("stone"));
            }
            world.stopPredicting(0);
            reach.onBedrockAttack(42, ZOMBIE_HIT, 1.0E-5);
            assertEquals(0, visibility.violations, "a predicted wall may not stand on the client");

            for (int x = -1; x <= 1; x++) {
                world.handleServerBlockUpdate(x, 64, -1, Setup.state("stone"), 0);
                world.handleServerBlockUpdate(x, 65, -1, Setup.state("stone"), 0);
            }
            reach.onBedrockAttack(42, ZOMBIE_HIT, 1.0E-5);
            assertEquals(1, visibility.violations, "the server's update confirmed the wall");

            // A removal the client has not acknowledged yet may already have opened the wall.
            int unanswered = setup.player().lastTransactionSent.get() + 1000;
            for (int x = -1; x <= 1; x++) world.queueServerBlockUpdate(x, 65, -1, Setup.state("air"), unanswered);
            reach.onBedrockAttack(42, ZOMBIE_HIT, 1.0E-5);
            assertEquals(1, visibility.violations, "the pending removal opens the top of the wall");
        }
    }

    @Test
    void anEyeInsideABlockIsNotJudged() throws Exception {
        try (var setup = new Setup()) {
            var visibility = setup.check(BedrockAttackMobVisibility.class);
            var reach = setup.check(BedrockEntityReach.class);
            setup.zombie();
            setup.wall("stone");
            // The client's head is in a block, which its pick passes through.
            setup.block(0, 65, -2, "stone");
            reach.onBedrockAttack(42, ZOMBIE_HIT, 1.0E-5);
            assertEquals(0, visibility.violations);
        }
    }

    @Test
    void aUseOnAHiddenFaceIsFlaggedAndADeferredOneAtTheTickEnd() throws Exception {
        try (var setup = new Setup()) {
            var visibility = setup.check(BedrockPlaceVisibility.class);
            Vec3 click = new Vec3(0.5, 0.5, 0);
            visibility.dropsUse(TARGET, NORTH, click, true);
            assertEquals(0, visibility.violations, "the stone's north face is in plain view");

            setup.wall("stone");
            visibility.dropsUse(TARGET, NORTH, click, true);
            assertEquals(1, visibility.violations, "the wall hides the clicked point");

            visibility.dropsUse(TARGET, NORTH, click, false);
            assertEquals(1, visibility.violations, "a use the client's tick made waits for the tick end");
            setup.tick();
            assertEquals(2, visibility.violations);
        }
    }

    @Test
    void aBreakOfAHiddenBlockIsFlagged() throws Exception {
        try (var setup = new Setup()) {
            var visibility = setup.check(BedrockBreakVisibility.class);
            Vec3 feet = new Vec3(setup.player().x, setup.player().y, setup.player().z);
            visibility.dropsAction("START_BREAK", TARGET, NORTH, feet);
            assertEquals(0, visibility.violations, "the stone is in plain view");

            setup.wall("stone");
            visibility.dropsAction("START_BREAK", TARGET, NORTH, feet);
            assertEquals(1, visibility.violations, "the wall hides the whole block");

            // The input's move may have stepped around the wall before the pick.
            visibility.dropsAction("START_BREAK", TARGET, NORTH, feet.add(2.5, 0, 0));
            assertEquals(1, visibility.violations, "the claimed position sees past the wall");
        }
    }

    @Test
    void aBedrockPickBlocksOnlyWithBoxesInsideItsPickShape() throws Exception {
        try (var setup = new Setup()) {
            assertBox(setup, "oak_slab[type=top]", 0, 0.5, 0, 1, 1, 1);
            assertBox(setup, "oak_stairs[facing=east,half=top]", 0, 0.5, 0, 1, 1, 1);
            assertBox(setup, "oak_door[facing=north,half=lower,open=false,hinge=left]", 0, 0, 0.82, 1, 1, 1);
            assertBox(
                    setup,
                    "oak_trapdoor[facing=north,half=bottom,open=false]",
                    0.001,
                    0.001,
                    0.001,
                    0.999,
                    0.18,
                    0.999);
            assertBox(setup, "oak_fence[east=true]", 6 / 16.0, 0, 6 / 16.0, 10 / 16.0, 1, 10 / 16.0);
            assertBox(setup, "glass_pane[north=true,south=true]", 7 / 16.0, 0, 7 / 16.0, 9 / 16.0, 1, 9 / 16.0);
            assertBox(setup, "cobblestone_wall[up=true]", 5 / 16.0, 0, 5 / 16.0, 11 / 16.0, 13 / 16.0, 11 / 16.0);
            assertBox(setup, "stone", 0, 0, 0, 1, 1, 1);
            setup.block(3, 64, 3, "oak_sapling");
            assertNull(BedrockPickBlocks.core(setup.player(), 3, 64, 3), "a sapling is no certain blocker");
        }
    }

    private static void assertBox(
            Setup setup, String block, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        setup.block(3, 64, 3, block);
        SimpleCollisionBox box = BedrockPickBlocks.core(setup.player(), 3, 64, 3);
        assertNotNull(box, block);
        double[] actual = {box.minX - 3, box.minY - 64, box.minZ - 3, box.maxX - 3, box.maxY - 64, box.maxZ - 3};
        double[] expected = {minX, minY, minZ, maxX, maxY, maxZ};
        assertArrayEquals(expected, actual, 1.0E-6, block);
    }

    private static final class Setup implements AutoCloseable {
        final RecordConsumerServices services = new RecordConsumerServices();
        final RecordReceiveFixture fixture = new RecordReceiveFixture(ProtocolVersion.V26_3);

        Setup() throws Exception {
            var player = fixture.player;
            player.gamemode = GameMode.SURVIVAL;
            player.x = .5;
            player.y = 64;
            player.z = -1.5;
            player.registryState = new ClientComponentRegistries();
            var world = player.compensatedWorld;
            var dimension = player.getWorldRegistries().dimension("minecraft:overworld");
            world.setLastClientboundDimension("minecraft:overworld", dimension.dimension());
            world.setDimension("minecraft:overworld", dimension);
            world.ensureValidationChunkLoaded(0, 0);
            world.ensureValidationChunkLoaded(0, -1);
            world.ensureValidationChunkLoaded(-1, 0);
            world.ensureValidationChunkLoaded(-1, -1);
            block(0, 64, 2, "stone");
            player.checkManager.allChecks.values().forEach(check -> check.setEnabled(false));
            player.movementPlatform = MovementPlatform.BEDROCK;
            player.bedrockState = new BedrockPlayerState(player.playerUUID);
            player.lastX = player.x;
            player.lastY = player.y;
            player.lastZ = player.z;
            tick();
            tick();
        }

        CultPlayer player() {
            return fixture.player;
        }

        <T extends Check> T check(Class<T> type) {
            var check = fixture.player.checkManager.getCheck(type);
            check.setEnabled(true);
            return check;
        }

        void tick() {
            var player = fixture.player;
            player.bedrockState.offerAuthInputFrame(BedrockAuthInputFrame.builder(player.playerUUID)
                    .protocolVersion(BedrockProtocolVersion.V1_26_60.protocol())
                    .inputMode(1)
                    .rotation(0, 0, 0)
                    .build());
            player.checkManager.endBedrockClientTick();
        }

        void zombie() {
            fixture.player.compensatedEntities.addEntity(42, EntityTypeIds.ZOMBIE, new Vec3(0.5, 64, 0.75), 0, 0, 0);
        }

        void wall(String block) {
            row(64, block);
            row(65, block);
        }

        void row(int y, String block) {
            for (int x = -1; x <= 1; x++) block(x, y, -1, block);
        }

        void block(int x, int y, int z, String block) {
            fixture.player.compensatedWorld.applyBlockChangeRawDANGER(x, y, z, state(block));
        }

        static int state(String key) {
            var registry = DataTables.defaults().registry();
            int bracket = key.indexOf('[');
            int result = registry.block("minecraft:" + (bracket < 0 ? key : key.substring(0, bracket)))
                    .defaultState();
            if (bracket >= 0)
                for (String property :
                        key.substring(bracket + 1, key.length() - 1).split(",")) {
                    String[] entry = property.split("=", 2);
                    result = registry.with(result, entry[0], entry[1]);
                }
            return result;
        }

        @Override
        public void close() throws Exception {
            try {
                fixture.close();
            } finally {
                services.close();
            }
        }
    }
}
