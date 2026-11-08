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
import ac.cult.cultac.bedrock.protocol.BedrockProtocolVersion;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.impl.visibility.BedrockAttackMobVisibility;
import ac.cult.cultac.checks.impl.visibility.BedrockAttackPlayerVisibility;
import ac.cult.cultac.checks.impl.visibility.BedrockAttackVisibility;
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
import ac.cult.cultac.utils.visibility.BedrockEye;
import ac.cult.cultac.utils.visibility.BedrockPickBlocks;
import ac.cult.cultac.utils.visibility.ClientVisibility;
import ac.cult.cultac.utils.visibility.ClientVisibilityProcessor;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

// You may not copy the check unless you are licensed under GPL
// PlayerReach and EntityReach run this check for a Java client's attack targets, BedrockPlayerReach and
// BedrockEntityReach for a Bedrock client's.
public abstract class ReachCheck extends Check implements CheckListener, ClientTickEndListener {
    private record QueuedAttack(
            int index,
            int entityId,
            List<Vec3> fromCandidates,
            SimpleCollisionBox targetBox,
            Vec3 targetSize,
            int type,
            boolean enforced,
            AttackReach reach,
            String context,
            BedrockAttackPick pick,
            Vec3 claimedEye) {}

    /**
     * An attack measured at the client's last tick end: its index among the tick's attack packets, the target's id,
     * type and lag-compensated reach box, the eye origins it may have come from, and the least-flagging reach to the
     * box, infinite when the look misses it.
     */
    public record MeasuredAttack(
            int index, int entityId, int type, SimpleCollisionBox box, List<Vec3> origins, double reach) {}

    private static final String MISSED_HITBOX = "Missed hitbox";
    private static final String DEFAULT_SCALE_TEXT = String.format("%.4f", 1.0F);
    // CultPlayer#getPossibleEyeHeights at scale 1.
    private static final double[] EYE_HEIGHTS = {0.4, 1.27, 1.62};
    // A Bedrock attack is measured on the target's box grown by 0.1 on every side, players and end crystals included.
    private static final double BEDROCK_PICK_MARGIN = 0.1D;
    private static final double BEDROCK_RAVAGER_PICK_WIDTH = 1.95F;
    private static final double BEDROCK_RAVAGER_PICK_HEIGHT = 2.2F;
    // InputMode ordinals of a Bedrock client that picks at a screen point rather than along its view.
    private static final int BEDROCK_TOUCH = 2;
    private static final int BEDROCK_MOTION_CONTROLLER = 4;
    private static final int BEDROCK_MOUSE = 1;
    private static final int BEDROCK_GAMEPAD = 3;
    // The length of a Bedrock client's pick ray by input mode.
    private static final double BEDROCK_MOUSE_RAY = 5.7F;
    private static final double BEDROCK_GAMEPAD_RAY = 5.6F;
    private static final double BEDROCK_OTHER_RAY = 12.0F;
    // How far /cult debug reach lets the measured reach exceed the client's claimed reach before showing it red.
    private static final double REACH_DEBUG_TOLERANCE = 0.001;
    private final ClientVisibilityProcessor visibility;
    // The looks measureBest tries, three components each.
    private final double[] looks = new double[15];
    // The least-flagging measured distance, the farthest a possible target box is entered along the same ray, and the
    // bounds vanilla allows it.
    private double bestDistance, bestFar, bestMin, bestMax;
    // At most the size of each possible box within the target box measureBest measures.
    private Vec3 targetSize;
    // The location hitAlong found.
    private double hitX, hitY, hitZ;
    private final List<QueuedAttack> playerAttackQueue = new ArrayList<>();
    private final List<QueuedAttack> measuredAttacks = new ArrayList<>();

    private boolean cancelImpossibleHits;
    private double threshold;
    private double cancelBuffer; // For the next 4 hits after using reach, we aggressively cancel reach
    // A Bedrock client's rotation at its last two tick ends, and at the one being ended.
    private float tickYaw, tickPitch, lastTickYaw, lastTickPitch, nextYaw, nextPitch;

    /** Attack packets this tick, every check's targets counted, so each attack's index is its place in the tick. */
    private int attacks;

    protected ReachCheck(CultPlayer player, ClientVisibilityProcessor visibility, CheckInfo info) {
        super(player, info);
        this.visibility = visibility;
    }

    /** Whether this check owns attacks on this entity type; -1 is an entity Cult does not track. */
    protected abstract boolean targets(int type);

    /** Whether this check judges a Bedrock client's attacks; the others judge a Java client's. */
    protected abstract boolean judgesBedrock();

    /** The reach check that owns {@code player}'s attacks on targets of {@code type}. */
    public static Class<? extends ReachCheck> owner(CultPlayer player, int type) {
        boolean avatar = type == EntityTypeIds.PLAYER;
        if (player.isBedrockMovement()) return avatar ? BedrockPlayerReach.class : BedrockEntityReach.class;
        return avatar ? PlayerReach.class : EntityReach.class;
    }

