package ac.cult.cultac.bedrock.replay.offline;

import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.TrackerData;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.nmsutil.EntityTypesCompat;
import io.netty.buffer.Unpooled;
import net.minecraft.world.phys.Vec3;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockPacketSerializer;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.geysermc.geyser.session.GeyserSession;
import org.junit.Assume;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.*;

public class BedrockEntityDeltaCodecTest {
    @Test @SuppressWarnings("unchecked")
    public void modernSerializedDeltaMovesTheControlledActorOnlyWhenForcedLocally() throws Exception {
        Class<?> codecType;
        try { codecType = Class.forName("org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168"); }
        catch (ClassNotFoundException absentOnLegacyGeyser) {
            Assume.assumeNoException("Run with a current Geyser codec for the native wire round trip", absentOnLegacyGeyser);
            return;
        }
        var codec = (BedrockCodec) codecType.getField("CODEC").get(null);
        var serializer = (BedrockPacketSerializer<MoveEntityDeltaPacket>) Class.forName(
                "org.cloudburstmc.protocol.bedrock.codec.v2168.serializer.MoveEntityDeltaSerializer_v2168")
                .getField("INSTANCE").get(null);
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            player.bedrockState.offerAuthInputFrame(BedrockAuthInputFrame.builder(player.playerUUID).protocolVersion(2168).build());
            player.compensatedEntities.addEntity(71, EntityTypesCompat.HORSE, new Vec3(0, 64, 0), 0, 0, 0);
            player.compensatedEntities.serverPositionsMap.put(71, new TrackerData(0, 64, 0, 0, 0, EntityTypesCompat.HORSE, 0));
            var horse = (PacketEntityHorse) player.compensatedEntities.getEntity(71);
            horse.hasSaddle = true;
            var session = Mockito.mock(GeyserSession.class, Mockito.RETURNS_DEEP_STUBS);
            Mockito.when(session.protocolVersion()).thenReturn(2168);
            Mockito.when(session.getPlayerEntity().geyserId()).thenReturn(1L);
            var translated = Mockito.mock(org.geysermc.geyser.entity.type.Entity.class);
            Mockito.when(translated.getEntityId()).thenReturn(71);
            Mockito.when(session.getEntityCache().getEntityByGeyserId(171L)).thenReturn(translated);
            var type = Class.forName("ac.cult.cultac.bedrock.bridge.GeyserEntityPositions");
            var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
            Object positions = constructor.newInstance();
            var capture = type.getDeclaredMethod("capture", GeyserSession.class, CultPlayer.class, BedrockPacket.class, BedrockCoordinateFrame.class);
            capture.setAccessible(true);
            var spawn = new AddEntityPacket();
            spawn.setRuntimeEntityId(171);
            spawn.setPosition(Vector3f.from(0, 64, 0));
            spawn.setRotation(Vector2f.ZERO);
            spawn.setMotion(Vector3f.ZERO);
            capture.invoke(positions, session, player, spawn, BedrockCoordinateFrame.IDENTITY);
            flush(type, positions, player);
            player.compensatedEntities.vehicles.setServerVehicle(71, new int[]{player.entityID}, 0);
            player.compensatedEntities.vehicles.applyVehiclePassengers(71, new int[]{player.entityID});
            for (boolean local : new boolean[]{false, true}) {
                var packet = new MoveEntityDeltaPacket();
                packet.setRuntimeEntityId(171);
                packet.setX(32); packet.setY(70); packet.setZ(16);
                packet.getFlags().addAll(java.util.EnumSet.of(MoveEntityDeltaPacket.Flag.HAS_X,
                        MoveEntityDeltaPacket.Flag.HAS_Y, MoveEntityDeltaPacket.Flag.HAS_Z));
                for (String flag : java.util.List.of("OnGround", "ForceMove", "ForceMoveLocalEntity", "ForceCompletion")) {
                    packet.getClass().getMethod("set" + flag, boolean.class).invoke(packet, flag.equals("ForceMoveLocalEntity") ? local : true);
                }
                var wire = Unpooled.buffer();
                var decoded = new MoveEntityDeltaPacket();
                try { serializer.serialize(wire, codec.createHelper(), packet); serializer.deserialize(wire, codec.createHelper(), decoded); }
                finally { wire.release(); }
                assertFalse(decoded.getFlags().contains(MoveEntityDeltaPacket.Flag.FORCE_MOVE_LOCAL_ENTITY));
                capture.invoke(positions, session, player, decoded, BedrockCoordinateFrame.IDENTITY);
                flush(type, positions, player);
                assertEquals(local ? new Vec3(32, 70, 16) : new Vec3(0, 64, 0), horse.clientPhysicalPosition);
                assertEquals(local, horse.onGround);
            }
        } finally { OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player); }
    }

    @SuppressWarnings("unchecked") private static void flush(Class<?> type, Object positions, CultPlayer player) throws Exception {
        var field = type.getDeclaredField("pending"); field.setAccessible(true);
        var pending = (java.util.List<Runnable>) field.get(positions);
        java.util.List.copyOf(pending).forEach(Runnable::run);
        pending.clear();
        player.latencyUtils.handleNettySyncTransaction(player.lastTransactionReceived.get());
    }
}
