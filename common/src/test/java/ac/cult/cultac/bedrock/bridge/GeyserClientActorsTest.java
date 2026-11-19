package ac.cult.cultac.bedrock.bridge;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import ac.cult.cultac.bedrock.player.BedrockPlayerState;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataMap;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityAbsolutePacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityDeltaPacket;
import org.cloudburstmc.protocol.bedrock.packet.RemoveEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket;
import org.geysermc.geyser.entity.type.Entity;
import org.geysermc.geyser.session.GeyserSession;
import org.junit.Test;

/**
 * The world border Geyser builds for a Bedrock client: two armor stands with no server entity, collidable, 10 wide
 * and 25 tall, spawned 10 below the player's feet and moved to the border in the same batch; switched off with a
 * zero size and on again; moved on every input.
 */
public class GeyserClientActorsTest {
    private static final BedrockCoordinateFrame ORIGIN = new BedrockCoordinateFrame(20064, 20080, 9);
    private static final long PLAYER = 3;
    private static final long X_WALL = 5680;
    private static final long Z_WALL = 5681;
    private static final SimpleCollisionBox EVERYWHERE =
            new SimpleCollisionBox(-3.0E7, -1000, -3.0E7, 3.0E7, 1000, 3.0E7);

    @Test
    public void theSpawnAndItsMoveToTheBorderTakeEffectTogetherAtTheNextMarker() {
        Harness h = new Harness();
        h.capture(add(X_WALL, -29.5F, 110.0F, 2.5F));
        h.capture(add(Z_WALL, -29.5F, 110.0F, 2.5F));
        h.capture(move(X_WALL, -21.000002F, 110.0F, 2.5F));
        h.capture(move(Z_WALL, -29.5F, 110.0F, -7.0F));
        assertTrue(h.actors.hasPending());
        // The client has processed none of it before the marker's answer.
        assertTrue(h.colliders().isEmpty());
        h.boundary();
        assertFalse(h.actors.hasPending());
        assertTrue(h.colliders().isEmpty());
        h.acknowledge();
        // Both stand where they spawned, around the player at local (-29.5, 120, 2.5), until a tick steps them.
        for (SimpleCollisionBox box : h.colliders()) {
            assertEquals(20064 - 34.5, box.minX, 0);
            assertEquals(20080 - 2.5, box.minZ, 0);
        }
        h.settle();
        List<SimpleCollisionBox> boxes = h.colliders();
        assertEquals(2, boxes.size());
        SimpleCollisionBox x = boxes.stream().filter(box -> box.minX > 20036).findFirst().orElseThrow();
        assertEquals(20064 + (double) (-21.000002F - 5.0F), x.minX, 0);
        assertEquals(110.0, x.minY, 0);
        assertEquals(135.0, x.maxY, 0);
        SimpleCollisionBox z = boxes.stream().filter(box -> box.maxZ < 20079).findFirst().orElseThrow();
        // Its face lies on the border at local z -2.
        assertEquals(20078.0, z.maxZ, 0);
        assertEquals(20068.0, z.minZ, 0);
        assertEquals(20064 - 34.5, z.minX, 0);
        assertEquals(20064 - 24.5, z.maxX, 0);
    }

    /** Each move restarts a lerp of three ticks from where the last step left the actor. */
    @Test
    public void aMoveIsReachedInThreeTicksAndARepeatedMoveStartsOver() {
        Harness h = new Harness();
        h.capture(add(Z_WALL, -5.5F, 110.0F, 50.5F));
        h.capture(move(Z_WALL, -5.5F, 110.0F, 41.000004F));
        h.proven();
        h.tick();
        float first = 50.5F + (41.000004F - 50.5F) * (1.0F / 3.0F);
        assertEquals(20080 + (double) (first + 5.0F), h.colliders().getFirst().maxZ, 0);
        h.capture(move(Z_WALL, -5.5F, 110.0F, 41.000004F));
        h.proven();
        h.tick();
        float second = first + (41.000004F - first) * (1.0F / 3.0F);
        // 50.222225 in the client's coordinates.
        assertEquals(50.222225F, second + 5.0F, 2.0E-6F);
        assertEquals(20080 + (double) (second + 5.0F), h.colliders().getFirst().maxZ, 0);
        h.tick();
        h.tick();
        assertEquals(20080 + (double) (41.000004F + 5.0F), h.colliders().getFirst().maxZ, 0);
        h.tick();
        assertEquals(20080 + (double) (41.000004F + 5.0F), h.colliders().getFirst().maxZ, 0);
    }

    @Test
    public void aZeroSizeWithoutTheFlagSwitchesTheWallOffAndTheFlagSwitchesItOnAgain() {
        Harness h = new Harness();
        h.capture(add(Z_WALL, -29.5F, 110.0F, 2.5F));
        h.capture(move(Z_WALL, -29.5F, 110.0F, -7.0F));
        h.proven();
        h.settle();
        assertEquals(1, h.colliders().size());
        h.capture(data(Z_WALL, false, 0.0F, 0.0F));
        assertEquals(1, h.colliders().size());
        h.proven();
        assertTrue(h.colliders().isEmpty());
        h.capture(data(Z_WALL, true, 10.0F, 25.0F));
        h.capture(move(Z_WALL, -29.5005F, 110.0F, -8.999999F));
        h.proven();
        h.settle();
        assertEquals(20080 + (double) (-8.999999F + 5.0F), h.colliders().getFirst().maxZ, 0);
    }

