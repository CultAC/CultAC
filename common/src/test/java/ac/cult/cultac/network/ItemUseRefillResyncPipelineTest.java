package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.events.packets.listeners.PacketSelfMetadataListener;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A kit reload empties and refills the offhand the server keeps eating from. The client stopped
 * on the emptied hand (LivingEntity#updatingUsingItem), and LocalPlayer#onSyncedDataUpdated restarts it only on the
 * server's use flags, with the item back in the hand.
 */
class ItemUseRefillResyncPipelineTest {
    private static final byte USING_OFFHAND = 3;

    @Test
    void theServersUseFlagsFollowTheRefillOfAHandItEmptied() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var slotType = register(fixture);
            SimItemStack apples = ItemUtil.modelItems().stack("minecraft:golden_apple", 6);
            fixture.player.getInventory().inventory.getInventoryStorage().setItem(Inventory.SLOT_OFFHAND, apples);
            byte[] flags = flags(fixture);
            assertTrue(fixture.channel.writeOutbound(Unpooled.wrappedBuffer(flags)));
            drain(fixture);
            assertTrue(fixture.player.itemUseBurst.tracking());

            // A slot that only repeats the held item resends nothing.
            assertEquals(-1, indexOf(send(fixture, slotType, apples), flags));

            assertEquals(-1, indexOf(send(fixture, slotType, SimItemStack.EMPTY), flags));
            byte[] refill = bytes(fixture.encode(slotType, slot(apples)));
            List<byte[]> written = send(fixture, slotType, apples);
            int refilled = indexOf(written, refill);
            assertTrue(refilled >= 0);
            assertTrue(indexOf(written, flags) > refilled);

            assertEquals(-1, indexOf(send(fixture, slotType, apples), flags));
        }
    }

    private static InventoryPackets.Slot slot(SimItemStack item) {
        return new InventoryPackets.Slot(0, 1, Inventory.SLOT_OFFHAND, item);
    }

    private static List<byte[]> send(
            RecordReceiveFixture fixture, PacketType<InventoryPackets.Slot> type, SimItemStack item) {
        assertTrue(fixture.channel.writeOutbound(fixture.encode(type, slot(item))));
        return drain(fixture);
    }

    @SuppressWarnings("unchecked")
    private static PacketType<InventoryPackets.Slot> register(RecordReceiveFixture fixture) {
        fixture.user.bind(mock(PlatformPlayer.class));
        var type = (PacketType<InventoryPackets.Slot>)
                fixture.registrar.runtime().typeForKey("clientbound.container_set_slot");
        fixture.registrar.register(routes -> {
            routes.registerSendListener(PacketListenerPriority.HIGH, new PacketSelfMetadataListener());
            routes.sendRoute(
                    type,
                    PacketListenerPriority.NORMAL,
                    (event, player, packet) -> player.getInventory()
                            .onContainerSetSlot(
                                    (PacketSendEvent<InventoryPackets.Slot>) (Object) event,
                                    player,
                                    (InventoryPackets.Slot) packet));
        });
        drain(fixture);
        return type;
    }

    private static byte[] flags(RecordReceiveFixture fixture) {
        var frame = Unpooled.buffer();
        Wire.writeVarInt(
                frame,
                fixture.data
                        .packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND)
                        .id("minecraft:set_entity_data"));
        Wire.writeVarInt(frame, fixture.player.entityID);
        frame.writeBytes(new byte[] {(byte) WatchableIndexUtil.LIVING_ENTITY_FLAGS, 0, USING_OFFHAND});
        frame.writeByte(255);
        return bytes(frame);
    }

    private static byte[] bytes(ByteBuf frame) {
        byte[] bytes = ByteBufUtil.getBytes(frame);
        frame.release();
        return bytes;
    }

    private static int indexOf(List<byte[]> written, byte[] frame) {
        for (int i = 0; i < written.size(); i++) if (Arrays.equals(written.get(i), frame)) return i;
        return -1;
    }

    private static List<byte[]> drain(RecordReceiveFixture fixture) {
        fixture.channel.runPendingTasks();
        var written = new ArrayList<byte[]>();
        Object message;
        while ((message = fixture.channel.readOutbound()) != null) {
            if (message instanceof ByteBuf buffer) {
                written.add(ByteBufUtil.getBytes(buffer));
                buffer.release();
            }
        }
        assertNull(fixture.channel.readOutbound());
        return written;
    }
}
