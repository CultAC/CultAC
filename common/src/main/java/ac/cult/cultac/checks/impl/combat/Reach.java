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

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.type.ClientTickEndListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.packet.NmsPacketUtil;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.nmsutil.BoundingBoxSize;
import ac.cult.cultac.utils.nmsutil.EntityTypesCompat;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import org.bukkit.GameMode;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.world.entity.EntityType;
import org.bukkit.util.Vector;

import java.util.*;

// You may not copy the check unless you are licensed under GPL
//@CheckData(name = "Reach", configName = "Reach", setback = 10)
public class Reach extends Check implements CheckListener, ClientTickEndListener {
    private record QueuedAttack(int entityId, List<Vec3> fromCandidates, SimpleCollisionBox targetBox, EntityType type,
                                boolean livingEntity, boolean exempt) {
    }

    private final List<QueuedAttack> playerAttackQueue = new ArrayList<>();
    private static final List<EntityType> blacklisted = Arrays.asList(EntityTypesCompat.SHULKER);

    private boolean cancelImpossibleHits;
    private double threshold;
    private double cancelBuffer; // For the next 4 hits after using reach, we aggressively cancel reach

    public Reach(CultPlayer player) { super(player, CheckInfo.builder()
            .name("Reach")
            .stableKey("cult.combat.reach")
            .description("Attacked an entity from too far away")
            .build()); }