    @Test
    public void partialMovesAndRemovalFollowTheWrittenActor() {
        Harness h = new Harness();
        h.capture(add(Z_WALL, -29.5F, 110.0F, -7.0F));
        var delta = new MoveEntityDeltaPacket();
        delta.setRuntimeEntityId(Z_WALL);
        delta.getFlags().add(MoveEntityDeltaPacket.Flag.HAS_X);
        delta.setX(-28.0F);
        h.capture(delta);
        h.proven();
        h.settle();
        assertEquals(20064 - 33.0, h.colliders().getFirst().minX, 0);
        assertEquals(20078.0, h.colliders().getFirst().maxZ, 0);
        var remove = new RemoveEntityPacket();
        remove.setUniqueEntityId(Z_WALL);
        h.capture(remove);
        h.proven();
        assertTrue(h.colliders().isEmpty());
        // Nothing is left to move.
        h.capture(move(Z_WALL, 0, 110, 0));
        assertFalse(h.actors.hasPending());
    }

    @Test
    public void actorsOfServerEntitiesAndThePlayerStayWithTheEntityTracker() {
        Harness h = new Harness();
        when(h.session.getEntityCache().getEntityByGeyserId(77L)).thenReturn(mock(Entity.class));
        h.capture(add(77L, -29.5F, 110.0F, 2.5F));
        h.capture(add(PLAYER, -29.5F, 110.0F, 2.5F));
        h.capture(move(77L, -29.5F, 110.0F, -7.0F));
        h.capture(data(77L, true, 10.0F, 25.0F));
        assertFalse(h.actors.hasPending());
    }

    @Test
    public void anotherDimensionHoldsNoneOfTheActors() {
        Harness h = new Harness();
        h.capture(add(Z_WALL, -29.5F, 110.0F, -7.0F));
        h.proven();
        h.actors.clear();
        h.proven();
        assertTrue(h.colliders().isEmpty());
        h.capture(move(Z_WALL, 0, 110, 0));
        h.proven();
        assertTrue(h.colliders().isEmpty());
    }

    private static AddEntityPacket add(long id, float x, float y, float z) {
        var packet = new AddEntityPacket();
        packet.setRuntimeEntityId(id);
        packet.setUniqueEntityId(id);
        packet.setPosition(Vector3f.from(x, y, z));
        shape(packet.getMetadata(), true, 10.0F, 25.0F);
        return packet;
    }

    private static MoveEntityAbsolutePacket move(long id, float x, float y, float z) {
        var packet = new MoveEntityAbsolutePacket();
        packet.setRuntimeEntityId(id);
        packet.setPosition(Vector3f.from(x, y, z));
        packet.setRotation(Vector3f.ZERO);
        packet.setTeleported(true);
        return packet;
    }

    private static SetEntityDataPacket data(long id, boolean collidable, float width, float height) {
        var packet = new SetEntityDataPacket();
        packet.setRuntimeEntityId(id);
        shape(packet.getMetadata(), collidable, width, height);
        return packet;
    }

    private static void shape(EntityDataMap data, boolean collidable, float width, float height) {
        data.setFlag(EntityFlag.INVISIBLE, true);
        data.setFlag(EntityFlag.COLLIDABLE, collidable);
        data.put(EntityDataTypes.WIDTH, width);
        data.put(EntityDataTypes.HEIGHT, height);
    }

    private static final class Harness {
        final GeyserSession session = mock(GeyserSession.class, RETURNS_DEEP_STUBS);
        final CultPlayer player = mock(CultPlayer.class);
        final GeyserClientActors actors = new GeyserClientActors();
        final List<Runnable> acknowledgements = new ArrayList<>();

        Harness() {
            when(session.getPlayerEntity().geyserId()).thenReturn(PLAYER);
            when(session.getEntityCache().getEntityByGeyserId(anyLong())).thenReturn(null);
            player.bedrockState = new BedrockPlayerState(UUID.randomUUID());
            doAnswer(call -> acknowledgements.add(call.getArgument(1)))
                    .when(player)
                    .addBedrockTransactionTask(any(), any());
        }

        void capture(org.cloudburstmc.protocol.bedrock.packet.BedrockPacket packet) {
            actors.capture(session, packet, ORIGIN);
        }

        void boundary() {
            try {
                var constructor = CultPlayer.BedrockTransaction.class.getDeclaredConstructor(int.class, int.class);
                constructor.setAccessible(true);
                actors.boundary(player, constructor.newInstance(0, 0));
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }

        void acknowledge() {
            acknowledgements.forEach(Runnable::run);
            acknowledgements.clear();
        }

        void proven() {
            boundary();
            acknowledge();
        }

        /** One client tick's lerp step. */
        void tick() {
            player.bedrockState.clientActors.tick();
        }

        /** The ticks a move takes to reach its position. */
        void settle() {
            for (int step = 0; step < 3; step++) tick();
        }

        List<SimpleCollisionBox> colliders() {
            List<SimpleCollisionBox> boxes = new ArrayList<>();
            player.bedrockState.clientActors.colliders(EVERYWHERE, boxes::add);
            return boxes;
        }
    }
}
