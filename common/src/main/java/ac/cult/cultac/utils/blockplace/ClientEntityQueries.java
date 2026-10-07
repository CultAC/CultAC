package ac.cult.cultac.utils.blockplace;

import ac.cult.blocksim.data.Box;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.HolderSets;
import ac.cult.blocksim.engine.BlockRaycast;
import ac.cult.blocksim.engine.SimPlayer;
import ac.cult.blocksim.engine.SimWorldView;
import ac.cult.blocksim.engine.Vec3;
import ac.cult.blocksim.engine.shapes.BooleanOp;
import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.engine.shapes.VoxelShape;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.EntityPose;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityArmorStand;
import ac.cult.cultac.utils.data.packetentity.PacketEntityCreaking;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHanging;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHappyGhast;
import ac.cult.cultac.utils.data.packetentity.PacketEntityInteraction;
import ac.cult.cultac.utils.data.packetentity.PacketEntityPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntitySpider;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** Action queries use the existing exact entity candidates, without widening their boxes. */
public final class ClientEntityQueries {
    private final CultPlayer player;
    private final SimWorldView world;
    private final HolderSets.Overlay tags;

    public ClientEntityQueries(CultPlayer player) {
        this(player, null);
    }

    public ClientEntityQueries(CultPlayer player, SimWorldView world) {
        this.player = player;
        this.world = world;
        var source =
                player.user.getCultConnection().dispatcher().runtime().data().version();
        var client = player.isBedrockMovement()
                ? source
                : ac.cult.cultac.protocol.ProtocolVersion.of(
                        player.getClientVersion().getProtocolVersion());
        tags = player.registryState == null
                ? HolderSets.Overlay.EMPTY
                : player.registryState.blockSimulatorTags(source, client, DataTables.defaults());
    }

    public boolean isUnobstructed(VoxelShape shape) {
        if (shape.isEmpty()) return true;
        var local = player.boundingBox;
        if (player.gamemode != GameMode.SPECTATOR
                && intersects(shape, new Box(local.minX, local.minY, local.minZ, local.maxX, local.maxY, local.maxZ)))
            return false;
        return !any(
                entity -> present(entity) && !spectator(entity) && blocksBuilding(entity),
                box -> intersects(shape, box));
    }

    public boolean hasMinecartIn(Box box) {
        return any(PacketEntity::isMinecart, box::intersects);
    }

    public boolean hasSittingCatAt(ac.cult.blocksim.engine.BlockPos pos) {
        var query = new Box(pos.x(), pos.y() + 1, pos.z(), pos.x() + 1, pos.y() + 2, pos.z() + 1);
        return any(
                entity -> entity instanceof ac.cult.cultac.utils.data.packetentity.PacketEntityCat cat && cat.sitting,
                query::intersects);
    }

    /** EntityGetter.getEntityCollisions returns every selected box in the inflated search area. */
    public boolean hasEntityCollision(Box box, boolean boatSource) {
        if (((box.maxX() - box.minX()) + (box.maxY() - box.minY()) + (box.maxZ() - box.minZ())) / 3.0 < 1.0E-7)
            return false;
        var search = box.inflate(1.0E-7);
        if (boatSource
                && player.gamemode != GameMode.SPECTATOR
                && player.compensatedEntities.getSelf().actionAlive
                && search.intersects(localBox())
                && (player.isFlying || !onClimbable(new Vec3(player.x, player.y, player.z), player.isGliding)))
            return true;
        return any(
                entity -> !spectator(entity),
                (entity, bounds) ->
                        search.intersects(bounds) && (collidable(entity) || boatSource && pushable(entity, bounds)));
    }

    /** Brush's ProjectileUtil overload has zero margin and does not count containment as a ray hit. */
    public boolean hasPickableEntityHit(Vec3 from, Vec3 to, SimPlayer owner) {
        var delta = BlockRaycast.viewVector(owner).scale(owner.sight().blockInteractionRange());
        var search = localBox().expandTowards(delta).inflate(1.0);
        if (any(this::pickable, bounds -> search.intersects(bounds) && bounds.clip(from, to) != null)) return true;
        requireDragonParts();
        return false;
    }

