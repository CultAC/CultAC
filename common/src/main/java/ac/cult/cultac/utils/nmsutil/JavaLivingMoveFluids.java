package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.data.FluidTags;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHappyGhast;
import ac.cult.cultac.utils.data.packetentity.PacketEntityNautilus;
import ac.cult.cultac.utils.math.Vec3;

/** LivingEntity#checkFallDamage's fluid refresh, before Entity#move responds to collisions. */
public final class JavaLivingMoveFluids {
    private JavaLivingMoveFluids() {}

    public record Result(Vec3 velocity, boolean inWater, boolean inLava, boolean resetFallDistance, boolean refreshed,
                         int passengerEyeFluidContact) {
        public Result(Vec3 velocity, boolean inWater, boolean inLava, boolean resetFallDistance, boolean refreshed) {
            this(velocity, inWater, inLava, resetFallDistance, refreshed, -1);
        }
        /** The local player's eye cache changes only when its reached callback refreshed the combined interaction. */
        public int playerEyeFluidContact(
                CultPlayer player, SimpleCollisionBox body, Vec3 position, double eyeY, int previous) {
            if (passengerEyeFluidContact >= 0) return passengerEyeFluidContact;
            return refreshed && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_1)
                    ? ClientFluidQueries.eyeFluidContact(player, body, position, eyeY)
                    : previous;
        }
    }

    public static Result apply(
            CultPlayer player,
            ClientVersion version,
            PacketEntity actor,
            SimpleCollisionBox box,
            Vec3 velocity,
            boolean inWater,
            boolean inLava) {
        return apply(player, version, actor, box, velocity, inWater, inLava, null);
    }

    public static Result apply(
            CultPlayer player, ClientVersion version, PacketEntity actor, SimpleCollisionBox box,
            Vec3 velocity, boolean inWater, boolean inLava, SimpleCollisionBox passengerVehicleBox) {
        return apply(player, version, actor, box, velocity, inWater, inLava, passengerVehicleBox,
                player.pose.eyeHeight * player.dimensionScale);
    }

    public static Result apply(
            CultPlayer player, ClientVersion version, PacketEntity actor, SimpleCollisionBox box,
            Vec3 velocity, boolean inWater, boolean inLava, SimpleCollisionBox passengerVehicleBox, double eyeHeight) {
        if (!refreshes(version, actor, inWater, inLava)) return new Result(velocity, inWater, inLava, false, false);

        if (!player.isBedrockMovement() && actor == player.compensatedEntities.getSelf() && actor.riding != null) {
            Vec3 feet = new Vec3((box.minX + box.maxX) / 2, box.minY, (box.minZ + box.maxZ) / 2);
            var sampled = JavaPassengerFluids.sample(player, box, feet, velocity, passengerVehicleBox, feet.y + eyeHeight);
            return new Result(sampled.velocity(), sampled.water(),
                    version.isNewerThanOrEquals(ClientVersion.V_26_1) ? sampled.lava() : inLava,
                    sampled.water(), true, version.isNewerThanOrEquals(ClientVersion.V_26_1) ? sampled.eye() : -1);
        }
        boolean playerEntity = actor.type == EntityTypeIds.PLAYER;
        boolean pushed = playerEntity ? !player.isFlying : !(actor instanceof PacketEntityNautilus);
        var current = new FluidCurrent();
        boolean waterCurrent =
                current.compute(player, box, playerEntity, velocity.x, velocity.z, FluidTags.WATER, .014D);
        inWater = current.touching;
        if (pushed && waterCurrent) velocity = velocity.add(current.x, current.y, current.z);

        // Through 1.21.11 this callback refreshes only water. The combined interaction refresh
        // starts in 26.1 and applies water first, then lava using the resulting deltaMovement.
        if (version.isNewerThanOrEquals(ClientVersion.V_26_1)) {
            boolean lavaCurrent = current.compute(
                    player,
                    box,
                    playerEntity,
                    velocity.x,
                    velocity.z,
                    FluidTags.LAVA,
                    player.compensatedWorld.hasFastLava() ? .007D : .0023333333333333335D);
            inLava = current.touching;
            if (pushed && lavaCurrent) velocity = velocity.add(current.x, current.y, current.z);
        }
        return new Result(velocity, inWater, inLava, inWater, true);
    }

    /**
     * Whether the callback refreshes the fluid interaction for {@code actor}, which the cached isInWater and isInLava
     * describe. Through 1.21.11 it refreshes water only; from 26.1 every fluid.
     */
    static boolean refreshes(ClientVersion version, PacketEntity actor, boolean inWater, boolean inLava) {
        // HappyGhast's override is empty; Strider in lava resets without calling LivingEntity.
        // The 1.21.4 local/player-controlled Entity#move never reaches the virtual callback.
        return version != ClientVersion.V_1_21_4
                && actor.isLivingEntity()
                && !(actor instanceof PacketEntityHappyGhast)
                && !(actor.isStrider() && inLava)
                && !inWater;
    }
}
