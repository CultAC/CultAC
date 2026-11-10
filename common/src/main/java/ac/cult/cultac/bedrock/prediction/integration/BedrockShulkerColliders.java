package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.SimulationContext.HardCollidingEntityCollision;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityShulker;
import java.util.ArrayList;
import java.util.List;

/** The shulkers a Bedrock player on foot collides with: each one's box with its lid where the last tick left it. */
final class BedrockShulkerColliders {
    // Farther than a tick's movement can sweep.
    private static final double REACH = 16.0D;

    private BedrockShulkerColliders() {}

    static List<HardCollidingEntityCollision> of(CultPlayer player, SimulationContext context) {
        if (context.getVehicle() != null) return List.of();
        List<PacketEntity> candidates = player.compensatedEntities.unknownCollisionCandidates();
        if (candidates.isEmpty()) return List.of();
        SimpleCollisionBox reach = context.getFromMaximumExtent().expand(REACH);
        SimpleCollisionBox box = new SimpleCollisionBox();
        SimpleCollisionBox scratch = new SimpleCollisionBox();
        List<HardCollidingEntityCollision> colliders = null;
        for (PacketEntity entity : candidates) {
            if (!(entity instanceof PacketEntityShulker shulker) || !shulker.collidable()) continue;
            if (shulker.bedrockCollisionBox(box, scratch) == null || !reach.isIntersected(box)) continue;
            if (colliders == null) colliders = new ArrayList<>();
            colliders.add(new HardCollidingEntityCollision(box.copy(), false, false));
        }
        return colliders == null ? List.of() : colliders;
    }
}
