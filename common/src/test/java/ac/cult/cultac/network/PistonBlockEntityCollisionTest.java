package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.Box;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.cultac.network.packet.WorldPackets;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.nmsutil.NativeBlockCollisionHelper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PistonBlockEntityCollisionTest {
    @Test
    void orderedBlockEntityPacketReloadsTheExistingPistonAndExactProgress() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var world = fixture.player.compensatedWorld;
            world.ensureValidationChunkLoaded(0, 0);
            var pos = new BlockPos(4, 64, 4);
            int moving = DataTables.defaults()
                    .registry()
                    .block("minecraft:moving_piston")
                    .defaultState();
            var registry = DataTables.defaults().registry();
            int piston = registry.with(registry.block("minecraft:piston").defaultState(), "facing", "east");
            var base = new BlockPos(3, 64, 4);
            world.updateBlock(base, piston);
            world.pistons.handleBlockEvent(base, registry.blockIndex(piston), 0, 5, 1);
            assertEquals(
                    "minecraft:moving_piston",
                    registry.block(world.getBlockStateIdAt(pos)).key());
            var tag = new NbtValue.Compound(Map.of(
                    "blockState", new NbtValue.Compound(Map.of("id", new NbtValue.Text("minecraft:stone"))),
                    "facing", new NbtValue.Numeric(NbtValue.Kind.INT, 5),
                    "progress", new NbtValue.Numeric(NbtValue.Kind.FLOAT, .375F),
                    "extending", new NbtValue.Numeric(NbtValue.Kind.BYTE, (byte) 1),
                    "source", new NbtValue.Numeric(NbtValue.Kind.BYTE, (byte) 0)));
            world.applyClientBlockEntityData(
                    "minecraft:overworld", new WorldPackets.BlockEntityUpdate(pos, "minecraft:piston", tag));
            // Original PistonMovingBlockEntity.loadAdditional loads .375 directly;
            // getCollisionShape shifts the unit stone by progress - 1 along EAST.
            assertEquals(
                    List.of(new Box(-.625, 0, 0, .375, 1, 1)),
                    NativeBlockCollisionHelper.getCollisionShape(fixture.player, moving, 4, 64, 4)
                            .boxes());
        }
    }

    @Test
    void blockEntityUpdatesDoNotCreateAPistonThatVanillaCannotCreate() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var world = fixture.player.compensatedWorld;
            world.ensureValidationChunkLoaded(0, 0);
            var pos = new BlockPos(4, 64, 4);
            int moving = DataTables.defaults()
                    .registry()
                    .block("minecraft:moving_piston")
                    .defaultState();
            world.updateBlock(pos, moving);
            var tag = new NbtValue.Compound(Map.of(
                    "blockState", new NbtValue.Compound(Map.of("id", new NbtValue.Text("minecraft:stone"))),
                    "facing", new NbtValue.Numeric(NbtValue.Kind.INT, 5),
                    "progress", new NbtValue.Numeric(NbtValue.Kind.FLOAT, .375F),
                    "extending", new NbtValue.Numeric(NbtValue.Kind.BYTE, (byte) 1)));
            world.applyClientBlockEntityData(
                    "minecraft:overworld", new WorldPackets.BlockEntityUpdate(pos, "minecraft:piston", tag));
            // MovingPistonBlock.newBlockEntity returns null; ClientPacketListener therefore ignores this update.
            assertTrue(NativeBlockCollisionHelper.getCollisionShape(fixture.player, moving, 4, 64, 4)
                    .isEmpty());
        }
    }
}
