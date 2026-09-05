package ac.cult.cultac.checks.impl.badpackets;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.NmsPacketUtil;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.nmsutil.EntityTypesCompat;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

@CheckData(name = "BadPacketsT", stableKey = "cult.badpackets.invalid_interact_vector", description = "Sent an entity interaction vector outside the target player's hitbox")
public class BadPacketsT extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("{f64:%.5f}/{f64:%.5f}/{f64:%.5f}");

    private final double maxHorizontalDisplacement;
    private final double minVerticalDisplacement;
    private final double maxVerticalDisplacement;

    public BadPacketsT(final CultPlayer player) {
        super(player);
        // pre-1.9 expands hitboxes by 0.1 on all sides; this is not lenience, it is vanilla.
        double expansion = player.getClientVersion().isOlderThan(ClientVersion.V_1_9) ? 0.1f : 0;
        maxHorizontalDisplacement = 0.3001 + expansion;
        minVerticalDisplacement = -0.0001 - expansion;
        maxVerticalDisplacement = 1.8001 + expansion;
    }

    @Override
    public boolean isApplicable() {
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_8);
    }

    @CultPacketHandler
    public void onInteract(final PacketReceiveEvent event, CultPlayer player, ServerboundInteractPacket packet) {
        if (!isApplicable()) return;

        final NmsPacketUtil.InteractData data = NmsPacketUtil.readInteract(packet);
        // Only INTERACT_AT actually has an interaction vector
        if (data.action() != NmsPacketUtil.InteractAction.INTERACT_AT) return;
        final Optional<Vec3> target = data.target();
        if (target.isEmpty()) return; // shouldn't ever happen, but whatever
        final Vec3 targetVector = target.get();

        if (!Double.isFinite(targetVector.x) || !Double.isFinite(targetVector.y) || !Double.isFinite(targetVector.z)) {
            flag(V.write(verbose()).f64(targetVector.x).f64(targetVector.y).f64(targetVector.z));
            return;
        }

        final PacketEntity packetEntity = player.compensatedEntities.getEntity(data.entityId());
        // Don't continue if the compensated entity hasn't been resolved
        if (packetEntity == null) {
            return;
        }

        // Make sure our target entity is actually a player (Player NPCs work too)
        if (packetEntity.getType() != EntityTypesCompat.PLAYER) {
            // We can't check for any entity that is not a player
            return;
        }


        final float scale = packetEntity.scale;
        if (targetVector.y > (minVerticalDisplacement * scale) && targetVector.y < (maxVerticalDisplacement * scale)
                && Math.abs(targetVector.x) < (maxHorizontalDisplacement * scale)
                && Math.abs(targetVector.z) < (maxHorizontalDisplacement * scale)) {
            return;
        }

        // Log the vector
        // We could pretty much ban the player at this point
        flag(V.write(verbose()).f64(targetVector.x).f64(targetVector.y).f64(targetVector.z));
    }
}
