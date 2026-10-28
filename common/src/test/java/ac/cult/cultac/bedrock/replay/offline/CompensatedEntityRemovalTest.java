package ac.cult.cultac.bedrock.replay.offline;

import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.nmsutil.EntityTypesCompat;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

public final class CompensatedEntityRemovalTest {
    @Test
    public void removedPassengerLeavesItsVehicleAndClientTickTraversal() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = offlineJavaPlayer();
        try {
            PacketEntity boat = addBoat(player);
            player.compensatedEntities.addEntity(72, EntityTypesCompat.PIG, new Vec3(0, 64, 0), 0, 0, 0);
            PacketEntity passenger = player.compensatedEntities.getEntity(72);
            passenger.mount(boat);

            player.compensatedEntities.removeEntity(72);

            assertNull(player.compensatedEntities.getEntity(72));
            assertNull(passenger.getRiding());
            assertFalse(boat.hasPassenger(passenger));
            List<PacketEntity> ticked = new ArrayList<>();
            player.compensatedEntities.tickClientEntities(ticked::add);
            assertEquals(List.of(boat), ticked);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void removedVehicleStillEjectsLocalAndRemotePassengers() {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = offlineJavaPlayer();
        try {
            PacketEntity boat = addBoat(player);
            player.compensatedEntities.addEntity(72, EntityTypesCompat.PIG, new Vec3(0, 64, 0), 0, 0, 0);
            PacketEntity passenger = player.compensatedEntities.getEntity(72);
            player.compensatedEntities.getSelf().mount(boat);
            passenger.mount(boat);

            player.compensatedEntities.removeEntity(71);

            assertNull(player.compensatedEntities.getSelf().getRiding());
            assertNull(passenger.getRiding());
            assertEquals(List.of(), boat.passengers);
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static PacketEntity addBoat(CultPlayer player) {
        player.compensatedWorld.ensureValidationChunkLoaded(0, 0);
        player.compensatedEntities.addEntity(71, EntityTypesCompat.OAK_BOAT, new Vec3(0, 64, 0), 0, 0, 0);
        return player.compensatedEntities.getEntity(71);
    }

    private static CultPlayer offlineJavaPlayer() {
        User user = new User(new User.Profile(UUID.fromString("ab7c66c7-9de3-40a9-afcb-a7ff7251884b"), ".Removal_Test"),
                null, null, null, new EmbeddedChannel());
        return new CultPlayer(user);
    }
}
