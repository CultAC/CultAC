package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Vanilla 26.3 movement sequences from Grim logs 2–8, captured on 2026-10-25 at 03:57–03:59 UTC. */
class JavaWalkingCarryRegressionTest {
    private static final double FALL = 0.0784000015258789;
    private static final ServerboundPlayerInput NONE = input(false, false, false, false, false);

    @ParameterizedTest(name = "Grim log {0}")
    @CsvSource({
        "2,262.26086,259.71088,248.91086,0.013198220170067998,0.09710719668777301,0.089414852197649,0.10995682102080906,0.14024148483338195,0.10056055936809116,false",
        "3,-52.61881,-53.818813,-55.018814,-0.013254424066815318,-0.0991130345758151,-0.02256947492759309,-0.15292866377509284,-0.02971743495032797,-0.181970012802239,false",
        "4,-30.568811,-29.368814,-27.418816,0.04983299974981037,0.08437929979844228,0.1235059848546598,0.07301614362374664,0.16275885458742323,0.07007343115374454,false",
        "5,26.131187,32.431187,39.03118,-0.0879833777382828,-0.043161620192762484,-0.14564054061173692,-0.001796462115166264,-0.20881443229404795,0.012542550719999213,true"
    })
    void turningAfterIdleRetainsTheWholeMovementInput(
            int log,
            float yaw0,
            float yaw1,
            float yaw2,
            double x0,
            double z0,
            double x1,
            double z1,
            double x2,
            double z2,
            boolean sprintLast)
            throws Exception {
        try (var f = idle()) {
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
            boolean left = log == 3 || log == 4;
            for (int tick = 0; tick < 3; tick++) {
                f.player.xRot = new float[] {yaw0, yaw1, yaw2}[tick];
                if (tick == 2 && sprintLast) sprint(f);
                f.input(
                        tick == 0 && log != 3
                                ? input(log == 4, false, false, log != 4, false)
                                : input(log != 3, log == 3, left, !left, tick == 2 && sprintLast));
                position = position.add(new double[] {x0, x1, x2}[tick], 0, new double[] {z0, z1, z2}[tick]);
                var result = f.tick(position, true);
                assertTrue(exact(result), "Grim log " + log + " tick " + tick + ": " + describe(result));
            }
        }
    }

    @ParameterizedTest(name = "Grim log {0}")
    @ValueSource(ints = {6, 7, 8})
    void turningDuringASprintJumpRetainsTheWholeMovementInput(int log) throws Exception {
        try (var f = idle()) {
            sprint(f);
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
            float[] yaw = {-366.26776F, -364.91782F, -362.5178F, -359.96774F, -358.6178F};
            double[][] delta = {
                {0.013908763984147754, 0, 0.12663850016328837},
                {0.03565295425067916, 0.41999998688697815, 0.39534008571077806},
                {0.0019064830171942049, 0.33319999363422426, 0.23502978348899717},
                {-0.016660446333105483, 0.24813599859093927, 0.23225130365268853},
                {-0.03398455151790358, 0.1647732818260721, 0.22928396426575404}
            };
            for (int tick = 0; tick <= log - 4; tick++) {
                f.player.xRot = yaw[tick];
                f.input(new ServerboundPlayerInput(true, false, false, tick >= 2, tick >= 1, false, true));
                position = position.add(delta[tick][0], delta[tick][1], delta[tick][2]);
                var result = f.tick(position, tick == 0);
                assertTrue(exact(result), "Grim log " + log + " tick " + tick + ": " + describe(result));
            }
        }
    }

    private static JavaTickFixture idle() throws Exception {
        var f = new JavaTickFixture();
        Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
        f.place(position, new Vec3(0, -FALL, 0));
        f.input(NONE);
        assertTrue(exact(f.tick(position, true)));
        for (int tick = 0; tick < 25; tick++) f.tickEnd();
        return f;
    }

    private static ServerboundPlayerInput input(
            boolean forward, boolean backward, boolean left, boolean right, boolean sprint) {
        return new ServerboundPlayerInput(forward, backward, left, right, false, false, sprint);
    }

    private static void sprint(JavaTickFixture f) {
        var command = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
        new PacketEntityAction()
                .onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, command), f.player, command);
    }
}
