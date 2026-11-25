package ac.cult.cultac.utils.visibility;

import ac.cult.blocksim.data.ItemComponents;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.blockplace.ClientEntityQueries;
import ac.cult.cultac.utils.collisions.ClientBlockShapes;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.TransactionVel;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BoundingBoxSize;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** Selection geometry shared by block use, digging, entity attacks and entity interactions. */
public final class ClientVisibility {
    private static final double CLIP_EPSILON = 1.0E-7;
    private final CultPlayer player;

    private record Selection(String heldItem, ItemComponents.AttackRange attackRange) {}

    private final List<Selection> selections = new ArrayList<>();
    private Selection activeSelection;

    public void beginSelection() {
        player.compensatedWorld.beginSelectionSnapshot();
        if (selections.isEmpty()) {
            var held = player.getInventory().getClientSelectedHeldItem();
            selections.add(new Selection(
                    held == null || held.isEmpty() ? "minecraft:air" : held.itemKey(), currentAttackRange()));
        }
    }

    public void onSlotChange(int slot) {
        beginSelection();
        if (slot < 0 || slot > 8) return;
        var held = player.getInventory().inventory.getInventoryStorage().getItem(36 + slot);
        var attack = player.isClientUsingItem()
                ? selections.getFirst().attackRange()
                : held == null || held.isEmpty() ? null : ItemComponents.attackRange(held.components());
        var candidate = new Selection(held == null || held.isEmpty() ? "minecraft:air" : held.itemKey(), attack);
        // Keyboard hotbar selection happens after pick; MouseHandler.onScroll can
        // change it before pick. The selected-slot packet does not identify which.
        if (!selections.contains(candidate)) selections.add(candidate);
    }

    /**
     * An item AttackRange LocalPlayer#raycastHitResult may have picked with, for one camera: ProjectileUtil#getHitEntitiesAlong's
     * ray runs from {@code rayStart} looks to {@code rayEnd} looks plus the positive part of one of the camera's possible
     * known {@code movements} (x, y and z in turn) along the look. {@code rayScale} bounds every pick's ray length.
     */
    public record ItemPick(float hitboxMargin, double rayStart, double rayEnd, double rayScale, double[] movements) {}

    /** The item picks Minecraft#pick may have made before this tick's attack. */
    public List<ItemPick> attackItemPicks() {
        beginSelection();
        List<ItemPick> picks = new ArrayList<>();
        double[] selfMovements = null;
        for (Selection selection : selections) {
            var attack = selection.attackRange();
            if (attack == null) continue;
            double rayScale = nativeItemRayScale(attack);
            for (PacketEntity camera : player.cameraEntity.getPossibilities()) {
                if (camera == player.compensatedEntities.getSelf()) {
                    if (selfMovements == null) selfMovements = knownMovements();
                    boolean creative = player.gamemode == GameMode.CREATIVE;
                    float min = creative ? attack.minCreativeReach() : attack.minReach();
                    float max = creative ? attack.maxCreativeReach() : attack.maxReach();
                    picks.add(new ItemPick(attack.hitboxMargin(), min, max, rayScale, selfMovements));
                    continue;
                }
                Vec3 movement = camera.deltaMovement;
                double[] movements = {movement.x, movement.y, movement.z};
                if (camera.type == EntityTypeIds.PLAYER) {
                    // AttackRange#effectiveMinRange/#effectiveMaxRange read the camera player's own game mode.
                    picks.add(new ItemPick(
                            attack.hitboxMargin(), attack.minReach(), attack.maxReach(), rayScale, movements));
                    picks.add(new ItemPick(
                            attack.hitboxMargin(),
                            attack.minCreativeReach(),
                            attack.maxCreativeReach(),
                            rayScale,
                            movements));
                } else {
                    picks.add(new ItemPick(
                            attack.hitboxMargin(),
                            attack.minReach() * attack.mobFactor(),
                            attack.maxReach() * attack.mobFactor(),
                            rayScale,
                            movements));
                }
            }
        }
        return picks;
    }

    // Entity#getKnownMovement at the pick, x, y and z in turn: what the last tick left, then in wire order each motion
    // handled since (Entity#lerpMotion replaces it) and explosion (Entity#push adds to it), or not yet when unanswered.
    private double[] knownMovements() {
        int tickEnd = player.packetStateData.lastClientTickEndTransaction;
        var knockback = player.checkManager.getKnockbackHandler();
        var explosion = player.checkManager.getExplosionHandler();
        TransactionVel motion = knockback.secondBread;
        TransactionVel push = explosion.secondBread;
        List<Vec3> left = new ArrayList<>();
        // Tick ends are ordered by receipt ping; a motion and an explosion proven by one shared ping are ordered by
        // their outbound sequence.
        boolean motionSinceTickEnd = motion != null && motion.getTransaction() > tickEnd;
        if (motionSinceTickEnd) {
            left.add(motion.getVel());
        } else {
            left.addAll(player.checkManager.getSimulationProcessor().getLastDerivedNextTickVelocities());
            if (left.isEmpty()) left.add(Vec3.ZERO);
            // Handled before the last tick end, by a tick no prediction committed.
            if (motion != null) left.add(motion.getVel());
            if (push != null && push.getTransaction() <= tickEnd) {
                for (int i = 0, size = left.size(); i < size; i++)
                    left.add(left.get(i).add(push.getVel()));
            }
        }
        // An explosion's velocity sums the explosions since the tick end or the last motion.
        boolean pushSinceReplacement = push != null
                && (motionSinceTickEnd
                        ? push.getOutboundSequence() > motion.getOutboundSequence()
                        : push.getTransaction() > tickEnd);
        Vec3 pushed = pushSinceReplacement ? push.getVel() : Vec3.ZERO;
        TransactionVel pendingMotion = knockback.firstBread;
        TransactionVel pendingPush = explosion.firstBread;
        int count = left.size() * (pendingPush == null ? 1 : 2) + (pendingMotion == null ? 0 : 1);
        double[] movements = new double[3 * count];
        int index = 0;
        for (Vec3 velocity : left) {
            index = putMovement(movements, index, velocity.add(pushed));
            if (pendingPush != null) index = putMovement(movements, index, velocity.add(pendingPush.getVel()));
        }
        if (pendingMotion != null) {
            Vec3 velocity = pendingMotion.getVel();
            if (pendingPush != null && pendingPush.getOutboundSequence() > pendingMotion.getOutboundSequence())
                velocity = velocity.add(pendingPush.getVel());
            putMovement(movements, index, velocity);
        }
        return movements;
    }

