package ac.cult.cultac.network.packet;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NmsPacketUtilUseAndVehicleTest {
    @Test
    void itemUsePreservesHandSequenceAndRotation() {
        var data = NmsPacketUtil.readUseItem(new ServerboundUseItemPacket(InteractionHand.OFF_HAND, -7, -177.5F, 89.75F));
        assertEquals(InteractionHand.OFF_HAND, data.hand());
        assertEquals(-7, data.sequence());
        assertEquals(-177.5F, data.yaw());
        assertEquals(89.75F, data.pitch());
    }

    @Test
    void placementPreservesNegativeCoordinatesFaceAndCursor() {
        BlockPos block = new BlockPos(-32, 70, -4);
        var hit = new BlockHitResult(new Vec3(-31.75, 71, -3.5), Direction.UP, block, true);
        var data = NmsPacketUtil.readUseItemOn(new ServerboundUseItemOnPacket(InteractionHand.OFF_HAND, hit, 123));
        assertEquals(InteractionHand.OFF_HAND, data.hand());
        assertEquals(block, data.blockPosition());
        assertEquals(BlockFace.UP, data.blockFace());
        assertEquals(new Vec3(0.25, 1, 0.5), data.cursor());
        assertTrue(data.insideBlock());
        assertEquals(123, data.sequence());
    }

    @Test
    void serverboundVehicleWireRetainsItsOnGroundBit() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeDouble(-1024.125);
            buffer.writeDouble(65.5);
            buffer.writeDouble(4096.25);
            buffer.writeFloat(-123.5F);
            buffer.writeFloat(67.25F);
            buffer.writeBoolean(true);
            var packet = ServerboundMoveVehiclePacket.STREAM_CODEC.decode(buffer);
            var data = NmsPacketUtil.readMoveVehicle(packet);
            assertEquals(new Vec3(-1024.125, 65.5, 4096.25), data.position());
            assertEquals(-123.5F, data.yaw());
            assertEquals(67.25F, data.pitch());
            assertTrue(data.onGround());
            assertTrue(data.hasOnGround());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void clientboundVehicleRoundTripPreservesTransformWithoutInventingGroundState() {
        Vec3 position = new Vec3(-1024.125, 65.5, 4096.25);
        var packet = NmsPacketUtil.clientboundMoveVehiclePacket(position, -123.5F, 67.25F);
        var data = NmsPacketUtil.readMoveVehicle(packet);
        assertEquals(position, data.position());
        assertEquals(-123.5F, data.yaw());
        assertEquals(67.25F, data.pitch());
        assertFalse(data.hasOnGround());
    }
}
