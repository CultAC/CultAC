package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.visibility.AttackMobVisibility;
import ac.cult.cultac.checks.impl.visibility.InteractEntityVisibility;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.events.packets.listeners.PacketPingListener;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolCodecs;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** A summoned interaction resized by its pairing data, clicked from a superflat floor (CultAC#55). */
class ResizedInteractionVisibilityTest {
    private static final int ID = 36;
    private static final ProtocolVersion VERSION = ProtocolVersion.V26_3;

    @Test
    void aSummonedInteractionIsClickedThroughItsReceivedSize() throws Exception {
        for (boolean bundled : List.of(true, false)) {
            try (var setup = new Setup()) {
                setup.pair(bundled);
                boolean interacted = setup.passes(setup.interactAt());
                // Minecraft#handleKeybinds attacks before it uses within a tick.
                assertTrue(setup.passes(setup.tickEnd()));
                boolean attacked = setup.passes(setup.attack());
                assertAll(
                        "bundled=" + bundled,
                        () -> assertTrue(interacted, "INTERACT_AT dropped"),
                        () -> assertTrue(attacked, "attack dropped"),
                        () -> assertEquals(0, setup.interact.violations),
                        () -> assertEquals(0, setup.attackCheck.violations));
                var entity = setup.player.compensatedEntities.getEntity(ID);
                var box = entity.toBoundingBox(setup.player, entity.getPossibleMovementCollisionBoxes());
                assertEquals(2, box.minX, 1.0E-6);
                assertEquals(5, box.maxX, 1.0E-6);
                assertEquals(-60, box.minY, 1.0E-6);
                assertEquals(-54.4, box.maxY, 1.0E-5);
                assertEquals(-1, box.minZ, 1.0E-6);
                assertEquals(2, box.maxZ, 1.0E-6);
            }
        }
    }

    private static final class Setup implements AutoCloseable {
        final RecordConsumerServices services = new RecordConsumerServices();
        final RecordReceiveFixture fixture = new RecordReceiveFixture(VERSION, false);
        final CultPlayer player;
        final InteractEntityVisibility interact;
        final AttackMobVisibility attackCheck;

        Setup() throws Exception {
            services.useNetwork(fixture.manager);
            var platformPlayer = Mockito.mock(PlatformPlayer.class);
            var platformWorld = Mockito.mock(PlatformWorld.class);
            Mockito.when(platformPlayer.getWorld()).thenReturn(platformWorld);
            Mockito.when(platformWorld.getName()).thenReturn("world");
            fixture.user.bind(platformPlayer);
            CultAPI.INSTANCE.getPlayerDataManager().addUser(fixture.user);
            player = fixture.user.getCultPlayer();
            player.checkManager.allChecks.values().forEach(check -> check.setEnabled(false));
            interact = player.checkManager.getCheck(InteractEntityVisibility.class);
            interact.setEnabled(true);
            attackCheck = player.checkManager.getCheck(AttackMobVisibility.class);
            attackCheck.setEnabled(true);
            player.gamemode = GameMode.SURVIVAL;
            // Loaded and ticked since the teleport, as the clicks in the report are.
            player.getSetbackTeleportUtil().hasFullyLoaded = true;
            player.packetStateData.localPlayerTickedSinceSpawn = true;
            // /tp @s 0.5 -60 0.5 -90 0 onto a superflat floor.
            player.x = .5;
            player.y = -60;
            player.z = .5;
            player.yRot = -90;
            player.xRot = 0;
            player.boundingBox = new SimpleCollisionBox(.2, -60, .2, .8, -58.2, .8);
            player.registryState = new ClientComponentRegistries();
            var world = player.compensatedWorld;
            var dimension = player.getWorldRegistries().dimension("minecraft:overworld");
            world.setLastClientboundDimension("minecraft:overworld", dimension.dimension());
            world.setDimension("minecraft:overworld", dimension);
            world.ensureValidationChunkLoaded(0, 0);
            world.ensureValidationChunkLoaded(0, -1);
            assertTrue(world.getMinHeight() <= -64, "level starts at " + world.getMinHeight());
            fixture.registrar.register(routes -> {
                var checks = new CheckManagerListener();
                checks.registerForwardingEarlyReceivePackets(routes, PacketListenerPriority.LOW);
                routes.registerReceiveListener(PacketListenerPriority.LOW, checks);
                checks.registerForwardingSendPackets(routes, PacketListenerPriority.LOW);
                routes.registerListener(PacketListenerPriority.LOWEST, new PacketPingListener());
            });
            fixture.channel.runPendingTasks();
        }

        /** ServerEntity#sendPairingData: add_entity, then the summon's width and height. */
        void pair(boolean bundled) {
            if (bundled) assertTrue(fixture.channel.writeOutbound(delimiter()));
            assertTrue(fixture.channel.writeOutbound(addInteraction()));
            assertTrue(fixture.channel.writeOutbound(size(3, 5.6F)));
            if (bundled) assertTrue(fixture.channel.writeOutbound(delimiter()));
            fixture.channel.runPendingTasks();
            answerPings();
        }

        private void answerPings() {
            ByteBuf frame;
            while ((frame = fixture.channel.readOutbound()) != null) {
                try {
                    int id = Wire.readVarInt(frame);
                    if (fixture.codec.read(PacketDirection.CLIENTBOUND, id, frame) instanceof ClientboundPing ping)
                        assertFalse(fixture.channel.writeInbound(fixture.pong(ping.id())));
                } finally {
                    frame.release();
                }
            }
            fixture.channel.runPendingTasks();
        }

        private ByteBuf delimiter() {
            return clientbound("bundle_delimiter");
        }

        private ByteBuf addInteraction() {
            var frame = clientbound("add_entity");
            Wire.writeVarInt(frame, ID);
            Wire.writeUuid(frame, new UUID(0, ID));
            Wire.writeVarInt(
                    frame, fixture.data.registry("minecraft:entity_type").id("minecraft:interaction"));
            frame.writeDouble(3.5).writeDouble(-60).writeDouble(.5);
            Wire.writeLpVec3(frame, new Vec3d(0, 0, 0));
            Wire.writeAngle(frame, VERSION, 0);
            Wire.writeAngle(frame, VERSION, 0);
            Wire.writeAngle(frame, VERSION, 0);
            Wire.writeVarInt(frame, 0);
            return frame;
        }

        /** Interaction#DATA_WIDTH_ID and DATA_HEIGHT_ID. */
        private ByteBuf size(float width, float height) {
            int serializer = ProtocolCodecs.decoder().floatSerializer(VERSION);
            var frame = clientbound("set_entity_data");
            Wire.writeVarInt(frame, ID);
            frame.writeByte(8);
            Wire.writeVarInt(frame, serializer);
            frame.writeFloat(width);
            frame.writeByte(9);
            Wire.writeVarInt(frame, serializer);
            frame.writeFloat(height);
            return frame.writeByte(255);
        }

        /** A play-phase clientbound frame header; the ID comes from the packet name. */
        private ByteBuf clientbound(String name) {
            return fixture.id(ClientboundPackets.ADD_ENTITY, name);
        }

        /** Right click on the near face, 1.62 above the entity's feet. */
        ByteBuf interactAt() {
            var frame = fixture.id(ServerboundPackets.INTERACT, "interact");
            Wire.writeVarInt(frame, ID);
            Wire.writeVarInt(frame, 0);
            Wire.writeLpVec3(frame, new Vec3d(-1.5, 1.62, 0));
            return frame.writeBoolean(false);
        }

        ByteBuf tickEnd() {
            return fixture.id(ServerboundPackets.CLIENT_TICK_END, "client_tick_end");
        }

        ByteBuf attack() {
            var frame = fixture.id(ServerboundPackets.INTERACT, "attack");
            Wire.writeVarInt(frame, ID);
            return frame;
        }

        boolean passes(ByteBuf frame) {
            boolean passed = fixture.channel.writeInbound(frame);
            for (Object read; (read = fixture.channel.readInbound()) != null; ) {
                if (read instanceof ByteBuf buffer) buffer.release();
            }
            return passed;
        }

        @Override
        public void close() throws Exception {
            try {
                fixture.close();
            } finally {
                services.close();
            }
        }
    }
}
