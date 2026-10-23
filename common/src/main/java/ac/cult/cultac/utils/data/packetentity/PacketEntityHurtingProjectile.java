package ac.cult.cultac.utils.data.packetentity;

import ac.cult.blocksim.engine.EntityCollisionContext;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.interaction.BlockHit;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.NativeBlockCollisionHelper;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

/**
 * An {@code AbstractHurtingProjectile} (fireballs, wind charges), which the client moves along its delta movement every
 * tick between server positions.
 */
public final class PacketEntityHurtingProjectile extends PacketEntity {
    private final double accelerationPower;
    private final float inertia;
    private final float liquidInertia;
    // Entity#isInWater: wasTouchingWater, which baseTick refreshes after each move.
    private boolean inWater;
    // Projectile#recreateFromPacket sets the owner only to an entity the client has when the spawn arrives.
    private final boolean ownerResolved;
    private final int ownerId;
    // Projectile#leftOwner, which checkLeftOwner sets after a move once no box of the owner's tree is near.
    private boolean leftOwner;
    // Set once the owner's riding tree held more than the local player, for which leftOwner is not modelled.
    private boolean leftOwnerUnknown;
    // Entity#tickCount, which commonTick raises before each tick.
    private int tickCount;

    public PacketEntityHurtingProjectile(CultPlayer player, int id, int type, Vec3 position, int ownerId) {
        super(player, id, type, position.x, position.y, position.z);
        boolean windCharge = isWindCharge(type);
        // AbstractWindCharge's constructors, getInertia and getLiquidInertia; AbstractHurtingProjectile's otherwise.
        accelerationPower = windCharge ? 0.0D : 0.1D;
        inertia = windCharge ? 1.0F : 0.95F;
        liquidInertia = windCharge ? inertia : 0.8F;
        this.ownerId = ownerId;
        ownerResolved = player.compensatedEntities.getEntity(ownerId) != null;
    }

    public static boolean isHurtingProjectile(int type) {
        return isWindCharge(type)
                || type == EntityTypeIds.FIREBALL
                || type == EntityTypeIds.SMALL_FIREBALL
                || type == EntityTypeIds.DRAGON_FIREBALL;
    }

    private static boolean isWindCharge(int type) {
        return type == EntityTypeIds.WIND_CHARGE || type == EntityTypeIds.BREEZE_WIND_CHARGE;
    }

