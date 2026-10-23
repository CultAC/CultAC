package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.events.packets.listeners.PacketSelfMetadataListener;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** LivingEntity#onSyncedDataUpdated(SLEEPING_POS_ID) moving the local player onto its bed. */
class SleepingPositionMetadataTest {
    private static final BlockPos BED = new BlockPos(8, 64, 8);
    private static final Vec3 START = new Vec3(10.5, 70, 10.5);

    @Test
    void aNonBedSleepingPositionLeavesThe263ClientWhereItIs() {
        try (var fixture = fixture()) {
            var player = fixture.player;
            block(player, BED, "minecraft:stone");
            sleepAt(fixture, BED);
            assertTrue(player.isInBed);
            assertEquals(START, player.bedPosition);
        }
    }

    @Test
    void the263ClientMovesOntoEachBedsSleepHeight() {
        try (var fixture = fixture()) {
            var player = fixture.player;
            block(player, BED, "minecraft:red_bed");
            sleepAt(fixture, BED);
            assertEquals(new Vec3(8.5, 64.6875, 8.5), player.bedPosition);

            var registry = DataTables.defaults().registry();
            int strawFoot = registry.block("minecraft:straw_bed").defaultState();
            int strawHead = registry.with(strawFoot, "part", "head");
            // A head without its connected foot has no sleep height.
            player.compensatedWorld.updateBlock(BED, strawHead);
            sleepAt(fixture, BED);
            assertEquals(new Vec3(8.5, 64.6875, 8.5), player.bedPosition);

            // The north-facing head's foot is to its south.
            player.compensatedWorld.updateBlock(BED.south(), strawFoot);
            sleepAt(fixture, BED);
            assertEquals(new Vec3(8.5, 64.375, 8.5), player.bedPosition);
        }
    }

    @Test
    void the262ClientMovesOntoAnySleepingPosition() throws Exception {
        try (var fixture = fixture()) {
            var player = fixture.player;
            var resolved = CultPlayer.class.getDeclaredField("resolvedClientVersion");
            resolved.setAccessible(true);
            resolved.set(player, ClientVersion.V_26_2);
            block(player, BED, "minecraft:stone");
            sleepAt(fixture, BED);
            assertEquals(new Vec3(8.5, 64.6875, 8.5), player.bedPosition);
        }
    }

    private static RecordReceiveFixture fixture() {
        var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3);
        fixture.user.bind(mock(PlatformPlayer.class));
        var player = fixture.player;
        player.entityID = 7;
        player.x = START.x;
        player.y = START.y;
        player.z = START.z;
        player.compensatedWorld.ensureValidationChunkLoaded(0, 0);
        return fixture;
    }

    private static void block(CultPlayer player, BlockPos pos, String block) {
        player.compensatedWorld.updateBlock(
                pos, DataTables.defaults().registry().block(block).defaultState());
    }

    private static void sleepAt(RecordReceiveFixture fixture, BlockPos bed) {
        var player = fixture.player;
        var packet = new EntityMetadata(
                7,
                List.of(new EntityMetadata.Entry(
                        WatchableIndexUtil.LIVING_SLEEPING_POS, Optional.of(bed), ByteBuffer.allocate(0))));
        var event = new PacketSendEvent<>(fixture.user, ConnectionPhase.PLAY, metadataType(), packet, false);
        new PacketSelfMetadataListener().onSetEntityData(event, player, packet);
        event.tasksAfter().forEach(Runnable::run);
        for (int id = player.lastTransactionReceived.get() + 1; id <= player.lastTransactionSent.get(); id++) {
            player.lastTransactionReceived.set(id);
            player.latencyUtils.handleNettySyncTransaction(id);
        }
    }

    @SuppressWarnings("unchecked")
    private static PacketType<EntityMetadata> metadataType() {
        return (PacketType<EntityMetadata>) TestProtocolRuntime.catalog().stream()
                .filter(type -> type.recordClass() == EntityMetadata.class)
                .findFirst()
                .orElseThrow();
    }
}
