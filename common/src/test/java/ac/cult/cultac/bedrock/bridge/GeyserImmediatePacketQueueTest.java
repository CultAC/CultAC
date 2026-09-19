package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.bedrock.protocol.BedrockTeleportProvenance;
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
        dispatch.finish(512, -256, BedrockTeleportProvenance.GFP_REBASE, null);
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
        dispatch.finish(1024, 0, BedrockTeleportProvenance.GFP_REBASE, null);
        assertSame(packet, queue.getFirst());
        assertEquals(1024, dispatch.take(packet).frame().originX());
    }
}
