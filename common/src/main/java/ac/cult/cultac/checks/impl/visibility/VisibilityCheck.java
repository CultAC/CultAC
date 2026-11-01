package ac.cult.cultac.checks.impl.visibility;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.combat.EntityReach;
import ac.cult.cultac.checks.impl.combat.PlayerReach;
import ac.cult.cultac.checks.impl.combat.ReachCheck;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import ac.cult.cultac.utils.visibility.ClientVisibility.Sight;
import ac.cult.cultac.utils.visibility.ClientVisibilityProcessor;

abstract class VisibilityCheck extends Check implements CheckListener {
    protected final ClientVisibilityProcessor visibility;

    protected VisibilityCheck(CultPlayer player, ClientVisibilityProcessor visibility) {
        super(player);
        this.visibility = visibility;
    }

    protected void reject(PacketReceiveEvent<?> event, String detail) {
        if (flag(detail) && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }

    protected static String type(PacketEntity entity) {
        return "type=" + EntityTypeUtil.getKey(entity.type);
    }

    /** The block the client's selection judged, where it is and the face it reported. */
    protected String block(BlockPos position, Direction face) {
        int state = player.compensatedWorld.withSelectionSnapshot(
                () -> player.compensatedWorld.getBlockStateIdAt(position));
        return "block=" + DataTables.defaults().registry().block(state).key() + ", pos=" + position.x() + ", "
                + position.y() + ", " + position.z() + ", face=" + face;
    }

    /**
     * Whether LocalPlayer#pick cannot have selected the attacked target. Beyond every pick range, the reach check
     * that owns the target judges the distance when it measures the attack beyond reach from every eye; while it
     * measures the attack within reach, the sight of its box decides.
     */
    protected boolean attackHidden(PacketEntity target) {
        Sight sight = visibility.entitySight(target);
        if (sight != Sight.OUT_OF_RANGE) return sight == Sight.BLOCKED;
        Class<? extends ReachCheck> owner = target.type == EntityTypeIds.PLAYER ? PlayerReach.class : EntityReach.class;
        ReachCheck reach = player.checkManager.getCheck(owner);
        if (reach == null || !reach.enforcesReach(target)) return true;
        if (!reach.measuresBeyondReach(target)) return visibility.entityHidden(target, reach.measuredBox(target));
        // A cancel this check can make stays here while reach may only flag.
        return shouldModifyPackets() && !reach.shouldModifyPackets();
    }
}
