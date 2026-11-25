package ac.cult.cultac.protocol;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPacket;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class ConnectionCodecStateTest {
    private record Value(int value) implements ClientboundPacket {}

    @Test
    void sharedRuntimeKeepsConcurrentConnectionStateOutOfItsBindings() throws Exception {
        var catalog = PacketCatalog.clientbound();
        var type = catalog.in(ConnectionPhase.PLAY).add("ping", Value.class, new WritablePacketCodec<Value>() {
            public Value read(ByteBuf input, ProtocolContext context) {
                return new Value(input.readInt() + context.state().require(Integer.class));
            }

            public void write(ByteBuf output, ProtocolContext context, Value value) {
                output.writeInt(value.value() - context.state().require(Integer.class));
            }
        });
        var runtime = ProtocolRuntime.create(ProtocolData.load(ProtocolVersion.V26_3), catalog.types());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> roundTrips(runtime, type, 71));
            var second = executor.submit(() -> roundTrips(runtime, type, -92));
            first.get();
            second.get();
        } finally {
            executor.shutdownNow();
        }
        int id = runtime.data()
                .packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND)
                .id("minecraft:ping");
        assertSame(
                CodecState.EMPTY,
                runtime.binding(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND, id)
                        .context()
                        .state());
    }

    private static void roundTrips(ProtocolRuntime runtime, PacketType<Value> type, int delta) {
        CodecState connection = new CodecState() {
            public <T> T require(Class<T> requested) {
                return requested.cast(delta);
            }
        };
        for (int value = 0; value < 1000; value++) {
            ByteBuf bytes = Unpooled.buffer();
            try {
                runtime.encode(ConnectionPhase.PLAY, type, new Value(value), bytes, connection);
                int id = Wire.readVarInt(bytes);
                assertEquals(value - delta, bytes.getInt(bytes.readerIndex()));
                assertEquals(
                        new Value(value),
                        runtime.decode(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND, id, bytes, connection));
            } finally {
                bytes.release();
            }
        }
    }
}