    private boolean owns(int type) {
        return player.isBedrockMovement() == judgesBedrock() && targets(type);
    }

    private int targetType(int entityId, PacketEntity entity) {
        if (entity != null) return entity.type;
        TrackerData tracked = player.compensatedEntities.serverPositionsMap.get(entityId);
        return tracked == null ? -1 : tracked.getEntityType();
    }

    /** ClientLevel#onTrackingStart adds a dragon's EnderDragonParts only once the client has processed its spawn. */
    private boolean isPartOfUnspawnedDragon(int entityId) {
        OptionalInt dragon = player.compensatedEntities.enderDragonOfPart(entityId);
        return dragon.isPresent() && !player.compensatedEntities.entityMap.containsKey(dragon.getAsInt());
    }

    /** The check that owns this check's attacks whose look misses the target. */
    protected abstract Class<? extends Check> hitboxes();

    @CultPacketHandler
    public void onInteract(
            PacketReceiveEvent<ServerboundInteract> event, CultPlayer player, ServerboundInteract packet) {
        if (packet != null && packet.action() == InteractAction.ATTACK && attack(packet.entityId(), null, 0, null)) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }

    /**
     * A Bedrock client's attack on the Java entity {@code entityId}, handed over by the Geyser bridge when it arrives
     * with the world point the client reports its pick hit, null when it sent none, and how far that point's floats
     * may lie from it; true when the bridge must drop it.
     */
    public boolean onBedrockAttack(int entityId, Vec3 hit, double hitPrecision) {
        return onBedrockAttack(entityId, hit, hitPrecision, null);
    }

    /**
     * As {@link #onBedrockAttack(int, Vec3, double)}, with the eye the client reports with the attack, its position at
     * its last tick end at eye height, or null when it sent none. Only /cult debug reach shows it.
     */
    public boolean onBedrockAttack(int entityId, Vec3 hit, double hitPrecision, Vec3 claimedEye) {
        return judgesBedrock() && attack(entityId, hit, hitPrecision, claimedEye);
    }

    /** Queues an attack for this client tick's end; true when the attack must be dropped. */
    private boolean attack(int entityId, Vec3 hit, double hitPrecision, Vec3 claimedEye) {
        if (player.isDisabled()) return false;
        int index = attacks++;
        PacketEntity entity = player.compensatedEntities.entityMap.get(entityId);
        if (!owns(targetType(entityId, entity))) return false;

        // Don't let the player teleport to bypass reach
        if (player.getSetbackTeleportUtil().shouldBlockMovement()) {
            debug(() -> {
                return "cancelled = teleport";
            });
            return true;
        }

        // ServerGamePacketListenerImpl#handleAttack drops attacks until the client has loaded, and the LocalPlayer
        // they would be judged from has not ticked yet.
        if (!player.packetStateData.localPlayerTickedSinceSpawn) {
            debug(() -> {
                return "cancelled = not ticked";
            });
            return true;
        }

        // Stop people from freezing transactions before an entity spawns to bypass reach
        if (entity == null) {
            // Only cancel if and only if we are tracking this entity
            // This is because we don't track paintings.
            if (shouldModifyPackets()
                    && (player.compensatedEntities.serverPositionsMap.containsKey(entityId)
                            || isPartOfUnspawnedDragon(entityId))) {
                debug(() -> {
                    return "cancelled = no entity";
                });
                return true;
            }
            return false;
        }

        if (!measured(entity)) return false;
        // A Bedrock client's attacks while mounted are left out.
        if (player.isBedrockMovement() && player.compensatedEntities.getSelf().getRiding() != null) return false;

        AttackReach reach = AttackReach.capture(player, visibility);
        BedrockAttackPick pick = player.isBedrockMovement() ? bedrockPick(hit, hitPrecision) : null;
        boolean tooManyAttacks = playerAttackQueue.size() > 10;
        if (!tooManyAttacks) {
            // Queue for next tick for a precise post-rotation check. Vanilla computes
            // the hit result before sending the attack packet; movement and tick-end
            // packets from the same client tick must not change the target box used here.
            playerAttackQueue.add(new QueuedAttack(
                    index,
                    entityId,
                    getAttackOrigins(),
                    getReachBox(entity),
                    getReachSize(entity),
                    entity.type,
                    isEnforced(entity),
                    reach,
                    describeContext(entity),
                    pick,
                    claimedEye));
        }

        final boolean knownInvalid = pick != null
                ? isEnforced(entity) && isKnownInvalidOnBedrock(pick, getReachBox(entity), reach)
                : isKnownInvalid(entity, reach);

        // A Bedrock client's attack through blocks is judged at the point its pick entered the target, as it arrives.
        if (pick != null && isEnforced(entity) && lerpsEveryTarget()) {
            boolean avatar = entity.type == EntityTypeIds.PLAYER;
            Class<? extends BedrockAttackVisibility> owner =
                    avatar ? BedrockAttackPlayerVisibility.class : BedrockAttackMobVisibility.class;
            String detail = avatar ? "" : "type=" + EntityTypeUtil.getKey(entity.type);
            if (player.checkManager.getCheck(owner).dropsAttack(hit, hitPrecision, detail)) return true;
        }

        if ((shouldModifyPackets() && cancelImpossibleHits && knownInvalid)
                || (tooManyAttacks && !CultAPI.INSTANCE.getConfigManager().isObserveOnly())) {
            debug(() -> {
                return "cancelled, many=" + tooManyAttacks + ", invalid=" + knownInvalid;
            });
            return true;
        }
        return false;
    }

