package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.breaking.FastBreak;
import ac.cult.cultac.checks.impl.verbose.VerboseCodecs;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.anticheat.update.BlockBreak;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BlockBreakSpeed;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MiningAttributeTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 2);

    @BeforeAll
    static void registerVerboseTags() {
        VerboseCodecs.ensureRegistered();
    }

    @Test
    void miningEfficiencyAttributeSpeedsUpBreaking() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var check = miner(f, "minecraft:mining_efficiency", 1024.0);
            breakInstantly(f.player, check);
            assertEquals(0, check.getViolations());
            // Player#getDestroySpeed: (8 + 1024) / 50 / 30 per tick, two ticks for obsidian.
            assertEquals(1032.0F / 50 / 30, damage(f.player), 1e-7);
        }
    }

    @Test
    void breakingFasterThanMiningEfficiencyAllowsFlags() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var check = miner(f, "minecraft:mining_efficiency", 100.0);
            breakInstantly(f.player, check);
            breakInstantly(f.player, check);
            assertEquals(1, check.getViolations());
        }
    }

    @Test
    void blockBreakSpeedScalesBreaking() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            miner(f, "minecraft:block_break_speed", 4.0);
            assertEquals(8.0F * 4 / 50 / 30, damage(f.player), 1e-7);
            attribute(f.player, "minecraft:block_break_speed", 0.25);
            assertEquals(8.0F * 0.25F / 50 / 30, damage(f.player), 1e-7);
        }
    }

    @Test
    void respawnWithoutKeptModifiersRestoresTheBlockBreakSpeedBase() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            miner(f, "minecraft:block_break_speed", 1.0, -0.75);
            assertEquals(8.0F * 0.25F / 50 / 30, damage(f.player), 1e-7);
            // ClientPacketListener#handleRespawn: AttributeMap#assignBaseValues copies only the base.
            f.respawn((byte) 0);
            f.player.packetStateData.packetPlayerOnGround = true;
            assertEquals(8.0F / 50 / 30, damage(f.player), 1e-7);
        }
    }

    @Test
    void respawnWithoutKeptModifiersDropsMiningEfficiency() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var check = miner(f, "minecraft:mining_efficiency", 0.0, 1024.0);
            breakInstantly(f.player, check);
            assertEquals(0, check.getViolations());
            f.respawn((byte) 0);
            f.player.packetStateData.packetPlayerOnGround = true;
            breakInstantly(f.player, check);
            assertEquals(1, check.getViolations());
        }
    }

    @Test
    void respawnKeepingModifiersKeepsMiningAttributes() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var check = miner(f, "minecraft:mining_efficiency", 0.0, 1024.0);
            attribute(f.player, "minecraft:block_break_speed", 1.0, -0.75);
            f.respawn(ClientboundRespawn.KEEP_ATTRIBUTE_MODIFIERS);
            f.player.packetStateData.packetPlayerOnGround = true;
            assertEquals(1032.0F * 0.25F / 50 / 30, damage(f.player), 1e-7);
        }
    }

    @Test
    void submergedMiningSpeedKeepsItsBaseAcrossARespawn() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            miner(f, "minecraft:submerged_mining_speed", 0.6, 0.4);
            assertEquals(1.0, f.player.compensatedEntities.getSelf().submergedMiningSpeed, 1e-7);
            f.respawn((byte) 0);
            assertEquals(0.6, f.player.compensatedEntities.getSelf().submergedMiningSpeed, 1e-7);
        }
    }

    @Test
    void horseJumpStrengthIsNotCappedAtTheLegacyRange() throws Exception {
        try (var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var entities = f.player.compensatedEntities;
            entities.addEntity(42, EntityTypeIds.HORSE, Vec3.ZERO, 0, 0, 0);
            entities.updateAttributes(42, List.of(new AttributeSnapshot("minecraft:jump_strength", 3.0, List.of())));
            assertEquals(3.0, ((PacketEntityHorse) entities.getEntity(42)).jumpStrength);
            entities.updateAttributes(42, List.of(new AttributeSnapshot("minecraft:jump_strength", 40.0, List.of())));
            assertEquals(32.0, ((PacketEntityHorse) entities.getEntity(42)).jumpStrength);
        }
    }

    private static FastBreak miner(RecordReceiveFixture f, String attribute, double value) {
        return miner(f, attribute, value, 0.0);
    }

    private static FastBreak miner(RecordReceiveFixture f, String attribute, double base, double added) {
        f.user.bind(mock(PlatformPlayer.class));
        f.player.packetStateData.packetPlayerOnGround = true;
        f.player.getInventory().inventory.setHeldItem(OfflineCultTestBootstrap.item("minecraft:diamond_pickaxe"));
        attribute(f.player, attribute, base, added);
        return f.player.checkManager.getCheck(FastBreak.class);
    }

    private static void attribute(CultPlayer player, String attribute, double value) {
        attribute(player, attribute, value, 0.0);
    }

    private static void attribute(CultPlayer player, String attribute, double base, double added) {
        List<AttributeModifier> modifiers = added == 0.0
                ? List.of()
                : List.of(new AttributeModifier("test:modifier", added, AttributeModifier.Operation.ADD_VALUE));
        player.compensatedEntities.updateAttributes(
                player.entityID, List.of(new AttributeSnapshot(attribute, base, modifiers)));
    }

    private static double damage(CultPlayer player) {
        return BlockBreakSpeed.getBlockDamage(
                player, player.getInventory().getHeldItem(), BlockIds.OBSIDIAN.defaultState());
    }

    private static void breakInstantly(CultPlayer player, FastBreak check) {
        int obsidian = BlockIds.OBSIDIAN.defaultState();
        for (PlayerAction action :
                new PlayerAction[] {PlayerAction.START_DESTROY_BLOCK, PlayerAction.STOP_DESTROY_BLOCK}) {
            check.onBlockBreak(new BlockBreak(
                    player, TARGET, Direction.DOWN, Direction.DOWN.get3DDataValue(), action, 1, obsidian));
        }
    }
}
