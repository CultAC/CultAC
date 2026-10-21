package ac.cult.cultac.utils.data.packetentity;

import ac.cult.blocksim.engine.EntityCollisionContext;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.interaction.BlockHit;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.blockplace.ClientEntityQueries;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.NativeBlockCollisionHelper;

/**
 * An {@code AbstractHurtingProjectile} (fireballs, wind charges), which the client moves along its delta movement every
 * tick between server positions.
 */
public final class PacketEntityHurtingProjectile extends PacketEntity {
    private final CultPlayer player;
    // AbstractHurtingProjectile#accelerationPower, which only projectile_power changes after spawn.
    public double accelerationPower;
    private final float inertia;
    private final float liquidInertia;
    // Entity#isInWater: wasTouchingWater, which baseTick refreshes after each move.
    private boolean inWater;
    // Entity#tickCount, which ClientLevel#tickNonPassenger increments before each tick.
    private int tickCount;

    public PacketEntityHurtingProjectile(CultPlayer player, int id, int type, Vec3 position) {
        super(player, id, type, position.x, position.y, position.z);
        this.player = player;
        boolean windCharge = isWindCharge(type);
        // AbstractWindCharge's constructors, getInertia and getLiquidInertia; AbstractHurtingProjectile's otherwise.
        accelerationPower = windCharge ? 0.0D : 0.1D;
        inertia = windCharge ? 1.0F : 0.95F;
        liquidInertia = windCharge ? inertia : 0.8F;
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
     * block hit of {@code ProjectileUtil#getHitResultOnMoveVector} or one delta movement on. Each possible location
     * moves from its own position.
     */
    @Override
    public void onMovement(CultPlayer player, boolean tickingReliably) {
        tickCount++;
        super.onMovement(player, tickingReliably);
        if (!modelled()) return;
        Vec3 movement = deltaMovement;
        deltaMovement =
                movement.add(movement.normalize().scale(accelerationPower)).scale(inWater ? liquidInertia : inertia);
        if (deltaMovement.lengthSqr() != 0.0D) {
            newPacketLocation.offset(from -> step(player, from));
            if (oldPacketLocation != null) oldPacketLocation.offset(from -> step(player, from));
            if (clientPhysicalPosition != null)
                clientPhysicalPosition = clientPhysicalPosition.add(step(player, clientPhysicalPosition));
        }
        inWater = clientPhysicalPosition != null && touchesWater(player, clientPhysicalPosition);
    }

    // Bedrock clients see Geyser's per-tick absolute moves, which the Geyser bridge already applies. 1.21.1 tests the
    // hit on the old delta, moves, then applies inertia; this models the 1.21.2+ order only.
    private boolean modelled() {
        return getRiding() == null
                && !player.isBedrockMovement()
                && !player.getClientVersion().isOlderThan(ClientVersion.V_1_21_2);
    }

    /**
     * {@code Player#attack} before 26.3: {@code deflectProjectile} aims a {@code #redirectable_projectile} along the
     * attacker's look ({@code ProjectileDeflection#AIM_DEFLECT}) on the client too. {@code WindCharge#deflect} refuses
     * until the projectile has ticked five times. Mounted, the vehicle can turn the player between the attack and the
     * rotation the client sends, so the look is not known. The deflection lands in the attack's client tick, whether
     * or not the projectile ticks in it.
     */
    public void onLocalAttack(ClientEntityQueries queries) {
        if (!modelled()
                || !player.getClientVersion().isOlderThan(ClientVersion.V_26_3)
                || player.compensatedEntities.getSelf().inVehicle()
                || !queries.redirectable(this)
                || type == EntityTypeIds.WIND_CHARGE && tickCount < 5) return;
        player.compensatedEntities.deflectAlongLookAtTickEnd(this);
    }

    /**
     * {@code ProjectileDeflection#AIM_DEFLECT}: the delta movement becomes the look the local player attacked with,
     * which is the rotation its client tick ends with.
     */
    public void aimAlongLocalLook() {
        deltaMovement = lookAngle(player.xRot, player.yRot);
    }

    /** {@code Entity#calculateViewVector} of the local player's rotation, as {@code Entity#getLookAngle}. */
    private static Vec3 lookAngle(float yaw, float pitch) {
        float pitchRadians = pitch * ((float) Math.PI / 180.0F);
        float yawRadians = -yaw * ((float) Math.PI / 180.0F);
        float cosPitch = CultMath.cos(pitchRadians);
        return new Vec3(
                CultMath.sin(yawRadians) * cosPitch, -CultMath.sin(pitchRadians), CultMath.cos(yawRadians) * cosPitch);
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

    private Vec3 step(CultPlayer player, Vec3 from) {
        // ClipContext.Block.COLLIDER with CollisionContext.of(this projectile).
        EntityCollisionContext context = EntityCollisionContext.position(from.y);
        BlockHit hit = player.compensatedWorld
                .geometry()
                .clipBlocks(
                        from,
                        from.add(deltaMovement),
                        (state, pos) -> NativeBlockCollisionHelper.getCollisionShape(
                                player, state, pos, from.y, null, context));
        if (hit == null) return deltaMovement;
        return new Vec3(hit.location().x(), hit.location().y(), hit.location().z()).subtract(from);
    }
}