    private static int putMovement(double[] movements, int index, Vec3 velocity) {
        return putMovement(movements, index, velocity.x, velocity.y, velocity.z);
    }

    private static int putMovement(double[] movements, int index, double x, double y, double z) {
        movements[index] = x;
        movements[index + 1] = y;
        movements[index + 2] = z;
        return index + 3;
    }

    private boolean hiddenForEverySelection(Supplier<Boolean> query) {
        if (selections.isEmpty()) return query.get();
        Selection previous = activeSelection;
        try {
            for (Selection selection : selections) {
                activeSelection = selection;
                if (!player.compensatedWorld.withSelectionHeldItemKey(selection.heldItem(), query)) return false;
            }
            return true;
        } finally {
            activeSelection = previous;
        }
    }

    public void endSelection() {
        selections.clear();
        activeSelection = null;
        player.compensatedWorld.endSelectionSnapshot();
    }

    // Ender dragon parts lie within this distance of the dragon's box (EnderDragon#aiStep's part offsets).
    private static final double DRAGON_PART_REACH = 8;
    private PacketEntity tickEndSelf;
    private double tickEndX, tickEndY, tickEndZ;
    private List<Vec3> tickEndSeatEyes = List.of();

    public ClientVisibility(CultPlayer player) {
        this.player = player;
    }

    public enum BlockVisibility {
        OUT_OF_RANGE,
        VISIBLE,
        OCCLUDED
    }

    /** Current-world diagnostic query with its own radius, without a claimed face or selection snapshot. */
    public BlockVisibility blockVisibility(BlockPos position, double range) {
        List<SimpleCollisionBox> targets = selectionBoxes(position);
        boolean inRange = false;
        for (Vec3 eye : eyes(0, targets, position, range)) {
            if (targets.isEmpty()) {
                inRange |= withinRange(eye, new SimpleCollisionBox(position), range);
            }
            for (SimpleCollisionBox target : targets) {
                SimpleCollisionBox envelope = target.copy();
                if (!withinRange(eye, envelope, range)) continue;
                inRange = true;
                if (OcclusionGeometry.hasVisiblePoint(
                        eye, envelope, null, blockers(eye, envelope, position, range), null, range))
                    return BlockVisibility.VISIBLE;
            }
        }
        return inRange ? BlockVisibility.OCCLUDED : BlockVisibility.OUT_OF_RANGE;
    }

    public boolean blockFaceHidden(BlockPos position, Direction face, double positionError) {
        return hiddenForEverySelection(() -> blockFaceHiddenForSelection(position, face, positionError));
    }

    private boolean blockFaceHiddenForSelection(BlockPos position, Direction face, double positionError) {
        List<SimpleCollisionBox> targets = selectionBoxes(position);
        int state = player.compensatedWorld.getBlockStateIdAt(position);
        List<SimpleCollisionBox> interactions = new ArrayList<>();
        for (var box :
                player.compensatedWorld.geometry().interaction(state, position).boxes()) {
            interactions.add(new SimpleCollisionBox(
                    box.minX() + position.x(),
                    box.minY() + position.y(),
                    box.minZ() + position.z(),
                    box.maxX() + position.x(),
                    box.maxY() + position.y(),
                    box.maxZ() + position.z()));
        }
        double error = CLIP_EPSILON;
        double range = player.compensatedEntities.getSelf().getBlockInteractionRange();
        double minimumSample = Math.nextDown(.001 * Math.sqrt(CLIP_EPSILON));
        double maximumSample = Math.nextUp(.001 * nativePickRange());
        for (Vec3 eye : eyes(positionError, targets, position, range)) {
            List<SimpleCollisionBox> overrides = new ArrayList<>();
            boolean insideInteraction = false;
            for (SimpleCollisionBox interaction : interactions) {
                if (frontFacing(face, eye, interaction, error)) overrides.add(faceEnvelope(interaction, face));
                insideInteraction = insideInteraction
                        || NativeInsideSelection.possible(
                                eye, interaction, face, error, minimumSample, maximumSample, range);
            }
            for (SimpleCollisionBox target : targets) {
                SimpleCollisionBox envelope = target.copy();
                if (!withinRange(eye, envelope, range)) continue;
                List<SimpleCollisionBox> blockers = blockers(eye, envelope, position, range);
                if (NativeInsideSelection.possible(eye, target, face, error, minimumSample, maximumSample, range)
                        && !OcclusionGeometry.fullyOccluded(eye, envelope, blockers)) return false;
                if (contains(target, eye, 0)) continue;
                for (SimpleCollisionBox other : targets)
                    if (other != target) {
                        SimpleCollisionBox interior = other.copy();
                        if (!interior.isEmpty() && !contains(interior, eye, 0)) blockers.add(interior);
                    }
                if (frontFacing(face, eye, target, error)
                        && OcclusionGeometry.hasVisiblePoint(
                                eye, faceEnvelope(target, face), null, blockers, null, range)) return false;
                // BlockGetter.clipWithInteractionOverride retains the outline hit
                // location but can report the earlier interaction shape's face.
                if (!overrides.isEmpty()
                        && OcclusionGeometry.hasVisiblePoint(eye, envelope, null, blockers, overrides, range))
                    return false;
                // When VoxelShape.clip's sample is inside the interaction shape (an eye inside scaffolding), it
                // reports the face opposite the ray's dominant axis, so that same ray must reach the outline.
                if (insideInteraction
                        && OcclusionGeometry.hasVisiblePointInCone(
                                eye, envelope, blockers, NativeInsideSelection.rays(face), range)) return false;
            }
        }
        return true;
    }

