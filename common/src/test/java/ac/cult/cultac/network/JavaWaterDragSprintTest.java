package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.Friction;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * LivingEntity#travelInWater reads the traveller's isSprinting once for its 0.9F or getWaterSlowDown slowdown and
 * again in getFluidFallingAdjustedMovement, which subtracts gravity / 16 only when it is clear.
 */
class JavaWaterDragSprintTest {
    private static final int VEHICLE = 100;
    private static final Vec3 VELOCITY = new Vec3(0.2, 0.1, 0.4);
    private static final double GRAVITY = 0.08;
    private static final Vec3 SPRINTING = new Vec3(0.2 * 0.9F, 0.1 * 0.8F, 0.4 * 0.9F);
    private static final Vec3 WALKING = new Vec3(0.2 * 0.8F, 0.1 * 0.8F - GRAVITY / 16, 0.4 * 0.8F);

    /** Only cats, ocelots and players call setSprinting, so a ridden pig's own flag stays clear. */
    @Test
    void aRiddenPigOnlySlowsByItsNonSprintingWaterDrag() throws Exception {
        try (var f = new JavaTickFixture()) {
            SimulationContext context = context(f);
            f.player.compensatedEntities.addEntity(VEHICLE, EntityTypeIds.PIG, context.getStart(), 0, 0, 0);
            JavaTickFixture.setField(
                    SimulationContext.class, context, "vehicle", f.player.compensatedEntities.getEntity(VEHICLE));
            for (DesyncStatus rider : DesyncStatus.values()) {
                context.setIsSprinting(rider);
                assertEquals(List.of(WALKING), drag(f, context), "rider sprint " + rider);
            }
        }
    }

    /** Each sprint state the player may hold keeps its own slowdown and fluid-falling pair. */
    @Test
    void aPlayerPairsEachSprintStateWithItsOwnWaterDrag() throws Exception {
        try (var f = new JavaTickFixture()) {
            SimulationContext context = context(f);
            context.setIsSprinting(DesyncStatus.TRUE);
            assertEquals(List.of(SPRINTING), drag(f, context));
            context.setIsSprinting(DesyncStatus.FALSE);
            assertEquals(List.of(WALKING), drag(f, context));
            context.setIsSprinting(DesyncStatus.UNKNOWN);
            assertEquals(List.of(WALKING, SPRINTING), drag(f, context));
        }
    }

    private static SimulationContext context(JavaTickFixture f) throws Exception {
        Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
        f.place(position, Vec3.ZERO);
        f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
        SimulationContext context = f.tick(position, true).getSimulationContext();
        JavaTickFixture.setField(SimulationContext.class, context, "waterMovementEfficiency", 0.0F);
        return context;
    }

    private static List<Vec3> drag(JavaTickFixture f, SimulationContext context) {
        var velocities = new ArrayList<Vec3>();
        Friction.applyTravelDrag(
                f.player, false, context.getStart(), VELOCITY, context, false, GRAVITY, true, false, false, velocities);
        return velocities;
    }
}
