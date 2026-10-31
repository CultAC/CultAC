package ac.cult.cultac.utils.data.packetentity;

import ac.cult.cultac.checks.impl.chat.ChatD;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementEngine;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementMath;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.MainHand;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.ReachUtils;

/** FireworkRocketEntity's attachment cache; changing metadata does not replace an already resolved target. */
public final class PacketEntityFirework extends PacketEntity {
    public int attachment = -1;
    private PacketEntity attachedEntity;

    public PacketEntityFirework(CultPlayer player, int id, int type, Vec3 position) {
        super(player, id, type, position.x, position.y, position.z);
    }

    public boolean boostsPlayer(CultPlayer player) {
        if (attachment < 0) return false;
        PacketEntity target =
                attachedEntity == null ? player.compensatedEntities.getEntity(attachment) : attachedEntity;
        return target == player.compensatedEntities.getSelf();
    }

    @Override
    public void onMovement(CultPlayer player) {
        super.onMovement(player);
        if (attachment < 0) return;
        if (attachedEntity == null) {
            PacketEntity target = player.compensatedEntities.getEntity(attachment);
            if (target != null && target.isLivingEntity()) attachedEntity = target;
        }
        if (attachedEntity != player.compensatedEntities.getSelf()) return;
        // The attached rocket follows the player rather than interpolating its packet position. This also
        // drives the ordinary ticking-chunk callbacks used by the next traversal of ClientLevel#tickEntities.
        var prediction = player.checkManager.getSimulationProcessor().getLastPrediction();
        Vec3 position = new Vec3(player.x, player.y, player.z);
        boolean gliding = player.isGliding;
        float yaw = player.xRot;
        if (prediction != null) {
            var context = prediction.getSimulationContext();
            boolean before = tickingOrder(player) < player.compensatedEntities.clientTickOrder(attachedEntity);
            var pass = JavaMovementEngine.passAfterTravel(prediction);
            position = before
                    ? context.getStart()
                    : pass == null ? context.getEnd() : pass.travel().position();
            gliding = before && player.packetStateData.glidingBeforeClientAction != null
                    ? player.packetStateData.glidingBeforeClientAction
                    : context.isGliding() && (before || !context.stopsFallFlyingAfterTravel());
            yaw = context.getXRot();
        }
        if (gliding) {
            var information = player.checkManager.getCheck(ChatD.class).information();
            boolean right = information == null || information.mainHand() == MainHand.RIGHT;
            var inventory = player.getInventory();
            if (inventory.getOffHand().getItem().key().equals("minecraft:firework_rocket")
                    && !inventory.getHeldItem().getItem().key().equals("minecraft:firework_rocket")) right = !right;
            float handYaw = yaw + (right ? 80 : -80);
            var offset = prediction == null
                    ? ReachUtils.getLook(player, handYaw, 0)
                    : JavaMovementMath.calculate(
                            player, prediction.getInitialStartingVel(), () -> ReachUtils.getLook(player, handYaw, 0));
            position = position.add(offset.getX() * 0.5D, offset.getY() * 0.5D, offset.getZ() * 0.5D);
        }
        setPositionRaw(GetBoundingBox.getPacketEntityBoundingBox(player, position.x, position.y, position.z, this));
    }
}
