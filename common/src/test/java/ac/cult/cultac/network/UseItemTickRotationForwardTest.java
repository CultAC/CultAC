package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsJ;
import ac.cult.cultac.checks.impl.movement.timer.AbstractTimerCheck;
import ac.cult.cultac.checks.impl.movement.timer.TimerCheck;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.events.packets.listeners.PacketPlayerRespawn;
import ac.cult.cultac.events.packets.listeners.PacketPlayerWindow;
import ac.cult.cultac.network.codec.ModelRegistryNamesState;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.network.packet.WorldPackets;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.netty.CultDecoder;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockChangedAck;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundContainerClose;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
    private static final int MINECART = 100;

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

    @Test
    void aUseAtARotationTheTickDoesNotSendIsTheOnlyPacketOfItsTickThatDoesNotReachTheServer() throws Exception {
        try (var f = new JavaTickFixture()) {
            var t = new Ticks(f);
            t.start();

            t.carry(1);
            t.use(1, YAW, 90);
            t.carry(2);
            t.tickEnd();
            assertEquals(List.of("set_carried_item", "set_carried_item", "client_tick_end"), t.forwarded());
            assertEquals(1, t.check.getViolations());
        }
    }

    @Test
    void aTickEndTheTimerRejectsStillJudgesTheUsesOfItsTick() throws Exception {
        try (var f = new JavaTickFixture()) {
            var t = new Ticks(f);
            t.start();

            t.use(1, YAW, PITCH);
            t.rejectedTickEnd();
            assertEquals(List.of("use_item 1"), t.forwarded());
            assertEquals(0, t.check.getViolations());

            t.use(2, YAW, 90);
            t.rejectedTickEnd();
            assertEquals(List.of(), t.forwarded());
            assertEquals(1, t.check.getViolations());
        }
    }

    /** Minecraft#handleKeybinds, which sends use_item, runs only without a screen. */
    @Test
    void aUseUnderTheLevelLoadingScreenNeverReachesTheServer() throws Exception {
        try (var f = new JavaTickFixture()) {
            var t = new Ticks(f);
            t.start();
            t.respawn();
            t.forwarded();

            t.use(1, YAW, 90);
            t.tickEnd();
            assertEquals(List.of("client_tick_end"), t.forwarded());
            assertEquals(0, t.check.getViolations());
        }
    }

    /** ClientPacketListener#handleContainerClose sets no screen in place of the LevelLoadingScreen. */
    @Test
    void aUseAfterAServerScreenReplacedTheLevelLoadingScreenReachesTheServer() throws Exception {
        try (var f = new JavaTickFixture()) {
            var t = new Ticks(f);
            t.start();
            t.respawn();
            f.teleport(f.player.sendTrailingTransaction(), START, new RelativeFlag(0), 0, 0, YAW, PITCH);
            t.containerClose();
            t.forwarded();

            t.use(1, TURNED_YAW, PITCH);
            t.tickEnd();
            assertEquals(List.of("use_item 1", "client_tick_end"), t.forwarded());
            assertEquals(0, t.check.getViolations());
        }
    }

    /** A respawned LocalPlayer starts yRotLast and xRotLast at zero, which its first sendPosition compares against. */
    @Test
    void aUseOfARespawnedPlayerIsJudgedByTheLookItsNewLocalPlayerSends() throws Exception {
        try (var f = new JavaTickFixture()) {
            var t = new Ticks(f);
            t.start();
            t.respawn();
            f.fixture.channel.writeInbound(f.fixture.id(ServerboundPackets.PLAYER_LOADED, "player_loaded"));
            t.forwarded();

            t.use(1, YAW, 90);
            t.tickEnd();
            assertEquals(List.of("client_tick_end"), t.forwarded());
            assertEquals(1, t.check.getViolations());

            t.use(2, YAW, PITCH);
            t.rot(YAW);
            t.tickEnd();
            assertEquals(List.of("use_item 2", "move_player", "client_tick_end"), t.forwarded());
            assertEquals(1, t.check.getViolations());
        }
    }

    @Test
    void aMinecartRidersUseIsJudgedByThePitchItsTickSends() throws Exception {
        try (var f = new JavaTickFixture()) {
            var t = new Ticks(f);
            t.start();
            f.player.compensatedWorld.applyClientFeatures(
                    new WorldPackets.EnabledFeatures(Set.of("minecraft:vanilla", "minecraft:minecart_improvements")));
            f.player.packetEntityReplication.addEntity(
                    MINECART, EntityTypes.defaults().byKey("minecraft:minecart").id(), START, 0, 0, 0, -1);
            f.setPassengers(MINECART, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            t.forwarded();

            t.use(1, YAW, PITCH);
            t.rot(TURNED_YAW);
            t.tickEnd();
            assertEquals(List.of("use_item 1", "move_player", "client_tick_end"), t.forwarded());
            assertEquals(0, t.check.getViolations());

            t.use(2, TURNED_YAW, 90);
            t.rot(TURNED_YAW);
            t.tickEnd();
            assertEquals(List.of("move_player", "client_tick_end"), t.forwarded());
            assertEquals(1, t.check.getViolations());
        }
    }

    @Test
    void theHeldPacketsReachTheServerBeforeTheConfigurationAcknowledgement() throws Exception {
        try (var f = new JavaTickFixture()) {
            var t = new Ticks(f);
            t.start();

            t.use(1, YAW, PITCH);
            f.fixture.channel.writeInbound(f.fixture.id(
                    ServerboundPackets.CONFIGURATION_ACKNOWLEDGED, "configuration_acknowledged"));
            assertEquals("use_item 1", t.forwarded().getFirst());
            assertFalse(f.fixture.connection.forwardHold().holds());
        }
    }

    @Test
    void removingTheDecoderForwardsTheHeldPacketsInOrder() throws Exception {
        try (var f = new JavaTickFixture()) {
            var t = new Ticks(f);
            t.start();

            t.use(1, YAW, PITCH);
            t.carry(1);
            CultDecoder.uninstall(f.fixture.channel);
            assertEquals(List.of("use_item 1", "set_carried_item"), t.forwarded());
        }
    }

    @Test
    void closingTheChannelReleasesTheHeldPackets() throws Exception {
        try (var f = new JavaTickFixture()) {
            var t = new Ticks(f);
            t.start();

            ByteBuf use = t.use(1, YAW, PITCH);
            assertEquals(1, use.refCnt(), "the use is not held");
            f.fixture.channel.close();
            assertEquals(0, use.refCnt());
        }
    }

    @Test
    void aUseFollowedByMorePacketsThanAClientTickHoldsIsDroppedAndTheRestReachTheServer() throws Exception {
        try (var f = new JavaTickFixture()) {
            var t = new Ticks(f);
            t.start();

            t.use(1, YAW, PITCH);
            for (int i = 0; i < ForwardHold.MAX_PACKETS; i++) t.carry(i % 9);
            List<String> forwarded = t.forwarded();
            assertEquals(ForwardHold.MAX_PACKETS, forwarded.size());
            assertTrue(forwarded.stream().allMatch("set_carried_item"::equals), "the use reached the server");
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

        /** The server's respawn into another dimension reaches the client, which has not loaded the new level yet. */
        void respawn() {
            var spawnInfo = new PlayerSpawnInfo(
                    ModelRegistryNamesState.defaults().id("minecraft:dimension_type", "minecraft:the_end"),
                    f.player.compensatedWorld.getLastClientboundDimension().dimension(),
                    GameMode.SURVIVAL);
            var packet = new ClientboundRespawn(spawnInfo, (byte) 0);
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.RESPAWN, packet, false);
            new PacketPlayerRespawn().onRespawn(event, f.player, packet);
            event.getTasksAfterSend().forEach(Runnable::run);
            f.pongThrough(f.player.lastTransactionSent.get());
            assertFalse(f.player.packetStateData.playerLoadedIntoLevel, "the respawn did not reach the client");
        }

        /** The server's container_close reaches the client. */
        void containerClose() {
            var packet = new ClientboundContainerClose(0);
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.CONTAINER_CLOSE, packet, false);
            new PacketPlayerWindow().onClientboundContainerClose(event, f.player, packet);
            event.getTasksAfterSend().forEach(Runnable::run);
            f.pongThrough(f.player.lastTransactionSent.get());
        }

        void rot(float yaw) {
            receive(
                    ServerboundPackets.MOVE_PLAYER,
                    new ServerboundMovePlayer(0, 0, 0, yaw, PITCH, true, false, false, true));
        }

        void tickEnd() {
            f.fixture.channel.writeInbound(f.fixture.id(ServerboundPackets.CLIENT_TICK_END, "client_tick_end"));
        }

        /** A tick end that TimerCheck rejects: the client's ticks run ahead of the wall clock. */
        void rejectedTickEnd() throws Exception {
            TimerCheck timer = f.player.checkManager.getCheck(TimerCheck.class);
            timer.setEnabled(true);
            JavaTickFixture.setField(
                    AbstractTimerCheck.class, timer, "timerBalanceRealTime", System.nanoTime() + 10_000_000_000L);
            tickEnd();
        }

        ByteBuf use(int sequence, float yaw, float pitch) {
            ByteBuf frame = f.fixture.id(ServerboundPackets.USE_ITEM, "use_item");
            Wire.writeVarInt(frame, Hand.MAIN_HAND.ordinal());
            Wire.writeVarInt(frame, sequence);
            frame.writeFloat(yaw).writeFloat(pitch);
            f.fixture.channel.writeInbound(frame);
            return frame;
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