    private void handleInteract(final PacketReceiveEvent event, NmsPacketUtil.InteractData action) {
        if (!player.isDisabled() && action != null && action.action() == NmsPacketUtil.InteractAction.ATTACK) {

            // Don't let the player teleport to bypass reach
            if (player.getSetbackTeleportUtil().shouldBlockMovement()) {
                debug(() -> { return "cancelled = teleport"; });
                event.setCancelled(true);
                player.onPacketCancel();
                return;
            }

            PacketEntity entity = player.compensatedEntities.entityMap.get(action.entityId());

            // Stop people from freezing transactions before an entity spawns to bypass reach
            if (entity == null) {
                // Only cancel if and only if we are tracking this entity
                // This is because we don't track paintings.
                if (shouldModifyPackets() && player.compensatedEntities.serverPositionsMap.containsKey(action.entityId())) {
                    debug(() -> { return "cancelled = no entity"; });
                    event.setCancelled(true);
                    player.onPacketCancel();
                }
                return;
            }

            // Dead entities cause false flags (https://github.com/GrimAnticheat/Grim/issues/546)
            if (entity.isDead) return;

            if (entity.type == EntityTypesCompat.ARMOR_STAND)
                return;

            if (player.gamemode == GameMode.SPECTATOR || player.gamemode == GameMode.CREATIVE) return;

            boolean tooManyAttacks = playerAttackQueue.size() > 10;
            if (!tooManyAttacks) {
                // Queue for next tick for a precise post-rotation check. Vanilla computes
                // the hit result before sending the attack packet; movement and tick-end
                // packets from the same client tick must not change the target box used here.
                playerAttackQueue.add(new QueuedAttack(
                        action.entityId(),
                        getAttackOrigins(),
                        getReachBox(entity),
                        entity.type,
                        entity.isLivingEntity(),
                        isReachExempt(entity)
                ));
            }

            final boolean knownInvalid = isKnownInvalid(entity);

            if ((shouldModifyPackets() && cancelImpossibleHits && knownInvalid) || tooManyAttacks) {
                debug(() -> { return "cancelled, many=" + tooManyAttacks + ", invalid=" + knownInvalid; });
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }
    }

    @CultPacketHandler
    public void onInteract(PacketReceiveEvent event, CultPlayer player, ServerboundInteractPacket packet) {
        handleInteract(event, NmsPacketUtil.readInteract(packet));
    }

    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundAttackPacket")
    public void onAttack(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
        handleInteract(event, NmsPacketUtil.readAttack(packet));
    }

    @Override
    public void onPlayerTickEnd(final PacketReceiveEvent event) {
        tickBetterReachCheckWithAngle();
    }

    // This method finds the most optimal point at which the user should be aiming at
    // and then measures the distance between the player's eyes and this target point
    //
    // It will not cancel every invalid attack but should cancel 3.05+ or so in real-time
    // Let the post look check measure the distance, as it will always return equal or higher
    // than this method.  If this method flags, the other method WILL flag.
    //
    // Meaning that the other check should be the only one that flags.
    private boolean isKnownInvalid(PacketEntity reachEntity) {
        // If the entity doesn't exist, or if it is exempt, or if it is dead
        if ((isReachExempt(reachEntity) || !reachEntity.isLivingEntity()) && reachEntity.type != EntityTypesCompat.END_CRYSTAL)
            return false; // exempt

        if (player.gamemode == GameMode.SPECTATOR || player.gamemode == GameMode.CREATIVE) return false;

        // Filter out what we assume to be cheats
        if (cancelBuffer != 0) {
            return checkReach(reachEntity, getAttackOrigins(), true) != null; // If they flagged
        } else {
            SimpleCollisionBox targetBox = getReachBox(reachEntity);
            return ReachUtils.getMinReachToBox(player, targetBox) > 3;
        }
    }

    private void tickBetterReachCheckWithAngle() {
        for (QueuedAttack attack : playerAttackQueue) {
            String result = checkReach(attack.targetBox(), attack.type(), attack.livingEntity(), attack.exempt(), attack.fromCandidates(), false);
            if (result != null) {
                if ("Missed hitbox".equals(result)) {
                    player.checkManager.getCheck(Hitboxes.class).flag(result);
                } else if (attack.type() == EntityTypesCompat.PLAYER) {
                    flag(result);
                } else {
                    flag(result + " type=" + EntityTypeUtil.getKey(attack.type()).getPath());
                }
            }
        }

        playerAttackQueue.clear();
    }

    private String checkReach(PacketEntity reachEntity, Vec3 from, boolean isPrediction) {
        return checkReach(reachEntity, List.of(from), isPrediction);
    }

    private List<Vec3> getAttackOrigins() {
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

    private String checkReach(PacketEntity reachEntity, Collection<Vec3> fromCandidates, boolean isPrediction) {
        return checkReach(getReachBox(reachEntity), reachEntity.type, reachEntity.isLivingEntity(), isReachExempt(reachEntity), fromCandidates, isPrediction);
    }

    private SimpleCollisionBox getReachBox(PacketEntity reachEntity) {
        if (reachEntity.type == EntityTypesCompat.END_CRYSTAL) { // Hardcode end crystal box
            return new SimpleCollisionBox(reachEntity.desyncClientPos.subtract(1, 0, 1), reachEntity.desyncClientPos.add(1, 2, 1));
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
                Math.max(0.0D, (reachWidth - movementWidth) / 2.0D)
        );
        return reachBox;
    }

    private String checkReach(SimpleCollisionBox targetBox, EntityType type, boolean livingEntity, boolean exempt,
                              Collection<Vec3> fromCandidates, boolean isPrediction) {
        targetBox = targetBox.copy();
        targetBox.expand(threshold);
        targetBox.expand(player.getMovementThreshold());

        double minDistance = Double.MAX_VALUE;

        List<Vector> possibleLookDirs = new ArrayList<>();
        possibleLookDirs.add(ReachUtils.getLook(player, player.xRot, player.yRot));

        // If we are a tick behind, we don't know their next look so don't bother doing this
        if (!isPrediction) {
            possibleLookDirs.add(ReachUtils.getLook(player, player.lastTickXRot, player.lastTickYRot));
        }

        for (Vector lookVec : possibleLookDirs) {
            for (Vec3 from : fromCandidates) {
                if (from == null) {
                    continue;
                }
                for (double eye : player.getPossibleEyeHeights()) {
                    Vector eyePos = new Vector(from.x, from.y + eye, from.z);
                    Vector endReachPos = eyePos.clone().add(new Vector(lookVec.getX() * 6, lookVec.getY() * 6, lookVec.getZ() * 6));

                    Vector intercept = ReachUtils.calculateIntercept(targetBox, eyePos, endReachPos).getFirst();

                    if (ReachUtils.isVecInside(targetBox, eyePos)) {
                        minDistance = 0;
                        break;
                    }

                    if (intercept != null) {
                        minDistance = Math.min(eyePos.distance(intercept), minDistance);
                    }
                }
                if (minDistance == 0) {
                    break;
                }
            }
            if (minDistance == 0) {
                break;
            }
        }

        // if the entity is not exempt and the entity is alive
        if ((!exempt && livingEntity) || type == EntityTypesCompat.END_CRYSTAL) {
            if (minDistance == Double.MAX_VALUE) {
                cancelBuffer = 1;
                return "Missed hitbox";
            } else if (minDistance > 3) {
                cancelBuffer = 1;
                return String.format("%.5f", minDistance) + " blocks";
            } else {
                cancelBuffer = Math.max(0, cancelBuffer - 0.25);
            }
        }

        return null;
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
