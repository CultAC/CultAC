package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.badpackets.BadPacketsJ;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockChangedAck;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Minecraft#handleKeybinds sends use_item from MultiPlayerGameMode#useItem with the player's rotation, before
 * LocalPlayer#sendPosition sends that rotation with the tick's movement, or sends none because it did not change, and
 * Minecraft#tick ends the tick with client_tick_end. ServerGamePacketListenerImpl#handleUseItem turns the player to the
 * use's rotation before it uses the item.
 */
class UseItemTickRotationForwardTest {
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final float YAW = 98.4052734375f;
    private static final float PITCH = 52.35009765625f;
    private static final float TURNED_YAW = 131.25f;

    @Test
    void aUseAtARotationTheTickDoesNotSendNeverReachesTheServerAndItsStackIsRestored() throws Exception {
        try (var f = new JavaTickFixture()) {
            var t = new Ticks(f);
            t.start();

            t.use(1, YAW, 90);
            assertEquals(List.of(), t.forwarded(), "the use went to the server before its tick's rotation");
            t.tickEnd();
            assertEquals(List.of("client_tick_end"), t.forwarded());
            assertEquals(1, t.check.getViolations());

            var corrections = t.sent();
            assertTrue(corrections.contains(new ClientboundBlockChangedAck(1)), "the use is not acknowledged");
            assertTrue(
                    corrections.stream()
                            .anyMatch(packet -> packet instanceof InventoryPackets.Slot slot
                                    && slot.item().is("minecraft:snowball")
                                    && slot.item().count() == 16),
                    "the thrown snowball is not restored: " + corrections);
        }
    }

    @Test
    void aUseAtTheTicksRotationReachesTheServerWithThePacketsAfterItInOrder() throws Exception {
        try (var f = new JavaTickFixture()) {
            var t = new Ticks(f);
            t.start();

            t.use(1, TURNED_YAW, PITCH);
            t.use(2, TURNED_YAW, PITCH);
            t.carry(1);
            assertEquals(List.of(), t.forwarded(), "the uses went to the server before their tick's rotation");
            t.rot(TURNED_YAW);
            assertEquals(List.of("use_item 1", "use_item 2", "set_carried_item", "move_player"), t.forwarded());
            t.tickEnd();
            assertEquals(List.of("client_tick_end"), t.forwarded());
            assertEquals(0, t.check.getViolations());
        }
    }

    @Test
    void aUseInATickThatSendsNoRotationReachesTheServerAtItsTickEnd() throws Exception {
        try (var f = new JavaTickFixture()) {
            var t = new Ticks(f);
            t.start();

            t.use(1, YAW, PITCH);
            assertEquals(List.of(), t.forwarded(), "the use went to the server before its tick ended");
            t.tickEnd();
            assertEquals(List.of("use_item 1", "client_tick_end"), t.forwarded());
            assertEquals(0, t.check.getViolations());
        }
    }

    private static final class Ticks {
        final JavaTickFixture f;
        final BadPacketsJ check;

        Ticks(JavaTickFixture f) {
            this.f = f;
            check = f.player.checkManager.getListener(BadPacketsJ.class);
            check.setEnabled(true);
            f.fixture.registrar.register(routes -> {
                var checks = new CheckManagerListener();
                checks.registerForwardingEarlyReceivePackets(routes, PacketListenerPriority.LOW);
                routes.registerReceiveListener(PacketListenerPriority.LOW, checks);
            });
        }

        /** Snowballs in the main hand; the client sent YAW and PITCH with its last movement. */
        void start() {
            var inventory = f.player.getInventory();
            var slot = new InventoryPackets.Slot(
                    0,
                    1,
                    inventory.inventory.menuSlotForStorage(Inventory.HOTBAR_OFFSET),
                    ItemUtil.modelItems().stack("minecraft:snowball", 16));
            var event = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, slotType(), slot, false);
            inventory.onContainerSetSlot(event, f.player, slot);
            event.tasksAfter().forEach(Runnable::run);
            f.pongThrough(f.player.lastTransactionSent.get());
            inventory.selectHotbarSlot(0);
            f.place(START, Vec3.ZERO);
            receive(
                    ServerboundPackets.MOVE_PLAYER,
                    new ServerboundMovePlayer(START.x, START.y, START.z, YAW, PITCH, true, false, true, true));
            tickEnd();
            forwarded();
            sent();
        }

        @SuppressWarnings("unchecked")
        private static PacketType<InventoryPackets.Slot> slotType() {
            return (PacketType<InventoryPackets.Slot>) TestProtocolRuntime.catalog().stream()
                    .filter(type -> type.recordClass() == InventoryPackets.Slot.class)
                    .findFirst()
                    .orElseThrow();
        }

        void rot(float yaw) {
            receive(
                    ServerboundPackets.MOVE_PLAYER,
                    new ServerboundMovePlayer(0, 0, 0, yaw, PITCH, true, false, false, true));
        }

        void tickEnd() {
            f.fixture.channel.writeInbound(f.fixture.id(ServerboundPackets.CLIENT_TICK_END, "client_tick_end"));
        }

        void use(int sequence, float yaw, float pitch) {
            ByteBuf frame = f.fixture.id(ServerboundPackets.USE_ITEM, "use_item");
            Wire.writeVarInt(frame, Hand.MAIN_HAND.ordinal());
            Wire.writeVarInt(frame, sequence);
            frame.writeFloat(yaw).writeFloat(pitch);
            f.fixture.channel.writeInbound(frame);
        }

        void carry(int slot) {
            ByteBuf frame = f.fixture.id(ServerboundPackets.SET_CARRIED_ITEM, "set_carried_item");
            frame.writeShort(slot);
            f.fixture.channel.writeInbound(frame);
        }

        <R> void receive(PacketType<R> type, R packet) {
            f.fixture.channel.writeInbound(f.fixture.encode(type, packet));
        }

        /** What reached the server since the last call, in order. */
        List<String> forwarded() {
            List<String> packets = new ArrayList<>();
            ByteBuf frame;
            while ((frame = f.fixture.channel.readInbound()) != null) {
                try {
                    int id = Wire.readVarInt(frame);
                    var type = f.fixture.codec.typeOf(PacketDirection.SERVERBOUND, id);
                    String name = type.key().substring(type.key().indexOf('.') + 1);
                    if (type == ServerboundPackets.USE_ITEM) {
                        var use = (ServerboundUseItem) f.fixture.codec.read(PacketDirection.SERVERBOUND, id, frame);
                        name += " " + use.sequence();
                    }
                    packets.add(name);
                } finally {
                    frame.release();
                }
            }
            return packets;
        }

        /** What CultAC sent the client since the last call. */
        List<Object> sent() {
            List<Object> packets = new ArrayList<>();
            ByteBuf frame;
            while ((frame = f.fixture.channel.readOutbound()) != null) {
                try {
                    int id = Wire.readVarInt(frame);
                    Object packet = f.fixture.codec.read(PacketDirection.CLIENTBOUND, id, frame);
                    if (packet != null) packets.add(packet);
                } finally {
                    frame.release();
                }
            }
            return packets;
        }
    }
}
