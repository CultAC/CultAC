package ac.grim.grimac.network;

import ac.grim.grimac.network.event.PacketReceiveEvent;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PacketReceivePipelineTest {
    @Test
    public void earlyCancellationFinishesEarlyPhaseAndBlocksEveryLaterPhase() {
        List<String> calls = new ArrayList<>();
        PacketReceiveEvent event = event();

        PacketReceivePipeline.dispatch(
                route(
                        (receiveEvent, player, packet) -> {
                            calls.add("early-cancel");
                            receiveEvent.setCancelled(true);
                        },
                        (receiveEvent, player, packet) -> calls.add("early-after-cancel")
                ),
                route((receiveEvent, player, packet) -> calls.add("ordinary")),
                route((receiveEvent, player, packet) -> calls.add("tap")),
                event,
                null,
                event.getNmsPacket()
        );

        assertEquals(List.of("early-cancel", "early-after-cancel"), calls);
        assertTrue(event.isCancelled());
    }

    @Test
    public void ordinaryCancellationStillFinishesOrdinaryPhaseAndRunsTap() {
        List<String> calls = new ArrayList<>();
        PacketReceiveEvent event = event();

        PacketReceivePipeline.dispatch(
                route((receiveEvent, player, packet) -> calls.add("early")),
                route(
                        (receiveEvent, player, packet) -> {
                            calls.add("ordinary-cancel");
                            receiveEvent.setCancelled(true);
                        },
                        (receiveEvent, player, packet) -> calls.add("ordinary-after-cancel")
                ),
                route((receiveEvent, player, packet) -> calls.add("tap")),
                event,
                null,
                event.getNmsPacket()
        );

        assertEquals(List.of("early", "ordinary-cancel", "ordinary-after-cancel", "tap"), calls);
        assertTrue(event.isCancelled());
    }

    @Test
    public void uncancelledPacketTraversesAllPhases() {
        List<String> calls = new ArrayList<>();
        PacketReceiveEvent event = event();

        PacketReceivePipeline.dispatch(
                route((receiveEvent, player, packet) -> calls.add("early")),
                route((receiveEvent, player, packet) -> calls.add("ordinary")),
                route((receiveEvent, player, packet) -> calls.add("tap")),
                event,
                null,
                event.getNmsPacket()
        );

        assertEquals(List.of("early", "ordinary", "tap"), calls);
        assertFalse(event.isCancelled());
    }

    @SafeVarargs
    private static PacketReceiveRoute route(PacketReceiveHandler<Packet<?>>... handlers) {
        return PacketReceiveRoute.of(handlers);
    }

    private static PacketReceiveEvent event() {
        return new PacketReceiveEvent(null, new ServerboundPongPacket(7), ConnectionProtocol.PLAY);
    }
}