    /**
     * {@code AbstractHurtingProjectile#tick} on 1.21.2+ clients: {@code applyInertia}, then {@code setPos} to the first
     * hit of {@code ProjectileUtil#getHitResultOnMoveVector} or one delta movement on, then
     * {@code Projectile#checkLeftOwner}. Each possible location moves from its own position. Of the entities the move
     * can hit, only the local player is modelled.
     */
    @Override
    public void onMovement(CultPlayer player, boolean tickingReliably) {
        super.onMovement(player, tickingReliably);
        tickCount++;
        // Bedrock clients see Geyser's per-tick absolute moves, which the Geyser bridge already applies.
        if (getRiding() != null || player.isBedrockMovement()) return;
        // 1.21.1 tests the hit on the old delta, moves, then applies inertia; this models the 1.21.2+ order only.
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_21_2)) return;
        Vec3 movement = deltaMovement;
        deltaMovement =
                movement.add(movement.normalize().scale(accelerationPower)).scale(inWater ? liquidInertia : inertia);
        SimpleCollisionBox self = selfBoxAtTick(player);
        SimpleCollisionBox target = self != null && canHitSelf(player) ? self : null;
        if (deltaMovement.lengthSqr() != 0.0D) {
            newPacketLocation.offset(from -> step(player, from, target));
            if (oldPacketLocation != null) oldPacketLocation.offset(from -> step(player, from, target));
            if (clientPhysicalPosition != null)
                clientPhysicalPosition = clientPhysicalPosition.add(step(player, clientPhysicalPosition, target));
        }
        inWater = clientPhysicalPosition != null && touchesWater(player, clientPhysicalPosition);
        if (ownerId == player.entityID && !leftOwner && clientPhysicalPosition != null) checkLeftOwner(player, self);
    }

    /** The local player's box when this projectile ticks: the box after the player's move if it ticks first. */
    private @Nullable SimpleCollisionBox selfBoxAtTick(CultPlayer player) {
        CompensatedEntities entities = player.compensatedEntities;
        return getClientTickOrder() > entities.clientTickOrder(entities.getSelf()) ? player.boundingBox : null;
    }

    /**
     * {@code AbstractHurtingProjectile#canHitEntity} for the local player: alive, not a spectator, and either no owner,
     * an owner outside the player's riding tree, or a left owner.
     */
    private boolean canHitSelf(CultPlayer player) {
        PacketEntitySelf self = player.compensatedEntities.getSelf();
        if (self.isDead || player.gamemode == GameMode.SPECTATOR) return false;
        if (!ownerResolved) return true;
        if (ownerId == player.entityID) return leftOwner;
        PacketEntity owner = player.compensatedEntities.getEntity(ownerId);
        return owner == null || !player.compensatedEntities.sharesRootVehicleWithSelf(owner, player.compensatedEntities.rootOfSelf());
    }

    /** {@code Projectile#isOutsideOwnerCollisionRange} for an owner riding nothing and carrying nothing. */
    private void checkLeftOwner(CultPlayer player, @Nullable SimpleCollisionBox self) {
        PacketEntitySelf owner = player.compensatedEntities.getSelf();
        if (leftOwnerUnknown || self == null || owner.getRiding() != null || !owner.passengers.isEmpty()) {
            leftOwnerUnknown = true;
            return;
        }
        if (player.gamemode == GameMode.SPECTATOR) {
            leftOwner = true;
            return;
        }
        leftOwner = !intersects(
                expandTowards(box(player, clientPhysicalPosition), deltaMovement)
                        .inflate(1, 1, 1),
                self);
    }

    private SimpleCollisionBox box(CultPlayer player, Vec3 position) {
        return toBoundingBox(
                player, GetBoundingBox.getPacketEntityBoundingBox(player, position.x, position.y, position.z, this));
    }

    /** {@code AABB#expandTowards}. */
    private static SimpleCollisionBox expandTowards(SimpleCollisionBox box, Vec3 delta) {
        return new SimpleCollisionBox(
                delta.x < 0 ? box.minX + delta.x : box.minX,
                delta.y < 0 ? box.minY + delta.y : box.minY,
                delta.z < 0 ? box.minZ + delta.z : box.minZ,
                delta.x > 0 ? box.maxX + delta.x : box.maxX,
                delta.y > 0 ? box.maxY + delta.y : box.maxY,
                delta.z > 0 ? box.maxZ + delta.z : box.maxZ);
    }

    /** {@code AABB#intersects}. */
    private static boolean intersects(SimpleCollisionBox a, SimpleCollisionBox b) {
        return a.minX < b.maxX
                && a.maxX > b.minX
                && a.minY < b.maxY
                && a.maxY > b.minY
                && a.minZ < b.maxZ
                && a.maxZ > b.minZ;
    }

    /** {@code EntityFluidInteraction#update}: water whose top reaches the bottom of the box deflated by 0.001. */
    private boolean touchesWater(CultPlayer player, Vec3 position) {
        SimpleCollisionBox box = toBoundingBox(
                        player,
                        GetBoundingBox.getPacketEntityBoundingBox(player, position.x, position.y, position.z, this))
                .expand(-0.001D);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = (int) Math.floor(box.minX); x < Math.ceil(box.maxX); x++) {
            for (int y = (int) Math.floor(box.minY); y < Math.ceil(box.maxY); y++) {
                for (int z = (int) Math.floor(box.minZ); z < Math.ceil(box.maxZ); z++) {
                    pos.set(x, y, z);
                    ClientFluidQueries.Sample fluid =
                            ClientFluidQueries.sample(player, player.compensatedWorld.getBlockStateIdAt(pos), pos);
                    if (fluid.water() && y + fluid.height() >= box.minY) return true;
                }
            }
        }
        return false;
    }

    /**
     * {@code ProjectileUtil#getHitResult}: the block clip ends the move, then {@code getEntityHitResult} ends it where it
     * enters {@code target} grown by {@code computeMargin}, when {@code target} meets the box swept along the delta
     * and grown by 1.
     */
    private Vec3 step(CultPlayer player, Vec3 from, @Nullable SimpleCollisionBox target) {
        // ClipContext.Block.COLLIDER with CollisionContext.of(this projectile).
        EntityCollisionContext context = EntityCollisionContext.position(from.y);
        BlockHit hit = player.compensatedWorld
                .geometry()
                .clipBlocks(
                        from,
                        from.add(deltaMovement),
                        (state, pos) -> NativeBlockCollisionHelper.getCollisionShape(
                                player, state, pos, from.y, null, context));
        Vec3 to = hit == null
                ? from.add(deltaMovement)
                : new Vec3(
                        hit.location().x(), hit.location().y(), hit.location().z());
        if (target != null
                && intersects(
                        target, expandTowards(box(player, from), deltaMovement).inflate(1, 1, 1))) {
            float margin = Math.max(0.0F, Math.min(0.3F, (tickCount - 2) / 20.0F));
            Optional<Vec3> entityHit = target.inflate(margin, margin, margin).clip(from, to);
            if (entityHit.isPresent()) return entityHit.get().subtract(from);
        }
        return hit == null ? deltaMovement : to.subtract(from);
    }
}
