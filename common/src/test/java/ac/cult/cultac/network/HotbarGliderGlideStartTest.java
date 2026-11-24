package ac.cult.cultac.network;

import static ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap.item;
import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketSelfMetadataListener;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.math.Vec3;
import com.google.gson.JsonParser;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Minecraft#tick runs MultiPlayerGameMode#tick, which sends a changed selected slot, before Minecraft#handleKeybinds
 * selects a hotbar slot and LocalPlayer#aiStep starts a glide. A glider equippable in the main hand, selected with the
 * jump that starts the glide, reaches the server one tick after the START_FALL_FLYING it allowed, so the server refuses
 * the glide. The client glides on its own flag until it handles the flags sent back, and its next tick keeps the
 * FALL_FLYING pose without fall flying, a crawl through LivingEntity#isVisuallySwimming.
 */
class HotbarGliderGlideStartTest {
    private static final Vec3 FROM = new Vec3(0.5, FLOOR_Y + 2, 0.5);
    private static final Vec3 CARRIED = new Vec3(0.0, -0.07544406518948656, 0.25);
    private static final int GLIDER_SLOT = 1;
    private static final double GRAVITY = 0.08;
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD_JUMP =
            new ServerboundPlayerInput(true, false, false, false, true, false, false);

    @Test
    void theTickAfterTheRefusedGlideCrawls() throws Exception {
        try (var f = airborne()) {
            SetBackData correction = refusedGlideStart(f);
            accept(f, correction);
            f.pongThrough(f.player.lastTransactionSent.get());

            // MultiPlayerGameMode#tick sends the slot at the start of the next tick.
            f.selectHotbarSlot(GLIDER_SLOT);
            Vec3 velocity = correction.getVelocity();
            // LocalPlayer#modifyInput scales the forward key by Attributes.SNEAKING_SPEED.
            Vec3 move = velocity.add(0, 0, (double) (0.98F * 0.3F) * 0.02F);
            Vec3 at = correction.getTeleportData().getLocation().add(move);
            PredictionResult crawl = f.tick(at, false);
            assertTrue(exact(crawl), "the tick after the refused glide: " + describe(crawl));

            velocity = air(move);
            move = velocity.add(0, 0, (double) 0.98F * 0.02F);
            PredictionResult next = f.tick(at.add(move), false);
            assertTrue(exact(next), "the tick after the crawl: " + describe(next));
        }
    }

    /** The flags precede the correction, so a client that accepted it no longer glides. */
    @Test
    void aGlideAfterTheAcceptedCorrectionIsRejected() throws Exception {
        try (var f = airborne()) {
            SetBackData correction = refusedGlideStart(f);
            accept(f, correction);

            f.selectHotbarSlot(GLIDER_SLOT);
            Vec3 at = correction.getTeleportData().getLocation().add(glide(correction.getVelocity()));
            PredictionResult glide = f.tick(at, false);
            assertFalse(glide.isExempt(), describe(glide));
            assertFalse(exact(glide), "a glide after the accepted correction: " + describe(glide));
        }
    }

    /** A glider selected a tick before the jump reaches the server before the START_FALL_FLYING. */
    @Test
    void aGliderSelectedBeforeTheJumpGlides() throws Exception {
        try (var f = airborne()) {
            Vec3 velocity = CARRIED;
            Vec3 at = FROM;
            f.selectHotbarSlot(GLIDER_SLOT);
            Vec3 move = velocity.add(0, 0, (double) 0.98F * 0.02F);
            at = at.add(move);
            PredictionResult selected = f.tick(at, false);
            assertTrue(exact(selected), "the tick selecting the glider: " + describe(selected));
            velocity = air(move);

            f.command(PlayerCommandAction.START_FLYING_WITH_ELYTRA);
            assertTrue(f.player.isGliding, "the glide start from the selected glider was refused");
            f.input(FORWARD_JUMP);
            for (int tick = 0; tick < 2; tick++) {
                velocity = glide(velocity);
                at = at.add(velocity);
                PredictionResult gliding = f.tick(at, false);
                assertTrue(exact(gliding), "glide tick " + tick + ": " + describe(gliding));
            }
        }
    }