    private static SimpleCollisionBox faceEnvelope(SimpleCollisionBox target, Direction face) {
        SimpleCollisionBox envelope = target.copy();
        double coordinate = switch (face) {
            case WEST -> target.minX;
            case EAST -> target.maxX;
            case DOWN -> target.minY;
            case UP -> target.maxY;
            case NORTH -> target.minZ;
            case SOUTH -> target.maxZ;
        };
        switch (face) {
            case WEST, EAST -> {
                envelope.minX = coordinate;
                envelope.maxX = coordinate;
            }
            case DOWN, UP -> {
                envelope.minY = coordinate;
                envelope.maxY = coordinate;
            }
            case NORTH, SOUTH -> {
                envelope.minZ = coordinate;
                envelope.maxZ = coordinate;
            }
        }
        return envelope;
    }

    /**
     * How LocalPlayer#pick may see an entity: some eye selects it, some eye within a pick range sees only blocks before
     * it, or every eye is beyond every pick range, where filterHitResult alone drops the hit.
     */
    public enum Sight {
        VISIBLE,
        BLOCKED,
        OUT_OF_RANGE
    }

    public boolean entityHidden(PacketEntity entity, double positionError) {
        return entitySight(entity, positionError) != Sight.VISIBLE;
    }

    /** The entity's sight under every selection this tick: any selection that selects it makes it visible. */
    public Sight entitySight(PacketEntity entity, double positionError) {
        // ProjectileUtil#getEntityHitResult clips the physical bounding box,
        // inflated only by the native pick radius/item margin. Reach's separate
        // 0.03125 expansion can invent an exposed point across a solid corner.
        boolean[] inRange = {false};
        if (!hiddenForEverySelection(() -> entityHidden(
                entity.toBoundingBox(player, entity.getPossibleMovementCollisionBoxes()),
                positionError,
                pickRadius(entity),
                inRange))) return Sight.VISIBLE;
        return inRange[0] ? Sight.BLOCKED : Sight.OUT_OF_RANGE;
    }

    /** Whether no selection selects any part of {@code box}, a box the entity's picked box may lie in. */
    public boolean entityHidden(PacketEntity entity, SimpleCollisionBox box, double positionError) {
        return hiddenForEverySelection(() -> entityHidden(box, positionError, pickRadius(entity), null));
    }

    private static double pickRadius(PacketEntity entity) {
        return EntityTypeUtil.modelType(entity.type).has(EntityTypes.PROJECTILE) ? 1.0F : 0;
    }

    public boolean entityHidden(SimpleCollisionBox target, double positionError) {
        return hiddenForEverySelection(() -> entityHidden(target, positionError, 0, null));
    }

    public boolean entityInteractionHidden(PacketEntity entity, double positionError) {
        double pickRadius = EntityTypeUtil.modelType(entity.type).has(EntityTypes.PROJECTILE) ? 1.0F : 0;
        return hiddenForEverySelection(() -> {
            var target = entity.toBoundingBox(player, entity.getPossibleMovementCollisionBoxes());
            var self = player.compensatedEntities.getSelf();
            double range = self.getEntityInteractionRange();
            if (!(range > 0)) return true;
            // Minecraft#startUseItem checks Player#isWithinEntityInteractionRange:
            // the uninflated entity box, measured from the player's own eye even
            // with another camera. Weapon reach cannot extend this range.
            for (Vec3 eye : eyes(positionError, List.of(target), null, range, List.of(self))) {
                if (!withinRange(eye, target, range)) continue;
                // LocalPlayer#raycastHitResult supplies the same item-dependent
                // selection for attacks and uses, including native pick margins.
                return entityHidden(target, positionError, pickRadius, null);
            }
            return true;
        });
    }

