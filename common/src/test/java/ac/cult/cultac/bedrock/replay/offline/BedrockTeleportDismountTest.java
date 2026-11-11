package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.prediction.integration.BedrockVehicleControl;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import java.util.Arrays;
import org.junit.Test;

/**
 * Geyser pushes a ridden horse back from the world border with a teleport of the rider that names no vehicle. A capture
 * shows the client's next input on foot at the target (25.50061, 120.0, 24.68542) with no predicted vehicle, while the
 * server keeps the player seated until it sends the passengers without it.
 */
public final class BedrockTeleportDismountTest {
    private static final int HORSE = 71;
    private static final Vec3 HORSE_POSITION = new Vec3(25.50065F, 120.0F, 25.103378F);

    @Test
    public void teleportOfTheRiderEndsItsVehicleControl() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            PacketEntityHorse horse = ride(player);

            player.compensatedEntities.vehicles.applyClientTeleportDismount();

            assertNull(player.compensatedEntities.getSelf().getRiding());
            assertFalse(horse.passengers.contains(player.compensatedEntities.getSelf()));
            assertNull(BedrockVehicleControl.controlledVehicle(player));
            // An on-foot input is the player's own movement again, not a pending passenger transition.
            assertFalse(player.compensatedEntities.vehicles.hasPlayerPassengerState());
            assertNull(player.compensatedEntities.vehicles.serverPlayerVehicle);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void serverDismountAfterwardsChangesNothingAndALaterLinkSeatsThePlayerAgain() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            PacketEntityHorse horse = ride(player);
            player.compensatedEntities.vehicles.applyClientTeleportDismount();

            // The player is already on foot: the server's dismount is no passenger transition for it.
            int removed = send(player);
            assertFalse(player.compensatedEntities.vehicles.hasPendingServerDismount());
            assertFalse(player.compensatedEntities.vehicles.hasPlayerPassengerState());
            acknowledge(player, removed);
            assertNull(player.compensatedEntities.getSelf().getRiding());
            assertFalse(player.compensatedEntities.vehicles.hasPlayerPassengerState());

            acknowledge(player, send(player, player.entityID));
            assertSame(horse, player.compensatedEntities.getSelf().getRiding());
            assertSame(horse, BedrockVehicleControl.controlledVehicle(player));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void teleportOnFootLeavesAPendingMountAlone() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            addHorse(player);
            int mount = send(player, player.entityID);

            player.compensatedEntities.vehicles.applyClientTeleportDismount();

            assertEquals(Integer.valueOf(HORSE), player.compensatedEntities.vehicles.serverPlayerVehicle);
            acknowledge(player, mount);
            assertSame(player.compensatedEntities.getEntity(HORSE), BedrockVehicleControl.controlledVehicle(player));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    /** Sends the horse's passengers and returns the transaction that proves the client handled them. */
    private static int send(CultPlayer player, int... passengers) {
        var packet = new ClientboundSetPassengers(
                HORSE, Arrays.stream(passengers).boxed().toList());
        player.packetEntityReplication.onSetPassengers(
                new PacketSendEvent<>(
                        player.user, ConnectionPhase.PLAY, ClientboundPackets.SET_PASSENGERS, packet, false),
                player,
                packet);
        int transaction = player.lastTransactionSent.get() + 1;
        player.lastTransactionSent.set(transaction);
        return transaction;
    }

    private static void acknowledge(CultPlayer player, int transaction) {
        player.lastTransactionReceived.set(transaction);
        player.latencyUtils.handleNettySyncTransaction(transaction);
    }

    private static PacketEntityHorse addHorse(CultPlayer player) {
        player.bedrockState.offerAuthInputFrame(BedrockAuthInputFrame.builder(player.playerUUID)
                .protocolVersion(944)
                .build());
        player.compensatedEntities.addEntity(HORSE, EntityTypeIds.HORSE, HORSE_POSITION, 0.0F, 0.0F, 0);
        var horse = (PacketEntityHorse) player.compensatedEntities.getEntity(HORSE);
        horse.hasSaddle = true;
        return horse;
    }

    private static PacketEntityHorse ride(CultPlayer player) {
        PacketEntityHorse horse = addHorse(player);
        player.compensatedEntities.vehicles.setServerVehicle(
                HORSE, new int[] {player.entityID}, player.lastTransactionSent.get());
        player.compensatedEntities.vehicles.applyVehiclePassengers(HORSE, new int[] {player.entityID});
        assertSame(horse, BedrockVehicleControl.controlledVehicle(player));
        assertTrue(player.compensatedEntities.vehicles.hasPlayerPassengerState());
        return horse;
    }
}
