package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.codec.entity.MoveEntityCodec;
import ac.cult.cultac.protocol.netty.CultDecoder;
import ac.cult.cultac.protocol.netty.CultEncoder;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Moves written through the encoder reach a route only CultAC's own handlers take as {@link MoveEntityCodec#SUNK},
 * with the values the record would carry, and any other route as the record; the wire bytes are the same either way,
 * and the hold ends with every dispatch, a failing handler and a malformed frame included.
 */
class SunkMoveDispatchTest {
    private static final String[] VARIANTS = {
        "minecraft:move_entity_pos", "minecraft:move_entity_pos_rot", "minecraft:move_entity_rot"
    };

    @Test
    void ownRoutesSeeSunkMovesWithTheRecordsValues() {
        OfflineCultTestBootstrap.installConfig();
        Random random = new Random(59);
        List<byte[]> frames = new ArrayList<>();
        var version = CultAPI.INSTANCE
                .getNetworkManager()
                .dispatcher()
                .runtime()
                .data()
                .version();
        for (int i = 0; i < 300; i++) frames.add(frame(random, version));
        Run own = run(frames, true);
        Run foreign = run(frames, false);
        assertEquals(foreign.wire, own.wire);
        assertEquals(frames.size(), own.seen.size());
        assertEquals(foreign.seen, own.seen);
        assertTrue(own.sunk > 150, "sunk " + own.sunk);
        assertEquals(0, foreign.sunk);
        assertEquals(frames.size() - own.sunk, own.records, "stepped moves stay records");
    }

    private static final class Run {
        final List<String> seen = new ArrayList<>();
        final List<String> wire = new ArrayList<>();
        int sunk, records;
    }

    private static Run run(List<byte[]> frames, boolean own) {
        OfflineCultTestBootstrap.installConfig();
        var runtime = CultAPI.INSTANCE.getNetworkManager().dispatcher().runtime();
        var dispatcher = new PacketDispatcher(runtime);
        var run = new Run();
        boolean[] fail = {false};
        Consumer<PacketRouteBuilder> routes = builder ->
                builder.send(ClientboundPackets.MOVE_ENTITY, PacketListenerPriority.NORMAL, (event, player, packet) -> {
                    if (packet == MoveEntityCodec.SUNK) {
                        run.sunk++;
                        run.seen.add(event.getUser()
                                .getCultConnection()
                                .sunkMove()
                                .packet()
                                .toString());
                    } else {
                        run.records++;
                        run.seen.add(packet.toString());
                    }
                    if (fail[0]) throw new IllegalStateException("handler failure");
                });
        if (own) dispatcher.registerOwn(routes);
        else dispatcher.register(routes);
        var channel = new EmbeddedChannel();
        var connection = new CultConnection(channel, dispatcher, ignored -> new PacketOwner(channel.eventLoop(), null));
        for (var direction : PacketDirection.values()) connection.phase(direction, ConnectionPhase.PLAY);
        var user = new User(new User.Profile(UUID.randomUUID(), ".Sunk_Move_Test"), connection);
        CultPlayer player = new CultPlayer(user);
        synchronized (connection) {
            connection.player(player);
        }
        channel.pipeline().addLast("decoder", new ChannelInboundHandlerAdapter());
        channel.pipeline().addLast("encoder", new ChannelOutboundHandlerAdapter());
        CultDecoder.install(connection);
        CultEncoder.install(connection);
        try {
            for (int i = 0; i < frames.size(); i++) {
                fail[0] = i % 7 == 3;
                write(channel, run, Unpooled.wrappedBuffer(frames.get(i)));
                assertFalse(connection.sunkMove().held(), "frame " + i);
                if (i % 11 == 5) {
                    byte[] whole = frames.get(i);
                    int seen = run.seen.size();
                    write(channel, run, Unpooled.wrappedBuffer(whole, 0, Math.max(1, whole.length - 3)));
                    assertFalse(connection.sunkMove().held(), "truncated frame " + i);
                    if (run.seen.size() > seen) run.seen.removeLast();
                    run.wire.add("truncated");
                }
            }
            fail[0] = false;
        } finally {
            player.onRemove();
            channel.finishAndReleaseAll();
        }
        return run;
    }

    private static void write(EmbeddedChannel channel, Run run, ByteBuf frame) {
        try {
            channel.writeAndFlush(frame);
            channel.checkException();
        } catch (RuntimeException failure) {
            run.wire.add(failure.getClass().getName());
        }
        for (Object out; (out = channel.readOutbound()) != null; ) {
            run.wire.add(ByteBufUtil.hexDump((ByteBuf) out));
            ((ByteBuf) out).release();
        }
    }

    private static byte[] frame(Random random, ProtocolVersion version) {
        var clientbound = CultAPI.INSTANCE
                .getNetworkManager()
                .dispatcher()
                .runtime()
                .data()
                .packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND);
        int variant = random.nextInt(3);
        boolean position = variant != 2;
        boolean rotation = variant != 0;
        ByteBuf bytes = Unpooled.buffer();
        Wire.writeVarInt(bytes, clientbound.id(VARIANTS[variant]));
        Wire.writeVarInt(bytes, random.nextInt(1 << 20));
        if (version.atLeast(ProtocolVersion.V26_3)) {
            if (position) {
                int steps = random.nextInt(4) == 0 ? 1 + random.nextInt(3) : 0;
                Wire.writeVarInt(bytes, steps << 1 | random.nextInt(2));
                for (int i = 0; i < steps; i++) {
                    Wire.writeVarInt(bytes, 1 + random.nextInt(3));
                    shorts(bytes, random);
                }
                if (steps == 0) shorts(bytes, random);
            } else bytes.writeBoolean(random.nextBoolean());
        } else if (position) shorts(bytes, random);
        if (rotation) bytes.writeByte(random.nextInt(256)).writeByte(random.nextInt(256));
        if (!version.atLeast(ProtocolVersion.V26_3)) bytes.writeBoolean(random.nextBoolean());
        byte[] array = ByteBufUtil.getBytes(bytes);
        bytes.release();
        return array;
    }

    private static void shorts(ByteBuf bytes, Random random) {
        for (int i = 0; i < 3; i++) bytes.writeShort(random.nextInt(65536));
    }
}
