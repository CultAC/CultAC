package ac.cult.cultac.checks.impl.combat;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.grim.grimac.api.storage.verbose.Verbose;

@CheckData(
        name = "MultiInteractB",
        stableKey = "cult.multiinteract.interact_at_position_changed",
        description = "Sent multiple entity interaction packets with different hit positions in one tick",
        experimental = true)
public class MultiInteractB extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("pos={f64}, {f64}, {f64}, lastPos={f64}, {f64}, {f64}");

    private Vec3d lastPos;
    private boolean hasInteracted;

    public MultiInteractB(final CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onInteractEntity(
            PacketReceiveEvent<ServerboundInteract> event, CultPlayer player, ServerboundInteract packet) {
        if (packet.action() != InteractAction.INTERACT_AT) return;

        Vec3d pos = packet.target().orElse(null);
        if (pos == null) return; // shouldn't ever happen, but whatever

        if (!player.cameraEntity.isSelf()) {
            hasInteracted = false;
        }

        if (hasInteracted && !pos.equals(lastPos)) {
            if (flag(V.write(verbose())
                            .f64(pos.x())
                            .f64(pos.y())
                            .f64(pos.z())
                            .f64(lastPos.x())
                            .f64(lastPos.y())
                            .f64(lastPos.z()))
                    && shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }

        lastPos = pos;
        hasInteracted = true;
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
}
