package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.ClientBlockShapes;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.JavaCollisionState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class JavaVoxelCollisionTest {
    @Test
    void movingPistonsUseTheClientsPitcherCropCollisionEvenForPluginAuthoredStates() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var registry = DataTables.defaults().registry();
            int state = registry.block("minecraft:pitcher_crop").defaultState();
            state = registry.with(state, "age", "0");
            state = registry.with(state, "half", "upper");
            var resolved = fixture.player.getClass().getDeclaredField("resolvedClientVersion");
            resolved.setAccessible(true);
            for (var client : List.of(
                    ClientVersion.V_1_21_2, ClientVersion.V_1_21_4, ClientVersion.V_1_21_5, ClientVersion.V_26_3)) {
                resolved.set(fixture.player, client);
                var shape = fixture.player
                        .compensatedWorld
                        .geometry()
                        .movingPiston(
                                state,
                                new BlockPos(4, 64, 4),
                                ac.cult.cultac.protocol.value.Direction.EAST,
                                true,
                                false,
                                .5F);
                // Original PistonMovingBlockEntity shifts the client's bulb half a block west.
                // Through 1.21.4, PitcherCropBlock tests age before half; later clients return empty.
                if (client.isOlderThan(ClientVersion.V_1_21_5)) {
                    assertEquals(
                            List.of(new ac.cult.blocksim.data.Box(.3125, -.0625, .3125, .6875, .1875, .6875)),
                            fixture.player
                                    .compensatedWorld
                                    .geometry()
                                    .collision(state, new BlockPos(4, 64, 4))
                                    .boxes(),
                            client.toString());
                    assertEquals(
                            List.of(new ac.cult.blocksim.data.Box(-.1875, -.0625, .3125, .1875, .1875, .6875)),
                            shape.boxes(),
                            client.toString());
                } else assertTrue(shape.isEmpty(), client.toString());
            }
        }
    }

    @Test
    void sweptColliderGatheringPreservesVanillasTinyMovementAndFullBlockContact() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var player = fixture.player;
            player.compensatedWorld.ensureValidationChunkLoaded(0, 0);
            int stone =
                    DataTables.defaults().registry().block("minecraft:stone").defaultState();
            player.compensatedWorld.updateBlock(new BlockPos(4, 64, 4), stone);
            player.compensatedEntities.getSelf().stepHeightAttribute = 0.0;
            var version = player.getClass().getDeclaredField("resolvedClientVersion");
            version.setAccessible(true);
            for (var client : List.of(ClientVersion.V_1_21_2, ClientVersion.V_26_3)) {
                version.set(player, client);
                var fullCube = ClientBlockShapes.movement(player, stone, 4, 64, 4);
                assertTrue(fullCube.isFullBlock(), client.toString());
                assertTrue(fullCube.copy().offset(1, 0, 0).isFullBlock(), client.toString());
                // Official Entity.collide, world-oracle frame 12: no intersecting
                // collider means the sub-epsilon request stays nonzero/unclipped.
                var outside = SimpleCollisionBox.between(5 + 5E-8, 64.1, 4.2, 5.6, 65.9, 4.8);
                assertEquals(5E-8, Collisions.collide(player, outside, 5E-8, 0, 0).x, 0, client.toString());
                var boxes = new ArrayList<SimpleCollisionBox>();
                Collisions.getCollisionBoxes(player, outside, boxes, false, outside.minY);
                assertTrue(boxes.isEmpty(), client.toString());

                // Frame 11: BlockCollisions uses strict AABB intersection for the
                // canonical full block, so a 5e-8 swept overlap is still a collider.
                var above = SimpleCollisionBox.between(4.2, 65 + 5E-8, 4.2, 4.8, 66.8, 4.8);
                var query = above.copy().expandToCoordinate(0, -1E-7, 0);
                assertFalse(Collisions.isEmpty(player, query, above.minY), client.toString());
                assertEquals(65 - above.minY, Collisions.collide(player, above, 0, -1E-7, 0).y, 0, client.toString());
            }
        }
    }

    @Test
    void pistonHeadClippingPreservesTheClientVersionsGrid() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var registry = DataTables.defaults().registry();
            int state = registry.block("minecraft:piston_head").defaultState();
            state = registry.with(state, "facing", "up");
            state = registry.with(state, "short", "false");
            var pos = new BlockPos(4, 64, 4);
            fixture.player.compensatedWorld.ensureValidationChunkLoaded(0, 0);
            fixture.player.compensatedWorld.updateBlock(pos, state);
            var version = fixture.player.getClass().getDeclaredField("resolvedClientVersion");
            version.setAccessible(true);
            for (var client : List.of(
                    ClientVersion.V_1_21_2, ClientVersion.V_1_21_4, ClientVersion.V_1_21_5, ClientVersion.V_26_3)) {
                version.set(fixture.player, client);
                var collider = ClientBlockShapes.movement(
                        fixture.player, state, 4, 64, 4, 64.1, new JavaCollisionState(0, false, false, false));
                var boxes = new ArrayList<SimpleCollisionBox>();
                collider.copy().offset(-4, -64, -4).downCastForMovement(boxes);
                var result = Collisions.collideBoundingBoxLegacy(
                        new Vec3(.25, -.2, .3),
                        SimpleCollisionBox.between(.2, .1, .2, .8, 1.9, .8),
                        boxes,
                        List.of(Collisions.Axis.Y, Collisions.Axis.Z, Collisions.Axis.X));
                // Official clients: 1.21.2/1.21.4 clip on the internal 7/8 face;
                // 1.21.5+ rotateAll produces no such face in the long upward head.
                boolean old = client.isOlderThan(ClientVersion.V_1_21_5);
                assertEquals(old ? .075 : .25, result.x, 1.0E-12, client.toString());
                assertEquals(-.1, result.y, 1.0E-12, client.toString());
                assertEquals(old ? .075 : .3, result.z, 1.0E-12, client.toString());
            }
        }
    }
}
