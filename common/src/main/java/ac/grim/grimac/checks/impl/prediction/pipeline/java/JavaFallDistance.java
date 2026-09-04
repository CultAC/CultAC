package ac.grim.grimac.checks.impl.prediction.pipeline.java;

import ac.grim.grimac.checks.impl.prediction.PredVector;
import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.impl.prediction.SimulationContext;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.nmsutil.Collisions;
import ac.grim.grimac.utils.nmsutil.JavaCollisionState;
import ac.grim.grimac.utils.nmsutil.NativeBlockCollisionHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Mirrors Java Entity#baseTick/#move and LivingEntity#aiStep, in client tick order. */
public final class JavaFallDistance {
    private JavaFallDistance() {}

    private static PacketEntity actor(GrimPlayer player, SimulationContext context) {
        return context.getVehicle() == null ? player.compensatedEntities.getSelf() : context.getVehicle();
    }

    public static JavaCollisionState beforeMove(GrimPlayer player, SimulationContext context, PredVector velocity) {
        PacketEntity actor = actor(player, context);
        double distance = context.getProfileCarry() instanceof JavaPredictionCarry carry && carry.actor() == actor
                ? carry.fallDistance() : 0.0;
        var world = context.getWorldData();
        boolean water = world.getInWater().determinePessimistically();
        boolean lava = world.getInLava().determinePessimistically();
        // handleOnClimbable runs in air travel, not water/lava travel. Use the START block.
        Vec3 start = context.getStart();
        boolean climb = actor.isLivingEntity() && !water && !lava && !context.usesFallFlyingMovement() && Collisions.onClimbable(player, start.x, start.y, start.z);
        boolean reset = water || climb || context.getVehicle() == null && player.isFlying
                || actor.isLivingEntity() && (player.compensatedEntities.getSlowFallingAmplifier() != null
                || player.compensatedEntities.getLevitationAmplifier() != null);
        distance = beforeMove(distance, lava, reset, context.isGliding(), velocity.y);
        return JavaCollisionState.of(player, actor, distance);
    }

    static double beforeMove(double distance, boolean lava, boolean reset, boolean gliding, double velocityY) {
        if (lava) distance *= 0.5;
        // LivingEntity#updateFallFlying -> Entity#checkFallDistanceAccumulation is BEFORE travel.
        if (gliding && velocityY > -0.5 && distance > 1.0) distance = 1.0;
        return reset ? 0.0 : distance;
    }

    public record Moved(double fallDistance, Vec3 movement, boolean landed) {}

    public static Moved afterCollision(GrimPlayer player, PredictionResult result, Vec3 acceptedDiff) {
        SimulationContext context = result.getSimulationContext();
        PacketEntity actor = actor(player, context);
        double distance = beforeMove(player, context, result.getInitialStartingVel()).fallDistance();
        Vec3 movement = result.hasEffectiveFlags()
                ? result.getLegacyLikePredictionVector().multiply(context.getLastStuckSpeed()) : acceptedDiff;
        var down = result.getCollideAxisData().getYNeg();
        boolean landed = down != null && down.isLikelyCollide()
                && Math.abs(movement.y - down.getResult() * context.getLastStuckSpeed().y) <= 1.0E-7;
        if (distance != 0 && movement.lengthSqr() >= 1 && crossesResetBlock(player, context, movement)) distance = 0;
        boolean water = context.getWorldData().getInWater().determinePessimistically();
        Vec3 end = context.getStart().add(movement);
        // LivingEntity#checkFallDamage refreshes fluids BEFORE generic accumulation.
        if (actor.isLivingEntity() && !water && touchesWater(player, context, end)) {
            water = true;
            distance = 0;
        }
        boolean waterBelow = player.compensatedWorld.getFluidStateAt(BlockPos.containing(end).below()).is(net.minecraft.tags.FluidTags.WATER);
        distance = afterMoveForActor(distance, movement.y, water, landed, actor.isBoat(), actor.riding != null,
                waterBelow, actor.isStrider() && context.getWorldData().getInLava().determinePessimistically());
        return new Moved(distance, movement, landed);
    }

