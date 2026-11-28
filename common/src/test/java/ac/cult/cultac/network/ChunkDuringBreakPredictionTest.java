package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.visibility.BreakVisibility;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.events.packets.listeners.PacketPingListener;
import ac.cult.cultac.events.packets.worldreader.PacketWorldReaderTwentySix;
import ac.cult.cultac.manager.player.SetbackFixtures;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.WorldPackets;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockChangedAck;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPong;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A chunk the client takes while its break of a block in it is unacknowledged replaces the predicted air
 * (ClientChunkCache#replaceWithPacketData), so the client can dig that block again before the acknowledgement.
 * Positions, sequences and faces are bundle 01a218af's (seqs 28353..28357).
 */
class ChunkDuringBreakPredictionTest {
    private static final int SECTIONS = 24, MIN_Y = -64, CHUNK_X = 16, CHUNK_Z = 50;
    private static final BlockPos GRASS = new BlockPos(258, 66, 807);

    @Test
    void aChunkResentDuringTheBreakShowsTheBlockAgain() throws Exception {
        try (var setup = new Setup()) {
            setup.chunk(true);
            setup.at(259.6711713082228, 805.206481603378);
            setup.dig(GRASS, Direction.NORTH, 741);
            assertEquals(state("air"), setup.block(GRASS));
            setup.chunk(true);
            setup.confirm(setup.output());
            assertEquals(state("short_grass"), setup.block(GRASS));
            setup.fixture.user.write(new ClientboundBlockChangedAck(741));
            var ack = setup.output();
            setup.at(259.65299385968297, 805.2383467451207);
            setup.dig(GRASS, Direction.UP, 742);
            assertEquals(0, setup.breaking.violations);
            setup.confirm(ack);
            assertEquals(state("air"), setup.block(GRASS));
        }
    }

    @Test
    void theAcknowledgementRestoresTheBlockRetainedAtTheBreakNotTheChunks() throws Exception {
        try (var setup = new Setup()) {
            setup.chunk(true);
            setup.at(259.6711713082228, 805.206481603378);
            setup.dig(GRASS, Direction.NORTH, 741);
            setup.chunk(false);
            setup.confirm(setup.output());
            assertEquals(state("air"), setup.block(GRASS));
            setup.fixture.user.write(new ClientboundBlockChangedAck(741));
            setup.confirm(setup.output());
            assertEquals(state("short_grass"), setup.block(GRASS));
        }
    }

    @Test
    void aBlockBrokenAgainBeforeAnyChunkStillFlags() throws Exception {
        try (var setup = new Setup()) {
            setup.chunk(true);
            setup.at(259.6711713082228, 805.206481603378);
            setup.dig(GRASS, Direction.NORTH, 741);
            setup.at(259.65299385968297, 805.2383467451207);
            setup.dig(GRASS, Direction.UP, 742);
            assertEquals(1, setup.breaking.violations);
        }
    }

    @Test
    void aChunkWithoutTheBlockKeepsItsBreakFlagged() throws Exception {
        try (var setup = new Setup()) {
            setup.chunk(true);
            setup.at(259.6711713082228, 805.206481603378);
            setup.dig(GRASS, Direction.NORTH, 741);
            setup.chunk(false);
            setup.confirm(setup.output());
            setup.at(259.65299385968297, 805.2383467451207);
            setup.dig(GRASS, Direction.UP, 742);
            assertEquals(1, setup.breaking.violations);
        }
    }

    private static int state(String name) {
        return DataTables.defaults().registry().block("minecraft:" + name).defaultState();
    }

    /** Pinned 26.3 column: grass blocks at y 64 and 65, and short grass at {@link #GRASS} when {@code grass}. */
    private static ByteBuffer column(boolean grass) {
        ByteBuf out = Unpooled.buffer();
        int air = state("air");
        int section = (GRASS.getY() - MIN_Y) >> 4;
        for (int i = 0; i < SECTIONS; i++) {
            if (i != section) {
                out.writeShort(0).writeShort(0);
                out.writeByte(0);
                Wire.writeVarInt(out, air);
            } else {
                long[] data = new long[256];
                int count = 0;
                for (int index = 0; index < 4096; index++) {
                    int y = index >> 8, z = index >> 4 & 15, x = index & 15;
                    int entry = y <= 1 ? 1 : 0;
                    if (grass && y == (GRASS.getY() & 15) && z == (GRASS.getZ() & 15) && x == (GRASS.getX() & 15))
                        entry = 2;
                    if (entry != 0) count++;
                    data[index >> 4] |= (long) entry << ((index & 15) * 4);
                }
                out.writeShort(count).writeShort(0);
                out.writeByte(4);
                Wire.writeVarInt(out, 3);
                Wire.writeVarInt(out, air);
                Wire.writeVarInt(out, DataTables.defaults().registry().block("minecraft:grass_block").defaultState());
                Wire.writeVarInt(out, state("short_grass"));
                for (long value : data) out.writeLong(value);
            }
            out.writeByte(0);
            Wire.writeVarInt(out, 0);
        }
        byte[] bytes = new byte[out.readableBytes()];
        out.readBytes(bytes);
        out.release();
        return ByteBuffer.wrap(bytes);
    }

    private static final class Setup implements AutoCloseable {
        final RecordConsumerServices services = new RecordConsumerServices();
        final RecordReceiveFixture fixture = new RecordReceiveFixture(ProtocolVersion.V26_3, false);
        final CultPlayer player;
        final BreakVisibility breaking;

        Setup() throws Exception {
            services.useNetwork(fixture.manager);
            CultAPI.INSTANCE.getPlayerDataManager().addUser(fixture.user);
            player = fixture.user.getCultPlayer();
            assertNotNull(player);
            player.platformPlayer = mock(PlatformPlayer.class);
            var platformWorld = mock(PlatformWorld.class);
            when(player.platformPlayer.getWorld()).thenReturn(platformWorld);
            when(platformWorld.getName()).thenReturn("world");
            fixture.user.bind(player.platformPlayer);
            player.checkManager.allChecks.values().forEach(check -> check.setEnabled(false));
            breaking = player.checkManager.getCheck(BreakVisibility.class);
            breaking.setEnabled(true);
            player.gamemode = GameMode.SURVIVAL;
            player.registryState = new ClientComponentRegistries();
            SetbackFixtures.setLoaded(player.getSetbackTeleportUtil(), true);
            var world = player.compensatedWorld;
            var dimension = player.getWorldRegistries().dimension("minecraft:overworld");
            world.setLastClientboundDimension("minecraft:overworld", dimension.dimension());
            world.setDimension("minecraft:overworld", dimension);
            fixture.registrar.register(routes -> {
                var checks = new CheckManagerListener();
                checks.registerForwardingEarlyReceivePackets(routes, PacketListenerPriority.LOW);
                routes.registerReceiveListener(PacketListenerPriority.LOW, checks);
                checks.registerForwardingSendPackets(routes, PacketListenerPriority.LOW);
                routes.registerListener(PacketListenerPriority.LOWEST, new PacketPingListener());
                routes.registerSendListener(PacketListenerPriority.HIGH, new PacketWorldReaderTwentySix());
            });
        }

        void at(double x, double z) {
            player.x = x;
            player.y = 66;
            player.z = z;
            player.packetStateData.clientSidePosition = new Vec3(x, 66, z);
            player.boundingBox = new SimpleCollisionBox(x - .3, 66, z - .3, x + .3, 67.8, z + .3);
        }

        /** The world reader on the server's column {@code CHUNK_X}, {@code CHUNK_Z}, every ping so far answered. */
        @SuppressWarnings("unchecked")
        void chunk(boolean grass) {
            var chunk = new WorldPackets.Chunk(CHUNK_X, CHUNK_Z, column(grass), List.of());
            var type = (PacketType<WorldPackets.Chunk>)
                    fixture.registrar.runtime().typeForKey("clientbound.level_chunk_with_light");
            var event = new PacketSendEvent<>(fixture.user, ConnectionPhase.PLAY, type, chunk, false);
            new PacketWorldReaderTwentySix().handleMapChunk(player, event, chunk);
            event.tasksAfter().forEach(Runnable::run);
            for (int id = player.lastTransactionReceived.get() + 1; id <= player.lastTransactionSent.get(); id++) {
                player.lastTransactionReceived.set(id);
                player.latencyUtils.handleNettySyncTransaction(id);
            }
            output();
        }

        int block(BlockPos pos) {
            return player.compensatedWorld.getBlockStateIdAt(pos);
        }

        void dig(BlockPos pos, Direction face, int sequence) {
            var frame = fixture.id(ServerboundPackets.PLAYER_ACTION, "player_action");
            Wire.writeVarInt(frame, PlayerAction.START_DESTROY_BLOCK.ordinal());
            Wire.writeBlockPos(frame, pos);
            frame.writeByte(face.ordinal());
            Wire.writeVarInt(frame, sequence);
            if (fixture.channel.writeInbound(frame)) {
                ByteBuf read = fixture.channel.readInbound();
                read.release();
            }
            fixture.forward(fixture.id(ServerboundPackets.CLIENT_TICK_END, "client_tick_end"));
        }

        List<Object> output() {
            List<Object> packets = new ArrayList<>();
            ByteBuf frame;
            while ((frame = fixture.channel.readOutbound()) != null) {
                try {
                    int id = Wire.readVarInt(frame);
                    Object packet = fixture.codec.read(PacketDirection.CLIENTBOUND, id, frame);
                    if (packet != null) packets.add(packet);
                } finally {
                    frame.release();
                }
            }
            return packets;
        }

        void confirm(List<Object> packets) {
            packets.stream()
                    .filter(ClientboundPing.class::isInstance)
                    .map(ClientboundPing.class::cast)
                    .forEach(ping -> fixture.channel.writeInbound(
                            fixture.encode(ServerboundPackets.PONG, new ServerboundPong(ping.id()))));
        }

        @Override
        public void close() throws Exception {
            try {
                fixture.close();
            } finally {
                services.close();
            }
        }
    }
}
