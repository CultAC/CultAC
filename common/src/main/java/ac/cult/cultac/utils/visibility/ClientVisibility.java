package ac.cult.cultac.utils.visibility;

import ac.cult.blocksim.data.BlockFamilies;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.ClientBlockShapes;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BoundingBoxSize;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import java.util.ArrayList;
import java.util.List;

/** Selection geometry shared by block use, digging, entity attacks and entity interactions. */
public final class ClientVisibility {
    private static final double CLIP_EPSILON = 1.0E-7;
    private final CultPlayer player;

    private record Selection(String heldItem, ac.cult.blocksim.data.ItemComponents.AttackRange attackRange) {}

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
                : held == null || held.isEmpty()
                        ? null
                        : ac.cult.blocksim.data.ItemComponents.attackRange(held.components());
        var candidate = new Selection(held == null || held.isEmpty() ? "minecraft:air" : held.itemKey(), attack);
        // Keyboard hotbar selection happens after pick; MouseHandler.onScroll can
        // change it before pick. The selected-slot packet does not identify which.
        if (!selections.contains(candidate)) selections.add(candidate);
    }

    /** An item AttackRange LocalPlayer#raycastHitResult may have picked with, and its ray scale bound. */
    public record ItemPick(float hitboxMargin, double rayScale) {}

    /** The item picks Minecraft#pick may have made before this tick's attack. */
    public List<ItemPick> attackItemPicks() {
        beginSelection();
        List<ItemPick> picks = new ArrayList<>();
        for (Selection selection : selections) {
            var attack = selection.attackRange();
            if (attack != null) picks.add(new ItemPick(attack.hitboxMargin(), nativeItemRayScale(attack)));
        }
        return picks;
    }

    private boolean hiddenForEverySelection(java.util.function.Supplier<Boolean> query) {
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
        for (Vec3 eye : eyes(positionError, targets, position, range)) {
            List<SimpleCollisionBox> overrides = new ArrayList<>();
            for (SimpleCollisionBox interaction : interactions) {
                if (frontFacing(face, eye, interaction, error)) overrides.add(faceEnvelope(interaction, face));
            }
            for (SimpleCollisionBox target : targets) {
                SimpleCollisionBox envelope = target.copy();
                if (!withinRange(eye, envelope, range)) continue;
                List<SimpleCollisionBox> blockers = blockers(eye, envelope, position, range);
                double pickRange = nativePickRange();
                if (NativeInsideSelection.possible(
                                eye,
                                target,
                                face,
                                error,
                                Math.nextDown(.001 * Math.sqrt(CLIP_EPSILON)),
                                Math.nextUp(.001 * pickRange),
                                range)
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

    public boolean entityHidden(ac.cult.cultac.utils.data.packetentity.PacketEntity entity, double positionError) {
        double pickRadius =
                EntityTypeUtil.modelType(entity.type).has(ac.cult.blocksim.entity.EntityTypes.PROJECTILE) ? 1.0F : 0;
        // ProjectileUtil#getEntityHitResult clips the physical bounding box,
        // inflated only by the native pick radius/item margin. Reach's separate
        // 0.03125 expansion can invent an exposed point across a solid corner.
        return hiddenForEverySelection(
                () -> entityHidden(entity.getPossibleMovementCollisionBoxes(), positionError, pickRadius));
    }

    public boolean entityHidden(SimpleCollisionBox target, double positionError) {
        return hiddenForEverySelection(() -> entityHidden(target, positionError, 0));
    }

    public boolean entityInteractionHidden(PacketEntity entity, double positionError) {
        double pickRadius =
                EntityTypeUtil.modelType(entity.type).has(ac.cult.blocksim.entity.EntityTypes.PROJECTILE) ? 1.0F : 0;
        return hiddenForEverySelection(() -> {
            var target = entity.getPossibleMovementCollisionBoxes();
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
                return entityHidden(target, positionError, pickRadius);
            }
            return true;
        });
    }

    private boolean entityHidden(SimpleCollisionBox target, double positionError, double pickRadius) {
        double defaultRange = player.compensatedEntities.getSelf().getEntityInteractionRange();
        ac.cult.blocksim.data.ItemComponents.AttackRange attack = selectionAttackRange();
        double itemRange = attack == null ? 0 : Math.sqrt(3) * nativeItemRayScale(attack);
        double margin = attack == null ? pickRadius : Math.max(pickRadius, attack.hitboxMargin());
        for (Vec3 eye :
                eyes(positionError, List.of(target.copy().expand(margin)), null, Math.max(defaultRange, itemRange))) {
            if (selectable(eye, target, pickRadius, defaultRange)) return false;
            if (attack != null && selectable(eye, target, attack.hitboxMargin(), itemRange)) return false;
        }
        return true;
    }

    private boolean selectable(Vec3 eye, SimpleCollisionBox target, double margin, double range) {
        SimpleCollisionBox envelope = target.copy().expand(margin);
        if (!(range > 0) || !withinRange(eye, envelope, range)) return false;
        if (contains(envelope, eye, 0)) return true;
        return OcclusionGeometry.hasVisiblePoint(
                eye, envelope, null, blockers(eye, envelope, null, range), null, range);
    }

    private ac.cult.blocksim.data.ItemComponents.AttackRange selectionAttackRange() {
        return activeSelection == null ? currentAttackRange() : activeSelection.attackRange();
    }

    private ac.cult.blocksim.data.ItemComponents.AttackRange currentAttackRange() {
        if (player.gamemode == ac.cult.cultac.protocol.value.GameMode.SPECTATOR) return null;
        if (player.getClientVersion().isOlderThan(ac.cult.cultac.network.protocol.ClientVersion.V_1_21_11)) return null;
        var hand = player.isClientUsingItem()
                ? player.getClientItemUseHand()
                : ac.cult.cultac.protocol.value.Hand.MAIN_HAND;
        var item = hand == ac.cult.cultac.protocol.value.Hand.MAIN_HAND
                ? player.getInventory().getClientSelectedHeldItem()
                : player.getInventory().getOffHand();
        if (hand == ac.cult.cultac.protocol.value.Hand.MAIN_HAND
                && player.isClientUsingItem()
                && !player.usesModernJavaItemUse())
            item = player.getInventory()
                    .inventory
                    .getInventoryStorage()
                    .getItem(36 + player.packetStateData.getSlowedByUsingItemSlot());
        return item == null || item.isEmpty()
                ? null
                : ac.cult.blocksim.data.ItemComponents.attackRange(item.components());
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
        for (var camera : cameras) {
            if (camera == self) {
                if (self.getRiding() == null) addPlayerEyes(eyes, trackedEyes, new Vec3(player.x, player.y, player.z));
                else {
                    var riding = self.getRiding();
                    for (SimpleCollisionBox box : riding.getPossibleMovementCollisionBoxCandidates()) {
                        Vec3 origin = BoundingBoxSize.getRidingOffsetFromVehicle(riding, self, player, box);
                        addPlayerEyes(eyes, trackedEyes, origin);
                    }
                }
            } else {
                for (SimpleCollisionBox box : camera.getPossibleMovementCollisionBoxCandidates()) {
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
            var shapes = new java.util.HashMap<Vec3, List<SimpleCollisionBox>>();
            java.util.function.Predicate<Vec3> valid = eye -> trackedEyes.contains(eye)
                    || EyePositionTolerance.outside(eye, shapes.computeIfAbsent(eye, e -> nearbyEyeShapes(e, radius)));
            return java.util.stream.Stream.concat(
                            eyes.stream().filter(valid),
                            eyes.stream().filter(valid).flatMap(eye -> {
                                if (hiddenAcrossBuffer(eye, radius, targets, excluded, range))
                                    return java.util.stream.Stream.empty();
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

    private void addPlayerEyes(List<Vec3> eyes, List<Vec3> trackedEyes, Vec3 origin) {
        Vec3 tracked = origin.add(0, player.getEyeHeight(), 0);
        eyes.add(tracked);
        trackedEyes.add(tracked);
        // Entity#getEyePosition uses the pose's discrete EntityDimensions eye
        // height. Avatar.POSES (26.3) has 1.62F, 1.27F and .4F; scale is a float
        // product. Include the tracked height separately for sleeping/forced poses.
        List<Pose> poses = List.of(Pose.STANDING, Pose.CROUCHING, Pose.SWIMMING);
        for (Pose pose : poses) {
            Vec3 eye = origin.add(0, pose.eyeHeight * player.getScale(), 0);
            if (!eyes.contains(eye)) eyes.add(eye);
        }
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
                    if (!BlockFamilies.SHULKER_BOX.test(state))
                        ClientBlockShapes.movement(player, state, x, y, z).downCast(result);
                }
        return result;
    }

    private List<SimpleCollisionBox> selectionBoxes(BlockPos position) {
        int state = player.compensatedWorld.getBlockStateIdAt(position);
        // Visibility deliberately treats shulker boxes as closed, independent of lid animation.
        if (BlockFamilies.SHULKER_BOX.test(state)) return List.of(new SimpleCollisionBox(position));
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

    private double nativeItemRayScale(ac.cult.blocksim.data.ItemComponents.AttackRange attack) {
        double range = player.gamemode == ac.cult.cultac.protocol.value.GameMode.CREATIVE
                ? attack.maxCreativeReach()
                : attack.maxReach();
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
                if (camera.type != ac.cult.blocksim.entity.EntityTypeIds.PLAYER)
                    range = Math.max(range, attack.maxReach() * attack.mobFactor());
            }
        // Mth's sin/cos lookup and float products bound each view component by
        // one. This also covers large float angles where the table pair is not
        // exactly unit length: |look| <= sqrt(3), vel.dot(look) <= sqrt(3)*|vel|.
        return range + Math.sqrt(3) * speed;
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
        for (long key : player.compensatedWorld.chunks.keySet()) {
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