    static double afterMoveForActor(double distance, double dy, boolean water, boolean landed,
                                    boolean boat, boolean passenger, boolean waterBelow, boolean striderInLava) {
        if (striderInLava) return 0;
        if (boat) return passenger ? distance : afterMove(distance, dy, waterBelow, landed);
        return afterMove(distance, dy, water, landed);
    }

    private static boolean touchesWater(GrimPlayer player, SimulationContext context, Vec3 end) {
        var box = context.getToActualPose().copy().offset(end.subtract(context.getEnd())).expand(-0.001);
        for (int x = (int)Math.floor(box.minX); x < Math.ceil(box.maxX); x++)
            for (int y = (int)Math.floor(box.minY); y < Math.ceil(box.maxY); y++)
                for (int z = (int)Math.floor(box.minZ); z < Math.ceil(box.maxZ); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    var fluid = player.compensatedWorld.getFluidStateAt(pos);
                    if (fluid.is(net.minecraft.tags.FluidTags.WATER)
                            && pos.getY() + fluid.getHeight(player.compensatedWorld, pos) >= box.minY) return true;
                }
        return false;
    }

    public static JavaPredictionCarry commit(GrimPlayer player, PredictionResult result, Vec3 acceptedDiff) {
        SimulationContext context = result.getSimulationContext();
        double distance = beforeMove(player, context, result.getInitialStartingVel()).fallDistance();
        // Rejected packet endpoints must not manufacture a fall-distance reset.
        Vec3 movement = result.hasEffectiveFlags()
                ? result.getLegacyLikePredictionVector().multiply(context.getLastStuckSpeed()) : acceptedDiff;
        var world = context.getWorldData();
        boolean water = world.getInWater().determinePessimistically();
        var down = result.getCollideAxisData().getYNeg();
        boolean landed = down != null && down.isLikelyCollide()
                && Math.abs(movement.y - down.getResult()) <= 1.0E-7;
        // Entity#move's fall-damage-reset ray is after collision, before checkFallDamage.
        if (distance != 0.0 && movement.lengthSqr() >= 1.0 && crossesResetBlock(player, context, movement)) distance = 0.0;
        distance = afterMove(distance, movement.y, water, landed);
        // entityInside -> makeStuckInBlock and the bubble/honey callbacks occur after landing.
        if (world.getStuckSpeed().getStuckSpeedMultiplier() != null
                || world.getBubbleColumn().hasBubbleColumn()
                || world.getHoneySlide().determinePessimistically()) distance = 0.0;
        return new JavaPredictionCarry(actor(player, context), distance);
    }

    static double afterMove(double distance, double resolvedY, boolean water, boolean landed) {
        // 26.2 Entity#checkFallDamage promotes the FLOAT delta into its double accumulator.
        if (!water && resolvedY < 0.0) distance -= (float) resolvedY;
        return landed ? 0.0 : distance;
    }

    private static boolean crossesResetBlock(GrimPlayer player, SimulationContext context, Vec3 movement) {
        Vec3 from = context.getStart();
        Vec3 to = from.add(movement.normalize().scale(Math.min(movement.length(), 8.0)));
        ClipContext clip = new ClipContext(from, to, ClipContext.Block.FALLDAMAGE_RESETTING, ClipContext.Fluid.WATER,
                NativeBlockCollisionHelper.collisionContext(player, from.y)) {
            @Override
            public VoxelShape getBlockShape(BlockState state, BlockGetter world, BlockPos pos) {
                // ClipContext's player-specific portal branch also requires EntityCollisionContext.
                return state.is(BlockTags.FALL_DAMAGE_RESETTING)
                        || context.getVehicle() == null && (state.is(Blocks.END_PORTAL) || state.is(Blocks.END_GATEWAY))
                        ? Shapes.block() : Shapes.empty();
            }
        };
        return player.compensatedWorld.clip(clip).getType() != HitResult.Type.MISS;
    }
}
