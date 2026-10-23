package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.elytra.ElytraB;
import ac.cult.cultac.events.packets.listeners.PacketPlayerRespawn;
import ac.cult.cultac.network.codec.ModelRegistryNamesState;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * LocalPlayer#aiStep sends START_FALL_FLYING only on a jump key going down: the keys its KeyboardInput read the tick
 * before (wasJumping) did not hold jump. LocalPlayer#tick sends PLAYER_INPUT after aiStep, and only for keys that
 * differ from LocalPlayer#lastSentInput.
 */
class ElytraBJumpPressTest {
    private static final ServerboundPlayerInput JUMP =
            new ServerboundPlayerInput(false, false, false, false, true, false, false);
    private static final Vec3 AIR = new Vec3(0.5, FLOOR_Y + 3, 0.5);

    /**
     * ClientPacketListener#handleRespawn gives the new LocalPlayer a new KeyboardInput, whose keys start at
     * Input.EMPTY, and with KEEP_ENTITY_DATA the old LocalPlayer#lastSentInput. A jump held through a portal goes down
     * again on the new player's first tick without a PLAYER_INPUT.
     */
    @Test
    void aJumpHeldAcrossADimensionChangeStartsAGlide() throws Exception {
        try (var f = jumpHeldInTheAir()) {
            respawnKeepingData(f);
            startGliding(f);
            assertEquals(0.0, elytraB(f).violations, 0.0);
        }
    }

    @Test
    void aGlideStartWithJumpHeldSinceTheLastTickFlags() throws Exception {
        try (var f = jumpHeldInTheAir()) {
            startGliding(f);
            assertTrue(elytraB(f).violations > 0, "ElytraB violations " + elytraB(f).violations);
        }
    }

    /** A player in the air that held jump through its last client tick. */
    private static JavaTickFixture jumpHeldInTheAir() throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.place(AIR, Vec3.ZERO);
        f.input(JUMP);
        f.tick(AIR, false);
        return f;
    }

    /** A portal's RESPAWN into the nether keeping entity data and attribute modifiers, answered through its pings. */
    private static void respawnKeepingData(JavaTickFixture f) {
        var spawnInfo = new PlayerSpawnInfo(
                ModelRegistryNamesState.defaults().id("minecraft:dimension_type", "minecraft:the_nether"),
                "minecraft:the_nether",
                GameMode.SURVIVAL);
        var packet = new ClientboundRespawn(
                spawnInfo, (byte) (ClientboundRespawn.KEEP_ENTITY_DATA | ClientboundRespawn.KEEP_ATTRIBUTE_MODIFIERS));
        var event =
                new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.RESPAWN, packet, false);
        new PacketPlayerRespawn().onRespawn(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void startGliding(JavaTickFixture f) {
        var packet = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_FLYING_WITH_ELYTRA, 0);
        elytraB(f).onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, packet), f.player, packet);
    }

    private static ElytraB elytraB(JavaTickFixture f) {
        return f.player.checkManager.getCheck(ElytraB.class);
    }
}
