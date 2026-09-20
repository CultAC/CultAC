package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.bedrock.protocol.BedrockTeleportProvenance;
import ac.cult.cultac.bedrock.protocol.BedrockTeleportOperation;
import java.util.ArrayList;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityDeltaPacket;
import org.junit.Test;

import static org.junit.Assert.*;

public final class GeyserImmediatePacketQueueTest {
    @Test
    public void tickEndBatchRetainsEnqueueOriginAcrossLaterRebase() {
        var dispatch = new BedrockOriginDispatch();
        var packets = new ArrayList<BedrockPacket>();
        var queue = new GeyserImmediatePacketQueue(packets, dispatch::enqueue);
        var first = new MoveEntityDeltaPacket();
        queue.add(first);

        dispatch.begin();
        dispatch.finish(512, -256, new BedrockTeleportOperation(1, BedrockTeleportProvenance.GFP_REBASE, null));
        var second = new MoveEntityDeltaPacket();
        queue.add(second);

        // GeyserSession.tick sends this array directly through BedrockPeer.
        assertArrayEquals(new BedrockPacket[]{first, second}, queue.toArray(new BedrockPacket[0]));
        queue.clear();
        assertTrue(packets.isEmpty());
        assertEquals(0, dispatch.take(first).frame().originX());
        var shifted = dispatch.take(second).frame();
        assertEquals(512, shifted.originX());
        assertEquals(-256, shifted.originZ());
    }

    @Test
    public void packetsQueuedDuringRewriteWaitForItsResolvedOrigin() {
        var dispatch = new BedrockOriginDispatch();
        var queue = new GeyserImmediatePacketQueue(new ArrayList<>(), dispatch::enqueue);
        var packet = new MoveEntityDeltaPacket();
        dispatch.begin();
        queue.add(packet);
        assertTrue(queue.isEmpty());
        dispatch.finish(1024, 0, new BedrockTeleportOperation(1, BedrockTeleportProvenance.GFP_REBASE, null));
        assertSame(packet, queue.getFirst());
        assertEquals(1024, dispatch.take(packet).frame().originX());
    }
    @Test
    public void nestedRebaseAndRetryRetainTheirOwnOperationAndEnqueueFrame() {
        var dispatch = new BedrockOriginDispatch();
        var queue = new GeyserImmediatePacketQueue(new ArrayList<>(), dispatch::enqueue);
        var teleport = new BedrockTeleportOperation(1, BedrockTeleportProvenance.GEYSER, 7);
        var rebase = new BedrockTeleportOperation(2, BedrockTeleportProvenance.GFP_REBASE, null);
        var first = new MoveEntityDeltaPacket();
        var nested = new MoveEntityDeltaPacket();
        var retry = new MoveEntityDeltaPacket();
        dispatch.withOperation(teleport, () -> {
            queue.add(first);
            dispatch.begin();
            queue.add(nested);
            dispatch.finish(4096, 0, rebase);
        });
        dispatch.withOperation(rebase, () -> queue.add(retry));
        assertEquals(0, dispatch.take(first).frame().originX());
        var nestedEmission = dispatch.take(nested);
        var retryEmission = dispatch.take(retry);
        assertEquals(rebase, nestedEmission.operation());
        assertEquals(nestedEmission, retryEmission);
        var ordinary = new MoveEntityDeltaPacket();
        queue.add(ordinary);
        assertNull(dispatch.take(ordinary).operation());
    }

}
