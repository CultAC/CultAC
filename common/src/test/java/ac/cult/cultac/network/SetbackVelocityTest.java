package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A setback that rejects a movement sends the position and velocity the 26.3 client reaches by one input-less tick
 * from the safe state.
 */
class SetbackVelocityTest {
    private static final double GRAVITY = 0.08D;
    // LivingEntity#travelInAir: computeModifiedFriction(0.98F, 1.0) for the vertical drag and Entity#getAirDrag.
    private static final double VERTICAL_DRAG = 0.98F;

    @Test
    void aSetbackLandingOnSlimeBounces() throws Exception {
        try (var f = new JavaTickFixture()) {
            slimeFloor(f);
            f.place(new Vec3(.5, JavaTickFixture.FLOOR_Y + .5, .5), new Vec3(0, -.3, 0));
            f.tick(new Vec3(.5, JavaTickFixture.FLOOR_Y + .2, .5), false);
            SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
            double carried = teleports.safePosition().getVector().y;

            SetBackData setback = violationSetback(f);

            assertEquals(JavaTickFixture.FLOOR_Y, setback.getTeleportData().getLocation().y, 1.0E-12);
            double bounced = restitutedY(carried, JavaTickFixture.FLOOR_Y - (JavaTickFixture.FLOOR_Y + .2), 1.0D);
            assertEquals((bounced - GRAVITY) * VERTICAL_DRAG, setback.getVelocity().y, 1.0E-12);
        }
    }

    @Test
    void aSneakingSetbackLandingOnSlimeDoesNotBounce() throws Exception {
        try (var f = new JavaTickFixture()) {
            slimeFloor(f);
            f.input(new ServerboundPlayerInput(false, false, false, false, false, true, false));
            f.place(new Vec3(.5, JavaTickFixture.FLOOR_Y + .5, .5), new Vec3(0, -.3, 0));
            f.tick(new Vec3(.5, JavaTickFixture.FLOOR_Y + .2, .5), false);

            SetBackData setback = violationSetback(f);

            // Entity#isSuppressingBounce: the shift key zeroes the restitution.
            assertEquals(JavaTickFixture.FLOOR_Y, setback.getTeleportData().getLocation().y, 1.0E-12);
            assertEquals(-GRAVITY * VERTICAL_DRAG, setback.getVelocity().y, 1.0E-12);
        }
    }

    @Test
    void theSafeStateOfALandingOnSlimeCarriesTheBounce() throws Exception {
        try (var f = new JavaTickFixture()) {
            slimeFloor(f);
            double falling = -.37;
            f.place(new Vec3(.5, JavaTickFixture.FLOOR_Y + .2, .5), new Vec3(0, falling, 0));
            f.tick(new Vec3(.5, JavaTickFixture.FLOOR_Y, .5), true);

            double bounced = restitutedY(falling, -.2, 1.0D);
            assertEquals(
                    (bounced - GRAVITY) * VERTICAL_DRAG,
                    f.player.getSetbackTeleportUtil().safePosition().getVector().y,
                    1.0E-12);
        }
    }

    /**
     * Entity#move clears the deltaMovement of an axis it collides on before LivingEntity#travel's friction, so a travel
     * into a wall carries none of that axis into the next tick; neither does the safe state, which a setback that runs
     * no tick of its own sends as is.
     */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_5", "V_26_3"})
    void theSafeStateOfATravelIntoAWallCarriesNoVelocityIntoIt(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            for (int y = 0; y < 2; y++) {
                f.player.compensatedWorld.updateBlock(
                        new BlockPos(1, JavaTickFixture.FLOOR_Y + y, 0), BlockIds.STONE.defaultState());
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
            f.place(new Vec3(.6, JavaTickFixture.FLOOR_Y, .5), new Vec3(.2, -0.0784000015258789, 0));
            var result = f.tick(new Vec3(.7, JavaTickFixture.FLOOR_Y, .5), true);
            assertTrue(JavaTickFixture.exact(result), JavaTickFixture.describe(result));

            SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
            assertEquals(0.0D, teleports.safePosition().getVector().x);
            f.pongThrough(f.player.sendTrailingTransaction());
            teleports.executeNonSimulatingSetback();
            assertEquals(0.0D, teleports.getRequiredSetBack().getVelocity().x);
        }
    }

    @Test
    void aLevitatingSetbackRises() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedEntities.getSelf().addPotionEffect(MovementEffect.LEVITATION, 0);
            Vec3 start = new Vec3(.5, JavaTickFixture.FLOOR_Y + 6, .5);
            f.place(start, new Vec3(0, .1, 0));
            f.tick(start.add(0, .1, 0), false);
            SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
            double carried = levitated(.1, 0);
            assertEquals(carried, teleports.safePosition().getVector().y, 1.0E-12);

            SetBackData setback = violationSetback(f);

            assertEquals(start.y + .1 + carried, setback.getTeleportData().getLocation().y, 1.0E-12);
            assertEquals(levitated(carried, 0), setback.getVelocity().y, 1.0E-12);
        }
    }

    private static SetBackData violationSetback(JavaTickFixture f) {
        // The client has the chunk it stands in once it answers a ping sent after it.
        f.pongThrough(f.player.sendTrailingTransaction());
        SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
        // The client's next movement arrives and this setback rejects it; the correction tick stands in for it.
        teleports.movementReceived();
        assertTrue(teleports.executeViolationSetback());
        return teleports.getRequiredSetBack();
    }

    private static void slimeFloor(JavaTickFixture f) {
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                f.player.compensatedWorld.updateBlock(
                        new BlockPos(x, JavaTickFixture.FLOOR_Y - 1, z), BlockIds.SLIME_BLOCK.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
    }

    /** Entity#restituteMovementAfterCollisions for a downward collision that bounces. */
    private static double restitutedY(double deltaY, double movedY, double restitution) {
        double portionWithMovement = movedY / deltaY;
        double gravityCompensation = portionWithMovement * GRAVITY;
        double effectiveDrag = 1.0D + portionWithMovement * (VERTICAL_DRAG - 1.0D);
        return (gravityCompensation - deltaY) * effectiveDrag * restitution;
    }

    /** LivingEntity#travelInAir: levitation replaces gravity before the vertical drag. */
    private static double levitated(double y, int amplifier) {
        return (y + (0.05D * (amplifier + 1) - y) * 0.2D) * VERTICAL_DRAG;
    }
}