    /** BoatItem's view search uses a fixed five blocks, then each entity's pick radius. */
    public boolean hasPickableEntityAtEye(SimPlayer owner) {
        var search = localBox()
                .expandTowards(BlockRaycast.viewVector(owner).scale(5.0))
                .inflate(1.0);
        if (any(
                this::pickable,
                (entity, bounds) -> search.intersects(bounds)
                        && bounds.inflate(modelType(entity).has(EntityTypes.PROJECTILE) ? 1.0F : 0.0F)
                                .contains(owner.sight().eyePosition()))) return true;
        requireDragonParts();
        return false;
    }

    private void requireDragonParts() {
        for (var entity : player.compensatedEntities.entityMap.values())
            if (modelType(entity).key().equals("minecraft:ender_dragon"))
                throw new IllegalStateException(
                        "Client entity picking requires the dragon's independently positioned parts");
    }

    private boolean pickable(PacketEntity entity) {
        var type = modelType(entity);
        if (entity instanceof PacketEntityArmorStand stand) return !stand.actionMarker();
        if (entity instanceof PacketEntityPlayer) return !spectator(entity);
        return switch (type.key()) {
            case "minecraft:ender_dragon" -> false;
            case "minecraft:interaction",
                    "minecraft:falling_block",
                    "minecraft:tnt",
                    "minecraft:end_crystal",
                    "minecraft:shulker_bullet" -> true;
            default ->
                type.has(EntityTypes.LIVING)
                        || type.has(EntityTypes.BOAT)
                        || type.has(EntityTypes.MINECART)
                        || type.has(EntityTypes.BLOCK_ATTACHED)
                        || type.has(EntityTypes.PROJECTILE)
                                && members("entity_type:minecraft:redirectable_projectile")
                                        .contains(type.key())
                                && (!type.has(EntityTypes.ARROW) || !entity.actionArrowInGround);
        };
    }

    private boolean collidable(PacketEntity entity) {
        if (entity.isBoat()) return true;
        if (entity.type == EntityTypeIds.SHULKER) return entity.actionAlive;
        return entity instanceof PacketEntityHappyGhast ghast && !ghast.isBaby && ghast.actionAlive && ghast.staysStill;
    }

    private boolean pushable(PacketEntity entity, Box bounds) {
        if (entity.isBoat() || entity.isMinecart()) return true;
        if (entity.isHorse()) return entity.passengers.isEmpty();
        if (entity instanceof PacketEntityArmorStand) return false;
        var key = modelType(entity).key();
        if (key.equals("minecraft:parrot")) return true;
        if (key.equals("minecraft:bat") || !entity.isLivingEntity() || !entity.actionAlive) return false;
        if (key.equals("minecraft:warden")
                && (entity.actionPose == EntityPose.DIGGING || entity.actionPose == EntityPose.EMERGING)) return false;
        if (entity instanceof PacketEntityCreaking creaking && !creaking.actionCanMove) return false;
        if (entity instanceof PacketEntitySpider spider) return !spider.actionClimbing;
        if (entity instanceof PacketEntityHappyGhast
                || key.equals("minecraft:ghast")
                || key.equals("minecraft:phantom")) return true;
        return !onClimbable(
                new Vec3(
                        bounds.minX() + .5 * (bounds.maxX() - bounds.minX()),
                        bounds.minY(),
                        bounds.minZ() + .5 * (bounds.maxZ() - bounds.minZ())),
                (entity.actionSharedFlags & 128) != 0);
    }

