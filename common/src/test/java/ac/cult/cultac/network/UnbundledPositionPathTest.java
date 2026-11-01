package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ac.cult.cultac.events.packets.PacketEntityReplication;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A 26.3 stepped move_entity proven by the pings around it rather than a bundle. Minecraft#runTick handles every queued
 * packet before the frame's client ticks, so with no client tick between the two pongs the client handled the path
 * between the same ticks as both pings, and SteppedInterpolationHandler places the entity exactly.
 */
class UnbundledPositionPathTest {
    private static final int ENTITY = 60;
    // EntityType.ZOMBIE: 0.6 wide, 1.95 tall.
    private static final double HALF_WIDTH = 0.3F;
    private static final double HEIGHT = 1.95F;

    @Test
    void aPathProvenWithoutAClientTickBetweenItsPingsIsExact() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(new Vec3(0.5, FLOOR_Y, -4.5), Vec3.ZERO);
            withoutBundleDelimiter(f);
            spawn(f, new Vec3d(0.5, FLOOR_Y, 0.5));
            PacketEntity entity = f.player.compensatedEntities.getEntity(ENTITY);
            assertNotNull(entity);

            f.player.sendTransaction();
            int first = f.player.lastTransactionSent.get();
            // Two VecDelta steps of 1024/4096 block, one and two ticks.
            moveAlongPath(
                    f,
                    new EntityDelta.Step((short) 1024, (short) 0, (short) 0, 1),
                    new EntityDelta.Step((short) 1024, (short) 0, (short) 0, 2));
            f.player.sendTransaction();
            f.pongThrough(first);
            f.pongThrough(first + 1);
            assertReachBoxAt(entity, 0.5, "handled, not yet interpolated");

            // SteppedInterpolationHandler#doInterpolate with updateInterval 3: the first tick consumes the one-tick
            // step, the second lerps half of the two-tick step, the third reaches its end.
            f.tickEnd();
            assertReachBoxAt(entity, 0.75, "first tick");
            f.tickEnd();
            assertReachBoxAt(entity, 0.875, "second tick");
            f.tickEnd();
            assertReachBoxAt(entity, 1.0, "third tick");
        }
    }

    private static void assertReachBoxAt(PacketEntity entity, double x, String message) {
        SimpleCollisionBox box = entity.getPossibleCollisionBoxes();
        assertEquals(x - HALF_WIDTH, box.minX, 1e-12, message + ": min x");
        assertEquals(x + HALF_WIDTH, box.maxX, 1e-12, message + ": max x");
        assertEquals(FLOOR_Y, box.minY, 1e-12, message + ": min y");
        assertEquals(FLOOR_Y + HEIGHT, box.maxY, 1e-12, message + ": max y");
        assertEquals(0.5 - HALF_WIDTH, box.minZ, 1e-12, message + ": min z");
        assertEquals(0.5 + HALF_WIDTH, box.maxZ, 1e-12, message + ": max z");
    }

    private static void withoutBundleDelimiter(JavaTickFixture f) throws ReflectiveOperationException {
        Field delimiter = PacketEntityReplication.class.getDeclaredField("useReachBundleDelimiter");
        delimiter.setAccessible(true);
        delimiter.setBoolean(f.player.packetEntityReplication, false);
    }

    private static void spawn(JavaTickFixture f, Vec3d at) {
        var packet = new ClientboundAddEntity(ENTITY, "minecraft:zombie", at, 0, 0, 0);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ADD_ENTITY, packet, false);
        f.player.packetEntityReplication.onAddEntity(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.player.sendTransaction();
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void moveAlongPath(JavaTickFixture f, EntityDelta.Step... steps) {
        var packet =
                new ClientboundMoveEntity(ENTITY, new EntityDelta.Stepped(List.of(steps)), 0, 0, true, true, false);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_ENTITY, packet, false);
        f.player.packetEntityReplication.onMoveEntity(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
    }
}
