package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.enums.BoatEntityStatus;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.VelocityCandidates;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** AbstractBoat#getDefaultGravity and floatBoat use doubles throughout 1.21.2–26.3. */
class JavaBoatGravityVersionsTest {
    private static final int BOAT = 100;
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y + 8, 0.5);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    @ParameterizedTest(name = "{0}: boat gravity is the vanilla double")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void airTravelUsesDoubleGravity(String release, ClientVersion version) throws Exception {
        travel(version, false, true, -0.04);
    }

    @ParameterizedTest(name = "{0}: buoyancy uses double default gravity")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void buoyancyUsesDoubleDefaultGravity(String release, ClientVersion version) throws Exception {
        travel(version, true, true, (-0.04 + (double) 0.01F * (0.04 / 0.65)) * 0.75);
    }

    @ParameterizedTest(name = "{0}: NoGravity does not disable default-gravity buoyancy")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void buoyancyDoesNotReadEffectiveGravity(String release, ClientVersion version) throws Exception {
        travel(version, true, false, ((double) 0.01F * (0.04 / 0.65)) * 0.75);
    }

    private static void travel(ClientVersion version, boolean submerged, boolean gravity, double expectedY)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            if (submerged) {
                for (int x = -2; x <= 2; x++)
                    for (int y = FLOOR_Y + 6; y <= FLOOR_Y + 11; y++)
                        for (int z = -2; z <= 2; z++)
                            f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), BlockIds.WATER.defaultState());
                for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            }
            f.place(START, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, START, 0, 0, 0);
            var boat = f.player.compensatedEntities.getEntity(BOAT);
            boat.hasGravity = gravity;
            // The old status is an already-submerged boat's state before this tick, so no entry snap runs.
            boat.boatStatus = submerged ? BoatEntityStatus.UNDER_WATER : BoatEntityStatus.IN_AIR;
            f.setPassengers(BOAT, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.input(NONE);
            var result = f.rideTick(START.add(0, expectedY, 0), false);
            assertTrue(exact(result), describe(result));
            assertEquals(
                    expectedY,
                    result.getAcceptedClosestToTarget().y,
                    1e-13,
                    "the accepted physical movement must use the source double, not an input/offset allowance");
            assertEquals(
                    expectedY,
                    VelocityCandidates.preCollisionVelocity(result).y,
                    1e-13,
                    "the carried source delta must retain its precision even when the packet endpoint rounds");
            assertEquals(START.y + expectedY, boat.clientPhysicalPosition.y, 1e-13);
        }
    }
}
