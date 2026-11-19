package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundUpdateMobEffect;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** MobEffectInstance clamps the amplifier of a received effect to 0..255; levitation then rises by that amplifier. */
class JavaEffectAmplifierClampTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final Vec3 AIR = new Vec3(0.5, 70, 0.5);

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_1_21_11", "V_26_3"})
    void anAmplifierAbove255LevitatesAs255(ClientVersion version) throws Exception {
        assertTrue(exact(levitate(version, 300, 255)), "rise at 255");
        assertFalse(exact(levitate(version, 300, 300)), "rise at 300");
        assertTrue(exact(levitate(version, 1000, 255)), "rise at 255 from 1000");
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_26_3"})
    void anAmplifierInRangeLevitatesAsSent(ClientVersion version) throws Exception {
        for (int amplifier : new int[] {0, 3, 255}) {
            PredictionResult result = levitate(version, amplifier, amplifier);
            assertTrue(exact(result), amplifier + ": " + describe(result));
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_26_3"})
    void aNegativeAmplifierLevitatesAsZero(ClientVersion version) throws Exception {
        PredictionResult result = levitate(version, -5, 0);
        assertTrue(exact(result), describe(result));
    }

    /** Amplifier 255 for fifty ticks: LivingEntity#travelInAir's rise carries the client hundreds of blocks up. */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_11", "V_26_3"})
    void anAmplifier255RisesPastTheBuildHeightExactly(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.place(AIR, Vec3.ZERO);
            f.input(NONE);
            var packet = new ClientboundUpdateMobEffect(f.player.entityID, "minecraft:levitation", 255);
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.UPDATE_MOB_EFFECT, packet, false);
            f.player.packetEntityReplication.onUpdateMobEffect(event, f.player, packet);
            event.tasksAfter().forEach(Runnable::run);
            f.pongThrough(f.player.lastTransactionSent.get());
            Vec3 position = AIR;
            double deltaY = 0.0;
            for (int tick = 0; tick < 50; tick++) {
                position = position.add(0.0, deltaY, 0.0);
                PredictionResult result = f.tick(position, false);
                assertTrue(exact(result), "tick " + tick + " at " + position + ": " + describe(result));
                deltaY = (deltaY + (0.05 * 256 - deltaY) * 0.2) * 0.98F;
            }
            assertTrue(position.y > 400, "rose to " + position.y);
        }
    }

    /** The same effect sent just before a teleport the client answers with it: the rise starts from the teleport. */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_11", "V_26_3"})
    void anAmplifier255SentBeforeATeleportRisesFromItsTarget(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.place(AIR, Vec3.ZERO);
            f.input(NONE);
            var packet = new ClientboundUpdateMobEffect(f.player.entityID, "minecraft:levitation", 255);
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.UPDATE_MOB_EFFECT, packet, false);
            f.player.packetEntityReplication.onUpdateMobEffect(event, f.player, packet);
            event.tasksAfter().forEach(Runnable::run);
            Vec3 position = AIR.add(0.0, 0.0, 3.0);
            f.teleport(position, new RelativeFlag(0));
            double deltaY = 0.0;
            for (int tick = 0; tick < 20; tick++) {
                position = position.add(0.0, deltaY, 0.0);
                PredictionResult result = f.tick(position, false);
                assertTrue(exact(result), "tick " + tick + " at " + position + ": " + describe(result));
                deltaY = (deltaY + (0.05 * 256 - deltaY) * 0.2) * 0.98F;
            }
        }
    }

    /** The server sends {@code sent}; the client rises as its effect of amplifier {@code applied} moves it. */
    private static PredictionResult levitate(ClientVersion version, int sent, int applied) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.place(AIR, Vec3.ZERO);
            f.input(NONE);
            var packet = new ClientboundUpdateMobEffect(f.player.entityID, "minecraft:levitation", sent);
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.UPDATE_MOB_EFFECT, packet, false);
            f.player.packetEntityReplication.onUpdateMobEffect(event, f.player, packet);
            event.tasksAfter().forEach(Runnable::run);
            f.pongThrough(f.player.lastTransactionSent.get());
            f.tick(AIR, false);
            // LivingEntity#travelInAir: y += (0.05 * (amplifier + 1) - y) * 0.2, then the 0.98F drag.
            double rise = (0.05 * (applied + 1) * 0.2) * 0.98F;
            return f.tick(AIR.add(0, rise, 0), false);
        }
    }
}