    @Override
    public void onPlayerTickEnd(final PacketReceiveEvent event) {
        endClientTick();
    }

    /** Measures the attacks sent since the last client tick end with this tick's look. */
    public void endClientTick() {
        var frame = player.isBedrockMovement() && player.bedrockState != null
                ? player.bedrockState.getLastOfferedFrame()
                : null;
        if (frame != null && frame.hasRotation()) {
            nextYaw = frame.getYaw();
            nextPitch = frame.getPitch();
        }
        tickBetterReachCheckWithAngle();
        lastTickYaw = tickYaw;
        lastTickPitch = tickPitch;
        tickYaw = nextYaw;
        tickPitch = nextPitch;
        attacks = 0;
    }

    // The pick of a Bedrock client's attack sent since its last tick end, from its eye's path over its last two ticks.
    private BedrockAttackPick bedrockPick(Vec3 hit, double hitPrecision) {
        var frame = player.bedrockState == null ? null : player.bedrockState.getLastOfferedFrame();
        int inputMode = frame == null ? -1 : frame.getInputMode();
        return new BedrockAttackPick(
                new Vec3(player.lastX, player.lastY, player.lastZ),
                new Vec3(player.x, player.y, player.z),
                hit,
                hitPrecision,
                lastTickYaw,
                lastTickPitch,
                tickYaw,
                tickPitch,
                inputMode == BEDROCK_TOUCH || inputMode == BEDROCK_MOTION_CONTROLLER,
                bedrockRayLength(inputMode),
                bedrockEyes());
    }

    private List<BedrockEye> bedrockEyes() {
        return BedrockEye.of(player).eyes();
    }

    // The shortest ray the input mode may have used.
    private static double bedrockRayLength(int inputMode) {
        if (inputMode == BEDROCK_MOUSE) return BEDROCK_MOUSE_RAY;
        if (inputMode == BEDROCK_TOUCH || inputMode == BEDROCK_MOTION_CONTROLLER) return BEDROCK_OTHER_RAY;
        return BEDROCK_GAMEPAD_RAY;
    }

