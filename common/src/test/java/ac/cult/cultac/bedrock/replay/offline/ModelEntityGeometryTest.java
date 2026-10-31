package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.data.packetentity.PacketEntityNautilus;
import ac.cult.cultac.utils.math.Vec3;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ModelEntityGeometryTest {
    @Test
    void chestedHorsesKeepTheirDistinctInitialMovementAttributes() {
        OfflineCultTestBootstrap.installConfig();
        var player = new CultPlayer(
                OfflineCultTestBootstrap.wireUser(new User.Profile(UUID.randomUUID(), "Model_Entities")));
        try {
            player.compensatedEntities.addEntity(71, EntityTypeIds.HORSE, Vec3.ZERO, 0F, 0F, 0);
            player.compensatedEntities.addEntity(72, EntityTypeIds.DONKEY, Vec3.ZERO, 0F, 0F, 0);
            var horse = assertInstanceOf(PacketEntityHorse.class, player.compensatedEntities.getEntity(71));
            var donkey = assertInstanceOf(PacketEntityHorse.class, player.compensatedEntities.getEntity(72));
            assertEquals(.7D, horse.jumpStrength);
            assertEquals(.225F, horse.movementSpeedAttribute);
            assertEquals(.5D, donkey.jumpStrength);
            assertEquals(.175F, donkey.movementSpeedAttribute);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    void zombieNautilusKeepsItsOwnInitialMovementSpeed() {
        OfflineCultTestBootstrap.installConfig();
        var player = new CultPlayer(
                OfflineCultTestBootstrap.wireUser(new User.Profile(UUID.randomUUID(), "Model_Nautilus")));
        try {
            var types = ac.cult.blocksim.entity.EntityTypes.defaults();
            player.compensatedEntities.addEntity(
                    71, types.byKey("minecraft:nautilus").id(), Vec3.ZERO, 0F, 0F, 0);
            player.compensatedEntities.addEntity(
                    72, types.byKey("minecraft:zombie_nautilus").id(), Vec3.ZERO, 0F, 0F, 0);
            var nautilus = assertInstanceOf(PacketEntityNautilus.class, player.compensatedEntities.getEntity(71));
            var zombie = assertInstanceOf(PacketEntityNautilus.class, player.compensatedEntities.getEntity(72));
            // AbstractNautilus#createAttributes and ZombieNautilus#createAttributes.
            assertEquals(1.0D, nautilus.movementSpeed.value());
            assertEquals(1.0D, nautilus.movementSpeedAttribute);
            assertEquals((double) 1.1F, zombie.movementSpeed.value());
            assertEquals((double) 1.1F, zombie.movementSpeedAttribute);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    void livingAndAgeableFishDoNotAcquireAnimalRules() {
        OfflineCultTestBootstrap.installConfig();
        var cod = new PacketEntity(EntityTypeIds.COD, 71);
        int squidType = ac.cult.blocksim.entity.EntityTypes.defaults()
                .byKey("minecraft:squid")
                .id();
        var squid = new PacketEntity(squidType, 72);
        assertTrue(cod.isLivingEntity());
        assertFalse(cod.isAgeable());
        assertFalse(cod.isAnimal());
        assertTrue(squid.isLivingEntity());
        assertTrue(squid.isAgeable());
        assertFalse(squid.isAnimal());
        assertFalse(new PacketEntity(EntityTypeIds.ITEM, 73).isLivingEntity());
        assertTrue(new PacketEntity(EntityTypeIds.ARMOR_STAND, 74).isLivingEntity());
    }
}
