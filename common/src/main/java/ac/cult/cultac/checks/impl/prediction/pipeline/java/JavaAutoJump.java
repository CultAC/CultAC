package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BlockProperties;

/** Source-proven automatic input: LocalPlayer#move/updateAutoJump, then next tick's aiStep/makeJump. */
public final class JavaAutoJump extends CultProcessor implements PostPredictionListener {
    private boolean queued;
    private boolean jumpedThisTick;

    public JavaAutoJump(CultPlayer player) {
        super(player);
    }

    public void reset() {
        queued = jumpedThisTick = false;
    }

    public boolean jumpedThisTick() {
        return jumpedThisTick;
    }

    public boolean hasManualMovementInput() {
        var input = player.packetStateData.knownInput;
        return input.movingNoJump() || input.jump() && !jumpedThisTick;
    }

    @Override
    public void onPredictionComplete(PredictionComplete complete) {
        if (player.isBedrockMovement() || complete.isTeleport()) return;
        jumpedThisTick = queued;
        queued = !complete.isExempt()
                && !complete.getPredictionResult().hasEffectiveFlags()
                && schedulesJump(complete.getPredictionResult());
    }

    boolean schedulesJump(PredictionResult result) {
        if (!JavaFallDistance.landsOnClip(
                        result.getCollideAxisData().getYNeg(),
                        result.getTarget().y,
                        result.getSimulationContext().getLastStuckSpeed().y)
                || player.isSneaking
                || player.inVehicle()
                || BlockProperties.onHoneyBlock(
                        player,
                        result.getSimulationContext().getWorldData().getMainSupportingBlockPos(),
                        new Vec3(player.x, player.y, player.z))) return false;
        var keys = player.packetStateData.knownInput;
        Vec3 input = new Vec3(
                (keys.left() ? 1 : 0) - (keys.right() ? 1 : 0),
                0,
                (keys.forward() ? 1 : 0) - (keys.backward() ? 1 : 0));
        if (input.horizontalDistanceSqr() == 0) return false;
        float yaw = player.xRot * ((float) Math.PI / 180.0F);
        float sin = player.trigHandler.sin(yaw), cos = player.trigHandler.cos(yaw);
        float speed = (float) (player.compensatedEntities.getPlayerMovementSpeed()
                * (player.sprintState.modifier() ? 1.0D + (double) 0.3F : 1.0D));
        Vec3 begin = new Vec3(player.x, player.y, player.z);
        float dx = (float) result.getTarget().x, dz = (float) result.getTarget().z;
        Vec3 end = begin.add(dx, 0, dz), diff = new Vec3(dx, 0, dz);
        float distanceSq = (float) diff.lengthSqr();
        if (distanceSq <= 0.001F) {
            double length = Math.sqrt(input.horizontalDistanceSqr());
            if (length > 1) input = input.scale(1 / length);
            float ix = speed * (float) input.x, iz = speed * (float) input.z;
            diff = new Vec3(ix * cos - iz * sin, 0, iz * cos + ix * sin);
            distanceSq = (float) diff.horizontalDistanceSqr();
            if (distanceSq <= 0.001F) return false;
        }
        float inverse = org.joml.Math.invsqrt(distanceSq);
        Vec3 direction = diff.scale(inverse);
        float pitchCos = player.trigHandler.cos(player.yRot * ((float) Math.PI / 180.0F));
        if ((float) (player.trigHandler.sin(-yaw) * pitchCos * direction.x
                        + player.trigHandler.cos(-yaw) * pitchCos * direction.z)
                < -0.15F) return false;
        int ceilingX = (int) Math.floor(player.x),
                ceilingY = (int) Math.floor(player.boundingBox.maxY),
                ceilingZ = (int) Math.floor(player.z);
        if (!JavaAutoJumpGeometry.block(player, ceilingX, ceilingY, ceilingZ).isEmpty()
                || !JavaAutoJumpGeometry.block(player, ceilingX, ++ceilingY, ceilingZ)
                        .isEmpty()) return false;
        float jumpHeight = 1.2F;
        Integer amplifier = result.getSimulationContext().getJumpAmplifier();
        if (amplifier != null) jumpHeight += (amplifier + 1) * 0.75F;
        float lookAhead = Math.max(speed * 7.0F, 1.0F / inverse);
        float width = player.pose.width * player.getScale(), height = player.getBukkitHeight();
        Vec3 far = end.add(direction.scale(lookAhead));
        var query = new ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox(
                        begin.x, begin.y, begin.z, far.x, far.y + height, far.z)
                .sort()
                .inflate(width, 0, width);
        Vec3 offset = new Vec3(-direction.z, 0, direction.x).scale(width * 0.5F);
        Vec3 lowBegin = begin.add(0, 0.51F, 0), lowEnd = far.add(0, 0.51F, 0);
        for (var box : JavaAutoJumpGeometry.collisions(player, query)) {
            if (!JavaAutoJumpGeometry.intersects(box, lowBegin.subtract(offset), lowEnd.subtract(offset))
                    && !JavaAutoJumpGeometry.intersects(box, lowBegin.add(offset), lowEnd.add(offset))) continue;
            float obstacleHeight = (float) box.maxY;
            Vec3 center = box.getCenter();
            int x = (int) Math.floor(center.x), y = (int) Math.floor(center.y), z = (int) Math.floor(center.z);
            for (int steps = 1; steps < jumpHeight; steps++) {
                var above = JavaAutoJumpGeometry.block(player, x, y + steps, z);
                if (!above.isEmpty()) {
                    // VoxelShape.max returns a local coordinate before the client's float promotion.
                    double max = Double.NEGATIVE_INFINITY;
                    for (var part : above) max = Math.max(max, part.maxY - (y + steps));
                    obstacleHeight = (float) max + y + steps;
                    if (obstacleHeight - player.y > jumpHeight) return false;
                }
                if (steps > 1
                        && !JavaAutoJumpGeometry.block(player, ceilingX, ++ceilingY, ceilingZ)
                                .isEmpty()) return false;
            }
            float rise = (float) (obstacleHeight - player.y);
            return rise > 0.5F && rise <= jumpHeight;
        }
        return false;
    }
}
