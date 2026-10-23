package ac.cult.cultac.checks.impl.combat;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSpectatorAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundTeleportToEntity;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.grim.grimac.api.storage.verbose.Verbose;

@CheckData(
        name = "MultiInteractA",
        stableKey = "cult.multiinteract.multiple_targets",
        description = "Interacted with multiple entities in the same tick",
        experimental = true)
public class MultiInteractA extends Check implements CheckListener {
    private static final Verbose V =
            Verbose.of("lastEntity={sint}, entity={sint}, lastSneaking={bool}, sneaking={bool}");

    private int lastEntity;
    private boolean lastSneaking;
    private boolean sneakingKnown;
    private boolean hasInteracted = false;

    public MultiInteractA(final CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onInteractEntity(
            PacketReceiveEvent<ServerboundInteract> event, CultPlayer player, ServerboundInteract packet) {
        boolean carriesSneaking = packet.action() != InteractAction.ATTACK
                || player.getClientVersion().isOlderThan(ClientVersion.V_26_1);
        onInteract(event, packet.entityId(), carriesSneaking ? packet.sneaking() : lastSneaking, carriesSneaking);
    }

    @CultPacketHandler
    public void onSpectatorAction(
            PacketReceiveEvent<ServerboundSpectatorAction> event,
            CultPlayer player,
            ServerboundSpectatorAction packet) {
        // PacketEvents exposed an absent 26.2 spectator target as entity id 0.
        // Preserve that decoded-field contract instead of silently dropping the interaction.
        onInteract(event, packet.target().orElse(0), lastSneaking, false);
    }

    @CultPacketHandler
    public void onTeleportToEntity(
            PacketReceiveEvent<ServerboundTeleportToEntity> event,
            CultPlayer player,
            ServerboundTeleportToEntity packet) {
        if (player.user.getUUID().equals(packet.target())) {
            onInteract(event, player.entityID, lastSneaking, false);
        }
    }

    // isTickPacket: movement packets reset unless they answered a teleport
    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        if (!player.cameraEntity.isSelf() || !player.packetStateData.lastPacketWasTeleport) {
            hasInteracted = false;
        }
    }

    // isTickPacket: tick end resets for 1.21.2+ clients when no movement arrived this client tick
    @CultPacketHandler("serverbound.client_tick_end")
    public void onClientTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        if (!player.cameraEntity.isSelf() || !player.packetStateData.receivedMovementThisClientTick) {
            hasInteracted = false;
        }
    }

    /**
     * Minecraft#handleKeybinds sends every interaction of a tick with the same LocalPlayer#isShiftKeyDown. Its value is
     * compared between the tick's ServerboundInteractPacket packets, which below 26.1 include the attack.
     */
    private void onInteract(PacketReceiveEvent event, int entity, boolean sneaking, boolean carriesSneaking) {
        if (!player.cameraEntity.isSelf()) {
            hasInteracted = false;
        }
        if (!hasInteracted) {
            sneakingKnown = false;
        }

        boolean sneakingChanged = carriesSneaking && sneakingKnown && sneaking != lastSneaking;
        if (hasInteracted && (entity != lastEntity || sneakingChanged)) {
            if (flag(V.write(verbose())
                            .sint(lastEntity)
                            .sint(entity)
                            .bool(lastSneaking)
                            .bool(sneaking))
                    && shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }

        lastEntity = entity;
        if (carriesSneaking) {
            lastSneaking = sneaking;
            sneakingKnown = true;
        }
        hasInteracted = true;
    }
}
