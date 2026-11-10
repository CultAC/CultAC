package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.checks.impl.combat.BedrockEntityReach;
import ac.cult.cultac.checks.impl.combat.BedrockPlayerReach;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.Vec3;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.geysermc.geyser.session.GeyserSession;

final class GeyserEntityInteractions {
    private GeyserEntityInteractions() {}

    // The client's pick hit point is a float of its local coordinates; this many float steps cover its rounding.
    private static final float HIT_STEPS = 4.0F;

    /** Hands an attack to the reach checks as it arrives, before Geyser translates it; true when it must be dropped. */
    static boolean dropsAttack(GeyserSession session, CultPlayer player, InventoryTransactionPacket packet) {
        if (packet.getTransactionType() != InventoryTransactionType.ITEM_USE_ON_ENTITY || packet.getActionType() != 1)
            return false;
        var entity = session.getEntityCache().getEntityByGeyserId(packet.getRuntimeEntityId());
        if (entity == null) return false;
        Vector3f click = packet.getClickPosition();
        Vec3 hit = toWorld(player, click);
        double precision = hit == null
                ? 0
                : HIT_STEPS
                        * Math.max(Math.ulp(click.getX()), Math.max(Math.ulp(click.getY()), Math.ulp(click.getZ())));
        // Only shown by /cult debug reach; the checks never judge from it.
        Vec3 eye = toWorld(player, packet.getPlayerPosition());
        int id = entity.getEntityId();
        boolean drop =
                player.checkManager.getListener(BedrockPlayerReach.class).onBedrockAttack(id, hit, precision, eye);
        drop |= player.checkManager.getListener(BedrockEntityReach.class).onBedrockAttack(id, hit, precision, eye);
        if (drop) player.onPacketCancel();
        return drop;
    }

    // A point the client reports in its GFP-local frame, in world coordinates; null when it sent none.
    private static Vec3 toWorld(CultPlayer player, Vector3f local) {
        if (local == null
                || !Float.isFinite(local.getX())
                || !Float.isFinite(local.getY())
                || !Float.isFinite(local.getZ())) return null;
        return player.getSetbackTeleportUtil()
                .bedrockTeleports()
                .getActiveBedrockCoordinateFrame()
                .toWorld(new Vec3(local.getX(), local.getY(), local.getZ()));
    }

    static void observe(GeyserSession session, CultPlayer player, InventoryTransactionPacket packet) {
        var entity = session.getEntityCache().getEntityByGeyserId(packet.getRuntimeEntityId());
        if (entity != null && packet.getActionType() == 1) player.actionManager.attack(entity.getEntityId());
    }
}