    /** The attacks this check measured at the client's last tick end, in packet order; kept while observed. */
    public List<MeasuredAttack> measuredAttacks() {
        if (measuredAttacks.isEmpty()) return List.of();
        List<MeasuredAttack> measured = new ArrayList<>(measuredAttacks.size());
        for (QueuedAttack attack : measuredAttacks) {
            if (attack.pick() != null) {
                bestDistance = attack.pick().reach(bedrockTarget(attack.pick(), measuredBox(attack.targetBox())));
            } else {
                measureBest(attack.targetBox(), attack.targetSize(), attack.fromCandidates(), attack.reach(), false);
            }
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
        if (!isEnforced(reachEntity)) return false;

        // Filter out what we assume to be cheats
        if (cancelBuffer != 0) {
            return checkReach(reachEntity, getAttackOrigins(), reach, true) != null; // If they flagged
        } else {
            SimpleCollisionBox targetBox = getReachBox(reachEntity);
            return ReachUtils.getMinReachToBox(player, targetBox) > reach.maxClosestDistance();
        }
    }

    // Whether a Bedrock client's attack lies beyond its range or reports a hit off the target's box; both are known
    // when it arrives, as neither needs the look its next tick sends.
    private boolean isKnownInvalidOnBedrock(BedrockAttackPick pick, SimpleCollisionBox reachBox, AttackReach reach) {
        SimpleCollisionBox box = measuredBox(reachBox);
        return pick.reach(bedrockTarget(pick, box)) - threshold > reach.maxClosestDistance() || !hitOnBox(pick, box);
    }

    // BedrockPlayerReach and BedrockEntityReach flag an attack whose hit lies beyond the client's range from every eye
    // on its path; their hitboxes checks one whose hit lies off the target's box, that no view the client could have
    // turned to that frame looks at, or that the client would have dropped for a block its ray met.
    private void judgeBedrock(QueuedAttack attack) {
        BedrockAttackPick pick = attack.pick();
        SimpleCollisionBox box = measuredBox(attack.targetBox());
        double distance = pick.reach(bedrockTarget(pick, box));
        double max = attack.reach().maxClosestDistance();
        boolean tooFar = attack.enforced() && distance - threshold > max;
        relayReach(attack, distance, max);
        if (!attack.enforced()) return;
        String type = attack.type() == EntityTypeIds.PLAYER
                ? ""
                : " type=" + EntityTypeUtil.getKey(attack.type()).getPath();
        if (tooFar) {
            flag(String.format("%.5f blocks, max=%.5f", distance, max) + type + attack.context());
        }
        String missed;
        if (!hitOnBox(pick, box)) {
            missed = "hit off box";
        } else if (!pick.looksAtHit(nextYaw, nextPitch, box)) {
            missed = "look off hit";
        } else if (keepsMarginHitsPastBlocks()) {
            missed = null;
        } else {
            SimpleCollisionBox raw = box.copy();
            raw.expand(-BEDROCK_PICK_MARGIN);
            boolean kept =
                    pick.keptPastBlocks(box, raw, (from, to, edge) -> BedrockPickBlocks.blocks(player, from, to, edge));
            missed = kept ? null : "hit in pick margin";
        }
        if (missed != null) {
            player.checkManager.getCheck(hitboxes()).flag((missed + type + attack.context()).strip());
        }
    }

    // Shows /cult debug reach listeners the reach measured above beside the one the client claims, from the eye it
    // reports with the attack to the hit it reports. calc is red when it exceeds the claim by more than
    // REACH_DEBUG_TOLERANCE, green otherwise, and gray when the client claimed no eye or hit. The hover holds the
    // points.
    private void relayReach(QueuedAttack attack, double distance, double max) {
        player.checkManager.getDebugHandler().relayReach(() -> {
            BedrockAttackPick pick = attack.pick();
            Vec3 eye = attack.claimedEye();
            Vec3 hit = pick.hit();
            String claimed = "none";
            String difference = "";
            NamedTextColor calcColor = NamedTextColor.GRAY;
            if (eye != null && hit != null) {
                double claimedReach = eye.distanceTo(hit);
                claimed = String.format(Locale.ROOT, "%.5f", claimedReach);
                difference = String.format(Locale.ROOT, " diff=%+.5f", distance - claimedReach);
                calcColor = distance - claimedReach > REACH_DEBUG_TOLERANCE ? NamedTextColor.RED : NamedTextColor.GREEN;
            }
            String details = "claimed eye " + debugPoint(eye) + "\nclaimed hit " + debugPoint(hit) + "\nfeet path "
                    + debugPoint(pick.last()) + " -> " + debugPoint(pick.now());
            return Component.text(EntityTypeUtil.getKey(attack.type()).getPath() + " calc=", NamedTextColor.GRAY)
                    .append(Component.text(String.format(Locale.ROOT, "%.5f", distance), calcColor))
                    .append(Component.text(" claimed=", NamedTextColor.GRAY))
                    .append(Component.text(claimed, NamedTextColor.WHITE))
                    .append(Component.text(
                            difference
                                    + String.format(Locale.ROOT, " max=%.5f", max)
                                    + (attack.enforced() ? "" : " unenforced"),
                            NamedTextColor.GRAY))
                    .hoverEvent(HoverEvent.showText(Component.text(details, NamedTextColor.GRAY)));
        });
    }

    private static String debugPoint(Vec3 point) {
        return point == null ? "none" : String.format(Locale.ROOT, "(%.5f, %.5f, %.5f)", point.x, point.y, point.z);
    }

    // The point of the box nearest the reported hit, or nearest the client's position when it reported none.
    private static Vec3 bedrockTarget(BedrockAttackPick pick, SimpleCollisionBox box) {
        Vec3 from = pick.hit() == null ? pick.now() : pick.hit();
        return new Vec3(
                Math.max(box.minX, Math.min(from.x, box.maxX)),
                Math.max(box.minY, Math.min(from.y, box.maxY)),
                Math.max(box.minZ, Math.min(from.z, box.maxZ)));
    }

    private static boolean hitOnBox(BedrockAttackPick pick, SimpleCollisionBox box) {
        Vec3 hit = pick.hit();
        double e = pick.hitPrecision();
        return hit != null
                && hit.x >= box.minX - e
                && hit.x <= box.maxX + e
                && hit.y >= box.minY - e
                && hit.y <= box.maxY + e
                && hit.z >= box.minZ - e
                && hit.z <= box.maxZ + e;
    }

    private void tickBetterReachCheckWithAngle() {
        for (QueuedAttack attack : playerAttackQueue) {
            if (attack.pick() != null) {
                judgeBedrock(attack);
                continue;
            }
            String result = checkReach(
                    attack.targetBox(),
                    attack.targetSize(),
                    attack.type(),
                    attack.enforced(),
                    attack.fromCandidates(),
                    attack.reach(),
                    false);
            if (MISSED_HITBOX.equals(result)) {
                String verbose = attack.type() == EntityTypeIds.PLAYER
                        ? attack.context()
                        : "type=" + EntityTypeUtil.getKey(attack.type()) + attack.context();
                player.checkManager.getCheck(hitboxes()).flag(verbose.strip());
            } else if (result != null) {
                if (attack.type() != EntityTypeIds.PLAYER) {
                    result += " type=" + EntityTypeUtil.getKey(attack.type()).getPath();
                }
                flag(result + attack.context());
            }
        }

        measuredAttacks.clear();
        if (player.observer != null) measuredAttacks.addAll(playerAttackQueue);
        playerAttackQueue.clear();
    }

    // Riding and scale change the modeled geometry; named in the verbose only when they apply.
    private String describeContext(PacketEntity target) {
        StringBuilder context = new StringBuilder();
        PacketEntity self = player.compensatedEntities.getSelf();
        if (self.getRiding() != null) {
            context.append(" vehicle=")
                    .append(EntityTypeUtil.getKey(self.getRiding().type).getPath());
        }
        if (target.getRiding() != null) {
            context.append(" target-vehicle=")
                    .append(EntityTypeUtil.getKey(target.getRiding().type).getPath());
        }
        appendScale(context, " scale=", self.scale);
        appendScale(context, " target-scale=", target.scale);
        return context.toString();
    }

    // Compared at printed precision so attribute float noise never logs a default "1.0000".
    private static void appendScale(StringBuilder context, String key, float scale) {
        String text = String.format("%.4f", scale);
        if (!text.equals(DEFAULT_SCALE_TEXT)) {
            context.append(key).append(text);
        }
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
                getReachSize(reachEntity),
                reachEntity.type,
                isEnforced(reachEntity),
                fromCandidates,
                reach,
                isPrediction);
    }

