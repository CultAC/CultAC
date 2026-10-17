package ac.cult.cultac.companion.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.companion.Stubs;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketProcessor;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.common.ServerCommonPacketListener;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import org.junit.jupiter.api.Test;

class OrderedTaskTest {
    private final List<String> handled = new ArrayList<>();
    private final PacketProcessor processor = new PacketProcessor(new Thread(() -> {}));
    private final ServerCommonPacketListener listener = listener(ServerCommonPacketListener.class);

    @Test
    void runsBetweenTheGamePacketsAroundIt() {
        processor.scheduleIfPossible(listener, new GamePacket("use item before"));
        assertTrue(OrderedTask.schedule(processor, listener, () -> handled.add("clear")));
        processor.scheduleIfPossible(listener, new GamePacket("use item after"));

        assertEquals(List.of(), handled);
        processor.processQueuedPackets();
        assertEquals(List.of("use item before", "clear", "use item after"), handled);
    }

    @Test
    void refusesListenersThatTakeNoGamePackets() {
        assertFalse(OrderedTask.schedule(processor, listener(PacketListener.class), () -> handled.add("clear")));
        processor.processQueuedPackets();
        assertEquals(List.of(), handled);
    }

    @Test
    void refusesAClosedQueue() {
        processor.close();
        assertFalse(OrderedTask.schedule(processor, listener, () -> handled.add("clear")));
    }

    private static <T extends PacketListener> T listener(Class<T> type) {
        return Stubs.stub(type, (proxy, method, args) -> switch (method.getName()) {
            case "isAcceptingMessages", "shouldHandleMessage" -> true;
            default -> null;
        });
    }

    private final class GamePacket implements Packet<ServerCommonPacketListener> {
        private final String name;

        GamePacket(String name) {
            this.name = name;
        }

        @Override
        public PacketType<ServerboundCustomPayloadPacket> type() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void handle(ServerCommonPacketListener listener) {
            handled.add(name);
        }
    }
}
