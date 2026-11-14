package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRemoveMobEffect;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundUpdateMobEffect;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Player#tryToStartFallFlying refuses a glide under levitation, as a passenger or in water. Each state counts once the
 * client has answered the ping that trails its packet; before that the client may not have it yet.
 */
class GlideStartClientStateTest {
    private static final Vec3 FROM = new Vec3(8.5, JavaTickFixture.FLOOR_Y + 40, 8.5);
    private static final Vec3 CARRIED = new Vec3(0.5, -0.1, 0.8);
    private static final int VEHICLE = 500;

    @Test
    void anAirborneGlideStartIsAccepted() throws Exception {
        try (var f = airborne()) {
            assertAccepted(f);
        }
    }

    @Test
    void aGlideStartUnderAppliedLevitationIsRejected() throws Exception {
        try (var f = airborne()) {
            levitation(f, true);
            f.pongThrough(f.player.lastTransactionSent.get());
            assertRejected(f);
        }
    }

    @Test
    void aGlideStartTheClientMadeBeforeHandlingTheLevitationIsAccepted() throws Exception {
        try (var f = airborne()) {
            levitation(f, true);
            assertAccepted(f);
        }
    }

    @Test
    void aGlideStartOnceTheLevitationRemovalIsAppliedIsAccepted() throws Exception {
        try (var f = airborne()) {
            levitation(f, true);
            f.pongThrough(f.player.lastTransactionSent.get());
            levitation(f, false);
            f.pongThrough(f.player.lastTransactionSent.get());
            assertAccepted(f);
        }
    }

    @Test
    void aGlideStartAsAnAppliedPassengerIsRejected() throws Exception {
        try (var f = airborne()) {
            f.player.compensatedEntities.addEntity(VEHICLE, EntityTypeIds.ARMOR_STAND, FROM, 0, 0, 0);
            passengers(f, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            assertRejected(f);
        }
    }

    @Test
    void aGlideStartTheClientMadeBeforeHandlingTheMountIsAccepted() throws Exception {
        try (var f = airborne()) {
            f.player.compensatedEntities.addEntity(VEHICLE, EntityTypeIds.ARMOR_STAND, FROM, 0, 0, 0);
            passengers(f, List.of(f.player.entityID));
            assertAccepted(f);
        }
    }

    @Test
    void aGlideStartOnceTheDismountIsAppliedIsAccepted() throws Exception {
        try (var f = airborne()) {
            f.player.compensatedEntities.addEntity(VEHICLE, EntityTypeIds.ARMOR_STAND, FROM, 0, 0, 0);
            passengers(f, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            passengers(f, List.of());
            f.pongThrough(f.player.lastTransactionSent.get());
            assertAccepted(f);
        }
    }

    @Test
    void aGlideStartSubmergedIsRejected() throws Exception {
        try (var f = airborne()) {
            for (int x = 6; x <= 11; x++) {
                for (int y = JavaTickFixture.FLOOR_Y + 36; y <= JavaTickFixture.FLOOR_Y + 44; y++) {
                    for (int z = 6; z <= 11; z++) {
                        f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), BlockIds.WATER.defaultState());
                    }
                }
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            f.tick(FROM.add(0, -0.02, 0), false);
            assertRejected(f);
        }
    }

    private static JavaTickFixture airborne() throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.player
                .getInventory()
                .inventory
                .getInventoryStorage()
                .setItem(Inventory.SLOT_CHESTPLATE, OfflineCultTestBootstrap.item("minecraft:elytra"));
        f.place(FROM, CARRIED);
        return f;
    }

    private static void levitation(JavaTickFixture f, boolean gain) {
        if (gain) {
            var packet = new ClientboundUpdateMobEffect(f.player.entityID, "minecraft:levitation", 0);
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.UPDATE_MOB_EFFECT, packet, false);
            f.player.packetEntityReplication.onUpdateMobEffect(event, f.player, packet);
            event.tasksAfter().forEach(Runnable::run);
        } else {
            var packet = new ClientboundRemoveMobEffect(f.player.entityID, "minecraft:levitation");
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.REMOVE_MOB_EFFECT, packet, false);
            f.player.packetEntityReplication.onRemoveMobEffect(event, f.player, packet);
            event.tasksAfter().forEach(Runnable::run);
        }
    }

    private static void passengers(JavaTickFixture f, List<Integer> passengers) {
        var packet = new ClientboundSetPassengers(VEHICLE, passengers);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.SET_PASSENGERS, packet, false);
        f.player.packetEntityReplication.onSetPassengers(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
    }

    private static PacketReceiveEvent<ServerboundPlayerCommand> startGlide(JavaTickFixture f) {
        var packet = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_FLYING_WITH_ELYTRA, 0);
        var event = f.event(ServerboundPackets.PLAYER_COMMAND, packet);
        new PacketEntityAction().onPlayerCommand(event, f.player, packet);
        return event;
    }

    private static void assertAccepted(JavaTickFixture f) {
        assertFalse(startGlide(f).isCancelled());
        assertTrue(f.player.isGliding);
    }

    private static void assertRejected(JavaTickFixture f) {
        assertTrue(startGlide(f).isCancelled());
        assertFalse(f.player.isGliding);
    }
}