    /** {@code reachEntity}'s lag-compensated box as the client's pick sees it. */
    public SimpleCollisionBox getReachBox(PacketEntity reachEntity) {
        SimpleCollisionBox box = getTargetBox(reachEntity);
        if (player.isBedrockMovement()) box.expand(BEDROCK_PICK_MARGIN);
        return box;
    }

    // A Bedrock attack on a ravager is measured on one box, 1.95 wide and 2.2 tall from its feet at any scale, instead
    // of the box its size data gives it.
    private SimpleCollisionBox ravagerPickBox(PacketEntity ravager, SimpleCollisionBox movementBox) {
        double grow = (BEDROCK_RAVAGER_PICK_WIDTH - BoundingBoxSize.getWidth(player, ravager)) / 2.0D;
        double feetTop = movementBox.maxY - BoundingBoxSize.getHeight(player, ravager);
        return new SimpleCollisionBox(
                movementBox.minX - grow,
                movementBox.minY,
                movementBox.minZ - grow,
                movementBox.maxX + grow,
                feetTop + BEDROCK_RAVAGER_PICK_HEIGHT,
                movementBox.maxZ + grow);
    }

    private SimpleCollisionBox getTargetBox(PacketEntity reachEntity) {
        if (reachEntity.type == EntityTypeIds.END_CRYSTAL && !player.isBedrockMovement()) { // Hardcode end crystal box
            return new SimpleCollisionBox(
                    reachEntity.desyncClientPos.subtract(1, 0, 1), reachEntity.desyncClientPos.add(1, 2, 1));
        }

        // For a Bedrock client Cult models float positions and the range of positions a pending move spans; the
        // packet-position margin covers neither.
        SimpleCollisionBox movementBox = reachEntity.toBoundingBox(
                player,
                player.isBedrockMovement()
                        ? reachEntity.getPossibleMovementCollisionBoxes()
                        : reachEntity.getPossibleCollisionBoxes());
        if (player.isBedrockMovement() && reachEntity.type == EntityTypeIds.RAVAGER)
            return ravagerPickBox(reachEntity, movementBox);
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

    // The size of each possible box getReachBox spans, at most its real size.
    private Vec3 getReachSize(PacketEntity reachEntity) {
        if (reachEntity.type == EntityTypeIds.END_CRYSTAL && !player.isBedrockMovement()) return new Vec3(2, 2, 2);
        if (reachEntity.isAvatar()) {
            double width = reachEntity.leastPoseWidth();
            return new Vec3(width, reachEntity.leastPoseHeight(), width);
        }
        // BoundingBoxSize scales linearly; the camel's sitting, the goat's long jump and the warden's digging and
        // emerging poses still take the standing size.
        double least = reachEntity.leastScale() / reachEntity.scale;
        double width = least
                * Math.max(
                        BoundingBoxSize.getWidth(player, reachEntity),
                        BoundingBoxSize.getReachWidth(player, reachEntity));
        double height = least
                * Math.max(
                        BoundingBoxSize.getHeight(player, reachEntity),
                        BoundingBoxSize.getReachHeight(player, reachEntity));
        return new Vec3(width, height, width);
    }

    private String checkReach(
            SimpleCollisionBox targetBox,
            Vec3 targetSize,
            int type,
            boolean enforced,
            List<Vec3> fromCandidates,
            AttackReach reach,
            boolean isPrediction) {
        targetBox = measuredBox(targetBox);

        measureBest(targetBox, targetSize, fromCandidates, reach, isPrediction);

        if (enforced) {
            if (bestDistance == Double.POSITIVE_INFINITY) {
                cancelBuffer = 1;
                return MISSED_HITBOX;
            } else if (excess() > 0) {
                cancelBuffer = 1;
                return bestFar < bestMin
                        ? String.format("%.5f blocks, min=%.5f", bestFar, bestMin)
                        : String.format("%.5f blocks, max=%.5f", bestDistance, bestMax);
            } else {
                cancelBuffer = Math.max(0, cancelBuffer - 0.25);
            }
        }

        return null;
    }

    /** Measures the least-flagging selection of the look at {@code targetBox} from the eyes at {@code fromCandidates}. */
    private void measureBest(
            SimpleCollisionBox targetBox,
            Vec3 targetSize,
            List<Vec3> fromCandidates,
            AttackReach reach,
            boolean isPrediction) {
        this.targetSize = targetSize;
        bestDistance = Double.POSITIVE_INFINITY;
        bestFar = Double.POSITIVE_INFINITY;
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
        float cosPitch = player.trigHandler.clientCos(pitchRadians);
        looks[count * 3] = player.trigHandler.clientSin(yawRadians) * cosPitch;
        looks[count * 3 + 1] = -player.trigHandler.clientSin(pitchRadians);
        looks[count * 3 + 2] = player.trigHandler.clientCos(yawRadians) * cosPitch;
        return count + 1;
    }

    private double excess() {
        return Math.max(bestDistance - bestMax, bestMin - bestFar);
    }

    // The maximum bounds the nearest possible hit and the minimum the farthest.
    private void consider(double distance, double far, double min, double max) {
        if (Math.max(distance - max, min - far) < excess()) {
            bestDistance = distance;
            bestFar = far;
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
        double far = farthest(box, distance, eyeX, eyeY, eyeZ, eyeX, eyeY, eyeZ, dx, dy, dz);
        if (reach.hasItemLimit()) {
            consider(distance, far, reach.itemMin(), Math.min(reach.entityRange(), reach.itemLimit()));
        } else {
            consider(distance, far, Double.NEGATIVE_INFINITY, reach.entityRange());
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
                double distance = distance(eyeX, eyeY, eyeZ, fromX, fromY, fromZ);
                double length = pick.rayEnd() - pick.rayStart();
                measureHit(
                        box,
                        pick.hitboxMargin(),
                        distance,
                        eyeX,
                        eyeY,
                        eyeZ,
                        fromX,
                        fromY,
                        fromZ,
                        lookX * length,
                        lookY * length,
                        lookZ * length,
                        min,
                        max);
                continue;
            }

            double[] movements = pick.movements();
            for (int m = 0; m < movements.length; m += 3) {
                double along = movements[m] * lookX + movements[m + 1] * lookY + movements[m + 2] * lookZ;
                double end = pick.rayEnd() + Math.max(0.0, along);
                double dx = eyeX + lookX * end - fromX;
                double dy = eyeY + lookY * end - fromY;
                double dz = eyeZ + lookZ * end - fromZ;
                if (hitAlong(box, pick.hitboxMargin(), fromX, fromY, fromZ, dx, dy, dz)) {
                    double distance = distance(eyeX, eyeY, eyeZ, hitX, hitY, hitZ);
                    measureHit(
                            box,
                            pick.hitboxMargin(),
                            distance,
                            eyeX,
                            eyeY,
                            eyeZ,
                            fromX,
                            fromY,
                            fromZ,
                            dx,
                            dy,
                            dz,
                            min,
                            max);
                } else if (!measureInsideMargin(
                        box, pick.hitboxMargin(), eyeX, eyeY, eyeZ, fromX, fromY, fromZ, dx, dy, dz, min, max)) {
                    measureShort(box, pick, fromX, fromY, fromZ, eyeX, eyeY, eyeZ, lookX, lookY, lookZ, end);
                }
            }
        }
    }

    // The move's hit at distance on box. A move meeting every box of targetSize within box hits the target directly, no
    // nearer than distance and no farther than its latest entry into one. Otherwise the target may be reached only
    // through its margin, by a projected hit within box, which box's nearest point and farthest corner bound.
    private void measureHit(
            SimpleCollisionBox box,
            float margin,
            double distance,
            double eyeX,
            double eyeY,
            double eyeZ,
            double x,
            double y,
            double z,
            double dx,
            double dy,
            double dz,
            double min,
            double max) {
        if (margin > 0 && !meetsEveryBox(box, x, y, z, dx, dy, dz)) {
            consider(nearest(box, eyeX, eyeY, eyeZ), farthestCorner(box, eyeX, eyeY, eyeZ), min, max);
        } else {
            consider(distance, farthest(box, distance, eyeX, eyeY, eyeZ, x, y, z, dx, dy, dz), min, max);
        }
    }

    // Whether the move meets the part of box that every box of targetSize within it holds.
    private boolean meetsEveryBox(
            SimpleCollisionBox box, double x, double y, double z, double dx, double dy, double dz) {
        double enter = 0;
        double exit = 1;
        for (int axis = 0; axis < 3; axis++) {
            double size = axis(axis, targetSize.x, targetSize.y, targetSize.z);
            double low = axis(axis, box.maxX, box.maxY, box.maxZ) - size;
            double high = axis(axis, box.minX, box.minY, box.minZ) + size;
            double from = axis(axis, x, y, z);
            double move = axis(axis, dx, dy, dz);
            if (low > high) return false;
            if (Math.abs(move) <= 1.0E-7) {
                if (from < low || from > high) return false;
                continue;
            }
            double a = (low - from) / move;
            double b = (high - from) / move;
            enter = Math.max(enter, Math.min(a, b));
            exit = Math.min(exit, Math.max(a, b));
        }
        return enter <= exit;
    }

    // A move starting within margin of box but outside it can still enter the margin of a box of targetSize within box
    // and hit it by projection: such a hit lies in box, so box's nearest point and farthest corner bound it.
    private boolean measureInsideMargin(
            SimpleCollisionBox box,
            float margin,
            double eyeX,
            double eyeY,
            double eyeZ,
            double x,
            double y,
            double z,
            double dx,
            double dy,
            double dz,
            double min,
            double max) {
        if (margin <= 0 || !contains(box.copy().expand(margin), x, y, z)) return false;
        if (Double.isNaN(farthestEntry(box, margin, x, y, z, dx, dy, dz))) return false;
        consider(nearest(box, eyeX, eyeY, eyeZ), farthestCorner(box, eyeX, eyeY, eyeZ), min, max);
        return true;
    }

    // The distance from the eye to box's nearest point.
    private static double nearest(SimpleCollisionBox box, double eyeX, double eyeY, double eyeZ) {
        double nearX = Math.max(box.minX, Math.min(eyeX, box.maxX));
        double nearY = Math.max(box.minY, Math.min(eyeY, box.maxY));
        double nearZ = Math.max(box.minZ, Math.min(eyeZ, box.maxZ));
        return distance(eyeX, eyeY, eyeZ, nearX, nearY, nearZ);
    }

    // The distance from the eye to box's farthest corner.
    private static double farthestCorner(SimpleCollisionBox box, double eyeX, double eyeY, double eyeZ) {
        double x = Math.max(Math.abs(box.minX - eyeX), Math.abs(box.maxX - eyeX));
        double y = Math.max(Math.abs(box.minY - eyeY), Math.abs(box.maxY - eyeY));
        double z = Math.max(Math.abs(box.minZ - eyeZ), Math.abs(box.maxZ - eyeZ));
        return Math.sqrt(x * x + y * y + z * z);
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
        consider(lookLength * along, lookLength * along, Double.NEGATIVE_INFINITY, lookLength * end);
    }

    // The farthest from the eye that the move directly hits a box of targetSize within box, and at least distance.
    private double farthest(
            SimpleCollisionBox box,
            double distance,
            double eyeX,
            double eyeY,
            double eyeZ,
            double x,
            double y,
            double z,
            double dx,
            double dy,
            double dz) {
        double scale = farthestEntry(box, 0, x, y, z, dx, dy, dz);
        if (Double.isNaN(scale)) return distance;
        return Math.max(distance, distance(eyeX, eyeY, eyeZ, x + scale * dx, y + scale * dy, z + scale * dz));
    }

    // The latest fraction of the move at which it enters a box of targetSize, both grown by margin, that lies within
    // box grown by margin; NaN when it enters none. The face it enters through lies between the near side and the size
    // short of the far side.
    private double farthestEntry(
            SimpleCollisionBox box, double margin, double x, double y, double z, double dx, double dy, double dz) {
        double latest = Double.NaN;
        for (int entering = 0; entering < 3; entering++) {
            if (Math.abs(axis(entering, dx, dy, dz)) <= 1.0E-7) continue;
            double enter = 0;
            double exit = 1;
            for (int axis = 0; axis < 3 && enter <= exit; axis++) {
                double low = axis(axis, box.minX, box.minY, box.minZ) - margin;
                double high = axis(axis, box.maxX, box.maxY, box.maxZ) + margin;
                double from = axis(axis, x, y, z);
                double move = axis(axis, dx, dy, dz);
                if (axis == entering) {
                    double size = axis(axis, targetSize.x, targetSize.y, targetSize.z) + 2 * margin;
                    double inset = Math.min(size, high - low);
                    if (move > 0) high -= inset;
                    else low += inset;
                }
                if (Math.abs(move) <= 1.0E-7) {
                    if (from < low || from > high) enter = Double.POSITIVE_INFINITY;
                    continue;
                }
                double a = (low - from) / move;
                double b = (high - from) / move;
                enter = Math.max(enter, Math.min(a, b));
                exit = Math.min(exit, Math.max(a, b));
            }
            if (enter <= exit && !(latest >= exit)) latest = exit;
        }
        return latest;
    }

    private static double axis(int axis, double x, double y, double z) {
        return axis == 0 ? x : axis == 1 ? y : z;
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

    /**
     * Whether this check measures this player's attack on the target sent now and flags it when beyond reach, on
     * itself or, for a missed hitbox, on its hitboxes check; it also cancels it as Reach.block-impossible-hits and
     * packet modification allow.
     */
    public boolean enforcesReach(PacketEntity target) {
        return isEnabled()
                && !isExemptPermission()
                && !player.checkManager.getCheck(hitboxes()).isExemptPermission()
                && !player.isDisabled()
                && owns(target.type)
                && playerAttackQueue.size() <= 10
                && measured(target)
                && isEnforced(target);
    }

    /**
     * Whether the attack on the target sent now lies beyond reach from every attack origin and eye: the box the tick
     * end measures is farther from each than any range the client's picks allow, so that measurement flags it as
     * beyond reach or as a missed hitbox.
     */
    public boolean measuresBeyondReach(PacketEntity target) {
        SimpleCollisionBox box = measuredBox(target);
        double max = AttackReach.capture(player, visibility).maxClosestDistance();
        double scale = player.getScale();
        for (Vec3 from : getAttackOrigins()) {
            for (double height : EYE_HEIGHTS) {
                if (nearest(box, from.x, from.y + height * scale, from.z) <= max) return false;
            }
        }
        return true;
    }

    /** The target's reach box grown by the threshold, as this check measures attacks on it. */
    public SimpleCollisionBox measuredBox(PacketEntity target) {
        return measuredBox(getReachBox(target));
    }

    private SimpleCollisionBox measuredBox(SimpleCollisionBox reachBox) {
        SimpleCollisionBox box = reachBox.copy();
        box.expand(threshold);
        box.expand(player.getMovementThreshold());
        return box;
    }

    private boolean measured(PacketEntity entity) {
        // Dead entities cause false flags (https://github.com/GrimAnticheat/Grim/issues/546)
        if (entity.isDead) return false;

        if (entity.type == EntityTypeIds.ARMOR_STAND) return false;

        // Riders outside the local player's own riding tree have no simulated position.
        if (player.compensatedEntities.isUntrackedRider(entity)) return false;

        return player.gamemode != GameMode.SPECTATOR;
    }

    // The targets whose picked box Cult models: living entities but shulkers, end crystals, and boats, which
    // AbstractBoat moves only by LinearInterpolationHandler's 3 steps to the position the server sent.
    private boolean isEnforced(PacketEntity reachEntity) {
        if (player.isBedrockMovement()) return isEnforcedOnBedrock(reachEntity);
        if (reachEntity.type == EntityTypeIds.END_CRYSTAL) return true;
        if (reachEntity.isBoat()) return true;
        return reachEntity.isLivingEntity() && reachEntity.type != EntityTypeIds.SHULKER;
    }

    // Cult judges a Bedrock attack on a player on every version, and on mobs, boats, minecarts and end crystals from
    // 1.26.60 on, whose positions it models from then; shulkers and the ender dragon are left out, as are mounted
    // actors and attacks while mounted.
    private boolean isEnforcedOnBedrock(PacketEntity target) {
        if (target.getRiding() != null
                || !target.passengers.isEmpty()
                || player.compensatedEntities.getSelf().getRiding() != null) return false;
        if (target.type == EntityTypeIds.PLAYER) return true;
        if (!lerpsEveryTarget()) return false;
        if (target.type == EntityTypeIds.END_CRYSTAL || target.isBoat() || target.isMinecart()) return true;
        return target.isLivingEntity()
                && target.type != EntityTypeIds.SHULKER
                && target.type != EntityTypeIds.ENDER_DRAGON;
    }

    // Whether a hit only in the pick margin counts whatever blocks its ray meets: before 1.26.60.
    private boolean keepsMarginHitsPastBlocks() {
        return player.bedrockState == null
                || player.bedrockState.getProtocolVersion().compareTo(BedrockProtocolVersion.V1_26_60) < 0;
    }

    private boolean lerpsEveryTarget() {
        return player.bedrockState != null
                && player.bedrockState.getProtocolVersion().compareTo(BedrockProtocolVersion.V1_26_60) >= 0;
    }

    @Override
    public void reload() {
        super.reload();
        this.cancelImpossibleHits = getConfig().getBooleanElse("Reach.block-impossible-hits", true);
        this.threshold = getConfig().getDoubleElse("Reach.threshold", 0.0005);
    }
}
