package ac.grim.grimac.events.packets.listeners;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.protocol.player.User;
import ac.grim.grimac.network.protocol.util.reflection.Reflection;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.network.event.PacketSendEvent;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.world.level.GameType;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public class PlayerInfoListener {
    private static final Field PLAYER_INFO_ENTRIES = Reflection.getField(ClientboundPlayerInfoUpdatePacket.class, List.class, 0);

    @GrimPacketHandler
    public void onPlayerInfoUpdate(PacketSendEvent event, GrimPlayer receiver, ClientboundPlayerInfoUpdatePacket packet) {
        // Team entries use names, so retain profiles for UUID resolution.
        if (packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) {
            receiver.latencyUtils.addRealTimeTask(receiver.lastTransactionSent.get(), () -> {
                for (ClientboundPlayerInfoUpdatePacket.Entry entry : packet.entries()) {
                    GameProfile gameProfile = entry.profile();
                    receiver.compensatedEntities.profiles.put(entry.profileId(),
                            new User.Profile(entry.profileId(), gameProfile == null ? null : gameProfile.name()));
                }
            });
        }

        if (packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE)) {
            boolean onlyGameMode = packet.actions().size() == 1;
            int hideCount = 0;
            List<ClientboundPlayerInfoUpdatePacket.Entry> visibleEntries = new ArrayList<>(packet.entries().size());

            for (ClientboundPlayerInfoUpdatePacket.Entry entry : packet.entries()) {
                boolean hidden = GrimAPI.INSTANCE.getSpectateManager().shouldHidePlayer(receiver, entry.profileId());
                if (hidden && entry.gameMode() == GameType.SPECTATOR) {
                    hideCount++;
                    continue;
                }
                visibleEntries.add(entry);
            }

            if (hideCount > 0) {
                if (onlyGameMode) {
                    if (visibleEntries.isEmpty()) {
                        event.setCancelled(true);
                        return;
                    }

                    ClientboundPlayerInfoUpdatePacket replacement = rebuildPacket(
                            EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE),
                            visibleEntries
                    );
                    if (replacement != null) {
                        event.setNmsPacket(replacement);
                    }
                    return;
                }

                EnumSet<ClientboundPlayerInfoUpdatePacket.Action> strippedActions = EnumSet.copyOf(packet.actions());
                strippedActions.remove(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE);

                ClientboundPlayerInfoUpdatePacket strippedPacket = rebuildPacket(strippedActions, packet.entries());
                if (strippedPacket == null) {
                    return;
                }
                event.setNmsPacket(strippedPacket);

                if (!visibleEntries.isEmpty()) {
                    ClientboundPlayerInfoUpdatePacket gameModeUpdate = rebuildPacket(
                            EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE),
                            visibleEntries
                    );
                    if (gameModeUpdate != null) {
                        event.getTasksAfterSend().add(() ->
                                GrimAPI.INSTANCE.getNetworkManager().sendPacket(event.getUser().getChannel(), gameModeUpdate, false));
                    }
                }
            }
        }
    }

    @GrimPacketHandler
    public void onPlayerInfoRemove(PacketSendEvent event, GrimPlayer receiver, ClientboundPlayerInfoRemovePacket packet) {
        receiver.latencyUtils.addRealTimeTask(receiver.lastTransactionSent.get(),
                () -> packet.profileIds().forEach(receiver.compensatedEntities.profiles::remove));
    }

    private static ClientboundPlayerInfoUpdatePacket rebuildPacket(
            EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions,
            List<ClientboundPlayerInfoUpdatePacket.Entry> entries
    ) {
        if (entries.isEmpty()) {
            return null;
        }

        java.util.Collection<net.minecraft.server.level.ServerPlayer> noPlayers = List.of();
        ClientboundPlayerInfoUpdatePacket packet = new ClientboundPlayerInfoUpdatePacket(EnumSet.copyOf(actions), noPlayers);
        try {
            PLAYER_INFO_ENTRIES.set(packet, List.copyOf(entries));
            return packet;
        } catch (IllegalAccessException ignored) {
            return null;
        }
    }
}
