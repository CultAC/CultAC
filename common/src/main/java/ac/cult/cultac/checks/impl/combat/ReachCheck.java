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

    // CultPlayer#getPossibleEyeHeights at scale 1.
    private static final double[] EYE_HEIGHTS = {0.4, 1.27, 1.62};
    private final ClientVisibilityProcessor visibility;
    // The looks measureBest tries, three components each.
    private final double[] looks = new double[15];
    // The least-flagging measured distance and the bounds vanilla allows it.
    private double bestDistance, bestMin, bestMax;
    // The location hitAlong found.
    private double hitX, hitY, hitZ;
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

            // ServerGamePacketListenerImpl#handleAttack drops attacks until the client has loaded, and the LocalPlayer
            // they would be judged from has not ticked yet.
            if (!player.packetStateData.localPlayerTickedSinceSpawn) {
                debug(() -> {
                    return "cancelled = not ticked";
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
            measureBest(attack.targetBox(), attack.fromCandidates(), attack.reach(), false);
            measured.add(new MeasuredAttack(
                    attack.index(),
                    attack.entityId(),
                    attack.type(),
                    attack.targetBox(),
                    attack.fromCandidates(),
                    bestDistance));
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
            PacketEntity reachEntity, List<Vec3> fromCandidates, AttackReach reach, boolean isPrediction) {
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
            List<Vec3> fromCandidates,
            AttackReach reach,
            boolean isPrediction) {
        targetBox = targetBox.copy();
        targetBox.expand(threshold);
        targetBox.expand(player.getMovementThreshold());

        measureBest(targetBox, fromCandidates, reach, isPrediction);

        // if the entity is not exempt and the entity is alive
        if ((!exempt && livingEntity) || type == EntityTypeIds.END_CRYSTAL) {
            if (bestDistance == Double.POSITIVE_INFINITY) {
                cancelBuffer = 1;
                return "Missed hitbox";
            } else if (excess() > 0) {
                cancelBuffer = 1;
                return bestDistance < bestMin
                        ? String.format("%.5f blocks, min=%.5f", bestDistance, bestMin)
                        : String.format("%.5f blocks, max=%.5f", bestDistance, bestMax);
            } else {
                cancelBuffer = Math.max(0, cancelBuffer - 0.25);
            }
        }

        return null;
    }

    /** Measures the least-flagging selection of the look at {@code targetBox} from the eyes at {@code fromCandidates}. */
    private void measureBest(
            SimpleCollisionBox targetBox, List<Vec3> fromCandidates, AttackReach reach, boolean isPrediction) {
        bestDistance = Double.POSITIVE_INFINITY;
        bestMin = Double.NEGATIVE_INFINITY;
        bestMax = 0;

        int lookCount = addLook(0, player.xRot, player.yRot);
        // If we are a tick behind, we don't know their next look so don't bother doing this
        if (!isPrediction) {
            lookCount = addLook(lookCount, player.lastTickXRot, player.lastTickYRot);
        }

        // The item ray looks along Entity#getHeadLookAngle: the pitch, with the yaw of LocalPlayer#yHeadRot.
        int viewLooks = lookCount;
        boolean headArc = false;
        if (!reach.picks().isEmpty()) {
            lookCount = addLook(lookCount, player.headYaw, player.yRot);
            if (!Float.isNaN(player.unclampedHeadYaw)) {
                lookCount = addLook(lookCount, player.unclampedHeadYaw, player.yRot);
            }
            headArc = !Float.isNaN(player.headArcFrom);
        }

        double scale = player.getScale();
        for (int look = 0; look < lookCount; look++) {
            double lookX = looks[look * 3];
            double lookY = looks[look * 3 + 1];
            double lookZ = looks[look * 3 + 2];
            for (int candidate = 0; candidate < fromCandidates.size(); candidate++) {
                Vec3 from = fromCandidates.get(candidate);
                if (from == null) {
                    continue;
                }
                for (double height : EYE_HEIGHTS) {
                    double eyeY = from.y + height * scale;
                    if (look < viewLooks) {
                        measureView(targetBox, from.x, eyeY, from.z, lookX, lookY, lookZ, reach);
                    } else {
                        measureItem(targetBox, from.x, eyeY, from.z, lookX, lookY, lookZ, reach);
                    }
                    if (bestDistance == 0 && excess() <= 0) return;
                }
            }
        }
        if (headArc) measureHeadArc(targetBox, fromCandidates, reach, scale, lookCount);
    }

    // Of the head yaws the arc allows, the one nearest the yaw from each eye to the target's center.
    private void measureHeadArc(
            SimpleCollisionBox targetBox, List<Vec3> fromCandidates, AttackReach reach, double scale, int slot) {
        double centerX = (targetBox.minX + targetBox.maxX) / 2.0;
        double centerZ = (targetBox.minZ + targetBox.maxZ) / 2.0;
        float from = player.headArcFrom;
        float length = player.headArcLength;
        for (int candidate = 0; candidate < fromCandidates.size(); candidate++) {
            Vec3 eye = fromCandidates.get(candidate);
            if (eye == null) {
                continue;
            }
            float aim = (float) Math.toDegrees(Math.atan2(eye.x - centerX, centerZ - eye.z));
            float into = aim - from;
            into -= 360.0F * (float) Math.floor(into / 360.0F);
            float yaw = into <= length ? from + into : into - length < 360.0F - into ? from + length : from;
            addLook(slot, yaw, player.yRot);
            double lookX = looks[slot * 3];
            double lookY = looks[slot * 3 + 1];
            double lookZ = looks[slot * 3 + 2];
            for (double height : EYE_HEIGHTS) {
                measureItem(targetBox, eye.x, eye.y + height * scale, eye.z, lookX, lookY, lookZ, reach);
                if (bestDistance == 0 && excess() <= 0) return;
            }
        }
    }

    // ReachUtils#getLook, into looks.
    private int addLook(int count, float yaw, float pitch) {
        float pitchRadians = pitch * ((float) Math.PI / 180F);
        float yawRadians = -yaw * ((float) Math.PI / 180F);
        float cosPitch = player.trigHandler.cos(pitchRadians);
        looks[count * 3] = player.trigHandler.sin(yawRadians) * cosPitch;
        looks[count * 3 + 1] = -player.trigHandler.sin(pitchRadians);
        looks[count * 3 + 2] = player.trigHandler.cos(yawRadians) * cosPitch;
        return count + 1;
    }

    private double excess() {
        return Math.max(bestDistance - bestMax, bestMin - bestDistance);
    }

    private void consider(double distance, double min, double max) {
        if (Math.max(distance - max, min - distance) < excess()) {
            bestDistance = distance;
            bestMin = min;
            bestMax = max;
        }
    }

    // LocalPlayer#pick hits the box along the view within entity_interaction_range; Minecraft#startAttack bounds
    // that hit by the main hand's AttackRange#isInRange.
    private void measureView(
            SimpleCollisionBox box,
            double eyeX,
            double eyeY,
            double eyeZ,
            double lookX,
            double lookY,
            double lookZ,
            AttackReach reach) {
        double length = reach.entityRange() + 3;
        double scale = length / Math.sqrt(lookX * lookX + lookY * lookY + lookZ * lookZ);
        double dx = lookX * scale;
        double dy = lookY * scale;
        double dz = lookZ * scale;
        double hit = contains(box, eyeX, eyeY, eyeZ) ? 0 : clip(box, 0, eyeX, eyeY, eyeZ, dx, dy, dz);
        if (Double.isNaN(hit)) return;
        double distance = distance(eyeX, eyeY, eyeZ, eyeX + hit * dx, eyeY + hit * dy, eyeZ + hit * dz);
        if (reach.hasItemLimit()) {
            consider(distance, reach.itemMin(), Math.min(reach.entityRange(), reach.itemLimit()));
        } else {
            consider(distance, Double.NEGATIVE_INFINITY, reach.entityRange());
        }
    }

    // AttackRange#getClosesetHit: where ProjectileUtil#getHitEntitiesAlong's segment from rayStart to rayEnd plus the
    // known movement along the look hits the box, in the segment's direction; Minecraft#startAttack bounds that hit by
    // the main hand's AttackRange#isInRange.
    private void measureItem(
            SimpleCollisionBox box,
            double eyeX,
            double eyeY,
            double eyeZ,
            double lookX,
            double lookY,
            double lookZ,
            AttackReach reach) {
        double min = reach.hasItemLimit() ? reach.itemMin() : Double.NEGATIVE_INFINITY;
        double max = reach.hasItemLimit() ? reach.itemLimit() : Double.POSITIVE_INFINITY;
        List<ClientVisibility.ItemPick> picks = reach.picks();
        for (int i = 0; i < picks.size(); i++) {
            ClientVisibility.ItemPick pick = picks.get(i);
            double fromX = eyeX + lookX * pick.rayStart();
            double fromY = eyeY + lookY * pick.rayStart();
            double fromZ = eyeZ + lookZ * pick.rayStart();
            if (contains(box, fromX, fromY, fromZ)) {
                consider(distance(eyeX, eyeY, eyeZ, fromX, fromY, fromZ), min, max);
                continue;
            }

            double[] movements = pick.movements();
            for (int m = 0; m < movements.length; m += 3) {
                double along = movements[m] * lookX + movements[m + 1] * lookY + movements[m + 2] * lookZ;
                double end = pick.rayEnd() + Math.max(0.0, along);
                if (hitAlong(
                        box,
                        pick.hitboxMargin(),
                        fromX,
                        fromY,
                        fromZ,
                        eyeX + lookX * end - fromX,
                        eyeY + lookY * end - fromY,
                        eyeZ + lookZ * end - fromZ)) {
                    consider(distance(eyeX, eyeY, eyeZ, hitX, hitY, hitZ), min, max);
                } else {
                    measureShort(box, pick, fromX, fromY, fromZ, eyeX, eyeY, eyeZ, lookX, lookY, lookZ, end);
                }
            }
        }
    }

    // A hit just past the end of a segment that missed measures how far the segment fell short.
    private void measureShort(
            SimpleCollisionBox box,
            ClientVisibility.ItemPick pick,
            double fromX,
            double fromY,
            double fromZ,
            double eyeX,
            double eyeY,
            double eyeZ,
            double lookX,
            double lookY,
            double lookZ,
            double end) {
        double past = Math.max(end, pick.rayStart()) + 3;
        double scale = clip(
                box,
                pick.hitboxMargin(),
                fromX,
                fromY,
                fromZ,
                eyeX + lookX * past - fromX,
                eyeY + lookY * past - fromY,
                eyeZ + lookZ * past - fromZ);
        if (Double.isNaN(scale)) return;
        double lookLength = Math.sqrt(lookX * lookX + lookY * lookY + lookZ * lookZ);
        double along = pick.rayStart() + scale * (past - pick.rayStart());
        consider(lookLength * along, Double.NEGATIVE_INFINITY, lookLength * end);
    }

    // ProjectileUtil#getManyEntityHitResult: the box's entry, or else the margin's entry projected toward the box's
    // center, into hitX, hitY and hitZ.
    private boolean hitAlong(
            SimpleCollisionBox box,
            float margin,
            double fromX,
            double fromY,
            double fromZ,
            double dx,
            double dy,
            double dz) {
        double scale = clip(box, 0, fromX, fromY, fromZ, dx, dy, dz);
        if (Double.isNaN(scale) && margin > 0) {
            scale = clip(box, margin, fromX, fromY, fromZ, dx, dy, dz);
            if (Double.isNaN(scale)) return false;
            fromX += scale * dx;
            fromY += scale * dy;
            fromZ += scale * dz;
            dx = box.minX + 0.5 * (box.maxX - box.minX) - fromX;
            dy = box.minY + 0.5 * (box.maxY - box.minY) - fromY;
            dz = box.minZ + 0.5 * (box.maxZ - box.minZ) - fromZ;
            scale = clip(box, 0, fromX, fromY, fromZ, dx, dy, dz);
        }
        if (Double.isNaN(scale)) return false;
        hitX = fromX + scale * dx;
        hitY = fromY + scale * dy;
        hitZ = fromZ + scale * dz;
        return true;
    }

    // AABB#contains.
    private static boolean contains(SimpleCollisionBox box, double x, double y, double z) {
        return x >= box.minX && x < box.maxX && y >= box.minY && y < box.maxY && z >= box.minZ && z < box.maxZ;
    }

    // AABB#clip on the box grown by margin: the fraction of the move at which it enters, NaN when it doesn't.
    private static double clip(
            SimpleCollisionBox box, double margin, double x, double y, double z, double dx, double dy, double dz) {
        double minX = box.minX - margin;
        double minY = box.minY - margin;
        double minZ = box.minZ - margin;
        double maxX = box.maxX + margin;
        double maxY = box.maxY + margin;
        double maxZ = box.maxZ + margin;
        double scale = 1;
        if (dx > 1.0E-7) {
            scale = clipFace(scale, minX, x, dx, y, dy, minY, maxY, z, dz, minZ, maxZ);
        } else if (dx < -1.0E-7) {
            scale = clipFace(scale, maxX, x, dx, y, dy, minY, maxY, z, dz, minZ, maxZ);
        }
        if (dy > 1.0E-7) {
            scale = clipFace(scale, minY, y, dy, z, dz, minZ, maxZ, x, dx, minX, maxX);
        } else if (dy < -1.0E-7) {
            scale = clipFace(scale, maxY, y, dy, z, dz, minZ, maxZ, x, dx, minX, maxX);
        }
        if (dz > 1.0E-7) {
            scale = clipFace(scale, minZ, z, dz, x, dx, minX, maxX, y, dy, minY, maxY);
        } else if (dz < -1.0E-7) {
            scale = clipFace(scale, maxZ, z, dz, x, dx, minX, maxX, y, dy, minY, maxY);
        }
        return scale < 1 ? scale : Double.NaN;
    }

    // AABB#clipPoint.
    private static double clipFace(
            double scale,
            double face,
            double a,
            double da,
            double b,
            double db,
            double minB,
            double maxB,
            double c,
            double dc,
            double minC,
            double maxC) {
        double s = (face - a) / da;
        double pb = b + s * db;
        double pc = c + s * dc;
        return 0 < s
                        && s < scale
                        && minB - 1.0E-7 < pb
                        && pb < maxB + 1.0E-7
                        && minC - 1.0E-7 < pc
                        && pc < maxC + 1.0E-7
                ? s
                : scale;
    }

    // Vec3#distanceToSqr from the hit to the eye, as AttackRange#isInRange measures it.
    private static double distance(double eyeX, double eyeY, double eyeZ, double x, double y, double z) {
        double dx = x - eyeX;
        double dy = y - eyeY;
        double dz = z - eyeZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
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
