// This file was designed and is an original check for CultAC
// Copyright (C) 2021 DefineOutside
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.
package ac.cult.cultac.checks.impl.combat;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.type.ClientTickEndListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.TrackerData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.Vector3dm;
import ac.cult.cultac.utils.math.VectorUtils;
import ac.cult.cultac.utils.nmsutil.BoundingBoxSize;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import ac.cult.cultac.utils.visibility.ClientVisibility;
import ac.cult.cultac.utils.visibility.ClientVisibilityProcessor;
import java.util.*;

// You may not copy the check unless you are licensed under GPL
// PlayerReach and EntityReach each run this check for their own attack targets.
public abstract class ReachCheck extends Check implements CheckListener, ClientTickEndListener {
    private record QueuedAttack(
            int index,
            int entityId,
            List<Vec3> fromCandidates,
            SimpleCollisionBox targetBox,
            int type,
            boolean livingEntity,
            boolean exempt,
            AttackReach reach) {}

    /**
     * An attack measured at the client's last tick end: its index among the tick's attack packets, the target's id,
     * type and lag-compensated reach box, the eye origins it may have come from, and the least-flagging reach to the
     * box, infinite when the look misses it.
     */
    public record MeasuredAttack(
            int index, int entityId, int type, SimpleCollisionBox box, List<Vec3> origins, double reach) {}

    // A measured distance against the bound vanilla allows it.
    private record Measure(double distance, double limit) {
        private static final Measure NONE = new Measure(Double.POSITIVE_INFINITY, 0);

        double excess() {
            return distance - limit;
        }

        Measure worse(Measure other) {
            return other.excess() > excess() ? other : this;
        }

        Measure better(Measure other) {
            return other.excess() < excess() ? other : this;
        }
    }

    private final ClientVisibilityProcessor visibility;
    private final List<QueuedAttack> playerAttackQueue = new ArrayList<>();
    private final List<QueuedAttack> measuredAttacks = new ArrayList<>();
    private static final List<Integer> blacklisted = Arrays.asList(EntityTypeIds.SHULKER);

    private boolean cancelImpossibleHits;
    private double threshold;
    private double cancelBuffer; // For the next 4 hits after using reach, we aggressively cancel reach

    /** Attack packets this tick, every check's targets counted, so each attack's index is its place in the tick. */
    private int attacks;

    protected ReachCheck(CultPlayer player, ClientVisibilityProcessor visibility, CheckInfo info) {
        super(player, info);
        this.visibility = visibility;
    }

    /** Whether this check owns attacks on this entity type; -1 is an entity Cult does not track. */
    protected abstract boolean targets(int type);

    private int targetType(int entityId, PacketEntity entity) {
        if (entity != null) return entity.type;
        TrackerData tracked = player.compensatedEntities.serverPositionsMap.get(entityId);
        return tracked == null ? -1 : tracked.getEntityType();
    }

    /** The check that owns this check's attacks whose look misses the target. */
    protected abstract Class<? extends Check> hitboxes();

