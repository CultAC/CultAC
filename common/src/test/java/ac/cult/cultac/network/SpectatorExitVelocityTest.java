package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.PacketChangeGameState;
import ac.cult.cultac.events.packets.PacketPlayerAbilities;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundGameEvent;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerAbilities;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.GameEventType;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * Entity#move's noPhysics branch moves a spectator by its whole deltaMovement, and Player#travel leaves 0.91F of that
 * move horizontally and 0.6 of its Y. A switch to survival keeps that deltaMovement for the first survival travel.
 */
class SpectatorExitVelocityTest {
    /** A 26.3 spectator flying without input, then four survival ticks holding forward and sprint, as captured. */
    private static final Vec3[] SPECTATOR = {
        new Vec3(-342.3987186022535, 84.05066929053281, -190.67479642798565),
        new Vec3(-345.9286837891506, 84.05066929053281, -190.40923676603617),
        new Vec3(-349.14095220180405, 84.05066929053281, -190.16757746669757)
    };

    private static final Vec3[] SURVIVAL = {
        new Vec3(-352.08954288719724, 84.05066929053281, -189.94601483974438),
        new Vec3(-354.79818683386833, 83.97226928900693, -189.742740185189),
        new Vec3(-357.28847924200954, 83.81703728449033, -189.55610758599525),
        new Vec3(-359.58007174436204, 83.58650991557737, -189.38461925761703)
    };
    private static final float YAW = 86.28545F;
    private static final float PITCH = 6.5937653F;
    private static final ServerboundPlayerInput SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);

    @Test
    void theFirstSurvivalTicksContinueTheLastSpectatorMove() throws Exception {
        try (var f = spectating(SPECTATOR)) {
            sprinting(f);
            PredictionResult last = null;
            for (int tick = 0; tick < SURVIVAL.length; tick++) {
                last = f.tick(SURVIVAL[tick], false);
                assertTrue(last.getOffset() < 1.0E-5, "tick " + tick + " " + describe(last));
            }
            assertTrue(exact(last), describe(last));
        }
    }

    @Test
    void aSurvivalMovePastTheCarriedVelocityAndInputFlags() throws Exception {
        try (var f = spectating(SPECTATOR)) {
            sprinting(f);
            for (int tick = 0; tick < SURVIVAL.length - 1; tick++) f.tick(SURVIVAL[tick], false);
            PredictionResult faster = f.tick(SURVIVAL[SURVIVAL.length - 1].add(-0.1, 0, 0), false);
            assertFalse(faster.isExempt(), describe(faster));
            assertTrue(faster.hasEffectiveFlags(), describe(faster));
        }
    }

    @Test
    void theFirstSurvivalTicksFallFromSixTenthsOfTheLastSpectatorDescent() throws Exception {
        // LocalPlayer#aiStep adds -0.6F a tick to a spectator holding shift at the 0.2F flying speed.
        double first = -0.6F;
        double second = first * 0.6 + first;
        Vec3 top = new Vec3(0.5, FLOOR_Y + 40, 0.5);
        Vec3 below = top.add(0, first, 0);
        Vec3 bottom = below.add(0, second, 0);
        try (var f = spectating(top, below, bottom)) {
            survival(f);
            double y = bottom.y;
            double velocity = second * 0.6;
            PredictionResult last = null;
            for (int tick = 0; tick < 6; tick++) {
                // LivingEntity#travelInAir moves by the velocity, then applies gravity and drag.
                y += velocity;
                velocity = (velocity - 0.08) * (double) 0.98F;
                last = f.tick(new Vec3(bottom.x, y, bottom.z), false);
                assertTrue(last.getOffset() < 1.0E-5, "tick " + tick + " " + describe(last));
            }
            assertTrue(exact(last), describe(last));
        }
    }

    /** A spectator placed at {@code moves[0]} at rest, then sent each later move as an exempt client tick. */
    private static JavaTickFixture spectating(Vec3... moves) throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.player.nettyScheduler.removeScheduler();
        f.fixture.channel.freezeTime();
        for (int x = -23; x <= -21; x++) {
            for (int z = -13; z <= -11; z++) f.player.compensatedWorld.ensureValidationChunkLoaded(x, z);
        }
        f.player.xRot = YAW;
        f.player.yRot = PITCH;
        f.place(moves[0], Vec3.ZERO);
        gameMode(f, GameMode.SPECTATOR);
        abilities(f, true, true);
        f.pongThrough(f.player.lastTransactionSent.get());
        for (int tick = 1; tick < moves.length; tick++) {
            PredictionResult result = f.tick(moves[tick], false);
            assertTrue(result.isExempt(), "spectator tick " + tick + " " + describe(result));
        }
        return f;
    }

    /** Survival, then the forward and sprint keys and START_SPRINTING the captured client sent with its first move. */
    private static void sprinting(JavaTickFixture f) {
        survival(f);
        f.input(SPRINT);
        f.command(PlayerCommandAction.START_SPRINTING);
    }

    /** ServerPlayer#setGameMode(SURVIVAL), applied by the client before its next tick. */
    private static void survival(JavaTickFixture f) {
        abilities(f, false, false);
        gameMode(f, GameMode.SURVIVAL);
        abilities(f, false, false);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void gameMode(JavaTickFixture f, GameMode mode) {
        var packet = new ClientboundGameEvent(GameEventType.CHANGE_GAME_MODE, mode == GameMode.SPECTATOR ? 3 : 0);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.GAME_EVENT, packet, false);
        f.player.checkManager.getListener(PacketChangeGameState.class).onGameEvent(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
    }

    private static void abilities(JavaTickFixture f, boolean canFly, boolean flying) {
        var packet = new ClientboundPlayerAbilities(false, flying, canFly, false, 0.05F, 0.1F);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.PLAYER_ABILITIES, packet, false);
        f.player.checkManager.getListener(PacketPlayerAbilities.class).onPlayerAbilities(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
    }
}