    /**
     * Whether Player#isWithinEntityInteractionRange holds for {@code entity} at every position the client may hold it
     * and every eye the player may have: Minecraft#startUseItem then sends MultiPlayerGameMode#interact for it.
     */
    public boolean entityWithinInteractionRange(PacketEntity entity, double positionError) {
        double range = player.compensatedEntities.getSelf().getEntityInteractionRange();
        if (!(range > 0)) return false;
        double error = EyePositionTolerance.BUFFER + positionError;
        var envelope = entity.toBoundingBox(
                player, entity.getPossibleCollisionBoxes().union(entity.getPossibleMovementCollisionBoxes()));
        double width = Double.MAX_VALUE, height = Double.MAX_VALUE, depth = Double.MAX_VALUE;
        for (SimpleCollisionBox candidate : entity.getPossibleMovementCollisionBoxCandidates()) {
            width = Math.min(width, candidate.maxX - candidate.minX);
            height = Math.min(height, candidate.maxY - candidate.minY);
            depth = Math.min(depth, candidate.maxZ - candidate.minZ);
        }
        List<Vec3> eyes = new ArrayList<>();
        addSelfEyes(eyes, new ArrayList<>());
        for (Vec3 eye : eyes) {
            double x = farthestGap(eye.x, error, envelope.minX, envelope.maxX, width);
            double y = farthestGap(eye.y, error, envelope.minY, envelope.maxY, height);
            double z = farthestGap(eye.z, error, envelope.minZ, envelope.maxZ, depth);
            if (x * x + y * y + z * z >= range * range) return false;
        }
        return true;
    }

    /** The largest gap along one axis from an eye within {@code error} to a box of {@code size} inside [min, max]. */
    private static double farthestGap(double eye, double error, double min, double max, double size) {
        double length = Math.min(size, max - min);
        return Math.max(0, Math.max((max - length) - (eye - error), (eye + error) - (min + length)));
    }

    /**
     * Whether LocalPlayer#raycastHitResult, from the local player's eye, may have selected {@code target}, an entity
     * that shares the player's Entity#getRootVehicle. ProjectileUtil#getEntityHitResult selects such an entity only
     * from inside its box, or along the ray once the box of any entity iterated before it holds the eye;
     * AttackRange#getClosesetHit selects it like any other entity.
     */
    public boolean ownVehicleTreePickPossible(PacketEntity target, double positionError) {
        if (!hiddenForEverySelection(() -> itemPickHidden(pickEnvelope(target), positionError))) return true;
        double error = EyePositionTolerance.BUFFER + positionError;
        List<Vec3> eyes = tickStartEyes();
        var self = player.compensatedEntities.getSelf();
        var queries = new ClientEntityQueries(player);
        // Through 1.21.3 Level#getEntities lists an ender dragon's parts right after it, so a part around the eye
        // comes before the entities after the dragon; later releases list every part last. Parts are not modelled.
        boolean partsInline = player.getClientVersion().isOlderThan(ClientVersion.V_1_21_4);
        for (PacketEntity entity : player.compensatedEntities.entityMap.values()) {
            if (entity == self) continue;
            double reach;
            if (entity.type == EntityTypeIds.ENDER_DRAGON) {
                if (!partsInline) continue;
                reach = error + DRAGON_PART_REACH;
            } else if (queries.pickable(entity)) {
                reach = error + pickRadius(entity);
            } else continue;
            SimpleCollisionBox box = pickEnvelope(entity);
            for (Vec3 eye : eyes) if (contains(box, eye, reach)) return true;
        }
        return false;
    }

    /**
     * Called at each client tick end. Minecraft#tick picks before LocalPlayer#rideTick, so the next tick's pick runs
     * from the eye this tick left: a vehicle mounted or moved since seats the player only after the pick.
     */
    public void onTickEnd() {
        var self = player.compensatedEntities.getSelf();
        tickEndSelf = self;
        tickEndX = player.x;
        tickEndY = player.y;
        tickEndZ = player.z;
        if (self.getRiding() == null) {
            tickEndSeatEyes = List.of();
            return;
        }
        List<Vec3> eyes = new ArrayList<>();
        addSelfEyes(eyes, null, crouchingPossible(), lowPosePossible());
        tickEndSeatEyes = eyes;
    }

    // The eyes the pick of the tick in progress may have run from: the eye the last tick end left, or the current
    // one should a teleport or the model's own lag have moved it since.
    private List<Vec3> tickStartEyes() {
        List<Vec3> eyes = new ArrayList<>();
        addSelfEyes(eyes, null, crouchingPossible(), lowPosePossible());
        if (tickEndSelf != player.compensatedEntities.getSelf()) return eyes;
        // Entity#startRiding sets the standing pose at once; any earlier pose may still hold on foot.
        if (tickEndSeatEyes.isEmpty()) addPoseEyes(eyes, null, new Vec3(tickEndX, tickEndY, tickEndZ), 0, true, true);
        else eyes.addAll(tickEndSeatEyes);
        return eyes;
    }

    // Player#getDesiredPose's crouching, which needs the shift key Player#updatePlayerPose last read.
    private boolean crouchingPossible() {
        return player.isSneaking || player.pose == Pose.CROUCHING;
    }

    // Player#getDesiredPose's 0.6 high poses: swimming, fall flying and the riptide spin.
    private boolean lowPosePossible() {
        return player.isGliding
                || player.isRiptidePose
                || player.riptideSpinAttackTicks > 0
                || player.swimState.swimming()
                || lowPose(player.pose);
    }