    private void handleInteract(final PacketReceiveEvent event, ServerboundInteract action) {
        if (!player.isDisabled() && action != null && action.action() == InteractAction.ATTACK) {
            int index = attacks++;
            PacketEntity entity = player.compensatedEntities.entityMap.get(action.entityId());
            if (!targets(targetType(action.entityId(), entity))) return;

            // Don't let the player teleport to bypass reach
            if (player.getSetbackTeleportUtil().shouldBlockMovement()) {
                debug(() -> {
                    return "cancelled = teleport";
                });
                event.setCancelled(true);
                player.onPacketCancel();
                return;
            }

            // Stop people from freezing transactions before an entity spawns to bypass reach
            if (entity == null) {
                // Only cancel if and only if we are tracking this entity
                // This is because we don't track paintings.
                if (shouldModifyPackets()
                        && player.compensatedEntities.serverPositionsMap.containsKey(action.entityId())) {
                    debug(() -> {
                        return "cancelled = no entity";
                    });
                    event.setCancelled(true);
                    player.onPacketCancel();
                }
                return;
            }

            // Dead entities cause false flags (https://github.com/GrimAnticheat/Grim/issues/546)
            if (entity.isDead) return;

            if (entity.type == EntityTypeIds.ARMOR_STAND) return;

            if (player.gamemode == GameMode.SPECTATOR) return;

            AttackReach reach = AttackReach.capture(player, visibility);
            boolean tooManyAttacks = playerAttackQueue.size() > 10;
            if (!tooManyAttacks) {
                // Queue for next tick for a precise post-rotation check. Vanilla computes
                // the hit result before sending the attack packet; movement and tick-end
                // packets from the same client tick must not change the target box used here.
                playerAttackQueue.add(new QueuedAttack(
                        index,
                        action.entityId(),
                        getAttackOrigins(),
                        getReachBox(entity),
                        entity.type,
                        entity.isLivingEntity(),
                        isReachExempt(entity),
                        reach));
            }

            final boolean knownInvalid = isKnownInvalid(entity, reach);

            if ((shouldModifyPackets() && cancelImpossibleHits && knownInvalid)
                    || (tooManyAttacks && !CultAPI.INSTANCE.getConfigManager().isObserveOnly())) {
                debug(() -> {
                    return "cancelled, many=" + tooManyAttacks + ", invalid=" + knownInvalid;
                });
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }
    }

    @CultPacketHandler
    public void onInteract(
            PacketReceiveEvent<ServerboundInteract> event, CultPlayer player, ServerboundInteract packet) {
        handleInteract(event, packet);
    }

    @Override
    public void onPlayerTickEnd(final PacketReceiveEvent event) {
        tickBetterReachCheckWithAngle();
        attacks = 0;
    }

    /** The attacks this check measured at the client's last tick end, in packet order; kept while observed. */
    public List<MeasuredAttack> measuredAttacks() {
        if (measuredAttacks.isEmpty()) return List.of();
        List<MeasuredAttack> measured = new ArrayList<>(measuredAttacks.size());
        for (QueuedAttack attack : measuredAttacks) {
            double reach = bestMeasure(attack.targetBox(), attack.fromCandidates(), attack.reach(), false)
                    .distance();
            measured.add(new MeasuredAttack(
                    attack.index(),
                    attack.entityId(),
                    attack.type(),
                    attack.targetBox(),
                    attack.fromCandidates(),
                    reach));
        }
        return measured;
    }

    // This method finds the most optimal point at which the user should be aiming at
    // and then measures the distance between the player's eyes and this target point
    //
    // It will not cancel every invalid attack but should cancel 3.05+ or so in real-time
    // Let the post look check measure the distance, as it will always return equal or higher
    // than this method.  If this method flags, the other method WILL flag.
    //
    // Meaning that the other check should be the only one that flags.
    private boolean isKnownInvalid(PacketEntity reachEntity, AttackReach reach) {
        // If the entity doesn't exist, or if it is exempt, or if it is dead
        if ((isReachExempt(reachEntity) || !reachEntity.isLivingEntity())
                && reachEntity.type != EntityTypeIds.END_CRYSTAL) return false; // exempt

        // Filter out what we assume to be cheats
        if (cancelBuffer != 0) {
            return checkReach(reachEntity, getAttackOrigins(), reach, true) != null; // If they flagged
        } else {
            SimpleCollisionBox targetBox = getReachBox(reachEntity);
            return ReachUtils.getMinReachToBox(player, targetBox) > reach.maxClosestDistance();
        }
    }

    private void tickBetterReachCheckWithAngle() {
        for (QueuedAttack attack : playerAttackQueue) {
            String result = checkReach(
                    attack.targetBox(),
                    attack.type(),
                    attack.livingEntity(),
                    attack.exempt(),
                    attack.fromCandidates(),
                    attack.reach(),
                    false);
            if (result != null) {
                if (attack.type() != EntityTypeIds.PLAYER) {
                    result += " type=" + EntityTypeUtil.getKey(attack.type()).getPath();
                }
                if (result.startsWith("Missed hitbox")) {
                    player.checkManager.getCheck(hitboxes()).flag(result);
                } else {
                    flag(result);
                }
            }
        }

        measuredAttacks.clear();
        if (player.observer != null) measuredAttacks.addAll(playerAttackQueue);
        playerAttackQueue.clear();
    }

    /** The positions the client may have attacked from: where it stands, or each seat its vehicle may hold it in. */
    public List<Vec3> getAttackOrigins() {
        PacketEntity self = player.compensatedEntities.getSelf();
        PacketEntity riding = self.getRiding();
        if (riding == null) {
            return List.of(new Vec3(player.x, player.y, player.z));
        }

        List<Vec3> packetDerivedOrigins = getMountedPacketDerivedOrigins(self, riding);

        // Each mounted candidate comes from packet/transaction-proven vehicle
        // interpolation state. Do not keep a separate vehicle-id latch for the
        // local passenger body unless validation proves the server cannot derive
        // the current origin from the mounted packet state.
        return packetDerivedOrigins;
    }

    private List<Vec3> getMountedPacketDerivedOrigins(PacketEntity self, PacketEntity riding) {
        List<Vec3> candidates = new ArrayList<>();
        for (SimpleCollisionBox vehicleBox : riding.getPossibleMovementCollisionBoxCandidates()) {
            candidates.add(BoundingBoxSize.getRidingOffsetFromVehicle(riding, self, player, vehicleBox));
        }
        if (candidates.isEmpty()) {
            candidates.add(BoundingBoxSize.getRidingOffsetFromVehicle(riding, self, player));
        }
        return candidates;
    }

    private String checkReach(
            PacketEntity reachEntity, Collection<Vec3> fromCandidates, AttackReach reach, boolean isPrediction) {
        return checkReach(
                getReachBox(reachEntity),
                reachEntity.type,
                reachEntity.isLivingEntity(),
                isReachExempt(reachEntity),
                fromCandidates,
                reach,
                isPrediction);
    }

    /** {@code reachEntity}'s lag-compensated box as the client's pick sees it. */
    public SimpleCollisionBox getReachBox(PacketEntity reachEntity) {
        if (reachEntity.type == EntityTypeIds.END_CRYSTAL) { // Hardcode end crystal box
            return new SimpleCollisionBox(
                    reachEntity.desyncClientPos.subtract(1, 0, 1), reachEntity.desyncClientPos.add(1, 2, 1));
        }

        SimpleCollisionBox movementBox = reachEntity.getPossibleCollisionBoxes();
        float movementWidth = BoundingBoxSize.getWidth(player, reachEntity);
        float movementHeight = BoundingBoxSize.getHeight(player, reachEntity);
        float reachWidth = BoundingBoxSize.getReachWidth(player, reachEntity);
        float reachHeight = BoundingBoxSize.getReachHeight(player, reachEntity);
        SimpleCollisionBox reachBox = movementBox.copy();
        reachBox.expand(
                Math.max(0.0D, (reachWidth - movementWidth) / 2.0D),
                Math.max(0.0D, reachHeight - movementHeight),
                Math.max(0.0D, (reachWidth - movementWidth) / 2.0D));
        return reachBox;
    }

    private String checkReach(
            SimpleCollisionBox targetBox,
            int type,
            boolean livingEntity,
            boolean exempt,
            Collection<Vec3> fromCandidates,
            AttackReach reach,
            boolean isPrediction) {
        targetBox = targetBox.copy();
        targetBox.expand(threshold);
        targetBox.expand(player.getMovementThreshold());

        Measure best = bestMeasure(targetBox, fromCandidates, reach, isPrediction);

        // if the entity is not exempt and the entity is alive
        if ((!exempt && livingEntity) || type == EntityTypeIds.END_CRYSTAL) {
            if (best.distance() == Double.POSITIVE_INFINITY) {
                cancelBuffer = 1;
                return "Missed hitbox";
            } else if (best.excess() > 0) {
                cancelBuffer = 1;
                return String.format("%.5f blocks, max=%.5f", best.distance(), best.limit());
            } else {
                cancelBuffer = Math.max(0, cancelBuffer - 0.25);
            }
        }

        return null;
    }

    /** The least-flagging measure of the look against {@code targetBox} from the eyes at {@code fromCandidates}. */
    private Measure bestMeasure(
            SimpleCollisionBox targetBox, Collection<Vec3> fromCandidates, AttackReach reach, boolean isPrediction) {
        Measure best = Measure.NONE;

        List<Vector3dm> possibleLookDirs = new ArrayList<>();
        possibleLookDirs.add(ReachUtils.getLook(player, player.xRot, player.yRot));

        // If we are a tick behind, we don't know their next look so don't bother doing this
        if (!isPrediction) {
            possibleLookDirs.add(ReachUtils.getLook(player, player.lastTickXRot, player.lastTickYRot));
        }

        for (Vector3dm lookVec : possibleLookDirs) {
            for (Vec3 from : fromCandidates) {
                if (from == null) {
                    continue;
                }
                for (double eye : player.getPossibleEyeHeights()) {
                    Vector3dm eyePos = new Vector3dm(from.x, from.y + eye, from.z);
                    best = best.better(measure(targetBox, eyePos, lookVec, reach));
                    if (best.distance() == 0) break;
                }
                if (best.distance() == 0) break;
            }
            if (best.distance() == 0) break;
        }

        return best;
    }

    // The least-flagging vanilla selection from one eye and look: Minecraft#pick, then Minecraft#startAttack.
    private Measure measure(SimpleCollisionBox targetBox, Vector3dm eyePos, Vector3dm lookVec, AttackReach reach) {
        // AttackRange#isInRange measures to a point on the target box, never nearer than its closest point.
        Measure itemLimit = Measure.NONE;
        if (reach.hasItemLimit()) {
            Vector3dm closest = VectorUtils.cutBoxToVector(eyePos, targetBox);
            itemLimit = new Measure(eyePos.distance(closest), reach.itemLimit());
        }

        // LocalPlayer#pick: the entity_interaction_range ray against the target box.
        Measure best = Measure.NONE;
        double rawHit = intercept(targetBox, eyePos, lookVec, reach.entityRange() + 3);
        if (rawHit != Double.POSITIVE_INFINITY) {
            Measure pick = new Measure(rawHit, reach.entityRange());
            best = reach.hasItemLimit() ? pick.worse(itemLimit) : pick;
        }

        // AttackRange#getClosesetHit: the item ray against the box inflated by its hitbox margin.
        for (ClientVisibility.ItemPick itemPick : reach.picks()) {
            double rayLength = lookVec.length() * itemPick.rayScale();
            SimpleCollisionBox marginBox = targetBox.copy().expand(itemPick.hitboxMargin());
            double itemHit = intercept(marginBox, eyePos, lookVec, rayLength + 3);
            if (itemHit == Double.POSITIVE_INFINITY) continue;
            Measure pick = new Measure(itemHit, rayLength);
            best = best.better(reach.hasItemLimit() ? pick.worse(itemLimit) : pick);
        }
        return best;
    }

    private static double intercept(SimpleCollisionBox box, Vector3dm eyePos, Vector3dm lookVec, double length) {
        if (ReachUtils.isVecInside(box, eyePos)) return 0;
        Vector3dm end = eyePos.clone().add(lookVec.clone().multiply(length / lookVec.length()));
        Vector3dm intercept = ReachUtils.calculateIntercept(box, eyePos, end).getFirst();
        return intercept == null ? Double.POSITIVE_INFINITY : eyePos.distance(intercept);
    }

    private boolean isReachExempt(PacketEntity reachEntity) {
        return blacklisted.contains(reachEntity.type) || EntityTypeUtil.isBoat(reachEntity.type);
    }

    @Override
    public void reload() {
        super.reload();
        this.cancelImpossibleHits = getConfig().getBooleanElse("Reach.block-impossible-hits", true);
        this.threshold = getConfig().getDoubleElse("Reach.threshold", 0.0005);
    }
}
