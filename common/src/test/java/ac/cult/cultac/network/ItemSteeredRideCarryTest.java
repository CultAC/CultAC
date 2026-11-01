package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.EquipmentSlot;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The velocity states the engine carries for a saddled pig, steered by a carrot on a stick, that reports no move. */
class ItemSteeredRideCarryTest {
    private static final int PIG = 100;
    private static final int TICKS = 200;
    private static final int MAX_CARRIED_STATES = 256;

    @Test
    void aZeroMoveRideWithTheItemHeldCarriesFewVelocityStates() {
        assertCarriesFewStates(true);
    }

    @Test
    void aZeroMoveRideWithTheItemInTheHotbarCarriesFewVelocityStates() {
        assertCarriesFewStates(false);
    }

    private static void assertCarriesFewStates(boolean held) {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            try (var f = new JavaTickFixture()) {
                int water = BlockIds.WATER.defaultState();
                for (int x = -8; x <= 8; x++) {
                    for (int z = -8; z <= 8; z++) {
                        f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y, z), water);
                    }
                }
                for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
                Vec3 at = new Vec3(0.5, FLOOR_Y, 0.5);
                f.place(at.add(0, 1, 0), Vec3.ZERO);
                send(
                        f,
                        ClientboundPackets.ADD_ENTITY,
                        new ClientboundAddEntity(PIG, "minecraft:pig", new Vec3d(at.x, at.y, at.z), 0, 0, 0));
                f.pongThrough(f.player.lastTransactionSent.get());
                f.setPassengers(PIG, List.of(f.player.entityID));
                f.pongThrough(f.player.lastTransactionSent.get());
                f.player.xRot = 37.0F;
                for (int tick = 0; tick < 20; tick++) f.rideTick();
                var inventory = f.player.getInventory().inventory;
                var item = ItemUtil.modelItems().stack("minecraft:carrot_on_a_stick", 1);
                if (held) {
                    inventory.setHeldItem(item);
                } else {
                    inventory
                            .getInventoryStorage()
                            .setItem(Inventory.HOTBAR_OFFSET + (inventory.selected + 4) % 9, item);
                }
                var saddle = ItemUtil.modelItems().stack("minecraft:saddle", 1);
                send(
                        f,
                        type(InventoryPackets.Equipment.class),
                        new InventoryPackets.Equipment(
                                PIG, List.of(new InventoryPackets.EquipmentEntry(EquipmentSlot.SADDLE, saddle))));
                f.pongThrough(f.player.lastTransactionSent.get());

                for (int tick = 0; tick < TICKS; tick++) {
                    f.player.xRot = 37.0F;
                    f.resetTimerBalances();
                    f.rideTick(at, true);
                    f.pongThrough(f.player.lastTransactionSent.get());
                    var carry = assertInstanceOf(
                            JavaPredictionCarry.class,
                            f.player.checkManager.getSimulationProcessor().getCurrentPredictionCarry());
                    int states = carry.states().size();
                    assertTrue(states <= MAX_CARRIED_STATES, "tick " + tick + " carries " + states + " states");
                }
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> PacketType<T> type(Class<T> record) {
        return (PacketType<T>) TestProtocolRuntime.catalog().stream()
                .filter(type -> type.recordClass() == record)
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static <T> void send(JavaTickFixture f, PacketType<T> type, T packet) {
        var event = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, type, packet, false);
        var replication = f.player.packetEntityReplication;
        Object raw = packet;
        if (raw instanceof ClientboundAddEntity add) {
            replication.onAddEntity((PacketSendEvent<ClientboundAddEntity>) (PacketSendEvent<?>) event, f.player, add);
        } else {
            replication.onSetEquipment(
                    (PacketSendEvent<InventoryPackets.Equipment>) (PacketSendEvent<?>) event,
                    f.player,
                    (InventoryPackets.Equipment) raw);
        }
        event.tasksAfter().forEach(Runnable::run);
    }
}
