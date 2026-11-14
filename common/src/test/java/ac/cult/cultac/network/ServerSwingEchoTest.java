package ac.cult.cultac.network;

import static ac.cult.cultac.network.ItemUseTestSupport.setOffhand;
import static ac.cult.cultac.network.ItemUseTestSupport.use;
import static ac.cult.cultac.network.ItemUseTestSupport.useOffhand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.autoclicker.AutoclickerLimit;
import ac.cult.cultac.checks.impl.breaking.AirLiquidBreak;
import ac.cult.cultac.checks.impl.breaking.FastBreak;
import ac.cult.cultac.checks.impl.breaking.NoSwingBreak;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsE;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsS;
import ac.cult.cultac.checks.impl.packetorder.PacketOrderB;
import ac.cult.cultac.checks.impl.post.PostCheck;
import ac.cult.cultac.checks.type.BlockBreakListener;
import ac.cult.cultac.events.packets.listeners.PacketPingListener;
import ac.cult.cultac.events.packets.listeners.PacketPlayerAttack;
import ac.cult.cultac.events.packets.listeners.PacketSelfMetadataListener;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.anticheat.update.BlockBreak;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.buffer.ByteBuf;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Before 26.3, ClientPacketListener#handleAnimate swings the local player for a self animate and LocalPlayer#swing
 * sends ServerboundSwingPacket while the client handles it.
 */
class ServerSwingEchoTest {
    private static final Vec3 STANDING = new Vec3(.5, JavaTickFixture.FLOOR_Y, .5);
    private static final BlockPos FLOOR = new BlockPos(0, JavaTickFixture.FLOOR_Y - 1, 0);
    private static final int TARGET = 100;
    // SwingAnimationType wire ids.
    private static final int NONE = 0;
    private static final int WHACK = 1;
    private static final int STAB = 2;

