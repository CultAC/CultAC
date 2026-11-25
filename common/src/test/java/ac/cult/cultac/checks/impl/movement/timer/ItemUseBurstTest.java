package ac.cult.cultac.checks.impl.movement.timer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.InventoryClick;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.inventory.inventory.WindowClickType;
import ac.cult.cultac.utils.latency.CompensatedInventory;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ItemUseBurstTest {
    private static final long TICK = 50_000_000L;
    private static final byte USING_MAIN_HAND = 1;
    private static final byte USING_OFF_HAND = 3;
    private static final int HOTBAR_0 = Inventory.HOTBAR_OFFSET;

    @Test
    void tickPeriodMatchesTheClientRateClampAndFloatRounding() {
        assertEquals(1_000_000_000L, ItemUseBurst.periodNanos(0, false));
        assertEquals(1_000_000_000L, ItemUseBurst.periodNanos(-10, false));
        assertEquals(50_000_000L, ItemUseBurst.periodNanos(200, false));
        assertEquals(50_000_000L, ItemUseBurst.periodNanos(5, true));
        long nativeNanos = (long) (1_000_000_000D / 6.0F);
        assertEquals((long) (((float) nativeNanos / 1_000_000F) * 1_000_000D), ItemUseBurst.periodNanos(6, false));
    }

    @Test
    void slowedTicksForTheWholeConsumptionAreNotFlagged() {
        run(f -> {
            f.start();
            f.ticks(32);
            f.check.serverComplete(null);
            assertEquals(0, f.check.violations);
            verify(f.setbacks, never()).executeNonSimulatingSetback();
        });
    }

    @Test
    void consumingWithoutClaimingTheServersUseTicksIsFlaggedAndSetBack() {
        run(f -> {
            f.start();
            f.now.addAndGet(32 * TICK);
            f.check.serverComplete(null);
            assertEquals(1, f.check.violations);
            verify(f.setbacks).executeNonSimulatingSetback();
        });
    }

    @Test
    void aClickTheServerAcceptsEndsTheUseAfterTheLastHeldTick() {
        run(f -> {
            f.start();
            f.ticks(5);
            f.clickAppleOut();
            // A forwarded click is only a claim the server accepted silently; it proves nothing.
            f.inventory().onClickForwarded(f.click());
            f.ticks(1);
            f.now.addAndGet(40 * TICK);
            // The server's own clear confirms the local stop.
            f.check.serverFlags((byte) 0, null);
            assertEquals(0, f.check.violations);
            assertFalse(f.check.tracking());
        });
    }

    @Test
    void theServerSendingTheItemBackKeepsTheUseCharged() {
        run(f -> {
            f.start();
            f.ticks(5);
            f.clickAppleOut();
            f.ticks(1);
            // ServerGamePacketListenerImpl#handleContainerClick corrects a rejected claim.
            var slot = new InventoryPackets.Slot(0, 1, HOTBAR_0, f.apple.copy());
            f.inventory().onContainerSetSlot(f.send(slot), f.original, slot);
            f.now.addAndGet(40 * TICK);
            // A later stop (another click) cannot confirm the refuted one.
            f.check.serverFlags((byte) 0, null);
            assertEquals(1, f.check.violations);
        });
    }

    @Test
    void aClickCultCancelledKeepsTheUseCharged() {
        run(f -> {
            f.start();
            f.ticks(5);
            f.clickAppleOut();
            f.ticks(1);
            // The server never saw the click, so a later clear cannot confirm it.
            f.inventory().resyncCancelledClick(f.click());
            f.now.addAndGet(40 * TICK);
            f.check.serverFlags((byte) 0, null);
            assertEquals(1, f.check.violations);
        });
    }

    @Test
    void theServerRefillingAHandItEmptiedResendsItsUseFlagsBehindTheRefill() {
        run(f -> {
            f.start();
            f.ticks(2);
            // A kit reload empties and refills the used hand between two server entity
            // ticks. The refill is sent before the client's empty-handed tick reaches the server.
            f.serverSlot(SimItemStack.EMPTY);
            var refill = f.serverSlot(f.apple.copy());
            // The flags restart the use only once the client holds the item again.
            verify(f.player, never()).resyncItemUse();
            refill.getTasksAfterSend().forEach(Runnable::run);
            verify(f.player).resyncItemUse();
            // LivingEntity#updatingUsingItem stopped the client on the emptied hand.
            f.clickAppleOut();
            f.ticks(1);
            // It restarts on the flags and Minecraft#handleKeybinds releases an unheld use on its next tick.
            f.check.onPlayerAction(null, f.player, f.release());
            f.ticks(30);
            assertEquals(0, f.check.violations);
            assertFalse(f.check.tracking());
        });
    }

    @Test
    void aClientThatIgnoresTheResentUseFlagsStillOwesTheUse() {
        run(f -> {
            f.start();
            f.ticks(2);
            f.serverSlot(SimItemStack.EMPTY);
            f.serverSlot(f.apple.copy()).getTasksAfterSend().forEach(Runnable::run);
            verify(f.player).resyncItemUse();
            f.clickAppleOut();
            f.now.addAndGet(30 * TICK);
            f.check.serverComplete(null);
            assertEquals(1, f.check.violations);
        });
    }

    @Test
    void aSlotRepeatingTheHeldItemResendsNoUseFlags() {
        run(f -> {
            f.start();
            f.ticks(2);
            f.serverSlot(f.apple.copy()).getTasksAfterSend().forEach(Runnable::run);
            verify(f.player, never()).resyncItemUse();
        });
    }

    @Test
    void theServerCorrectingARefusedClickResendsItsUseFlags() {
        run(f -> {
            f.start();
            f.ticks(2);
            // No tick end has shown the emptied hand yet.
            f.clickAppleOut();
            f.serverSlot(f.apple.copy()).getTasksAfterSend().forEach(Runnable::run);
            verify(f.player).resyncItemUse();
        });
    }

    @Test
    void releasingUsesTheServerStartedNeverAddsUpToAFlag() {
        // hoplite capture 01a1f48c (26.3): the release of an unheld server-started use arrives 146.977 ms
        // after its flag with two tick ends between. Each was 0.793 ticks of debt, flagging the 14th.
        run(f -> {
            f.inventory().inventory.getInventoryStorage().setItem(Inventory.SLOT_OFFHAND, f.apple.copy());
            for (int i = 0; i < 40; i++) {
                long flag = f.now.get();
                f.check.serverFlags(USING_OFF_HAND, null);
                f.now.set(flag + 48_354_000L);
                f.check.onPlayerTickEnd(null);
                f.now.set(flag + 98_796_000L);
                f.check.onPlayerTickEnd(null);
                f.now.set(flag + 146_977_000L);
                f.check.onPlayerAction(null, f.player, f.release());
                f.check.serverFlags((byte) 2, null);
                f.ticks(20);
            }
            assertEquals(0, f.check.violations);
            verify(f.setbacks, never()).executeNonSimulatingSetback();
        });
    }

    @Test
    void aUseTheServerStartedIsFlaggedWhenItsTicksAreNeverClaimed() {
        run(f -> {
            f.inventory().inventory.getInventoryStorage().setItem(Inventory.SLOT_OFFHAND, f.apple.copy());
            f.check.serverFlags(USING_OFF_HAND, null);
            f.now.addAndGet(32 * TICK);
            f.check.serverComplete(null);
            assertEquals(1, f.check.violations);
            verify(f.setbacks).executeNonSimulatingSetback();
        });
    }

    private static final class Fixture {
        CultPlayer original;
        CultPlayer player;
        ItemUseBurst check;
        SetbackTeleportUtil setbacks;
        final SimItemStack apple = ItemUtil.modelItems().stack("minecraft:golden_apple", 1);
        final AtomicLong now = new AtomicLong(10_000_000_000L);

        CompensatedInventory inventory() {
            return original.getInventory();
        }

        void start() {
            check.onUseItem(null, player, new ServerboundUseItem(Hand.MAIN_HAND, 1, 0, 0));
            check.serverFlags(USING_MAIN_HAND, null);
        }

        void ticks(int count) {
            for (int i = 0; i < count; i++) {
                now.addAndGet(TICK);
                check.onPlayerTickEnd(null);
            }
        }

        /** MultiPlayerGameMode#handleContainerInput applies the click locally before sending it. */
        void clickAppleOut() {
            inventory().inventory.getInventoryStorage().setItem(HOTBAR_0, SimItemStack.EMPTY);
        }

        /** A player-inventory slot of the used hand as the server sends it. */
        PacketSendEvent<InventoryPackets.Slot> serverSlot(SimItemStack item) {
            var slot = new InventoryPackets.Slot(0, 1, HOTBAR_0, item);
            var event = send(slot);
            inventory().onContainerSetSlot(event, original, slot);
            return event;
        }

        ServerboundPlayerAction release() {
            return new ServerboundPlayerAction(PlayerAction.RELEASE_USE_ITEM, new BlockPos(0, 0, 0), Direction.DOWN, 0);
        }

        InventoryClick click() {
            return new InventoryClick(
                    0, 0, HOTBAR_0, 0, WindowClickType.PICKUP, Map.of(HOTBAR_0, SimItemStack.EMPTY), apple.copy());
        }

        @SuppressWarnings("unchecked")
        PacketSendEvent<InventoryPackets.Slot> send(InventoryPackets.Slot slot) {
            var type = (PacketType<InventoryPackets.Slot>) CultAPI.INSTANCE
                    .getNetworkManager()
                    .dispatcher()
                    .scanner()
                    .runtime()
                    .typeForRecord(InventoryPackets.Slot.class);
            return new PacketSendEvent<>(original.user, ConnectionPhase.PLAY, type, slot, false);
        }
    }

    private static void run(Consumer<Fixture> body) {
        OfflineCultTestBootstrap.installConfig();
        var channel = new EmbeddedChannel();
        var user = TestUsers.create(new User.Profile(UUID.randomUUID(), ".ItemUse_Burst_Test"), channel);
        var original = new CultPlayer(user);
        try {
            var f = new Fixture();
            f.original = original;
            f.player = spy(original);
            f.setbacks = mock(SetbackTeleportUtil.class);
            doReturn(f.setbacks).when(f.player).getSetbackTeleportUtil();
            doReturn(true).when(f.player).usesModernJavaItemUse();
            doNothing().when(f.player).resyncItemUse();
            // No transaction is acknowledged, so no client time is proven while using.
            doReturn(0L).when(f.player).playerClockAtLeast();
            f.player.packetStateData.lastSlotSelected = 0;
            f.inventory().inventory.getInventoryStorage().setItem(HOTBAR_0, f.apple.copy());
            f.check = new ItemUseBurst(f.player, f.now::get);
            f.check.setEnabled(true);
            // Route the inventory's own hooks to the check under test.
            var field = CultPlayer.class.getDeclaredField("itemUseBurst");
            field.setAccessible(true);
            field.set(original, f.check);
            body.accept(f);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        } finally {
            original.onRemove();
            channel.finishAndReleaseAll();
        }
    }
}
