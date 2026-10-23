package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityPositionSync;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPacket;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.PositionPath;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.TrackerData;
import ac.cult.cultac.utils.data.packetentity.PacketEntityTrackXRot;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Entity movement keeps a packet's rotation as two floats and a presence flag: every bit pattern, NaN payloads and
 * signed zeros included, reaches the tracker and a yaw-tracking entity exactly, and a movement without rotation
 * leaves both untouched.
 */
class EntityMovementRotationTest {
    private static final int CHICKEN = 50;
    private static final int ZOMBIE = 51;

    @Test
    void rotationBitsSurviveTheMovementPaths() throws Exception {
        for (ClientVersion version : List.of(ClientVersion.V_26_3, ClientVersion.V_1_21_4)) {
            try (var f = new JavaTickFixture()) {
                CultPlayer player = f.player;
                JavaTickFixture.setField(CultPlayer.class, player, "resolvedClientVersion", version);
                f.place(new Vec3(0.5, JavaTickFixture.FLOOR_Y, 0.5), Vec3.ZERO);
                send(
                        f,
                        ClientboundPackets.ADD_ENTITY,
                        new ClientboundAddEntity(
                                CHICKEN, "minecraft:chicken", new Vec3d(2.5, JavaTickFixture.FLOOR_Y, 2.5), 10, 5, 0));
                send(
                        f,
                        ClientboundPackets.ADD_ENTITY,
                        new ClientboundAddEntity(
                                ZOMBIE, "minecraft:zombie", new Vec3d(-2.5, JavaTickFixture.FLOOR_Y, 2.5), 20, 7, 0));
                pongAll(f);
                Random random = new Random(version.ordinal());
                int rotated = 0;
                int unrotated = 0;
                for (int i = 0; i < 400; i++) {
                    int id = random.nextBoolean() ? CHICKEN : ZOMBIE;
                    TrackerData data = player.compensatedEntities.getTrackedEntity(id);
                    int trackedYaw = Float.floatToRawIntBits(data.getXRot());
                    int trackedPitch = Float.floatToRawIntBits(data.getYRot());
                    PacketEntityTrackXRot chicken =
                            (PacketEntityTrackXRot) player.compensatedEntities.getEntity(CHICKEN);
                    int packetYaw = Float.floatToRawIntBits(chicken.packetYaw);
                    float yaw = angle(random);
                    float pitch = angle(random);
                    boolean sync = random.nextInt(4) == 0;
                    boolean hasRotation = sync || random.nextBoolean();
                    if (sync) {
                        send(
                                f,
                                ClientboundPackets.ENTITY_POSITION_SYNC,
                                new ClientboundEntityPositionSync(
                                        id,
                                        new PositionPath.Linear(new Vec3d(data.getX(), data.getY(), data.getZ())),
                                        yaw,
                                        pitch,
                                        true));
                    } else {
                        send(
                                f,
                                ClientboundPackets.MOVE_ENTITY,
                                new ClientboundMoveEntity(
                                        id,
                                        new EntityDelta.Linear((short) 0, (short) 0, (short) 0),
                                        yaw,
                                        pitch,
                                        random.nextBoolean(),
                                        random.nextBoolean(),
                                        hasRotation));
                    }
                    pongAll(f);
                    String where = version + " op " + i;
                    data = player.compensatedEntities.getTrackedEntity(id);
                    if (hasRotation) {
                        rotated++;
                        assertEquals(Float.floatToRawIntBits(yaw), Float.floatToRawIntBits(data.getXRot()), where);
                        assertEquals(Float.floatToRawIntBits(pitch), Float.floatToRawIntBits(data.getYRot()), where);
                        if (id == CHICKEN)
                            assertEquals(
                                    Float.floatToRawIntBits(yaw), Float.floatToRawIntBits(chicken.packetYaw), where);
                    } else {
                        unrotated++;
                        assertEquals(trackedYaw, Float.floatToRawIntBits(data.getXRot()), where);
                        assertEquals(trackedPitch, Float.floatToRawIntBits(data.getYRot()), where);
                        assertEquals(packetYaw, Float.floatToRawIntBits(chicken.packetYaw), where);
                    }
                }
                assertTrue(rotated > 50 && unrotated > 50, version + ": " + rotated + "/" + unrotated);
            }
        }
    }

    private static float angle(Random random) {
        return switch (random.nextInt(6)) {
            case 0 -> Float.intBitsToFloat(random.nextInt());
            case 1 -> Float.intBitsToFloat(0x7fc00000 | random.nextInt(1 << 22));
            case 2 -> -0.0F;
            default -> random.nextInt(256) * 360.0F / 256.0F - 180.0F;
        };
    }

    private static void pongAll(JavaTickFixture f) {
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    @SuppressWarnings("unchecked")
    private static <T extends ClientboundPacket> void send(JavaTickFixture f, PacketType<T> type, T packet) {
        var event = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, type, packet, false);
        var replication = f.player.packetEntityReplication;
        Consumer<?> handler;
        if (type == ClientboundPackets.ADD_ENTITY) {
            handler = (PacketSendEvent<ClientboundAddEntity> e) -> replication.onAddEntity(e, f.player, e.getPacket());
        } else if (type == ClientboundPackets.MOVE_ENTITY) {
            handler =
                    (PacketSendEvent<ClientboundMoveEntity> e) -> replication.onMoveEntity(e, f.player, e.getPacket());
        } else {
            handler = (PacketSendEvent<ClientboundEntityPositionSync> e) ->
                    replication.onEntityPositionSync(e, f.player, e.getPacket());
        }
        ((Consumer<PacketSendEvent<T>>) handler).accept(event);
        event.tasksAfter().forEach(Runnable::run);
    }
}
