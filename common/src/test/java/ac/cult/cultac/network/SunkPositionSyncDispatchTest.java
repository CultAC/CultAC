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
import ac.cult.cultac.protocol.codec.entity.EntityPositionSyncCodec;
import ac.cult.cultac.protocol.codec.entity.MoveEntityCodec;
import ac.cult.cultac.protocol.netty.CultDecoder;
import ac.cult.cultac.protocol.netty.CultEncoder;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.value.Vec3d;
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
 * Position syncs written through the encoder, between moves, reach a route only CultAC's own handlers take as
 * {@link EntityPositionSyncCodec#SUNK}, with the values the record would carry, and any other route as the record;
 * the wire bytes are the same either way, and the hold ends with every dispatch, a failing handler and a malformed
 * frame included.
 */
class SunkPositionSyncDispatchTest {
    @Test
    void ownRoutesSeeSunkSyncsWithTheRecordsValues() {
        OfflineCultTestBootstrap.installConfig();
        Random random = new Random(61);
        List<byte[]> frames = new ArrayList<>();
        var version = CultAPI.INSTANCE
                .getNetworkManager()
                .dispatcher()
                .runtime()
                .data()
                .version();
        int syncs = 0;
        for (int i = 0; i < 300; i++) {
            boolean sync = random.nextInt(3) != 0;
            frames.add(sync ? syncFrame(random, version) : moveFrame(random, version));
            if (sync) syncs++;
        }
        Run own = run(frames, true);
        Run foreign = run(frames, false);
        assertEquals(foreign.wire, own.wire);
        assertEquals(frames.size(), own.seen.size());
        assertEquals(foreign.seen, own.seen);
        assertTrue(own.sunk > 100, "sunk " + own.sunk);
        assertEquals(0, foreign.sunk);
        assertEquals(syncs - own.sunk, own.records, "stepped syncs stay records");
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
        Consumer<PacketRouteBuilder> routes = builder -> {
            builder.send(
                    ClientboundPackets.ENTITY_POSITION_SYNC, PacketListenerPriority.NORMAL, (event, player, packet) -> {
                        if (packet == EntityPositionSyncCodec.SUNK) {
                            run.sunk++;
                            run.seen.add(event.getUser()
                                    .getCultConnection()
                                    .sunkPositionSync()
                                    .packet()
                                    .toString());
                        } else {
                            run.records++;
                            run.seen.add(packet.toString());
                        }
                        if (fail[0]) throw new IllegalStateException("handler failure");
                    });
            builder.send(ClientboundPackets.MOVE_ENTITY, PacketListenerPriority.NORMAL, (event, player, packet) -> {
                var sunkMove = event.getUser().getCultConnection().sunkMove();
                run.seen.add(packet == MoveEntityCodec.SUNK ? sunkMove.packet().toString() : packet.toString());
            });
        };
        if (own) dispatcher.registerOwn(routes);
        else dispatcher.register(routes);
        var channel = new EmbeddedChannel();
        var connection = new CultConnection(channel, dispatcher, ignored -> new PacketOwner(channel.eventLoop(), null));
        for (var direction : PacketDirection.values()) connection.phase(direction, ConnectionPhase.PLAY);
        var user = new User(new User.Profile(UUID.randomUUID(), ".Sunk_Sync_Test"), connection);
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
                assertFalse(connection.sunkPositionSync().held(), "frame " + i);
                assertFalse(connection.sunkMove().held(), "frame " + i);
                if (i % 11 == 5) {
                    byte[] whole = frames.get(i);
                    int seen = run.seen.size();
                    write(channel, run, Unpooled.wrappedBuffer(whole, 0, Math.max(1, whole.length - 3)));
                    assertFalse(connection.sunkPositionSync().held(), "truncated frame " + i);
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

    private static int id(String name) {
        return CultAPI.INSTANCE
                .getNetworkManager()
                .dispatcher()
                .runtime()
                .data()
                .packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND)
                .id(name);
    }

    private static byte[] syncFrame(Random random, ProtocolVersion version) {
        ByteBuf bytes = Unpooled.buffer();
        Wire.writeVarInt(bytes, id("minecraft:entity_position_sync"));
        Wire.writeVarInt(bytes, random.nextInt(1 << 20));
        if (version.atLeast(ProtocolVersion.V26_3)) {
            boolean stepped = random.nextInt(4) == 0;
            Wire.writeVarInt(bytes, stepped ? 1 : 0);
            if (stepped) {
                int steps = 1 + random.nextInt(3);
                Wire.writeVarInt(bytes, steps);
                for (int i = 0; i < steps; i++) {
                    Wire.writeVec3(bytes, vec(random));
                    Wire.writeVarInt(bytes, random.nextInt(4));
                }
            } else Wire.writeVec3(bytes, vec(random));
        } else {
            Wire.writeVec3(bytes, vec(random));
            bytes.writeZero(24);
        }
        bytes.writeFloat(random.nextFloat() * 360 - 180).writeFloat(random.nextFloat() * 180 - 90);
        bytes.writeBoolean(random.nextBoolean());
        byte[] array = ByteBufUtil.getBytes(bytes);
        bytes.release();
        return array;
    }

    private static byte[] moveFrame(Random random, ProtocolVersion version) {
        ByteBuf bytes = Unpooled.buffer();
        Wire.writeVarInt(bytes, id("minecraft:move_entity_pos"));
        Wire.writeVarInt(bytes, random.nextInt(1 << 20));
        if (version.atLeast(ProtocolVersion.V26_3)) Wire.writeVarInt(bytes, random.nextInt(2));
        for (int i = 0; i < 3; i++) bytes.writeShort(random.nextInt(65536));
        if (!version.atLeast(ProtocolVersion.V26_3)) bytes.writeBoolean(random.nextBoolean());
        byte[] array = ByteBufUtil.getBytes(bytes);
        bytes.release();
        return array;
    }

    private static Vec3d vec(Random random) {
        return new Vec3d(random.nextDouble() * 6.0e7 - 3.0e7, random.nextDouble() * 640 - 64, random.nextDouble());
    }
}
