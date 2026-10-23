package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.combat.EntityReach;
import ac.cult.cultac.checks.impl.combat.PlayerReach;
import ac.cult.cultac.checks.impl.visibility.AttackMobVisibility;
import ac.cult.cultac.checks.impl.visibility.AttackPlayerVisibility;
import ac.cult.cultac.checks.impl.visibility.BreakVisibility;
import ac.cult.cultac.checks.impl.visibility.InteractEntityVisibility;
import ac.cult.cultac.checks.impl.visibility.PlaceVisibility;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPacket;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.visibility.ClientVisibility;
import ac.cult.cultac.utils.visibility.ClientVisibilityProcessor;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.event.events.FlagEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

class InteractionVisibilityTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 2);

    @Test
    void attacksOnlyFlagTheirTargetCategory() throws Exception {
        for (int type : new int[] {EntityTypeIds.PLAYER, EntityTypeIds.COW, EntityTypeIds.ARMOR_STAND}) {
            try (var setup = new Setup()) {
                setup.wall("stone");
                var players = setup.check(AttackPlayerVisibility.class);
                var mobs = setup.check(AttackMobVisibility.class);
                var placing = setup.check(PlaceVisibility.class);
                var breaking = setup.check(BreakVisibility.class);
                setup.fixture.player.compensatedEntities.addEntity(42, type, new Vec3(.5, 64, 1.5), 0, 0, 0);
                assertTrue(setup.route(ServerboundPackets.INTERACT, attack(42)).isCancelled());
                assertEquals(type == EntityTypeIds.PLAYER ? 1 : 0, players.violations);
                assertEquals(type == EntityTypeIds.PLAYER ? 0 : 1, mobs.violations);
                assertEquals(0, placing.violations);
                assertEquals(0, breaking.violations);
                assertEquals("AttackPlayerVisibility", players.getConfigName());
                assertEquals("AttackMobVisibility", mobs.getConfigName());
                assertNotEquals(players.getStableKey(), mobs.getStableKey());
            }
        }
    }

    @Test
    void attackChecksIgnoreRightClicksAndMissingEntities() throws Exception {
        try (var setup = new Setup()) {
            setup.wall("stone");
            var players = setup.check(AttackPlayerVisibility.class);
            var mobs = setup.check(AttackMobVisibility.class);
            setup.fixture.player.compensatedEntities.addEntity(42, EntityTypeIds.COW, new Vec3(.5, 64, 1.5), 0, 0, 0);
            var interaction =
                    new ServerboundInteract(42, InteractAction.INTERACT, Hand.MAIN_HAND, Optional.empty(), false);
            assertFalse(setup.route(ServerboundPackets.INTERACT, interaction).isCancelled());
            assertFalse(setup.route(ServerboundPackets.INTERACT, attack(999)).isCancelled());
            assertEquals(0, players.violations);
            assertEquals(0, mobs.violations);
        }
    }

    @Test
    void placeAndBreakPacketModificationCanBeDisabledIndependently() throws Exception {
        try (var setup = new Setup()) {
            setup.wall("stone");
            var placing = setup.check(PlaceVisibility.class);
            var breaking = setup.check(BreakVisibility.class);
            placing.setEnabled(false);
            assertFalse(setup.route(ServerboundPackets.USE_ITEM_ON, use()).isCancelled());
            assertTrue(setup.route(ServerboundPackets.PLAYER_ACTION, dig()).isCancelled());
            placing.setEnabled(true);
            breaking.setEnabled(false);
            assertTrue(setup.route(ServerboundPackets.USE_ITEM_ON, use()).isCancelled());
            assertFalse(setup.route(ServerboundPackets.PLAYER_ACTION, dig()).isCancelled());
            assertEquals("PlaceVisibility", placing.getConfigName());
            assertEquals("BreakVisibility", breaking.getConfigName());
            assertNotEquals(placing.getStableKey(), breaking.getStableKey());
        }
    }

    @Test
    void playerAndMobPacketModificationCanBeDisabledIndependently() throws Exception {
        try (var setup = new Setup()) {
            setup.wall("stone");
            var players = setup.check(AttackPlayerVisibility.class);
            var mobs = setup.check(AttackMobVisibility.class);
            var entities = setup.fixture.player.compensatedEntities;
            entities.addEntity(42, EntityTypeIds.PLAYER, new Vec3(.5, 64, 1.5), 0, 0, 0);
            entities.addEntity(43, EntityTypeIds.COW, new Vec3(.5, 64, 1.5), 0, 0, 0);
            players.setEnabled(false);
            assertFalse(setup.route(ServerboundPackets.INTERACT, attack(42)).isCancelled());
            assertTrue(setup.route(ServerboundPackets.INTERACT, attack(43)).isCancelled());
            players.setEnabled(true);
            mobs.setEnabled(false);
            assertTrue(setup.route(ServerboundPackets.INTERACT, attack(42)).isCancelled());
            assertFalse(setup.route(ServerboundPackets.INTERACT, attack(43)).isCancelled());
        }
    }

    @Test
    void allFourChecksShareTheSelectionWorldUntilTheTickEnds() throws Exception {
        try (var setup = new Setup()) {
            setup.check(PlaceVisibility.class);
            setup.check(BreakVisibility.class);
            setup.check(AttackPlayerVisibility.class);
            setup.check(AttackMobVisibility.class);
            var player = setup.fixture.player;
            player.compensatedEntities.addEntity(42, EntityTypeIds.PLAYER, new Vec3(.5, 64, 1.5), 0, 0, 0);
            player.compensatedEntities.addEntity(43, EntityTypeIds.COW, new Vec3(.5, 64, 1.5), 0, 0, 0);
            setup.route(ServerboundPackets.USE_ITEM, new ServerboundUseItem(Hand.MAIN_HAND, 0, 0, 0));
            var world = player.compensatedWorld;
            world.startPredicting();
            world.updateBlock(0, 64, 0, state("stone"));
            world.updateBlock(0, 65, 0, state("stone"));
            world.stopPredicting(1);
            assertFalse(setup.route(ServerboundPackets.USE_ITEM_ON, use()).isCancelled());
            assertFalse(setup.route(ServerboundPackets.PLAYER_ACTION, dig()).isCancelled());
            assertFalse(setup.route(ServerboundPackets.INTERACT, attack(42)).isCancelled());
            assertFalse(setup.route(ServerboundPackets.INTERACT, attack(43)).isCancelled());
            player.checkManager.getListener(ClientVisibilityProcessor.class).onPlayerTickEnd(null);
            assertTrue(setup.route(ServerboundPackets.USE_ITEM_ON, use()).isCancelled());
            assertTrue(setup.route(ServerboundPackets.PLAYER_ACTION, dig()).isCancelled());
            assertTrue(setup.route(ServerboundPackets.INTERACT, attack(42)).isCancelled());
            assertTrue(setup.route(ServerboundPackets.INTERACT, attack(43)).isCancelled());
        }
    }

    @Test
    void aFailingAlertNeitherStopsTheCancelNorTheNextAlert() throws Exception {
        try (var setup = new Setup()) {
            setup.wall("stone");
            var player = setup.fixture.player;
            ConfigManager config = mock(ConfigManager.class);
            when(config.getBooleanElse(anyString(), anyBoolean())).thenAnswer(invocation -> invocation.getArgument(1));
            when(config.getStringElse(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
            when(config.getBooleanElse("test-mode", false)).thenReturn(true);
            when(config.getStringElse(eq("alerts-format"), anyString())).thenReturn("alert");
            when(config.getStringListElse(eq("Punishments"), any())).thenReturn((List)
                    List.of(new LinkedHashMap<>(Map.of(
                            "checks",
                            List.of("AttackPlayerVisibility"),
                            "commands",
                            List.of("1:0 [alert]", "1:0 [alert]")))));
            player.punishmentManager.reload(config);
            var visibility = player.checkManager.getCheck(AttackPlayerVisibility.class);
            PlatformPlayer platform = mock(PlatformPlayer.class);
            doThrow(new IllegalStateException("alert"))
                    .doNothing()
                    .when(platform)
                    .sendMessage(any(Component.class));
            player.platformPlayer = platform;
            player.compensatedEntities.addEntity(42, EntityTypeIds.PLAYER, new Vec3(.5, 64, 1.5), 0, 0, 0);

            assertTrue(setup.route(ServerboundPackets.INTERACT, attack(42)).isCancelled());
            assertEquals(1, visibility.violations);
            verify(platform, times(2)).sendMessage(any(Component.class));
        }
    }

    @Test
    void verboseNamesNonPlayerTargetTypesAndTheJudgedBlock() throws Exception {
        try (var setup = new Setup()) {
            setup.wall("stone");
            setup.check(AttackPlayerVisibility.class);
            setup.check(AttackMobVisibility.class);
            setup.check(InteractEntityVisibility.class);
            setup.check(PlaceVisibility.class);
            setup.check(BreakVisibility.class);
            var entities = setup.fixture.player.compensatedEntities;
            entities.addEntity(42, EntityTypeIds.PLAYER, new Vec3(.5, 64, 1.5), 0, 0, 0);
            entities.addEntity(43, EntityTypeIds.COW, new Vec3(.5, 64, 1.5), 0, 0, 0);
            List<String> verbose = new ArrayList<>();
            FlagEvent.Channel flags = CultAPI.INSTANCE.getEventBus().get(FlagEvent.class);
            FlagEvent.SupplierHandler record = (user, flagged, text, cancelled) -> {
                if (flagged.getCheckName().endsWith("Visibility"))
                    verbose.add(flagged.getCheckName() + ": " + text.get());
                return cancelled;
            };
            flags.onFlagSupplier(CultAPI.INSTANCE.getGrimPlugin(), record, 0, false);
            try {
                assertTrue(setup.route(ServerboundPackets.INTERACT, attack(42)).isCancelled());
                assertTrue(setup.route(ServerboundPackets.INTERACT, attack(43)).isCancelled());
                assertTrue(
                        setup.route(ServerboundPackets.INTERACT, interactAt(42)).isCancelled());
                assertTrue(
                        setup.route(ServerboundPackets.INTERACT, interactAt(43)).isCancelled());
                assertTrue(setup.route(ServerboundPackets.USE_ITEM_ON, use()).isCancelled());
                assertTrue(setup.route(ServerboundPackets.PLAYER_ACTION, dig()).isCancelled());
            } finally {
                flags.unsubscribe(record);
            }
            assertEquals(
                    List.of(
                            "AttackPlayerVisibility: ",
                            "AttackMobVisibility: type=minecraft:cow",
                            "InteractEntityVisibility: action=INTERACT_AT, type=minecraft:player",
                            "InteractEntityVisibility: action=INTERACT_AT, type=minecraft:cow",
                            "PlaceVisibility: block=minecraft:stone, pos=0, 64, 2, face=NORTH",
                            "BreakVisibility: action=START_DESTROY_BLOCK, block=minecraft:stone, pos=0, 64, 2, face=NORTH"),
                    verbose);
        }
    }

    private static ServerboundInteract interactAt(int entityId) {
        return new ServerboundInteract(
                entityId, InteractAction.INTERACT_AT, Hand.MAIN_HAND, Optional.of(new Vec3d(0, .5, -.3)), false);
    }

    private static ServerboundInteract attack(int entityId) {
        return new ServerboundInteract(entityId, InteractAction.ATTACK, Hand.MAIN_HAND, Optional.empty(), false);
    }

    private static ServerboundUseItemOn use() {
        return new ServerboundUseItemOn(Hand.MAIN_HAND, TARGET, Direction.NORTH, new Vec3d(.5, .5, 0), false, false, 1);
    }

    private static ServerboundPlayerAction dig() {
        return new ServerboundPlayerAction(PlayerAction.START_DESTROY_BLOCK, TARGET, Direction.NORTH, 2);
    }

    @Test
    void emptyHandUseAndDigBothRejectAWallWithoutReadingRotation() throws Exception {
        try (var setup = new Setup()) {
            setup.wall("stone");
            var place = setup.check(PlaceVisibility.class);
            var breaking = setup.check(BreakVisibility.class);
            for (float angle : new float[] {-180, -90, 0, 90, 180}) {
                setup.fixture.player.xRot = angle;
                setup.fixture.player.yRot = angle / 2;
                var use = new ServerboundUseItemOn(
                        Hand.MAIN_HAND, TARGET, Direction.NORTH, new Vec3d(.5, .5, 0), false, false, 1);
                double before = place.violations;
                place.onUseItemOn(
                        new PacketReceiveEvent<>(
                                setup.fixture.user, ConnectionPhase.PLAY, ServerboundPackets.USE_ITEM_ON, use),
                        setup.fixture.player,
                        use);
                assertEquals(before + 1, place.violations);
                var dig = new ServerboundPlayerAction(PlayerAction.START_DESTROY_BLOCK, TARGET, Direction.NORTH, 2);
                before = breaking.violations;
                breaking.onDig(
                        new PacketReceiveEvent<>(
                                setup.fixture.user, ConnectionPhase.PLAY, ServerboundPackets.PLAYER_ACTION, dig),
                        setup.fixture.player,
                        dig);
                assertEquals(before + 1, breaking.violations);
            }
        }
    }

    @Test
    void clearFrontFaceIsPossibleAndBackFaceIsImpossible() throws Exception {
        try (var setup = new Setup()) {
            assertFalse(setup.visibility.blockFaceHidden(TARGET, Direction.NORTH, 0));
            assertTrue(setup.visibility.blockFaceHidden(TARGET, Direction.SOUTH, 0));
            assertTrue(setup.visibility.blockFaceHidden(TARGET, Direction.SOUTH, .0002));
        }
    }

    @Test
    void glassBlocksPickingWhileAnOpenSlabRegionRemainsVisible() throws Exception {
        try (var setup = new Setup()) {
            setup.wall("glass");
            assertTrue(setup.visibility.blockFaceHidden(TARGET, Direction.NORTH, 0));
            setup.wall("air");
            setup.block(0, 64, 0, "oak_slab[type=bottom,waterlogged=false]");
            assertFalse(setup.visibility.blockFaceHidden(TARGET, Direction.NORTH, 0));
        }
    }

    @Test
    void entitySilhouetteMustBeCompletelyCovered() throws Exception {
        try (var setup = new Setup()) {
            var entity = new SimpleCollisionBox(.2, 64, 1.2, .8, 65.8, 1.8);
            assertFalse(setup.visibility.entityHidden(entity, 0));
            setup.wall("stone");
            assertTrue(setup.visibility.entityHidden(entity, 0));
            setup.block(0, 65, 0, "air");
            assertFalse(setup.visibility.entityHidden(entity, 0));
        }
    }

    @Test
    void entityReachUsesTheReceivedInteractionAttribute() throws Exception {
        try (var setup = new Setup()) {
            var target = new SimpleCollisionBox(.2, 64, 2, .8, 65.8, 2.5);
            assertTrue(setup.visibility.entityHidden(target, 0));
            setup.fixture
                    .player
                    .compensatedEntities
                    .getSelf()
                    .entityInteractionRange
                    .replace(4, List.of());
            assertFalse(setup.visibility.entityHidden(target, 0));
            setup.wall("stone");
            assertTrue(setup.visibility.entityHidden(target, 0));
        }
    }

    @Test
    void eyeBufferPermitsOneMillimetreWithoutExpandingTheTarget() throws Exception {
        try (var setup = new Setup()) {
            assertFalse(setup.visibility.entityHidden(new SimpleCollisionBox(.2, 64, 1.5005, .8, 65.8, 1.8), 0));
            assertTrue(setup.visibility.entityHidden(new SimpleCollisionBox(.2, 64, 1.5011, .8, 65.8, 1.8), 0));
            setup.wall("stone");
            assertTrue(setup.visibility.entityHidden(new SimpleCollisionBox(.2, 64, 1.5005, .8, 65.8, 1.8), 0));
        }
    }

    @Test
    void anEntityReachEnforcesIsLeftToReachBeyondEveryPickRange() throws Exception {
        for (int type : new int[] {EntityTypeIds.PLAYER, EntityTypeIds.COW, EntityTypeIds.OAK_BOAT}) {
            try (var setup = new Setup()) {
                var players = setup.check(AttackPlayerVisibility.class);
                var mobs = setup.check(AttackMobVisibility.class);
                setup.check(PlayerReach.class);
                setup.check(EntityReach.class);
                setup.block(0, 64, 2, "air");
                // 3.7 blocks from the eye to the hitbox: out of reach, in plain sight.
                setup.fixture.player.compensatedEntities.addEntity(42, type, new Vec3(.5, 64, 2.5), 0, 0, 0);
                assertFalse(setup.route(ServerboundPackets.INTERACT, attack(42)).isCancelled());
                assertEquals(0, players.violations + mobs.violations);
            }
        }
    }

    @Test
    void targetsReachDoesNotEnforceStayHiddenBeyondRange() throws Exception {
        for (int type : new int[] {EntityTypeIds.SHULKER, EntityTypeIds.ARMOR_STAND}) {
            try (var setup = new Setup()) {
                var mobs = setup.check(AttackMobVisibility.class);
                setup.check(EntityReach.class);
                setup.block(0, 64, 2, "air");
                setup.fixture.player.compensatedEntities.addEntity(42, type, new Vec3(.5, 64, 2.5), 0, 0, 0);
                assertTrue(setup.route(ServerboundPackets.INTERACT, attack(42)).isCancelled(), "type=" + type);
                assertEquals(1, mobs.violations, "type=" + type);
            }
        }
        try (var setup = new Setup()) {
            var reach = setup.check(EntityReach.class);
            setup.block(0, 64, 2, "air");
            setup.fixture.player.compensatedEntities.addEntity(
                    42, EntityTypeIds.OAK_BOAT, new Vec3(.5, 64, 2.5), 0, 0, 0);
            var boat = setup.fixture.player.compensatedEntities.getEntity(42);
            assertTrue(reach.enforcesReach(boat), "A Java client's boat is left to reach");
            assertTrue(reach.measuresBeyondReach(boat));
            setup.fixture.player.movementPlatform = MovementPlatform.BEDROCK;
            assertFalse(reach.enforcesReach(boat), "Reach does not enforce a Bedrock client's boat");
        }
    }

    @Test
    void anOccludedEyeInRangeIsNotRescuedByAnotherEyeBeyondRange() throws Exception {
        try (var setup = new Setup()) {
            var mobs = setup.check(AttackMobVisibility.class);
            // Standing and crouching eyes are within 2.95 blocks; the crawling eye is 3.08 blocks away.
            setup.fixture.player.compensatedEntities.addEntity(
                    42, EntityTypeIds.SILVERFISH, new Vec3(.5, 65.3, 1.65), 0, 0, 0);
            assertFalse(setup.route(ServerboundPackets.INTERACT, attack(42)).isCancelled());
            setup.fixture
                    .player
                    .checkManager
                    .getListener(ClientVisibilityProcessor.class)
                    .onPlayerTickEnd(null);
            // Blocks every ray from the two eyes in range; the crawling eye still sees under it.
            setup.block(0, 65, -1, "stone");
            assertTrue(setup.route(ServerboundPackets.INTERACT, attack(42)).isCancelled());
            assertEquals(1, mobs.violations);
            setup.fixture
                    .player
                    .checkManager
                    .getListener(ClientVisibilityProcessor.class)
                    .onPlayerTickEnd(null);
            setup.fixture
                    .player
                    .compensatedEntities
                    .getSelf()
                    .entityInteractionRange
                    .replace(3.2, List.of());
            assertFalse(
                    setup.route(ServerboundPackets.INTERACT, attack(42)).isCancelled(),
                    "Within range the crawling eye sees it");
        }
    }

    @Test
    void everyPossiblePoseHeightCanSupplyARealVisibleRay() throws Exception {
        // The blocker is close to the eye so the other two discrete heights
        // cannot slope into the opening before hitting it.
        try (var setup = new Setup()) {
            setup.block(0, 64, -1, "stone");
            setup.block(0, 65, -1, "oak_slab[type=top]");
            assertFalse(
                    setup.visibility.entityHidden(new SimpleCollisionBox(.2, 65.25, 1.2, .8, 65.3, 1.8), 0),
                    "Only the crouching eye can see this opening");
        }
        try (var setup = new Setup()) {
            setup.block(0, 65, -1, "stone");
            assertFalse(
                    setup.visibility.entityHidden(new SimpleCollisionBox(.2, 64.25, 1.2, .8, 64.45, 1.8), 0),
                    "Only the crawling/swimming/flying/spin eye can see this opening");
        }
        try (var setup = new Setup()) {
            setup.fixture.player.pose = ac.cult.cultac.utils.enums.Pose.SWIMMING;
            setup.block(0, 64, -1, "stone");
            setup.block(0, 65, -1, "oak_slab[type=bottom]");
            assertFalse(
                    setup.visibility.entityHidden(new SimpleCollisionBox(.2, 65.62, 1.2, .8, 65.65, 1.8), 0),
                    "The standing eye remains possible when the tracked pose is swimming");
        }
    }

    @Test
    void heightsBetweenPosesAreNotInvented() throws Exception {
        try (var setup = new Setup()) {
            setup.block(0, 64, -1, "oak_slab[type=bottom]");
            setup.block(0, 65, -1, "stone");
            assertTrue(setup.visibility.entityHidden(new SimpleCollisionBox(.2, 64.85, 1.2, .8, 64.9, 1.8), 0));
        }
    }

    @Test
    void touchingBlockSeamsDoNotBecomeVisibleWithEyeOrPacketPositionError() throws Exception {
        try (var setup = new Setup()) {
            setup.fixture.player.x = 1;
            for (int x = 0; x <= 1; x++) {
                setup.block(x, 64, 0, "stone");
                setup.block(x, 65, 0, "stone");
            }
            var entity = new SimpleCollisionBox(.99995, 64.25, 1.2, 1.00005, 65.75, 1.8);
            for (double error : new double[] {0, .0002, .03}) {
                assertTrue(setup.visibility.entityHidden(entity, error), "position error=" + error);
                assertTrue(setup.visibility.blockFaceHidden(TARGET, Direction.NORTH, error));
            }
            assertEquals(ClientVisibility.BlockVisibility.OCCLUDED, setup.visibility.blockVisibility(TARGET, 10));
        }
    }

    @Test
    void anEyeNextToAWallCannotUseTheBufferToEnterIt() throws Exception {
        try (var setup = new Setup()) {
            setup.wall("stone");
            setup.fixture.player.z = -.0005;
            assertTrue(setup.visibility.blockFaceHidden(TARGET, Direction.NORTH, 0));
            assertTrue(setup.visibility.entityHidden(new SimpleCollisionBox(.2, 64, 1.2, .8, 65.8, 1.8), 0));
            assertEquals(ClientVisibility.BlockVisibility.OCCLUDED, setup.visibility.blockVisibility(TARGET, 10));
        }
    }

    @Test
    void anAlternativePoseCannotPutTheEyeInsideTheCeiling() throws Exception {
        try (var setup = new Setup()) {
            setup.fixture.player.pose = ac.cult.cultac.utils.enums.Pose.SWIMMING;
            setup.block(0, 65, -2, "stone");
            // This entity contains the standing eye, which is inside the ceiling.
            // The actual crawling eye still has to ray through the ceiling to it.
            assertTrue(setup.visibility.entityHidden(new SimpleCollisionBox(.2, 65.5, -1.8, .8, 65.9, -1.2), 0));
        }
    }

    @Test
    void aRayCannotPassThroughTheContactPointOfADiagonalSolidCorner() throws Exception {
        try (var setup = new Setup()) {
            var player = setup.fixture.player;
            player.x = -.5;
            player.z = -.5;
            player.compensatedWorld.ensureValidationChunkLoaded(-1, -1);
            player.compensatedWorld.ensureValidationChunkLoaded(-1, 0);
            var target = new BlockPos(0, 64, 0);
            setup.block(0, 64, 0, "stone");
            for (int y : new int[] {64, 65}) {
                setup.block(0, y, -1, "stone");
                setup.block(-1, y, 0, "stone");
            }
            assertTrue(setup.visibility.blockFaceHidden(target, Direction.NORTH, 0));
            assertTrue(setup.visibility.blockFaceHidden(target, Direction.WEST, 0));
            assertEquals(ClientVisibility.BlockVisibility.OCCLUDED, setup.visibility.blockVisibility(target, 10));
        }
    }

    @Test
    void entitySelectionDoesNotExposeAReachExpandedHitboxThroughATouchingCorner() throws Exception {
        for (int type : new int[] {EntityTypeIds.PLAYER, EntityTypeIds.COW, EntityTypeIds.ARMOR_STAND}) {
            try (var setup = new Setup()) {
                var player = setup.fixture.player;
                player.x = -.5;
                player.z = -.5;
                player.compensatedWorld.ensureValidationChunkLoaded(-1, -1);
                player.compensatedWorld.ensureValidationChunkLoaded(-1, 0);
                for (int y : new int[] {64, 65}) {
                    setup.block(0, y, -1, "stone");
                    setup.block(-1, y, 0, "stone");
                }
                double halfWidth = type == EntityTypeIds.COW ? .45F : type == EntityTypeIds.ARMOR_STAND ? .25F : .3F;
                player.compensatedEntities.addEntity(42, type, new Vec3(halfWidth + .01, 64, halfWidth + .01), 0, 0, 0);
                var entity = player.compensatedEntities.getEntity(42);
                assertTrue(setup.visibility.entityHidden(entity.getPossibleMovementCollisionBoxes(), 0));
                assertTrue(
                        setup.visibility.entityHidden(entity.getPossibleCollisionBoxes(), 0),
                        "A Java client's reach box is its physical hitbox");
                for (double error : new double[] {0, .0002, .03}) {
                    assertTrue(
                            setup.visibility.entityHidden(entity, error), "Native selection uses the physical hitbox");
                    assertTrue(setup.visibility.entityInteractionHidden(entity, error));
                }
                Check check = type == EntityTypeIds.PLAYER
                        ? setup.check(AttackPlayerVisibility.class)
                        : setup.check(AttackMobVisibility.class);
                assertTrue(setup.route(ServerboundPackets.INTERACT, attack(42)).isCancelled());
                assertEquals(1, check.violations);
            }
        }
    }

    @Test
    void hotbarPacketRetainsExactlyTheOldAndNewSelectionCandidates() throws Exception {
        try (var setup = new Setup()) {
            var player = setup.fixture.player;
            var sword = ac.cult.cultac.utils.inventory.ItemUtil.modelItems().stack("minecraft:iron_sword", 1);
            var components = new ac.cult.blocksim.data.Components(java.util.Map.of(
                    "minecraft:attack_range", com.google.gson.JsonParser.parseString("{\"max_reach\":4}")));
            var ranged = new ac.cult.blocksim.engine.SimItemStack(sword.definition(), 1, components);
            var storage = player.getInventory().inventory.getInventoryStorage();
            storage.setItem(36, ranged);
            var target = new SimpleCollisionBox(.2, 64, 2, .8, 65.8, 2.5);
            player.packetStateData.lastSlotSelected = 0;
            setup.visibility.onSlotChange(1);
            player.packetStateData.lastSlotSelected = 1;
            assertFalse(setup.visibility.entityHidden(target, 0), "Keyboard selection follows this tick's pick");
            setup.visibility.endSelection();
            assertTrue(setup.visibility.entityHidden(target, 0), "An unselected ranged weapon cannot supply reach");
            setup.visibility.onSlotChange(0);
            assertFalse(setup.visibility.entityHidden(target, 0), "Mouse scrolling can precede this tick's pick");
        }
    }

    @Test
    void selectionKeepsTheWorldFromBeforeThisTicksLocalPredictions() throws Exception {
        try (var setup = new Setup()) {
            var world = setup.fixture.player.compensatedWorld;
            world.beginSelectionSnapshot();
            world.startPredicting();
            world.updateBlock(0, 64, 0, state("stone"));
            world.updateBlock(0, 65, 0, state("stone"));
            world.stopPredicting(1);
            assertTrue(setup.visibility.blockFaceHidden(TARGET, Direction.NORTH, 0));
            assertFalse(
                    world.withSelectionSnapshot(() -> setup.visibility.blockFaceHidden(TARGET, Direction.NORTH, 0)));
            world.endSelectionSnapshot();
            assertTrue(world.withSelectionSnapshot(() -> setup.visibility.blockFaceHidden(TARGET, Direction.NORTH, 0)));
        }
    }

    @Test
    void abortDoesNotRequireVisibilityOfTheOldTarget() throws Exception {
        try (var setup = new Setup()) {
            setup.wall("stone");
            var check = setup.check(BreakVisibility.class);
            var abort = new ServerboundPlayerAction(PlayerAction.ABORT_DESTROY_BLOCK, TARGET, Direction.DOWN, 0);
            check.onDig(
                    new PacketReceiveEvent<>(
                            setup.fixture.user, ConnectionPhase.PLAY, ServerboundPackets.PLAYER_ACTION, abort),
                    setup.fixture.player,
                    abort);
            assertEquals(0, check.violations);
        }
    }

    @Test
    void shulkerVisibilityUsesTheClosedBoxWithoutChangingItsAnimation() throws Exception {
        try (var setup = new Setup()) {
            var player = setup.fixture.player;
            var world = player.compensatedWorld;
            player.pose = ac.cult.cultac.utils.enums.Pose.SWIMMING;
            player.z = 1.6;
            setup.block(0, 64, 2, "shulker_box[facing=north]");
            var data = new ac.cult.blocksim.engine.BlockEntityData(
                    "minecraft:shulker_box",
                    new ac.cult.blocksim.data.Components(java.util.Map.of(
                            "animation_status", new com.google.gson.JsonPrimitive("OPENED"),
                            "progress", new com.google.gson.JsonPrimitive(1.0F))),
                    new ac.cult.blocksim.data.nbt.NbtValue.Compound(java.util.Map.of()));
            world.applyPredictedBlockEntityData(TARGET, data);

            assertEquals(ClientVisibility.BlockVisibility.OUT_OF_RANGE, setup.visibility.blockVisibility(TARGET, .1));
            assertEquals(
                    1.0F,
                    world.getClientBlockEntityData(TARGET)
                            .data()
                            .get("progress")
                            .getAsFloat());
            var movement = new java.util.ArrayList<SimpleCollisionBox>();
            ac.cult.cultac.utils.collisions.ClientBlockShapes.movement(
                            player, world.getBlockStateIdAt(TARGET), 0, 64, 2)
                    .downCast(movement);
            assertEquals(1.5, movement.getFirst().minZ, "Movement still models the real open lid");
        }
    }

    @Test
    void aLocallyBrokenShulkerStillHasAClosedSelectionInTheSameTick() throws Exception {
        try (var setup = new Setup()) {
            var player = setup.fixture.player;
            var world = player.compensatedWorld;
            setup.block(0, 64, 2, "shulker_box[facing=north]");
            world.beginSelectionSnapshot();
            world.startPredicting();
            world.updateBlock(TARGET, state("air"));
            world.stopPredicting(1);

            assertFalse(
                    world.withSelectionSnapshot(() -> setup.visibility.blockFaceHidden(TARGET, Direction.NORTH, 0)));
            assertNull(world.getClientBlockEntityData(TARGET));
            assertEquals(state("air"), world.getBlockStateIdAt(TARGET));
        }
    }

    @Test
    void insideAndBoundaryOriginsStillDetectAnInterveningBlock() throws Exception {
        try (var setup = new Setup()) {
            setup.wall("stone");
            for (double z : new double[] {0, .5}) {
                setup.fixture.player.z = z;
                assertTrue(setup.visibility.blockFaceHidden(TARGET, Direction.NORTH, 0), "eye z=" + z);
            }
            setup.fixture.player.z = .999;
            var eye = new ac.cult.cultac.utils.math.Vec3(.5, 64 + setup.fixture.player.getEyeHeight(), .999);
            var end = eye.add(new ac.cult.cultac.utils.math.Vec3(0, 64.5 - eye.y, 2 - eye.z)
                    .normalize()
                    .scale(4.5));
            var pick = setup.fixture
                    .player
                    .compensatedWorld
                    .geometry()
                    .clipOutline(eye, end, setup.fixture.player, state -> false);
            assertNotNull(pick);
            assertEquals(2, pick.pos().z(), "VoxelShape.clip's initial sample has left the near block");
            assertFalse(setup.visibility.blockFaceHidden(TARGET, Direction.NORTH, 0));
            setup.block(0, 64, -2, "stone");
            setup.fixture.player.z = 0;
            assertFalse(setup.visibility.blockFaceHidden(new BlockPos(0, 64, -2), Direction.SOUTH, 0));
        }
    }

    @Test
    void nativeOutlinePicksAreNeverRejectedAcrossComplexShapes() throws Exception {
        try (var setup = new Setup()) {
            int hits = 0;
            for (String block : new String[] {
                "stone",
                "oak_slab",
                "oak_stairs",
                "oak_fence",
                "glass_pane",
                "hopper",
                "cauldron",
                "lectern",
                "anvil",
                "oak_trapdoor",
                "end_portal_frame",
                "snow"
            }) {
                setup.block(0, 64, 2, block);
                boolean[] hidden = new boolean[Direction.values().length];
                for (Direction face : Direction.values())
                    hidden[face.ordinal()] = setup.visibility.blockFaceHidden(TARGET, face, 0);
                var player = setup.fixture.player;
                var eye = new ac.cult.cultac.utils.math.Vec3(player.x, player.y + player.getEyeHeight(), player.z);
                for (int u = -16; u <= 16; u++)
                    for (int v = -16; v <= 8; v++) {
                        var delta = new ac.cult.cultac.utils.math.Vec3(u / 32.0, v / 32.0, 1)
                                .normalize()
                                .scale(4.5);
                        var pick = player.compensatedWorld
                                .geometry()
                                .clipOutline(eye, eye.add(delta), player, state -> false);
                        if (pick != null
                                && pick.pos().x() == 0
                                && pick.pos().y() == 64
                                && pick.pos().z() == 2) {
                            hits++;
                            assertFalse(hidden[pick.face().ordinal()], block + " face=" + pick.face());
                        }
                    }
            }
            assertTrue(hits > 100, "Native oracle must actually select target shapes");
        }
    }

    @Test
    void initialSampleCanReportADifferentFaceFromGeometricEntry() throws Exception {
        try (var setup = new Setup()) {
            var player = setup.fixture.player;
            player.x = -.001;
            player.y = 63;
            player.z = 2.5;
            var eye = new ac.cult.cultac.utils.math.Vec3(player.x, player.y + player.getEyeHeight(), player.z);
            var end = eye.add(
                    new ac.cult.cultac.utils.math.Vec3(.5, -1, 0).normalize().scale(4.5));
            var pick = player.compensatedWorld.geometry().clipOutline(eye, end, player, state -> false);
            assertNotNull(pick);
            assertEquals(ac.cult.blocksim.engine.Direction.UP, pick.face());
            assertTrue(pick.inside());
            assertFalse(setup.visibility.blockFaceHidden(TARGET, Direction.UP, 0));
        }
    }

    private static int state(String key) {
        var registry = DataTables.defaults().registry();
        int bracket = key.indexOf('[');
        int result = registry.block("minecraft:" + (bracket < 0 ? key : key.substring(0, bracket)))
                .defaultState();
        if (bracket >= 0)
            for (String property : key.substring(bracket + 1, key.length() - 1).split(",")) {
                String[] entry = property.split("=", 2);
                result = registry.with(result, entry[0], entry[1]);
            }
        return result;
    }

    private static final class Setup implements AutoCloseable {
        final RecordConsumerServices services = new RecordConsumerServices();
        final RecordReceiveFixture fixture = new RecordReceiveFixture(ProtocolVersion.V26_3);
        final ClientVisibility visibility = new ClientVisibility(fixture.player);

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
            block(0, 64, 2, "stone");
            player.checkManager.allChecks.values().forEach(check -> check.setEnabled(false));
        }

        <T extends Check> T check(Class<T> type) {
            var check = fixture.player.checkManager.getCheck(type);
            check.setEnabled(true);
            return check;
        }

        <T extends ServerboundPacket> PacketReceiveEvent<T> route(PacketType<T> type, T packet) {
            var event = new PacketReceiveEvent<>(fixture.user, ConnectionPhase.PLAY, type, packet);
            fixture.player.checkManager.dispatchEarlyReceive(event);
            return event;
        }

        void wall(String block) {
            block(0, 64, 0, block);
            block(0, 65, 0, block);
        }

        void block(int x, int y, int z, String block) {
            fixture.player.compensatedWorld.applyBlockChangeRawDANGER(x, y, z, state(block));
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