    /** A glide start with no glider to select keeps the standing box, which an overhang stops. */
    @Test
    void aGlideStartWithNoGliderToSelectKeepsTheStandingBox() throws Exception {
        Vec3 from = FROM.add(0, 0, 0.9);
        try (var f = airborne(from, false)) {
            for (int x = -1; x <= 1; x++) {
                for (int y = FLOOR_Y + 3; y <= FLOOR_Y + 4; y++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, y, 2), BlockIds.STONE.defaultState());
                }
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
            Vec3 move = CARRIED.add(0, 0, (double) 0.98F * 0.02F);
            Vec3 at = from.add(move);
            PredictionResult falling = f.tick(at, false);
            assertTrue(exact(falling), "the falling tick: " + describe(falling));

            f.command(PlayerCommandAction.START_FLYING_WITH_ELYTRA);
            SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
            assertTrue(teleports.isPendingSetback(), "the glide start without a glider was not set back");
            f.input(FORWARD_JUMP);
            f.move(at, false);
            f.tickEnd();
            SetBackData correction = teleports.getRequiredSetBack();
            accept(f, correction);

            Vec3 crawl = correction.getVelocity().add(0, 0, (double) (0.98F * 0.3F) * 0.02F);
            PredictionResult under =
                    f.tick(correction.getTeleportData().getLocation().add(crawl), false);
            assertFalse(under.isExempt(), describe(under));
            assertFalse(exact(under), "a crawl under the overhang: " + describe(under));
        }
    }

    private static JavaTickFixture airborne() throws Exception {
        return airborne(FROM, true);
    }

    /** Falling with the forward key from {@code from}, slot 0 empty and slot 1 holding a main-hand elytra if asked. */
    private static JavaTickFixture airborne(Vec3 from, boolean glider) throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
        f.fixture.registrar.register(
                routes -> routes.registerSendListener(PacketListenerPriority.HIGH, new PacketSelfMetadataListener()));
        if (glider) {
            SimItemStack elytra = item("minecraft:elytra");
            elytra.components(elytra.components()
                    .with("minecraft:equippable", JsonParser.parseString("{\"slot\":\"mainhand\"}")));
            f.player
                    .getInventory()
                    .inventory
                    .getInventoryStorage()
                    .setItem(Inventory.HOTBAR_OFFSET + GLIDER_SLOT, elytra);
        }
        f.place(from, CARRIED);
        f.playerPosition(from, CARRIED, Set.of());
        f.pongThrough(f.player.sendTrailingTransaction());
        f.input(FORWARD);
        return f;
    }

    /**
     * One ordinary falling tick, then the tick whose keys select the glider and jump: its START_FALL_FLYING arrives
     * while the server still holds slot 0, and its glide is the move that follows. Returns the correction.
     */
    private static SetBackData refusedGlideStart(JavaTickFixture f) {
        Vec3 move = CARRIED.add(0, 0, (double) 0.98F * 0.02F);
        Vec3 at = FROM.add(move);
        PredictionResult falling = f.tick(at, false);
        assertTrue(exact(falling), "the falling tick: " + describe(falling));

        SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
        f.command(PlayerCommandAction.START_FLYING_WITH_ELYTRA);
        assertTrue(teleports.isPendingSetback(), "the glide start without a held glider was not set back");
        f.input(FORWARD_JUMP);
        f.move(at.add(glide(air(move))), false);
        f.tickEnd();
        return teleports.getRequiredSetBack();
    }

    /** The client handles the correction: it answers the correction's ping and accepts its exact target. */
    private static void accept(JavaTickFixture f, SetBackData correction) {
        var teleport = correction.getTeleportData();
        f.pongThrough(teleport.getTransaction());
        var target = teleport.getLocation();
        var packet = new ServerboundAcceptTeleportation(
                teleport.getTeleportId(), JavaTickFixture.vec3d(target), f.player.xRot, f.player.yRot);
        var event = f.event(ServerboundPackets.ACCEPT_TELEPORTATION, packet);
        new PacketServerTeleport().onAcceptTeleportation(event, f.player, packet);
        assertFalse(event.isCancelled(), "the correction's acceptance was rejected");
    }

    /** LivingEntity#travelInAir's friction and gravity after the move {@code move}. */
    private static Vec3 air(Vec3 move) {
        return new Vec3(move.x * 0.91F, (move.y - GRAVITY) * 0.98F, move.z * 0.91F);
    }

    /** LivingEntity#updateFallFlyingMovement with yaw 0 and pitch 0: look (0, 0, 1), lift force 1. */
    private static Vec3 glide(Vec3 movement) {
        double moveHorLength = Math.sqrt(movement.x * movement.x + movement.z * movement.z);
        movement = movement.add(new Vec3(0, GRAVITY * (-1.0 + 0.75), 0));
        if (movement.y < 0.0) {
            double convert = movement.y * -0.1;
            movement = movement.add(new Vec3(0, convert, convert));
        }
        movement = movement.add(new Vec3(-movement.x * 0.1, 0, (moveHorLength - movement.z) * 0.1));
        return movement.multiply(0.99F, 0.98F, 0.99F);
    }
}
