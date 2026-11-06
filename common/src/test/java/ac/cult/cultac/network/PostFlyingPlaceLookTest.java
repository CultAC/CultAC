package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.scaffolding.DuplicateRotPlace;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Minecraft#tick sends a place from handleKeybinds, then LocalPlayer#sendPosition, then ServerboundClientTickEndPacket.
 * sendPosition sends nothing when neither position nor rotation changed, so such a tick places at its unchanged look.
 */
class PostFlyingPlaceLookTest {
    private static final Vec3 STANDING = new Vec3(0.5, JavaTickFixture.FLOOR_Y, 0.5);
    private static final BlockPos CLICKED = new BlockPos(2, JavaTickFixture.FLOOR_Y, 0);
    private static final float PITCH = 36;

    private JavaTickFixture fixture;
    private int sequence;

    @BeforeEach
    void connect() throws Exception {
        fixture = new JavaTickFixture();
        fixture.player.setExperimentalChecks(true);
        int stone = DataTables.defaults().registry().block("minecraft:stone").defaultState();
        fixture.player.compensatedWorld.updateBlock(CLICKED.getX(), CLICKED.getY(), CLICKED.getZ(), stone);
        fixture.place(STANDING, Vec3.ZERO);
        fixture.player.xRot = -90;
        fixture.player.yRot = PITCH;
        fixture.tick(STANDING, true);
        var storage = fixture.player.getInventory().inventory.getInventoryStorage();
        storage.setItem(Inventory.HOTBAR_OFFSET, ItemUtil.modelItems().stack("minecraft:stone", 64));
        fixture.player.getInventory().selectHotbarSlot(0);
    }

    @AfterEach
    void disconnect() throws Exception {
        fixture.close();
    }

    @Test
    void aPlaceInATickWithoutMovementTakesThatTicksLookNotTheNextTurn() {
        look(-89.5F);
        fixture.tickEnd();

        useOn();
        fixture.tickEnd();

        look(-87F);
        fixture.tickEnd();

        useOn();
        look(-84.5F);
        fixture.tickEnd();

        assertEquals(0, fixture.player.checkManager.getCheck(DuplicateRotPlace.class).violations);
    }

    private void look(float yaw) {
        var packet = new ServerboundMovePlayer(0, 0, 0, yaw, PITCH, true, false, false, true);
        fixture.listener.onMovePlayer(fixture.event(ServerboundPackets.MOVE_PLAYER, packet), fixture.player, packet);
    }

    private void useOn() {
        fixture.player.packetStateData.clientSidePosition = STANDING;
        var packet = new ServerboundUseItemOn(
                Hand.MAIN_HAND, CLICKED, Direction.WEST, new Vec3d(0, 0.5, 0.5), false, false, ++sequence);
        fixture.listener.onUseItemOn(fixture.event(ServerboundPackets.USE_ITEM_ON, packet), fixture.player, packet);
    }
}
