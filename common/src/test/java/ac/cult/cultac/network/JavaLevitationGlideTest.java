package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.setField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRemoveMobEffect;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundUpdateMobEffect;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/** A 1.21.11 glide that starts the tick after the server removes levitation (SuperDebug pastes 2026-10-21). */
class JavaLevitationGlideTest {
    private static final ServerboundPlayerInput SPRINT_FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    // The recorded End-dimension coordinates, moved by whole blocks onto the fixture's loaded chunks.
    private static final Vec3 SHIFT = new Vec3(1208, 0, -1080);

    @Test
    void levitationStaysUntilTheClientHandlesItsRemoval() throws Exception {
        try (var f = new JavaTickFixture()) {
            prepare(f);
            int added = updateEffect(f, "minecraft:levitation", 0);
            f.pongThrough(added);

            Vec3 previousMove = new Vec3(-0.13975293112093823, 0.045370374458485685, 0.24365362433991322);
            // LivingEntity#travelInAir: levitation I, then the 0.91F horizontal and 0.98F vertical drag.
            double levitatedY = (previousMove.y + (0.05 - previousMove.y) * 0.2) * 0.98F;
            Vec3 start = shift(new Vec3(-1207.706997430277, 107.79550817171723, 1080.8953262769194));
            rotate(f, -1767.6825F, 57.286465F);
            f.place(start, new Vec3(previousMove.x * 0.91F, levitatedY, previousMove.z * 0.91F));

            int removed = removeEffect(f, "minecraft:levitation");
            // The client ticks once more before it handles the removal.
            f.input(SPRINT_FORWARD);
            PredictionResult levitating =
                    f.tick(shift(new Vec3(-1207.8477960834364, 107.84087854617572, 1081.1385831759353)), false);
            assertTrue(levitating.getOffset() < 1.0E-6, describe(levitating));

            f.pongThrough(removed);
            // LocalPlayer#aiStep: tryToStartFallFlying needs canGlide, which levitation forbids.
            f.player.isGliding = true;
            f.player.refreshPlayerPose();
            rotate(f, -1767.0519F, 56.8361F);
            PredictionResult gliding =
                    f.tick(shift(new Vec3(-1207.9759725856197, 107.8250263191812, 1081.3574387528713)), false);
            assertTrue(gliding.getSimulationContext().usesFallFlyingMovement());
            assertTrue(gliding.getOffset() < 1.0E-6, describe(gliding));
        }
    }

    @Test
    void theFlaggedGlideIsTheCarriedVerticalVelocity() throws Exception {
        Vec3 from = shift(new Vec3(-1214.6069429187385, 92.92129421897066, 1090.7182121249486));
        Vec3 to = shift(new Vec3(-1215.110939914054, 92.25516182998595, 1091.2585038079528));
        // Paste ip5mXRXKPI: Cult's carried velocity, and the client's own (its previous uncollided move).
        Vec3 carried = new Vec3(-0.4743549434108445, -0.6834765809953322, 0.524686344092288);
        Vec3 client = new Vec3(-0.4743549434108445, -0.6800727831209059, 0.524686344092288);
        assertEquals(0.003114457067606269, glide(from, to, carried).getOffset(), 1.0E-6);
        PredictionResult vanilla = glide(from, to, client);
        assertTrue(vanilla.getOffset() < 1.0E-6, describe(vanilla));
    }

    private static PredictionResult glide(Vec3 from, Vec3 to, Vec3 velocity) throws Exception {
        try (var f = new JavaTickFixture()) {
            prepare(f);
            f.player.isGliding = true;
            f.player.refreshPlayerPose();
            rotate(f, -1752.0973F, 39.8123F);
            f.place(from, velocity);
            rotate(f, -1751.827F, 38.821503F);
            f.input(SPRINT_FORWARD);
            return f.tick(to, false);
        }
    }

    private static void prepare(JavaTickFixture f) throws Exception {
        setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21_11);
        f.player.isSprinting = true;
        f.player.sprintState.onClientTick(true);
    }

    private static void rotate(JavaTickFixture f, float yaw, float pitch) {
        f.player.xRot = yaw;
        f.player.yRot = pitch;
    }

    private static Vec3 shift(Vec3 position) {
        return position.add(SHIFT);
    }

    private static int updateEffect(JavaTickFixture f, String effect, int amplifier) {
        var packet = new ClientboundUpdateMobEffect(f.player.entityID, effect, amplifier);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.UPDATE_MOB_EFFECT, packet, false);
        f.player.packetEntityReplication.onUpdateMobEffect(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        return f.player.lastTransactionSent.get();
    }

    private static int removeEffect(JavaTickFixture f, String effect) {
        var packet = new ClientboundRemoveMobEffect(f.player.entityID, effect);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.REMOVE_MOB_EFFECT, packet, false);
        f.player.packetEntityReplication.onRemoveMobEffect(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        return f.player.lastTransactionSent.get();
    }
}
