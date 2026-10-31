package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.data.FluidTags;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.enums.BoatEntityStatus;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;

/** Entity's fluid update for the local passenger, including AbstractBoat's passenger interaction box. */
public final class JavaPassengerFluids {
    private JavaPassengerFluids() {}

    public record Sample(Vec3 velocity, boolean water, boolean lava, double waterHeight, double lavaHeight, double floatableHeight, int eye) {}

    public static Sample sample(
            CultPlayer player, SimpleCollisionBox body, Vec3 feet, Vec3 velocity, SimpleCollisionBox vehicleBox) {
        return sample(player, body, feet, velocity, vehicleBox,
                feet.y + player.pose.eyeHeight * player.dimensionScale);
    }

    public static Sample sample(
            CultPlayer player, SimpleCollisionBox body, Vec3 feet, Vec3 velocity,
            SimpleCollisionBox vehicleBox, double eyeY) {
        PacketEntity vehicle = player.compensatedEntities.getSelf().getRiding();
        SimpleCollisionBox query = body.copy().expand(-.001D);
        boolean waterAllowed = true;
        if (vehicle != null && vehicle.isBoat()) {
            if (vehicleBox == null) vehicleBox = vehicle.possibleMovementCollisionBoxCandidate(0, new SimpleCollisionBox());
            BoatEntityStatus status = vehicle.boatStatus;
            if (status == null) status = ac.cult.cultac.utils.data.BoatData.sampleStatus(player, vehicleBox).status();
            boolean under = status == BoatEntityStatus.UNDER_WATER || status == BoatEntityStatus.UNDER_FLOWING_WATER;
            if (!under) {
                if (player.getClientVersion().isOlderThan(ClientVersion.V_26_1)) waterAllowed = false;
                else if (vehicleBox.maxY >= query.maxY) return new Sample(velocity, false, false, 0, 0, 0, 0);
                else query.minY = Math.max(query.minY, vehicleBox.maxY);
            }
        }
        if (!ClientFluidQueries.hasLoadedFluidNeighborhood(player, body))
            return new Sample(velocity, false, false, 0, 0, 0, 0);
        double reference = ClientFluidQueries.usesLegacyFluidDepth(player) ? query.minY : body.minY;
        double waterHeight = 0, lavaHeight = 0, floatableHeight = 0;
        boolean water = false, lava = false;
        var pos = new BlockPos.MutableBlockPos();
        for (int x = (int) Math.floor(query.minX); x < Math.ceil(query.maxX); x++) {
            for (int y = (int) Math.floor(query.minY); y < Math.ceil(query.maxY); y++) {
                for (int z = (int) Math.floor(query.minZ); z < Math.ceil(query.maxZ); z++) {
                    pos.set(x, y, z);
                    var fluid = ClientFluidQueries.fluidAt(player.compensatedWorld, pos);
                    // Entity's old updater tests the received tag even for EmptyFluid; 26.1's tracker skips it.
                    if (fluid.isEmpty() && !ClientFluidQueries.usesLegacyFluidDepth(player)) continue;
                    double top = ClientFluidQueries.fluidSurfaceY(
                            player, y, ClientFluidQueries.height(player.compensatedWorld, pos, fluid));
                    if (top < query.minY) continue;
                    if (waterAllowed && ClientFluidTagOrder.contactMatches(player, fluid, FluidTags.WATER)) {
                        water = true;
                        waterHeight = Math.max(waterHeight, top - reference);
                    }
                    if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3)
                            && ClientFluidTagOrder.contactMatches(player, fluid, FluidTags.ENTITY_FLOATABLE))
                        floatableHeight = Math.max(floatableHeight, top - reference);
                    if (ClientFluidTagOrder.contactMatches(player, fluid, FluidTags.LAVA)) {
                        lava = true;
                        lavaHeight = Math.max(lavaHeight, top - reference);
                    }
                }
            }
        }
        if (!player.isFlying) {
            FluidCurrent current = new FluidCurrent();
            if (waterAllowed && current.computePassenger(player, body, query, velocity.x, velocity.z, FluidTags.WATER, .014D))
                velocity = velocity.add(current.x, current.y, current.z);
            if (current.computePassenger(player, body, query, velocity.x, velocity.z, FluidTags.LAVA,
                    player.compensatedWorld.hasFastLava() ? .007D : .0023333333333333335D))
                velocity = velocity.add(current.x, current.y, current.z);
        }
        // Older updateFluidOnEyes is independent of the body/boat query; the combined tracker shares its bounds.
        int eye = ClientFluidQueries.eyeFluidContact(
                player, body, feet, eyeY);
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_1)
                && eyeY < query.minY) eye = 0;
        return new Sample(velocity, water, lava, waterHeight, lavaHeight, floatableHeight, eye);
    }
}
