package ac.cult.cultac.events.packets.listeners;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSetCarriedItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.anticheat.update.BlockBreak;
import ac.cult.cultac.utils.data.ClientItemUseState;
import ac.cult.cultac.utils.inventory.ItemTypes;
import ac.cult.cultac.utils.nmsutil.RiptideUtil;
import java.util.ArrayList;
import java.util.List;

public class PacketPlayerDigging {
    // LOW
    @CultPacketHandler
    public void onUseItem(PacketReceiveEvent<ServerboundUseItem> event, CultPlayer player, ServerboundUseItem packet) {
        Hand hand = packet.hand();
        SimItemStack item = player.getInventory().getHandItem(hand);
        if (chargesRiptide(player, item)) {
            player.packetStateData.riptideUseHand = hand;
            player.packetStateData.riptideUseStartClientTick = player.packetStateData.acceptedClientTick;
        } else if (hand == player.packetStateData.riptideUseHand) {
            RiptideUtil.clearTrackedUse(player);
        }
    }

    /**
     * Whether a use of {@code item} may end in a riptide release. LivingEntity#updatingUsingItem keeps a Java use and
     * its timer across a switch to another stack of the same item, so any trident use can release a riptide one.
     */
    private static boolean chargesRiptide(CultPlayer player, SimItemStack item) {
        return player.usesModernJavaItemUse()
                ? item != null && item.getItem() == ItemTypes.TRIDENT
                : RiptideUtil.getRiptideLevel(item) > 0;
    }

    @CultPacketHandler
    public void onPlayerAction(
            PacketReceiveEvent<ServerboundPlayerAction> event, CultPlayer player, ServerboundPlayerAction packet) {
        if (packet.action() == PlayerAction.RELEASE_USE_ITEM) {
            // Runs before ActionManager ends the use, so ClientItemUseState still holds the stacks it bound.
            if (player.usesModernJavaItemUse()) releaseJavaUse(player);
            else releaseTrackedHand(player);
            RiptideUtil.clearTrackedUse(player);
        }

        // Cancellation prevents post-flying checks but not movement prediction or resync.
        PlayerAction action = packet.action();
        if (action == PlayerAction.START_DESTROY_BLOCK
                || action == PlayerAction.STOP_DESTROY_BLOCK
                || action == PlayerAction.ABORT_DESTROY_BLOCK) {
            BlockPos blockPosition = packet.position();
            BlockBreak blockBreak = new BlockBreak(
                    player,
                    blockPosition,
                    packet.direction(),
                    packet.direction().ordinal(),
                    action,
                    packet.sequence(),
                    player.compensatedWorld.getBlockStateIdAt(blockPosition));

            player.checkManager.onBlockBreak(blockBreak);

            if (blockBreak.isCancelled()) {
                event.setCancelled(true);
                player.onPacketCancel();
                return;
            }

            player.checkManager.queuePostFlyingBlockBreak(blockBreak);
        }
    }

    @CultPacketHandler
    public void onSetCarriedItem(
            PacketReceiveEvent<ServerboundSetCarriedItem> event, CultPlayer player, ServerboundSetCarriedItem packet) {
        selectHotbarSlot(player, packet.slot());
    }

    public static void selectHotbarSlot(CultPlayer player, int slotId) {
        // Stop people from spamming the server with out of bounds exceptions
        if (slotId > 8 || slotId < 0) return;

        player.packetStateData.lastSlotSelected = slotId;
        // ServerGamePacketListenerImpl#handleSetCarriedItem stops only the server's use. A Java client keeps its use
        // and charge until that stop reaches it, and ClientItemUseState decides what a release in between releases.
        if (!player.usesModernJavaItemUse() && player.packetStateData.riptideUseHand != Hand.OFF_HAND) {
            RiptideUtil.clearTrackedUse(player);
        }
    }

    /**
     * TridentItem#releaseUsing on each stack LivingEntity#releaseUsingItem may release. It spins only after ten ticks
     * of use, in water or rain, with a riptide trident whose next damage leaves it intact.
     */
    private static void releaseJavaUse(CultPlayer player) {
        var inventory = player.getInventory();
        List<ClientItemUseState.Release> releases = player.clientItemUse.releases(
                player.getClientVersion(),
                orEmpty(inventory.getHandItem(Hand.MAIN_HAND)),
                orEmpty(inventory.getHandItem(Hand.OFF_HAND)));
        List<Integer> levels = new ArrayList<>(1);
        boolean mayNotSpin = false;
        Hand invalid = null;
        for (var release : releases) {
            int level = RiptideUtil.getRiptideLevel(release.stack());
            boolean valid = RiptideUtil.isValidRiptideRelease(player, release.hand());
            if (level > 0 && valid && !release.stack().nextDamageWillBreak()) {
                if (!levels.contains(level)) levels.add(level);
                continue;
            }
            mayNotSpin = true;
            if (level > 0 && !valid) invalid = release.hand();
        }
        if (!levels.isEmpty()) spin(player, levels, mayNotSpin);
        else if (invalid != null) rejectRelease(player, invalid);
    }

    /** The release the Bedrock bridge models: the riptide trident the tracked hand holds now. */
    private static void releaseTrackedHand(CultPlayer player) {
        Hand hand = player.packetStateData.riptideUseHand;
        SimItemStack item = hand == null ? null : player.getInventory().getHandItem(hand);
        int level = RiptideUtil.getRiptideLevel(item);
        if (level > 0 && RiptideUtil.isValidRiptideRelease(player, hand)) spin(player, List.of(level), false);
        else if (level > 0) rejectRelease(player, hand);
    }

    /** A release that spins at one of {@code levels}, or may also not spin. */
    private static void spin(CultPlayer player, List<Integer> levels, boolean mayNotSpin) {
        player.packetStateData.riptideLevel = levels.getFirst();
        player.packetStateData.riptideAlternativeLevels = List.copyOf(levels.subList(1, levels.size()));
        player.packetStateData.riptideReleaseCertain = !mayNotSpin && RiptideUtil.isCertainRiptideRelease(player);
        player.riptideSpinAttackTicks = 20;
        player.riptideSpinCertain = player.packetStateData.riptideReleaseCertain;
    }

    private static void rejectRelease(CultPlayer player, Hand hand) {
        player.packetStateData.invalidRiptideRelease = true;
        player.packetStateData.invalidRiptideReleaseReason = RiptideUtil.invalidReleaseReason(player, hand);
    }

    private static SimItemStack orEmpty(SimItemStack stack) {
        return stack == null ? SimItemStack.EMPTY : stack;
    }
}
