package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.RootVehicleInterpolationCollision;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class JavaPistonInterpolationVersionsTest {
    private static final BlockRegistry BLOCKS = DataTables.defaults().registry();

    /** Entity#move(PISTON) changes the current collision box immediately, while an active lerp keeps its path. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void indexedEntityCandidatesReadThePhysicalPistonBox(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Vec3 start = new Vec3(0.5, FLOOR_Y + 10, 0.5);
            f.player.compensatedEntities.addEntity(100, EntityTypeIds.ZOMBIE, start, 0, 0, 0);
            var actor = f.player.compensatedEntities.getEntity(100);
            actor.onBundledPositionSyncTransaction(3.5, start.y, start.z, f.player);
            actor.onMovement(f.player);
            Vec3 position = actor.clientPhysicalPosition.add(0.51, 0, 0);
            SimpleCollisionBox physical =
                    GetBoundingBox.getPacketEntityBoundingBox(f.player, position.x, position.y, position.z, actor);
            actor.applyBlockEntityMovement(f.player, physical, new Vec3(0.51, 0, 0));
            assertEquals(1, actor.possibleMovementCollisionBoxCandidateCount());
            SimpleCollisionBox indexed = actor.possibleMovementCollisionBoxCandidate(0, new SimpleCollisionBox());
            assertEquals(physical.minX, indexed.minX, 1.0E-12);
            assertEquals(physical.maxX, indexed.maxX, 1.0E-12);
            assertEquals(physical.minY, indexed.minY);
            assertEquals(physical.maxY, indexed.maxY);
            assertEquals(physical.minZ, indexed.minZ);
            assertEquals(physical.maxZ, indexed.maxZ);
        }
    }

    /** Before 1.21.5 LivingEntity's lerp target is fixed; newer handlers carry local piston displacement. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aRemotePistonMoveKeepsTheVersionsOwnInterpolationTarget(String release, ClientVersion version)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Vec3 start = new Vec3(0.5, FLOOR_Y + 10, 0.5);
            f.player.compensatedEntities.addEntity(100, EntityTypeIds.ZOMBIE, start, 0, 0, 0);
            var actor = f.player.compensatedEntities.getEntity(100);
            actor.onBundledPositionSyncTransaction(3.5, start.y, start.z, f.player);
            actor.onMovement(f.player);
            Vec3 codec = actor.desyncClientPos;
            Vec3 moved = actor.clientPhysicalPosition.add(0.51, 0, 0);
            actor.applyBlockEntityMovement(
                    f.player,
                    GetBoundingBox.getPacketEntityBoundingBox(f.player, moved.x, moved.y, moved.z, actor),
                    new Vec3(0.51, 0, 0));
            actor.onMovement(f.player);
            actor.onMovement(f.player);
            assertEquals(
                    version.isOlderThan(ClientVersion.V_1_21_5) ? 3.5 : 4.01, actor.clientPhysicalPosition.x, 1.0E-12);
            assertEquals(codec, actor.desyncClientPos, "local physics does not rewrite the network delta codec");
        }
    }

    /** The 1.21.5 InterpolationHandler erroneously moves the current box by the absolute target position. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aShiftUsesTheVersionsOwnCollisionProbeOrigin(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.player.compensatedEntities.addEntity(100, EntityTypeIds.ZOMBIE, new Vec3(2.51, 12, 2), 0, 0, 0);
            var actor = f.player.compensatedEntities.getEntity(100);
            f.player.compensatedWorld.updateBlock(
                    new BlockPos(6, 24, 4), BLOCKS.block("minecraft:stone").defaultState());
            assertEquals(
                    version != ClientVersion.V_1_21_5,
                    RootVehicleInterpolationCollision.shouldShiftRootInterpolationTarget(
                            f.player, actor, new Vec3(3, 12, 2), new Vec3(0.51, 0, 0)));
        }
    }
}
