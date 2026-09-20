package ac.cult.cultac.bedrock.bridge;

import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.mcprotocollib.network.event.session.PacketSendingEvent;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomPayloadPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class GeyserServerResponsesTest {
    @Test
    public void orderedMarkersBracketOnlyServerTranslationResponses() {
        var session = mock(GeyserSession.class, RETURNS_DEEP_STUBS);
        var channel = new EmbeddedChannel();
        var downstream = session.getDownstream().getSession();
        when(downstream.getChannel()).thenReturn(channel);
        var responses = new GeyserServerResponses(session);
        List<Packet> writes = new ArrayList<>();
        doAnswer(call -> { writes.add(call.getArgument(0)); return null; }).when(downstream).send(any(Packet.class));
        var echo = new ServerboundMovePlayerPosRotPacket(false, false, 527.2019975614745,
                64.84375002384186, -78.90235218181445, 0, 0);
        Runnable movement = () -> {
            responses.sending(new PacketSendingEvent(downstream, echo));
            writes.add(echo);
        };
        try {
            // A raw-input translation before or after the server dispatch gets no marker.
            channel.eventLoop().execute(movement);
            responses.translate(() -> channel.eventLoop().execute(movement));
            channel.eventLoop().execute(movement);
            channel.runPendingTasks();
            assertEquals(5, writes.size());
            assertSame(echo, writes.get(0));
            assertTrue(writes.get(1) instanceof ServerboundCustomPayloadPacket);
            assertSame(echo, writes.get(2));
            assertTrue(writes.get(3) instanceof ServerboundCustomPayloadPacket);
            assertSame(echo, writes.get(4));
        } finally { channel.finishAndReleaseAll(); }
    }
}