    private boolean onClimbable(Vec3 position, boolean gliding) {
        if (world == null) throw new IllegalStateException("Client pushable query requires its compensated block view");
        var pos = new ac.cult.blocksim.engine.BlockPos(
                (int) Math.floor(position.x()), (int) Math.floor(position.y()), (int) Math.floor(position.z()));
        int state = world.stateAt(pos);
        var registry = DataTables.defaults().registry();
        var block = registry.block(state);
        if (gliding && members("block:minecraft:can_glide_through").contains(block.key())) return false;
        if (members("block:minecraft:climbable").contains(block.key())) return true;
        if (!block.bindings().get("classHierarchy").contains("net.minecraft.world.level.block.TrapDoorBlock")
                || !registry.value(state, "open").equals("true")) return false;
        int below = world.stateAt(pos.relative(ac.cult.blocksim.engine.Direction.DOWN));
        return registry.block(below).key().equals("minecraft:ladder")
                && registry.value(state, "facing").equals(registry.value(below, "facing"));
    }

    private Set<String> members(String tag) {
        return tags.tags().getOrDefault(tag, DataTables.defaults().tags().getOrDefault(tag, Set.of()));
    }

    private static EntityTypes.Type modelType(PacketEntity entity) {
        return EntityTypeUtil.modelType(entity.type);
    }

    private Box localBox() {
        var box = player.boundingBox;
        return new Box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    private boolean any(Predicate<PacketEntity> selected, Predicate<Box> query) {
        return any(selected, (entity, box) -> query.test(box));
    }

    private boolean any(Predicate<PacketEntity> selected, BiPredicate<PacketEntity, Box> query) {
        boolean unresolved = false;
        for (var entity : player.compensatedEntities.entityMap.values()) {
            if (!present(entity) || !selected.test(entity)) continue;
            var result = test(entity, box -> query.test(entity, box));
            if (Boolean.TRUE.equals(result)) return true;
            unresolved |= result == null;
        }
        if (unresolved) throw new IllegalStateException("Client entity candidates disagree on the block action query");
        return false;
    }

    /** Null means that the existing packet/tick ordering has not proved one query outcome. */
    private static Boolean test(PacketEntity entity, Predicate<Box> query) {
        if (entity instanceof PacketEntityHanging hanging) return query.test(hanging.actionBounds());
        Boolean result = null;
        for (var position : entity.getPossibleMovementCollisionBoxCandidates()) {
            Box box;
            if (entity.isLivingEntity() && entity.actionPose == EntityPose.SLEEPING) {
                double x = position.minX + .5 * (position.maxX - position.minX),
                        z = position.minZ + .5 * (position.maxZ - position.minZ);
                double radius = .2F / 2.0F;
                box = new Box(x - radius, position.minY, z - radius, x + radius, position.minY + .2F, z + radius);
            } else if (entity instanceof PacketEntityPlayer remote) box = remote.actionBounds(position);
            else if (entity instanceof PacketEntityArmorStand stand) box = stand.actionBounds(position);
            else if (entity instanceof PacketEntityInteraction interaction) box = interaction.actionBounds(position);
            else
                box = new Box(position.minX, position.minY, position.minZ, position.maxX, position.maxY, position.maxZ);
            boolean matched = box != null && query.test(box);
            if (result != null && result != matched) return null;
            result = matched;
        }
        return result;
    }

    private static boolean intersects(VoxelShape shape, Box box) {
        return Shapes.joinIsNotEmpty(shape, Shapes.create(box), BooleanOp.AND);
    }

    private static boolean blocksBuilding(PacketEntity entity) {
        if (entity instanceof PacketEntityArmorStand stand) return !stand.actionMarker();
        return entity.isLivingEntity()
                || entity.isBoat()
                || entity.isMinecart()
                || entity.type == EntityTypeIds.FALLING_BLOCK
                || entity.type == EntityTypeIds.TNT
                || entity.type == EntityTypeIds.END_CRYSTAL;
    }

    private static boolean present(PacketEntity entity) {
        return !(entity instanceof PacketEntityPlayer remote) || remote.actionPresent;
    }

    private boolean spectator(PacketEntity entity) {
        return entity instanceof PacketEntityPlayer remote
                && remote.actionSpectator(player.compensatedEntities.clientPlayerModes);
    }
}