    private static boolean lowPose(Pose pose) {
        return pose == Pose.SWIMMING || pose == Pose.FALL_FLYING || pose == Pose.SPIN_ATTACK;
    }

    // Every position the client may hold the entity's bounding box at.
    private SimpleCollisionBox pickEnvelope(PacketEntity entity) {
        return entity.toBoundingBox(
                player, entity.getPossibleCollisionBoxes().union(entity.getPossibleMovementCollisionBoxes()));
    }

    // Whether no eye's AttackRange pick selects the target under the active selection.
    private boolean itemPickHidden(SimpleCollisionBox target, double positionError) {
        ItemComponents.AttackRange attack = selectionAttackRange();
        if (attack == null) return true;
        double range = Math.sqrt(3) * nativeItemRayScale(attack);
        for (Vec3 eye : eyes(positionError, List.of(target.copy().expand(attack.hitboxMargin())), null, range)) {
            if (selectable(eye, target, attack.hitboxMargin(), range)) return false;
        }
        return true;
    }

    /** Whether no eye selects the target under the active selection; sets {@code inRange[0]} for an eye within range. */
    private boolean entityHidden(
            SimpleCollisionBox target, double positionError, double pickRadius, boolean[] inRange) {
        double defaultRange = player.compensatedEntities.getSelf().getEntityInteractionRange();
        ItemComponents.AttackRange attack = selectionAttackRange();
        double itemRange = attack == null ? 0 : Math.sqrt(3) * nativeItemRayScale(attack);
        double margin = attack == null ? pickRadius : Math.max(pickRadius, attack.hitboxMargin());
        for (Vec3 eye :
                eyes(positionError, List.of(target.copy().expand(margin)), null, Math.max(defaultRange, itemRange))) {
            if (selectable(eye, target, pickRadius, defaultRange)) return false;
            if (attack != null && selectable(eye, target, attack.hitboxMargin(), itemRange)) return false;
            if (inRange != null
                    && (inRange(eye, target, pickRadius, defaultRange)
                            || attack != null && inRange(eye, target, attack.hitboxMargin(), itemRange)))
                inRange[0] = true;
        }
        return true;
    }

    private static boolean inRange(Vec3 eye, SimpleCollisionBox target, double margin, double range) {
        return range > 0 && withinRange(eye, target.copy().expand(margin), range);
    }

    private boolean selectable(Vec3 eye, SimpleCollisionBox target, double margin, double range) {
        SimpleCollisionBox envelope = target.copy().expand(margin);
        if (!(range > 0) || !withinRange(eye, envelope, range)) return false;
        if (contains(envelope, eye, 0)) return true;
        return OcclusionGeometry.hasVisiblePoint(
                eye, envelope, null, blockers(eye, envelope, null, range), null, range);
    }

    private ItemComponents.AttackRange selectionAttackRange() {
        return activeSelection == null ? currentAttackRange() : activeSelection.attackRange();
    }