    @Test
    void theSwingOfASelfAnimateWhileUsingASpearIsNotFlagged() throws Exception {
        try (var f = fixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11)) {
            useSpearInOffhand(f);
            Animate animate = animate(f, 0);
            pong(f, animate.lead());
            swing(f, Hand.MAIN_HAND);
            pong(f, animate.response());
            assertEquals(0, f.player.checkManager.getCheck(MultiActionsE.class).violations);
        }
    }

    @Test
    void anOffhandAnimateIsEchoedByAnOffhandSwing() throws Exception {
        try (var f = fixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11)) {
            useSpearInOffhand(f);
            Animate animate = animate(f, 3);
            pong(f, animate.lead());
            swing(f, Hand.MAIN_HAND);
            swing(f, Hand.OFF_HAND);
            assertEquals(1, f.player.checkManager.getCheck(MultiActionsE.class).violations);
        }
    }

    @Test
    void aSwingWhileUsingWithoutAnAnimateIsFlagged() throws Exception {
        try (var f = fixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11)) {
            useSpearInOffhand(f);
            swing(f, Hand.MAIN_HAND);
            assertEquals(1, f.player.checkManager.getCheck(MultiActionsE.class).violations);
        }
    }

    @Test
    void aSwingBeforeTheClientAnsweredThePingBeforeTheAnimateIsFlagged() throws Exception {
        try (var f = fixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11)) {
            useSpearInOffhand(f);
            Animate animate = animate(f, 0);
            pong(f, animate.lead() - 1);
            swing(f, Hand.MAIN_HAND);
            assertEquals(1, f.player.checkManager.getCheck(MultiActionsE.class).violations);
        }
    }

    @Test
    void aSwingAfterTheClientAnsweredThePingAfterTheAnimateIsFlagged() throws Exception {
        try (var f = fixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11)) {
            useSpearInOffhand(f);
            Animate animate = animate(f, 0);
            pong(f, animate.response());
            swing(f, Hand.MAIN_HAND);
            assertEquals(1, f.player.checkManager.getCheck(MultiActionsE.class).violations);
        }
    }

    @Test
    void oneAnimateIsEchoedByOneSwing() throws Exception {
        try (var f = fixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11)) {
            useSpearInOffhand(f);
            Animate animate = animate(f, 0);
            pong(f, animate.lead());
            swing(f, Hand.MAIN_HAND);
            swing(f, Hand.MAIN_HAND);
            assertEquals(1, f.player.checkManager.getCheck(MultiActionsE.class).violations);
        }
    }

    @Test
    void theEchoDoesNotTakeTheSwingOfADrop() throws Exception {
        try (var f = fixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11)) {
            useSpearInOffhand(f);
            Animate animate = animate(f, 0);
            pong(f, animate.lead());
            swing(f, Hand.MAIN_HAND);
            var check = f.player.checkManager.getCheck(MultiActionsE.class);
            var drop = new ServerboundPlayerAction(PlayerAction.DROP_ITEM, new BlockPos(0, 0, 0), Direction.DOWN, 0);
            check.onPlayerAction(f.event(ServerboundPackets.PLAYER_ACTION, drop), f.player, drop);
            swing(f, Hand.MAIN_HAND);
            assertEquals(0, check.violations);
        }
    }

    @Test
    void aClientFrom26_3OnArmsNothing() throws Exception {
        for (ProtocolVersion decoded : new ProtocolVersion[] {ProtocolVersion.V1_21_11, ProtocolVersion.V26_3}) {
            try (var f = fixture(decoded, ClientVersion.V_26_3)) {
                useSpearInOffhand(f);
                int before = f.player.lastTransactionSent.get();
                writeAnimate(f, 0);
                pong(f, f.player.lastTransactionSent.get());
                assertFalse(f.player.lastTransactionSent.get() > before + 1, "no response proof for " + decoded);
                swing(f, Hand.MAIN_HAND);
                assertEquals(1, f.player.checkManager.getCheck(MultiActionsE.class).violations, decoded.name());
            }
        }
    }

    @Test
    void aClientWithoutBundlesArmsNothing() throws Exception {
        try (var f = fixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11)) {
            Field client = CultPlayer.class.getDeclaredField("resolvedClientVersion");
            client.setAccessible(true);
            client.set(f.player, ClientVersion.V_1_19);
            int before = f.player.lastTransactionSent.get();
            writeAnimate(f, 0);
            assertFalse(f.player.lastTransactionSent.get() > before + 1, "no response proof");
        }
    }

    /** ViaBackwards turns a 26.3 host's main-hand swing animation into the animate an older client echoes. */
    @Test
    void theEchoOfATranslatedMainHandSwingAnimationIsNotFlagged() throws Exception {
        for (int type : new int[] {WHACK, STAB}) {
            try (var f = fixture(ProtocolVersion.V26_3, ClientVersion.V_1_21_11)) {
                useSpearInOffhand(f);
                Animate animate = swingAnimation(f, 0, type);
                pong(f, animate.lead());
                swing(f, Hand.MAIN_HAND);
                pong(f, animate.response());
                assertEquals(0, f.player.checkManager.getCheck(MultiActionsE.class).violations, "type " + type);
            }
        }
    }

    @Test
    void oneTranslatedSwingAnimationIsEchoedByOneSwing() throws Exception {
        try (var f = fixture(ProtocolVersion.V26_3, ClientVersion.V_1_21_11)) {
            useSpearInOffhand(f);
            Animate animate = swingAnimation(f, 0, WHACK);
            pong(f, animate.lead());
            swing(f, Hand.MAIN_HAND);
            swing(f, Hand.MAIN_HAND);
            assertEquals(1, f.player.checkManager.getCheck(MultiActionsE.class).violations);
        }
    }

    /** ViaBackwards cancels the off-hand swing an older client echoes, so only a client punch reaches the host. */
    @Test
    void anOffHandSwingAnimationArmsNothing() throws Exception {
        assertSwingAnimationArmsNothing(ClientVersion.V_1_21_11, 1, WHACK);
    }

    /** ViaBackwards cancels a NONE swing animation instead of animating it. */
    @Test
    void aSwingAnimationWithoutAnAnimationArmsNothing() throws Exception {
        assertSwingAnimationArmsNothing(ClientVersion.V_1_21_11, 0, NONE);
    }

    /** From 26.3, ClientPacketListener#handleSwingAnimation swings without sending a packet. */
    @Test
    void aSwingAnimationToA26_3ClientArmsNothing() throws Exception {
        assertSwingAnimationArmsNothing(ClientVersion.V_26_3, 0, WHACK);
    }

    private static void assertSwingAnimationArmsNothing(ClientVersion client, int hand, int type) throws Exception {
        try (var f = fixture(ProtocolVersion.V26_3, client)) {
            useSpearInOffhand(f);
            int before = f.player.lastTransactionSent.get();
            writeSwingAnimation(f, hand, type);
            pong(f, f.player.lastTransactionSent.get());
            assertFalse(f.player.lastTransactionSent.get() > before + 1, "no response proof");
            swing(f, Hand.MAIN_HAND);
            assertEquals(1, f.player.checkManager.getCheck(MultiActionsE.class).violations);
        }
    }

    @Test
    void theEchoIsNotAnInventorySwing() throws Exception {
        try (var f = fixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11)) {
            f.player.openWindow.onClientOpenOrCloseWindow(true);
            assertTrue(f.player.openWindow.mustBeOpen());
            Animate animate = animate(f, 0);
            pong(f, animate.lead());
            swing(f, Hand.MAIN_HAND);
            pong(f, animate.response());
            f.move(STANDING, true);
            assertEquals(0, f.player.checkManager.getCheck(MultiActionsS.class).violations);
        }
    }

    @Test
    void aSwingInAnInventoryWithoutAnAnimateIsFlagged() throws Exception {
        try (var f = fixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11)) {
            f.player.openWindow.onClientOpenOrCloseWindow(true);
            assertTrue(f.player.openWindow.mustBeOpen());
            swing(f, Hand.MAIN_HAND);
            f.move(STANDING, true);
            assertEquals(1, f.player.checkManager.getCheck(MultiActionsS.class).violations);
        }
    }

    /** The ping before the animate is answered after the client's tick, so the echo never follows a flying. */
    @Test
    void theEchoAfterAFlyingIsNotAPostSwing() throws Exception {
        try (var f = fixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11)) {
            for (int tick = 0; tick < 4; tick++) f.tick(STANDING, true);
            f.move(STANDING, true);
            Animate animate = animate(f, 0);
            pong(f, animate.lead());
            swing(f, Hand.MAIN_HAND);
            pong(f, animate.response());
            f.tickEnd();
            for (int tick = 0; tick < 2; tick++) f.tick(STANDING, true);
            assertEquals(0, f.player.checkManager.getCheck(PostCheck.class).violations);
        }
    }

    @Test
    void aSwingAfterAFlyingIsAPostSwing() throws Exception {
        try (var f = fixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11)) {
            for (int tick = 0; tick < 4; tick++) f.tick(STANDING, true);
            f.move(STANDING, true);
            swing(f, Hand.MAIN_HAND);
            f.tickEnd();
            for (int tick = 0; tick < 2; tick++) f.tick(STANDING, true);
            assertEquals(1, f.player.checkManager.getCheck(PostCheck.class).violations);
        }
    }

    /** ClientPacketListener#handleAnimate's LocalPlayer#swing leaves Player#attackStrengthTicker as it is. */
    @Test
    void theEchoIsNotAMiss() throws Exception {
        try (var f = fixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11)) {
            for (int tick = 0; tick < 2; tick++) f.tick(STANDING, true);
            assertTrue(f.player.attackStrength.isFullStrength(1));
            echo(f);
            tickEnd(f);
            assertTrue(f.player.attackStrength.isFullStrength(2));
            // The tick end closes a tick without a position, whose Player#tick advances the ticker once.
            swing(f, Hand.MAIN_HAND);
            tickEnd(f);
            assertFalse(f.player.attackStrength.isFullStrength(2));
        }
    }

    /** Minecraft#startAttack swings after the START it sends. */
    @Test
    void theEchoIsNotTheSwingOfABreak() throws Exception {
        try (var f = fixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11)) {
            var check = f.player.checkManager.getCheck(NoSwingBreak.class);
            echo(f);
            dig(f, check, PlayerAction.START_DESTROY_BLOCK);
            tickEnd(f);
            assertEquals(1, check.violations);
            dig(f, check, PlayerAction.START_DESTROY_BLOCK);
            swing(f, Hand.MAIN_HAND);
            tickEnd(f);
            assertEquals(1, check.violations);
        }
    }

    /** Minecraft#continueAttack's second swing in the START tick follows a tick of destroy progress. */
    @Test
    void theEchoIsNotASecondSwingInTheStartTick() throws Exception {
        try (var f = fixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11)) {
            var check = mineObsidianInFourteenTicks(f);
            for (int i = 0; i < 25; i++) breakObsidianInThirteenTicks(f, check, () -> echo(f));
            assertTrue(check.violations > 0);
        }
        try (var f = fixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11)) {
            var check = mineObsidianInFourteenTicks(f);
            for (int i = 0; i < 25; i++) breakObsidianInThirteenTicks(f, check, () -> swing(f, Hand.MAIN_HAND));
            assertEquals(0, check.violations);
        }
    }

    /** The echo is sent before the next tick's MultiPlayerGameMode#tick sends the carried slot. */
    @Test
    void theEchoDoesNotSettleASpearStartBeforeTheCarriedSlot() throws Exception {
        try (var f = fixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11)) {
            var check = digWithASpear(f);
            echo(f);
            assertEquals(0, check.violations);
            selectSlot(f, 1);
            tickEnd(f);
            assertEquals(0, check.violations);
        }
        try (var f = fixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11)) {
            var check = digWithASpear(f);
            swing(f, Hand.MAIN_HAND);
            assertEquals(1, check.violations);
        }
    }

    /** Minecraft#startAttack swings after the attack it sends. */
    @Test
    void theEchoIsNotTheSwingOfAnAttack() throws Exception {
        try (var f = fixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11)) {
            var check = f.player.checkManager.getCheck(PacketOrderB.class);
            f.addRemotePlayer(TARGET, new Vec3(.5, JavaTickFixture.FLOOR_Y, 2), GameMode.SURVIVAL);
            attack(f);
            swing(f, Hand.MAIN_HAND);
            tickEnd(f);
            assertEquals(0, check.violations);
            attack(f);
            echo(f);
            tickEnd(f);
            assertEquals(1, check.violations);
        }
    }

    /** ClientPacketListener#handleAnimate's swing is not a click. */
    @Test
    void theEchoIsNotAClick() throws Exception {
        try (var f = fixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11)) {
            var check = f.player.checkManager.getCheck(AutoclickerLimit.class);
            for (int i = 0; i < 150; i++) echo(f);
            assertEquals(0, check.violations);
            for (int i = 0; i < 150; i++) swing(f, Hand.MAIN_HAND);
            assertEquals(1, check.violations);
        }
    }

    private record Animate(int lead, int response) {}

    private static JavaTickFixture fixture(ProtocolVersion decoded, ClientVersion client) throws Exception {
        OfflineCultTestBootstrap.installConfig();
        var f = decoded.atLeast(ProtocolVersion.V26_3)
                ? new JavaTickFixture(decoded, client)
                : JavaTickFixture.olderHost(decoded, client);
        f.player.setExperimentalChecks(true);
        // A flush closes the flush bundle; the watchdog's would land between the writes.
        f.player.nettyScheduler.removeScheduler();
        f.fixture.channel.runPendingTasks();
        var listener = new PacketSelfMetadataListener();
        f.fixture.registrar.register(routes -> {
            routes.send(ClientboundPackets.ANIMATE, PacketListenerPriority.HIGH, listener::onAnimate);
            routes.send(ClientboundPackets.SWING_ANIMATION, PacketListenerPriority.HIGH, listener::onSwingAnimation);
            // The swing reaches the checks the way PacketManager#registerListeners routes it.
            f.listener.registerForwardingEarlyReceivePackets(routes, PacketListenerPriority.LOW);
            routes.registerReceiveListener(PacketListenerPriority.LOW, new PacketPlayerAttack());
            routes.registerReceiveListener(PacketListenerPriority.LOW, f.listener);
            routes.registerListener(PacketListenerPriority.LOWEST, new PacketPingListener());
        });
        f.player.recordProvenance(true);
        f.place(STANDING, Vec3.ZERO);
        f.pongThrough(f.player.lastTransactionSent.get());
        return f;
    }

    /** The client's tick: use_item for the main hand, then for the offhand spear, which starts using it. */
    private static void useSpearInOffhand(JavaTickFixture f) {
        f.player
                .getInventory()
                .inventory
                .getInventoryStorage()
                .setItem(Inventory.HOTBAR_OFFSET, OfflineCultTestBootstrap.item("minecraft:iron_sword"));
        setOffhand(f.player, OfflineCultTestBootstrap.item("minecraft:iron_spear"));
        use(f.player, Hand.MAIN_HAND, 1);
        useOffhand(f.player, 2);
        assertTrue(f.player.isClientUsingItem());
    }

    /** The server swings the player's own arm; the pings the client answers before and after handling it. */
    private static Animate animate(JavaTickFixture f, int action) {
        writeAnimate(f, action);
        int response = f.player.lastTransactionSent.get();
        int lead = response - 1;
        assertTrue(lead > f.player.lastTransactionReceived.get(), "the animate is led by its own ping");
        return new Animate(lead, response);
    }

    /** The server's own ClientboundAnimatePacket, as it reaches the outbound pipeline. */
    private static void writeAnimate(JavaTickFixture f, int action) {
        ByteBuf frame = f.fixture.id(ClientboundPackets.ANIMATE, "animate");
        Wire.writeVarInt(frame, f.player.entityID);
        frame.writeByte(action);
        f.fixture.channel.write(frame);
        f.fixture.channel.flush();
        f.fixture.channel.releaseOutbound();
    }

    /** The 26.3 server's swing; the pings the client answers before and after handling its translated animate. */
    private static Animate swingAnimation(JavaTickFixture f, int hand, int type) {
        writeSwingAnimation(f, hand, type);
        int response = f.player.lastTransactionSent.get();
        int lead = response - 1;
        assertTrue(lead > f.player.lastTransactionReceived.get(), "the swing animation is led by its own ping");
        return new Animate(lead, response);
    }

    /** The server's own ClientboundSwingAnimationPacket: entity, InteractionHand, SwingAnimation type and duration. */
    private static void writeSwingAnimation(JavaTickFixture f, int hand, int type) {
        ByteBuf frame = f.fixture.id(ClientboundPackets.SWING_ANIMATION, "swing_animation");
        Wire.writeVarInt(frame, f.player.entityID);
        Wire.writeVarInt(frame, hand);
        Wire.writeVarInt(frame, type);
        Wire.writeVarInt(frame, 6);
        f.fixture.channel.write(frame);
        f.fixture.channel.flush();
        f.fixture.channel.releaseOutbound();
    }

    /** The client handles a main-hand self animate between its pings and echoes it. */
    private static void echo(JavaTickFixture f) {
        Animate animate = f.fixture.data.version().atLeast(ProtocolVersion.V26_3)
                ? swingAnimation(f, 0, WHACK)
                : animate(f, 0);
        pong(f, animate.lead());
        swing(f, Hand.MAIN_HAND);
        pong(f, animate.response());
    }

    /** ServerboundClientTickEndPacket, decoded from the wire. */
    private static void tickEnd(JavaTickFixture f) {
        f.fixture.forward(f.fixture.id(ServerboundPackets.CLIENT_TICK_END, "client_tick_end"));
    }

    /** A destroy action on the floor block under the player, as PacketPlayerDigging hands it to {@code check}. */
    private static void dig(JavaTickFixture f, BlockBreakListener check, PlayerAction action) {
        int block = f.player.compensatedWorld.getBlockStateIdAt(FLOOR);
        check.onBlockBreak(
                new BlockBreak(f.player, FLOOR, Direction.UP, Direction.UP.get3DDataValue(), action, 1, block));
    }

    /** A diamond pickaxe on obsidian needs 14 ticks at mining efficiency 100. */
    private static FastBreak mineObsidianInFourteenTicks(JavaTickFixture f) {
        f.player.packetStateData.packetPlayerOnGround = true;
        f.player.getInventory().inventory.setHeldItem(OfflineCultTestBootstrap.item("minecraft:diamond_pickaxe"));
        f.player.compensatedEntities.updateAttributes(
                f.player.entityID,
                List.of(new AttributeSnapshot("minecraft:mining_efficiency", 100.0, List.of())));
        f.player.compensatedWorld.updateBlock(FLOOR, BlockIds.OBSIDIAN.defaultState());
        return f.player.checkManager.getCheck(FastBreak.class);
    }

    /**
     * A held break: the STOP's five ticks of destroyDelay, then a START tick that swings once and then
     * {@code secondSwing}, and the STOP 13 ticks after it.
     */
    private static void breakObsidianInThirteenTicks(JavaTickFixture f, FastBreak check, Runnable secondSwing) {
        for (int tick = 0; tick < 5; tick++) {
            swing(f, Hand.MAIN_HAND);
            tickEnd(f);
        }
        dig(f, check, PlayerAction.START_DESTROY_BLOCK);
        swing(f, Hand.MAIN_HAND);
        secondSwing.run();
        tickEnd(f);
        for (int tick = 0; tick < 12; tick++) {
            swing(f, Hand.MAIN_HAND);
            tickEnd(f);
        }
        dig(f, check, PlayerAction.STOP_DESTROY_BLOCK);
        swing(f, Hand.MAIN_HAND);
        tickEnd(f);
    }

    /** A START sent with a spear in the server-side hand and a pickaxe in the slot the client selects next. */
    private static AirLiquidBreak digWithASpear(JavaTickFixture f) {
        var storage = f.player.getInventory().inventory.getInventoryStorage();
        storage.setItem(Inventory.HOTBAR_OFFSET, OfflineCultTestBootstrap.item("minecraft:iron_spear"));
        storage.setItem(Inventory.HOTBAR_OFFSET + 1, OfflineCultTestBootstrap.item("minecraft:diamond_pickaxe"));
        var check = f.player.checkManager.getCheck(AirLiquidBreak.class);
        dig(f, check, PlayerAction.START_DESTROY_BLOCK);
        tickEnd(f);
        assertEquals(0, check.violations);
        return check;
    }

    /** MultiPlayerGameMode#attack's ServerboundInteractPacket before 26.1, decoded from the wire. */
    private static void attack(JavaTickFixture f) {
        ByteBuf frame = f.fixture.id(ServerboundPackets.INTERACT, "interact");
        Wire.writeVarInt(frame, TARGET);
        Wire.writeVarInt(frame, InteractAction.ATTACK.ordinal());
        frame.writeBoolean(false);
        f.fixture.forward(frame);
    }

    /** MultiPlayerGameMode#ensureHasSentCarriedItem's ServerboundSetCarriedItemPacket, decoded from the wire. */
    private static void selectSlot(JavaTickFixture f, int slot) {
        ByteBuf frame = f.fixture.id(ServerboundPackets.SET_CARRIED_ITEM, "set_carried_item");
        frame.writeShort(slot);
        f.fixture.forward(frame);
    }

    /** The client answers each ping through {@code transaction} with a pong. */
    private static void pong(JavaTickFixture f, int transaction) {
        for (int id = f.player.lastTransactionReceived.get() + 1; id <= transaction; id++) {
            Integer wireId = f.player.wireIdOf(id);
            assertNotNull(wireId, "ping " + id + " was sent");
            // CultAC consumes the pongs of its own pings.
            f.fixture.channel.writeInbound(f.fixture.pong(wireId));
        }
        assertEquals(transaction, f.player.lastTransactionReceived.get());
    }

    /** LocalPlayer#swing's ServerboundSwingPacket, decoded from the wire; from 26.3 a main-hand punch. */
    private static void swing(JavaTickFixture f, Hand hand) {
        if (f.fixture.data.version().atLeast(ProtocolVersion.V26_3)) {
            f.fixture.forward(f.fixture.id(ServerboundPackets.SWING, "punch"));
            return;
        }
        ByteBuf frame = f.fixture.id(ServerboundPackets.SWING, "swing");
        Wire.writeVarInt(frame, hand == Hand.MAIN_HAND ? 0 : 1);
        f.fixture.forward(frame);
    }
}
