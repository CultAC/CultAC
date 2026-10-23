package ac.cult.cultac.network;

import static ac.cult.cultac.network.ItemUseTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemUtil;
import org.junit.jupiter.api.Test;

class SwapOffhandHandsTest {
    @Test
    void swapOffhandLeavesTheHandsToTheServersSlotUpdates() {
        try (var fixture = fixture()) {
            var player = fixture.player;
            player.gamemode = GameMode.SURVIVAL;
            var inventory = player.getInventory();
            var mace = ItemUtil.modelItems().stack("minecraft:mace", 1);
            var totem = ItemUtil.modelItems().stack("minecraft:totem_of_undying", 1);
            int mainHand = Inventory.HOTBAR_OFFSET + inventory.inventory.selected;
            inventory.inventory.getInventoryStorage().setItem(mainHand, mace.copy());
            setOffhand(player, totem.copy());

            // Minecraft#handleKeybinds only sends the action; ServerGamePacketListenerImpl#handlePlayerAction swaps.
            inventory.onPlayerAction(
                    null,
                    player,
                    new ServerboundPlayerAction(
                            PlayerAction.SWAP_ITEM_WITH_OFFHAND, new BlockPos(0, 0, 0), Direction.DOWN, 0));
            assertHands(player, "minecraft:mace", "minecraft:totem_of_undying");

            int mainProof = setSlot(player, mainHand, totem.copy());
            int offProof = setSlot(player, Inventory.SLOT_OFFHAND, mace.copy());
            assertHands(player, "minecraft:mace", "minecraft:totem_of_undying");

            acknowledge(player, mainProof);
            assertHands(player, "minecraft:totem_of_undying", "minecraft:totem_of_undying");
            acknowledge(player, offProof);
            assertHands(player, "minecraft:totem_of_undying", "minecraft:mace");
        }
    }

    private static void assertHands(CultPlayer player, String main, String off) {
        assertEquals(main, player.getInventory().getHeldItem().itemKey());
        assertEquals(off, player.getInventory().getOffHand().itemKey());
    }

    private static int setSlot(CultPlayer player, int slot, SimItemStack item) {
        var packet = new InventoryPackets.Slot(0, 1, slot, item);
        PacketSendEvent<InventoryPackets.Slot> event = sendEvent(player, slotType(), packet);
        player.getInventory().onContainerSetSlot(event, player, packet);
        event.tasksAfter().forEach(Runnable::run);
        return player.lastTransactionSent.get();
    }

    @SuppressWarnings("unchecked")
    private static PacketType<InventoryPackets.Slot> slotType() {
        return (PacketType<InventoryPackets.Slot>) TestProtocolRuntime.catalog().stream()
                .filter(type -> type.recordClass() == InventoryPackets.Slot.class)
                .findFirst()
                .orElseThrow();
    }
}