    private ItemComponents.AttackRange currentAttackRange() {
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_21_11)) return null;
        // LivingEntity#getActiveItem is empty for a spectator from 26.1.
        if (player.gamemode == GameMode.SPECTATOR
                && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_1)) return null;
        var hand = player.isClientUsingItem() ? player.getClientItemUseHand() : Hand.MAIN_HAND;
        var item = hand == Hand.MAIN_HAND
                ? player.getInventory().getClientSelectedHeldItem()
                : player.getInventory().getOffHand();
        if (hand == Hand.MAIN_HAND && player.isClientUsingItem() && !player.usesModernJavaItemUse())
            item = player.getInventory()
                    .inventory
                    .getInventoryStorage()
                    .getItem(36 + player.packetStateData.getSlowedByUsingItemSlot());
        return item == null || item.isEmpty() ? null : ItemComponents.attackRange(item.components());
    }

    private Iterable<Vec3> eyes(
            double positionError, List<SimpleCollisionBox> targets, BlockPos excluded, double range) {
        return eyes(positionError, targets, excluded, range, player.cameraEntity.getPossibilities());
    }

    private Iterable<Vec3> eyes(
            double positionError,
            List<SimpleCollisionBox> targets,
            BlockPos excluded,
            double range,
            Iterable<? extends PacketEntity> cameras) {
        List<Vec3> eyes = new ArrayList<>();
        List<Vec3> trackedEyes = new ArrayList<>();
        var self = player.compensatedEntities.getSelf();
        // Entity#getEyePosition and Entity#positionRider read the position, not a shell a shulker's peek opened.
        for (var camera : cameras) {
            if (camera == self) {
                addSelfEyes(eyes, trackedEyes);
            } else {
                for (SimpleCollisionBox box : camera.getPositionCollisionBoxCandidates()) {
                    // Entity dimensions carry eye heights independently of the player's pose.
                    var eye = new Vec3(
                            (box.minX + box.maxX) * .5,
                            box.minY + CameraEyeHeight.of(camera, player.getClientVersion()),
                            (box.minZ + box.maxZ) * .5);
                    eyes.add(eye);
                    trackedEyes.add(eye);
                }
            }
        }
        // Ordinary visible picks need no local shape queries or buffered origins.
        return () -> {
            double radius = EyePositionTolerance.BUFFER + positionError;
            var shapes = new HashMap<Vec3, List<SimpleCollisionBox>>();
            Predicate<Vec3> valid = eye -> trackedEyes.contains(eye)
                    || EyePositionTolerance.outside(eye, shapes.computeIfAbsent(eye, e -> nearbyEyeShapes(e, radius)));
            return Stream.concat(
                            eyes.stream().filter(valid),
                            eyes.stream().filter(valid).flatMap(eye -> {
                                if (hiddenAcrossBuffer(eye, radius, targets, excluded, range)) return Stream.empty();
                                var bounds = EyePositionTolerance.bounds(
                                        eye,
                                        positionError,
                                        shapes.computeIfAbsent(eye, e -> nearbyEyeShapes(e, radius)));
                                return EyePositionTolerance.candidates(eye, bounds).stream();
                            }))
                    .iterator();
        };
    }

    private boolean hiddenAcrossBuffer(
            Vec3 eye, double radius, List<SimpleCollisionBox> targets, BlockPos excluded, double range) {
        for (SimpleCollisionBox target : targets) {
            var envelope = target.copy().expand(radius + CLIP_EPSILON);
            List<SimpleCollisionBox> interiors = new ArrayList<>();
            for (SimpleCollisionBox blocker :
                    OccluderBoxes.coalesce(blockers(eye, envelope, excluded, range + radius))) {
                var interior = blocker.copy().expand(-radius);
                if (!interior.isEmpty()) interiors.add(interior);
            }
            // A rejection-only proof for the entire continuous eye cube: translate
            // each candidate back to this eye. The expanded target contains every
            // translated target and these interiors lie in every translated blocker.
            // Visibility here NEVER accepts an interaction; it only triggers actual
            // fixed-world candidate queries below, avoiding eroded-wall bypasses.
            if (!OcclusionGeometry.fullyOccluded(eye, envelope, interiors)) return false;
        }
        return true;
    }

    private void addSelfEyes(List<Vec3> eyes, List<Vec3> trackedEyes) {
        addSelfEyes(eyes, trackedEyes, true, true);
    }

    /**
     * Entity#getEyePosition of the local player in the tracked pose, standing, crouching when {@code crouching} and in
     * the 0.4 poses when {@code low}. Entity#positionRider subtracts the pose's EntityAttachment.VEHICLE from the
     * seat; Avatar.POSES gives one to standing and crouching only, so the other poses sit at the seat.
     */
    private void addSelfEyes(List<Vec3> eyes, List<Vec3> trackedEyes, boolean crouching, boolean low) {
        var self = player.compensatedEntities.getSelf();
        var riding = self.getRiding();
        if (riding == null) {
            addPoseEyes(eyes, trackedEyes, new Vec3(player.x, player.y, player.z), 0, crouching, low);
            return;
        }
        double attachment = BoundingBoxSize.vehicleAttachmentY(self);
        for (SimpleCollisionBox box : riding.getPositionCollisionBoxCandidates()) {
            Vec3 feet = BoundingBoxSize.getRidingOffsetFromVehicle(riding, self, player, box);
            addPoseEyes(eyes, trackedEyes, feet, attachment, crouching, low);
        }
    }

    /** {@code feet} is where the standing pose's attachment puts the feet; {@code attachment} is that attachment. */
    private void addPoseEyes(
            List<Vec3> eyes, List<Vec3> trackedEyes, Vec3 feet, double attachment, boolean crouching, boolean low) {
        boolean attached = player.pose == Pose.STANDING || player.pose == Pose.CROUCHING;
        Vec3 tracked = feet.add(0, (attached ? 0 : attachment) + player.getEyeHeight(), 0);
        eyes.add(tracked);
        if (trackedEyes != null) trackedEyes.add(tracked);
        // Entity#getEyePosition uses the pose's discrete EntityDimensions eye
        // height. Avatar.POSES (26.3) has 1.62F, 1.27F and .4F; scale is a float
        // product. Include the tracked height separately for sleeping/forced poses.
        float scale = player.getScale();
        addEye(eyes, feet.add(0, Pose.STANDING.eyeHeight * scale, 0));
        if (crouching) addEye(eyes, feet.add(0, Pose.CROUCHING.eyeHeight * scale, 0));
        if (low) addEye(eyes, feet.add(0, attachment + Pose.SWIMMING.eyeHeight * scale, 0));
    }

    private static void addEye(List<Vec3> eyes, Vec3 eye) {
        if (!eyes.contains(eye)) eyes.add(eye);
    }

    private List<SimpleCollisionBox> nearbyEyeShapes(Vec3 eye, double radius) {
        List<SimpleCollisionBox> result = new ArrayList<>();
        // Neighbouring owners also cover outlines/collisions protruding from
        // their voxel (e.g. fences). No movement epsilon is used for these bounds.
        for (int x = (int) Math.floor(eye.x - radius) - 1; x <= (int) Math.floor(eye.x + radius) + 1; x++)
            for (int y = (int) Math.floor(eye.y - radius) - 1; y <= (int) Math.floor(eye.y + radius) + 1; y++)
                for (int z = (int) Math.floor(eye.z - radius) - 1; z <= (int) Math.floor(eye.z + radius) + 1; z++) {
                    if (!player.compensatedWorld.isChunkLoaded(x >> 4, z >> 4)) continue;
                    var position = new BlockPos(x, y, z);
                    result.addAll(selectionBoxes(position));
                    int state = player.compensatedWorld.getBlockStateIdAt(position);
                    ClientBlockShapes.movement(player, state, x, y, z).downCast(result);
                }
        return result;
    }

    public List<SimpleCollisionBox> outlineBoxes(BlockPos position) {
        List<SimpleCollisionBox> boxes = new ArrayList<>();
        if (selections.isEmpty()) boxes.addAll(selectionBoxes(position));
        for (Selection selection : selections)
            boxes.addAll(player.compensatedWorld.withSelectionHeldItemKey(
                    selection.heldItem(), () -> selectionBoxes(position)));
        if (boxes.isEmpty()) boxes.add(new SimpleCollisionBox(position));
        return boxes;
    }

    private List<SimpleCollisionBox> selectionBoxes(BlockPos position) {
        int state = player.compensatedWorld.getBlockStateIdAt(position);
        List<SimpleCollisionBox> result = new ArrayList<>();
        ClientBlockShapes.visual(player, state, position.getX(), position.getY(), position.getZ())
                .downCast(result);
        return result;
    }

    private static boolean contains(SimpleCollisionBox box, Vec3 eye, double error) {
        return eye.x >= box.minX - error
                && eye.x <= box.maxX + error
                && eye.y >= box.minY - error
                && eye.y <= box.maxY + error
                && eye.z >= box.minZ - error
                && eye.z <= box.maxZ + error;
    }

    private static boolean frontFacing(Direction face, Vec3 eye, SimpleCollisionBox box, double error) {
        return switch (face) {
            case WEST -> eye.x <= box.minX + error;
            case EAST -> eye.x >= box.maxX - error;
            case DOWN -> eye.y <= box.minY + error;
            case UP -> eye.y >= box.maxY - error;
            case NORTH -> eye.z <= box.minZ + error;
            case SOUTH -> eye.z >= box.maxZ - error;
        };
    }

    private static boolean withinRange(Vec3 eye, SimpleCollisionBox box, double range) {
        double x = Math.max(box.minX, Math.min(box.maxX, eye.x)) - eye.x;
        double y = Math.max(box.minY, Math.min(box.maxY, eye.y)) - eye.y;
        double z = Math.max(box.minZ, Math.min(box.maxZ, eye.z)) - eye.z;
        return x * x + y * y + z * z <= range * range;
    }

    private double nativeItemRayScale(ItemComponents.AttackRange attack) {
        double range = player.gamemode == GameMode.CREATIVE ? attack.maxCreativeReach() : attack.maxReach();
        double speed = 0;
        for (Vec3 velocity : player.checkManager.getSimulationProcessor().getLastDerivedNextTickVelocities())
            speed = Math.max(speed, velocity.length());
        var knockback = player.checkManager.getKnockbackHandler();
        if (knockback.firstBread != null)
            speed = Math.max(speed, knockback.firstBread.getVel().length());
        if (knockback.secondBread != null)
            speed = Math.max(speed, knockback.secondBread.getVel().length());
        var explosion = player.checkManager.getExplosionHandler();
        double extra = 0;
        if (explosion.firstBread != null)
            extra = Math.max(extra, explosion.firstBread.getVel().length());
        if (explosion.secondBread != null)
            extra = Math.max(extra, explosion.secondBread.getVel().length());
        speed += extra;
        for (var camera : player.cameraEntity.getPossibilities())
            if (camera != player.compensatedEntities.getSelf()) {
                speed = Math.max(speed, camera.deltaMovement.length());
                if (camera.type != EntityTypeIds.PLAYER)
                    range = Math.max(range, attack.maxReach() * attack.mobFactor());
            }
        // Mth's sin/cos lookup and float products bound each view component by
        // one. This also covers large float angles where the table pair is not
        // exactly unit length: |look| <= sqrt(3), vel.dot(look) <= sqrt(3)*|vel|.
        return range + Math.sqrt(3) * speed;
    }

    /** The longest Minecraft#pick ray any of this tick's selections may have cast. */
    public double maxPickRange() {
        if (selections.isEmpty()) return nativePickRange();
        Selection previous = activeSelection;
        try {
            double range = 0;
            for (Selection selection : selections) {
                activeSelection = selection;
                range = Math.max(range, nativePickRange());
            }
            return range;
        } finally {
            activeSelection = previous;
        }
    }

    private double nativePickRange() {
        double pickRange = Math.max(
                player.compensatedEntities.getSelf().getBlockInteractionRange(),
                player.compensatedEntities.getSelf().getEntityInteractionRange());
        var attack = selectionAttackRange();
        if (attack != null) pickRange = Math.max(pickRange, nativeItemRayScale(attack));
        return pickRange;
    }

    private void addNativeShadows(List<SimpleCollisionBox> result, Vec3 eye, SimpleCollisionBox box) {
        double pickRange = nativePickRange();
        double sample = Math.nextUp(.001 * pickRange);
        if (box.minX >= eye.x + sample
                || box.maxX <= eye.x - sample
                || box.minY >= eye.y + sample
                || box.maxY <= eye.y - sample
                || box.minZ >= eye.z + sample
                || box.maxZ <= eye.z - sample) {
            result.add(box);
            return;
        }
        // Every view-vector component is bounded by one. A ray reaching any of
        // these six interior slabs before its target has its 0.001 sample inside
        // the original convex box. This also handles eye-on-face rays entering it.
        for (int axis = 0; axis < 3; axis++)
            for (boolean positive : new boolean[] {false, true}) {
                SimpleCollisionBox part = box.copy();
                switch (axis) {
                    case 0 -> {
                        if (positive) part.minX = Math.max(part.minX, eye.x + sample);
                        else part.maxX = Math.min(part.maxX, eye.x - sample);
                    }
                    case 1 -> {
                        if (positive) part.minY = Math.max(part.minY, eye.y + sample);
                        else part.maxY = Math.min(part.maxY, eye.y - sample);
                    }
                    case 2 -> {
                        if (positive) part.minZ = Math.max(part.minZ, eye.z + sample);
                        else part.maxZ = Math.min(part.maxZ, eye.z - sample);
                    }
                }
                if (part.minX < part.maxX && part.minY < part.maxY && part.minZ < part.maxZ) result.add(part);
            }
    }

    private List<SimpleCollisionBox> blockers(Vec3 eye, SimpleCollisionBox target, BlockPos excluded, double range) {
        List<SimpleCollisionBox> result = new ArrayList<>();
        int minY = player.compensatedWorld.getMinHeight();
        int maxY = minY + player.compensatedWorld.getLastClientboundSectionCount() * 16;
        // These are the same global owning-voxel bounds used by VisibilityVoxels.
        // Skip distant received chunks before allocating or projecting a cone.
        int minChunkX = (int) Math.floor(Math.min(eye.x, Math.max(target.minX, eye.x - range))) >> 4;
        int maxChunkX = (int) Math.floor(Math.max(eye.x, Math.min(target.maxX, eye.x + range))) >> 4;
        int minChunkZ = (int) Math.floor(Math.min(eye.z, Math.max(target.minZ, eye.z - range))) >> 4;
        int maxChunkZ = (int) Math.floor(Math.max(eye.z, Math.min(target.maxZ, eye.z + range))) >> 4;
        // Iterate received chunks, so plugin-authored large velocities or entity
        // boxes cannot turn this into a scan through millions of unloaded cells.
        for (long key : player.compensatedWorld.chunkPositions()) {
            int chunkX = (int) (key >> 32), chunkZ = (int) key;
            if (chunkX < minChunkX || chunkX > maxChunkX || chunkZ < minChunkZ || chunkZ > maxChunkZ) continue;
            SimpleCollisionBox query =
                    new SimpleCollisionBox(chunkX * 16, minY, chunkZ * 16, chunkX * 16 + 16, maxY, chunkZ * 16 + 16);
            VisibilityVoxels.visit(eye, target, range, query, position -> {
                if (position.equals(excluded)) return;
                int x = position.x(), y = position.y(), z = position.z();
                if (player.compensatedWorld.getChunk(x >> 4, z >> 4) == null) return;
                for (SimpleCollisionBox shape : selectionBoxes(new BlockPos(x, y, z))) {
                    // BlockGetter.traverseBlocks visits the owning voxel, not every
                    // collision box intersecting the ray. Restrict proofs to that voxel.
                    double x0 = Math.max(x, shape.minX);
                    double y0 = Math.max(y, shape.minY);
                    double z0 = Math.max(z, shape.minZ);
                    double x1 = Math.min(x + 1, shape.maxX);
                    double y1 = Math.min(y + 1, shape.maxY);
                    double z1 = Math.min(z + 1, shape.maxZ);
                    if (x0 >= x1 || y0 >= y1 || z0 >= z1) continue;
                    SimpleCollisionBox interior = new SimpleCollisionBox(x0, y0, z0, x1, y1, z1);
                    if (excluded == null) result.add(interior);
                    else addBeforeTargetVoxel(result, eye, interior, excluded);
                }
            });
        }
        List<SimpleCollisionBox> shadows = new ArrayList<>();
        for (SimpleCollisionBox box : OccluderBoxes.coalesce(result)) addNativeShadows(shadows, eye, box);
        return shadows;
    }

    private void addBeforeTargetVoxel(
            List<SimpleCollisionBox> result, Vec3 eye, SimpleCollisionBox box, BlockPos target) {
        // BlockGetter.traverseBlocks stops at the first owning voxel whose full
        // outline ray test hits. Piston-head outlines can extend outside their
        // voxel: a geometrically nearer hit in a later voxel cannot disprove a
        // claimed block. These slabs prove a hit before entering its owning voxel.
        double[] origin = {eye.x, eye.y, eye.z};
        int[] position = {target.x(), target.y(), target.z()};
        for (int axis = 0; axis < 3; axis++) {
            double low = position[axis], high = position[axis] + 1;
            boolean before = origin[axis] < low, after = origin[axis] > high;
            if (!before && !after) continue;
            SimpleCollisionBox part = box.copy();
            switch (axis) {
                case 0 -> {
                    if (before) part.maxX = Math.min(part.maxX, low);
                    else part.minX = Math.max(part.minX, high);
                }
                case 1 -> {
                    if (before) part.maxY = Math.min(part.maxY, low);
                    else part.minY = Math.max(part.minY, high);
                }
                case 2 -> {
                    if (before) part.maxZ = Math.min(part.maxZ, low);
                    else part.minZ = Math.max(part.minZ, high);
                }
            }
            if (part.minX < part.maxX && part.minY < part.maxY && part.minZ < part.maxZ) result.add(part);
        }
    }
}
