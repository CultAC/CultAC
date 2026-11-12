package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.simulation.collision.BedrockDepenetrationLimit;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.SimulationContext.HardCollidingEntityCollision;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityShulker;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import java.util.ArrayList;
import java.util.List;

/**
 * The solid actors a Bedrock move collides with after the blocks: every actor with the collidable flag whose box
 * meets the mover's box grown by {@link #REACH}, at the box its last lerp step left it, with the depenetration limit
 * it gives the move. There is no push between actors. Tracked entities come first in tracker order, then the actors
 * only the client holds in spawn order; the move meets them in the reverse of that order. A boat or happy ghast in
 * the middle of its lerp lends a move it ends what the player's {@link BedrockSolidActorBuffer} has for the tick,
 * since Cult's box for it can stand a step off the client's.
 */
public final class BedrockSolidActors {
    private static final double REACH = 2.0D;

    private BedrockSolidActors() {}

    public static List<HardCollidingEntityCollision> collect(CultPlayer player, SimulationContext context) {
        PacketEntity vehicle = context.getVehicle();
        // Only a mob collides with solid actors: the player on foot, or the mob it rides, but not a boat.
        if (vehicle != null && !vehicle.isLivingEntity()) return List.of();
        SimpleCollisionBox reach = context.getFromMaximumExtent().expand(REACH);
        List<HardCollidingEntityCollision> solid = new ArrayList<>();
        CompensatedEntities entities = context.getEntities();
        PacketEntity rootOfSelf = entities.rootOfSelf();
        SimpleCollisionBox box = new SimpleCollisionBox();
        float buffer = context.getBedrockInput() == null
                ? 0.0F
                : player.bedrockState.solidActorBuffer.available(
                        context.getBedrockInput().getClientTick());
        for (PacketEntity entity : entities.entityMap.values()) {
            if (!entity.bedrockCollidable || entity.isDead) continue;
            if (entities.sharesRootVehicleWithSelf(entity, rootOfSelf)) continue;
            if (!entity.bedrockShownBox(player, box).isIntersected(reach)) continue;
            solid.add(new HardCollidingEntityCollision(
                    box.copy(), depenetrationLimit(entity), buffers(entity) ? buffer : 0.0F));
        }
        player.bedrockState.clientActors.colliders(
                reach,
                actor -> solid.add(
                        new HardCollidingEntityCollision(actor, BedrockDepenetrationLimit.SOLID_ACTOR, 0.0F)));
        return solid;
    }

    /** Whether the actor is a boat or happy ghast in the middle of a lerp, or one whose steps Cult does not know. */
    private static boolean buffers(PacketEntity entity) {
        if (!entity.isBoat() && !entity.isHappyGhast()) return false;
        if (!entity.clientPhysicalPositionExact || entity.oldPacketLocation != null) return true;
        return entity.newPacketLocation != null && entity.newPacketLocation.bedrockStepsLeft() != 0;
    }

    /** How deep a move that collects {@code entity} may overlap a shape and still leave it. */
    private static BedrockDepenetrationLimit depenetrationLimit(PacketEntity entity) {
        if (entity instanceof PacketEntityShulker shulker) return shulker.bedrockDepenetrationLimit();
        return entity.isHappyGhast() ? BedrockDepenetrationLimit.HAPPY_GHAST : BedrockDepenetrationLimit.SOLID_ACTOR;
    }
}
