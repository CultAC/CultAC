package ac.cult.cultac.events.packets.listeners;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.CultWrite;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerInfoRemove;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerInfoUpdate;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerInfoUpdate.Action;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerInfoUpdate.Entry;
import ac.cult.cultac.protocol.value.GameMode;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class PlayerInfoListener {
    @CultPacketHandler
    public void onPlayerTeam(
            PacketSendEvent<ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPlayerTeam> event,
            CultPlayer player,
            ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPlayerTeam packet) {
        if (!player.isBedrockMovement())
            prove(event, player, () -> player.compensatedEntities.teamCollision.apply(packet));
    }

    @CultPacketHandler
    public void onPlayerInfoUpdate(
            PacketSendEvent<ClientboundPlayerInfoUpdate> event,
            CultPlayer receiver,
            ClientboundPlayerInfoUpdate packet) {
        if (!packet.actions().contains(Action.UPDATE_GAME_MODE)) {
            track(event, receiver, packet, List.of());
            return;
        }
        List<Entry> visibleEntries = new ArrayList<>(packet.entries().size());
        for (Entry entry : packet.entries()) {
            boolean hidden = CultAPI.INSTANCE.getSpectateManager().shouldHidePlayer(receiver, entry.profileId());
            if (!(hidden && entry.gameMode() == GameMode.SPECTATOR)) visibleEntries.add(entry);
        }
        if (visibleEntries.size() == packet.entries().size()) {
            track(event, receiver, packet, visibleEntries);
            return;
        }

        if (packet.actions().size() == 1) {
            if (visibleEntries.isEmpty()) event.setCancelled(true);
            else {
                event.replace(new ClientboundPlayerInfoUpdate(EnumSet.of(Action.UPDATE_GAME_MODE), visibleEntries));
                track(event, receiver, packet, visibleEntries);
            }
            return;
        }

        EnumSet<Action> strippedActions = EnumSet.copyOf(packet.actions());
        strippedActions.remove(Action.UPDATE_GAME_MODE);
        var stripped = new ClientboundPlayerInfoUpdate(strippedActions, packet.entries());
        event.replace(stripped);
        track(event, receiver, stripped, List.of());
        if (!visibleEntries.isEmpty()) {
            var gameModeUpdate = new ClientboundPlayerInfoUpdate(EnumSet.of(Action.UPDATE_GAME_MODE), visibleEntries);
            event.getTasksAfterSend().add(() -> event.getUser().write(new CultWrite(gameModeUpdate, false)));
        }
        // The generated UPDATE_GAME_MODE passes through this listener at its own actual wire position.
    }

    private static void track(
            PacketSendEvent<ClientboundPlayerInfoUpdate> event,
            CultPlayer receiver,
            ClientboundPlayerInfoUpdate packet,
            List<Entry> visibleEntries) {
        if (receiver.isBedrockMovement()) return;
        if (packet.entries().isEmpty()
                || !packet.actions().contains(Action.ADD_PLAYER)
                        && !packet.actions().contains(Action.UPDATE_GAME_MODE)) return;
        Set<UUID> visibleModes = visibleEntries.stream().map(Entry::profileId).collect(Collectors.toUnmodifiableSet());
        long sequence = receiver.nextImpulseSequence();
        // This is the outbound local-player lifetime, not hasClientLoaded: packet handlers can write motion
        // before LocalPlayer#tick is enabled. Capture it here, before later login/configuration writes.
        boolean localPlayerPresent = event.getPhase() == ac.cult.cultac.protocol.ConnectionPhase.PLAY
                && receiver.packetStateData.javaLocalPlayerPresent
                && receiver.getClientVersion().isNewerThanOrEquals(
                        ac.cult.cultac.network.protocol.ClientVersion.V_1_21_2);
        prove(event, receiver, () -> receiver.compensatedEntities.clientPlayerModes.update(
                packet, visibleModes, receiver.compensatedEntities.entityMap.values(), (id, mode) -> {
                    if (localPlayerPresent && receiver.playerUUID.equals(id) && mode == GameMode.SPECTATOR)
                        receiver.checkManager.getSimulationProcessor().applyPlayerInfoSpectatorReset(sequence);
                }));
    }

    /** Applies {@code task} once the client has handled the event's packet, at its flush proof. */
    private static void prove(PacketSendEvent<?> event, CultPlayer receiver, Runnable task) {
        int proof = receiver.appendFlushProofTransaction(event);
        if (proof > 0) receiver.latencyUtils.addRealTimeTask(proof, task);
        else receiver.latencyUtils.addRealTimeTaskNext(task);
    }

    @CultPacketHandler
    public void onPlayerInfoRemove(
            PacketSendEvent<ClientboundPlayerInfoRemove> event,
            CultPlayer receiver,
            ClientboundPlayerInfoRemove packet) {
        if (receiver.isBedrockMovement()) return;
        if (packet.profiles().isEmpty()) return;
        prove(event, receiver, () -> receiver.compensatedEntities.clientPlayerModes.remove(packet.profiles()));
    }
}
