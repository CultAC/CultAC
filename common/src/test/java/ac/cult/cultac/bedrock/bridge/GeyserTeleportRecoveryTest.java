package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.bedrock.protocol.BedrockTeleportOperation;
import ac.cult.cultac.bedrock.protocol.BedrockTeleportProvenance;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockPositionTranslator;
import java.util.ArrayList;
import java.util.List;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityAbsolutePacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.session.cache.TeleportCache;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.*;

public class GeyserTeleportRecoveryTest {
    @Test public void rejectedMovementConfirmsGeyserQueueWithoutForwardingMovement() throws Exception {
        var session = Mockito.mock(GeyserSession.class, Mockito.CALLS_REAL_METHODS);
        Mockito.doReturn(true).when(session).isSpawned();
        var pending = Mockito.mock(TeleportCache.class);
        Mockito.when(pending.canConfirm(Vector3f.from(20, 64, 30))).thenReturn(true);
        var field = GeyserSession.class.getDeclaredField("unconfirmedTeleport");
        field.setAccessible(true);
        field.set(session, pending);

        var input = new PlayerAuthInputPacket();
        float offset = (float) BedrockPositionTranslator.PLAYER_PACKET_Y_OFFSET;
        input.setPosition(Vector3f.from(21, 64 + offset, 30));
        GeyserTeleportRecovery.confirmRejectedInput(session, input);
        assertSame(pending, session.getUnconfirmedTeleport());
        Mockito.verify(pending).incrementUnconfirmedFor();

        input.setPosition(Vector3f.from(20, 64 + offset, 30));
        GeyserTeleportRecovery.confirmRejectedInput(session, input);
        assertNull(session.getUnconfirmedTeleport());
        Mockito.verify(pending).canConfirm(Vector3f.from(20, 64, 30));
        Mockito.verify(session, Mockito.never()).sendDownstreamGamePacket(Mockito.any());
    }

    @Test public void originChangeRetriesLogicalOperationImmediatelyAndKeepsMotion() {
        var recovery = new GeyserTeleportRecovery();
        var operation = new BedrockTeleportOperation(7, BedrockTeleportProvenance.CULT_SETBACK, 42);
        var origin = new BedrockCoordinateFrame(512, 0, 1);
        List<BedrockPacket> writes = new ArrayList<>();
        List<SetEntityMotionPacket> retries = new ArrayList<>();
        var teleport = teleport();
        recovery.begin(teleport, operation, origin, 100, writes::add, retries::add);
        var motion = new SetEntityMotionPacket();
        motion.setRuntimeEntityId(1);
        motion.setMotion(Vector3f.from(0.1, 0, 0));
        recovery.motion(motion);
        motion.setMotion(Vector3f.ZERO);
        recovery.input(101, true, origin);
        assertTrue(retries.isEmpty());
        recovery.input(102, true, new BedrockCoordinateFrame(1024, 0, 2));
        assertEquals(1, retries.size());
        assertEquals(Vector3f.from(0.1, 0, 0), retries.getFirst().getMotion());
        assertEquals(operation, recovery.operation());
        assertEquals(1, writes.size());
        recovery.input(103, false, origin);
        assertFalse(recovery.active());
        recovery.input(200, true, origin);
        assertEquals(1, retries.size());
    }

    @Test public void resetPrecedesTeleportAndUsesOnlyAnObservedTick() {
        var recovery = new GeyserTeleportRecovery();
        var operation = new BedrockTeleportOperation(1, BedrockTeleportProvenance.CULT_SETBACK, 1);
        var teleport = teleport();
        List<BedrockPacket> writes = new ArrayList<>();
        recovery.begin(teleport, operation, BedrockCoordinateFrame.IDENTITY, 90, writes::add, ignored -> {});
        writes.add(teleport);
        var reset = (MovePlayerPacket) writes.getFirst();
        assertEquals(MovePlayerPacket.Mode.RESPAWN, reset.getMode());
        assertEquals(90, reset.getTick());
        assertEquals(teleport.getPosition(), reset.getPosition());
        assertEquals(MovePlayerPacket.Mode.TELEPORT, teleport.getMode());
        assertEquals(0, teleport.getTick());
        writes.clear();
        recovery.begin(teleport, operation, BedrockCoordinateFrame.IDENTITY, 0, writes::add, ignored -> {});
        assertTrue(writes.isEmpty());
    }

    @Test public void unchangedOriginRetriesAfterTwentyDistinctInputs() {
        var recovery = new GeyserTeleportRecovery();
        List<SetEntityMotionPacket> retries = new ArrayList<>();
        recovery.begin(teleport(), new BedrockTeleportOperation(1, BedrockTeleportProvenance.CULT_SETBACK, 1),
                BedrockCoordinateFrame.IDENTITY, 50, ignored -> {}, retries::add);
        for (long tick = 51; tick < 70; tick++) {
            recovery.input(tick, true, BedrockCoordinateFrame.IDENTITY);
            recovery.input(tick, true, BedrockCoordinateFrame.IDENTITY);
        }
        assertTrue(retries.isEmpty());
        recovery.input(70, true, BedrockCoordinateFrame.IDENTITY);
        assertEquals(1, retries.size());
    }

    @Test public void absolutePlayerTeleportResetUsesTheSameFinalWireDestination() {
        var teleport = new MoveEntityAbsolutePacket();
        teleport.setRuntimeEntityId(3);
        teleport.setPosition(Vector3f.from(56.96045, 65.144684, -90.754425));
        teleport.setRotation(Vector3f.from(-1.367798, -56.008316, -56.008316));
        teleport.setOnGround(false);
        teleport.setTeleported(true);
        var reset = GeyserTeleportRecovery.reset(teleport, 867);
        assertEquals(MovePlayerPacket.Mode.RESPAWN, reset.getMode());
        assertEquals(867, reset.getTick());
        assertEquals(teleport.getRuntimeEntityId(), reset.getRuntimeEntityId());
        assertEquals(teleport.getPosition(), reset.getPosition());
        assertEquals(teleport.getRotation(), reset.getRotation());
        assertEquals(teleport.isOnGround(), reset.isOnGround());
        assertTrue(teleport.isTeleported());
    }

    private static MovePlayerPacket teleport() {
        var packet = new MovePlayerPacket();
        packet.setRuntimeEntityId(1);
        packet.setPosition(Vector3f.from(20, 66, 30));
        packet.setRotation(Vector3f.ZERO);
        packet.setMode(MovePlayerPacket.Mode.TELEPORT);
        return packet;
    }
}
