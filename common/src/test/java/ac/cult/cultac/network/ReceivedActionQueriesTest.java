package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.Box;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.ItemRegistry;
import ac.cult.blocksim.engine.SimInventory;
import ac.cult.blocksim.engine.SimPlayer;
import ac.cult.blocksim.engine.Vec3;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ModelBlockStates;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.blockplace.BlockSimulatorWorldView;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.latency.CompensatedBlockEntities;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReceivedActionQueriesTest {
    private static BlockSimulatorWorldView view(RecordReceiveFixture fixture) {
        return new BlockSimulatorWorldView(
                fixture.player.compensatedWorld,
                fixture.player.checkManager.getListener(ac.cult.cultac.events.packets.PacketWorldBorder.class),
                ModelBlockStates.project(ProtocolVersion.V26_3, ProtocolVersion.V26_3));
    }

    private static EntityMetadata.Entry field(int index, Object value) {
        // These tests consume already-decoded metadata; the wire bytes are retained by the record.
        return new EntityMetadata.Entry(index, value, ByteBuffer.allocate(0));
    }

    @Test
    void collisionsDistinguishPushableLivingEntitiesClimbingAndReceivedHealth() throws Exception {
        try (var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var player = fixture.player;
            player.gamemode = GameMode.SURVIVAL;
            player.boundingBox = new SimpleCollisionBox(-3, 64, -3, -2.4, 65.8, -2.4, false);
            player.compensatedWorld.ensureValidationChunkLoaded(0, 0);
            var entities = player.compensatedEntities;
            var pos = new ac.cult.cultac.utils.math.Vec3(1.5, 64, 1.5);
            var query = new Box(1, 64, 1, 2, 65, 2);
            entities.addEntity(
                    42,
                    ac.cult.blocksim.entity.EntityTypes.defaults()
                            .byKey("minecraft:cat")
                            .id(),
                    pos,
                    0,
                    0,
                    0);
            assertFalse(view(fixture).hasEntityCollision(query, false));
            assertTrue(view(fixture).hasEntityCollision(query, true));
            var feet = new BlockPos(1, 64, 1);
            player.compensatedWorld.updateBlock(
                    feet,
                    DataTables.defaults().registry().block("minecraft:ladder").defaultState());
            assertFalse(view(fixture).hasEntityCollision(query, true));
            player.registryState = new ac.cult.cultac.utils.latency.ClientComponentRegistries();
            player.registryState.appendTags(new ac.cult.cultac.network.packet.RegistryTags(java.util.Map.of(
                    "minecraft:block",
                    new ac.cult.cultac.network.packet.RegistryTags.Payload(
                            java.util.Map.of("minecraft:climbable", List.of())))));
            assertTrue(
                    view(fixture).hasEntityCollision(query, true),
                    "The received empty climbable tag replaces the default membership");
            player.compensatedWorld.updateBlock(
                    feet,
                    DataTables.defaults().registry().block("minecraft:air").defaultState());
            entities.updateEntityMetadata(42, List.of(field(9, 0.0F)));
            assertFalse(view(fixture).hasEntityCollision(query, true));
            entities.updateEntityMetadata(42, List.of(field(9, 1.0F)));
            assertTrue(view(fixture).hasEntityCollision(query, true));
            entities.removeEntity(42);
            entities.addEntity(43, EntityTypeIds.SHULKER, pos, 0, 0, 0);
            assertTrue(view(fixture).hasEntityCollision(query, false));
            entities.updateEntityMetadata(43, List.of(field(9, 0.0F)));
            assertFalse(view(fixture).hasEntityCollision(query, false));
            entities.removeEntity(43);
            entities.addEntity(
                    44,
                    ac.cult.blocksim.entity.EntityTypes.defaults()
                            .byKey("minecraft:creaking")
                            .id(),
                    pos,
                    0,
                    0,
                    0);
            assertTrue(view(fixture).hasEntityCollision(query, true));
            entities.updateEntityMetadata(44, List.of(field(16, false)));
            assertFalse(view(fixture).hasEntityCollision(query, true));
            entities.removeEntity(44);
            player.registryState = null;
            player.compensatedWorld.updateBlock(
                    feet,
                    DataTables.defaults().registry().block("minecraft:ladder").defaultState());
            entities.addEntity(
                    45,
                    ac.cult.blocksim.entity.EntityTypes.defaults()
                            .byKey("minecraft:spider")
                            .id(),
                    pos,
                    0,
                    0,
                    0);
            assertTrue(
                    view(fixture).hasEntityCollision(query, true),
                    "Spiders read their climbing flag, even when inside a ladder");
            entities.updateEntityMetadata(45, List.of(field(16, (byte) 1)));
            assertFalse(view(fixture).hasEntityCollision(query, true));
        }
    }

    @Test
    void brushEntryFacesAndBoatEyeContainmentUseDifferentPickingRules() throws Exception {
        try (var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var player = fixture.player;
            player.gamemode = GameMode.SURVIVAL;
            player.boundingBox = new SimpleCollisionBox(.2, 64, -.3, .8, 65.8, .3, false);
            var items = new ItemRegistry(DataTables.load("26.3"));
            var inventory = new SimInventory(Collections.nCopies(43, items.empty()), 0, false, items.empty());
            var from = new Vec3(.5, 65.62, 0);
            var owner = new SimPlayer(
                    new SimPlayer.State(
                            new Vec3(.5, 64, 0), 0, 0, false, false, true, false, SimPlayer.GameMode.SURVIVAL),
                    inventory,
                    null,
                    new SimPlayer.Sight(from, 4.5));
            var type = ac.cult.blocksim.entity.EntityTypes.defaults()
                    .byKey("minecraft:interaction")
                    .id();
            var entities = player.compensatedEntities;
            entities.addEntity(42, type, new ac.cult.cultac.utils.math.Vec3(.5, 65, 2), 0, 0, 0);
            assertTrue(view(fixture).hasPickableEntityHit(from, new Vec3(.5, 65.62, 3), owner));
            assertFalse(view(fixture).hasPickableEntityHit(from, new Vec3(.5, 65.62, 1.5), owner));
            assertFalse(view(fixture).hasPickableEntityAtEye(owner));
            entities.removeEntity(42);
            entities.addEntity(43, type, new ac.cult.cultac.utils.math.Vec3(.5, 65, 0), 0, 0, 0);
            assertTrue(view(fixture).hasPickableEntityAtEye(owner));
            assertFalse(view(fixture).hasPickableEntityHit(from, new Vec3(.5, 65.62, 3), owner));
            entities.updateEntityMetadata(43, List.of(field(9, .1F)));
            assertFalse(view(fixture).hasPickableEntityAtEye(owner));
            assertFalse(view(fixture).hasEntityCollision(new Box(0, 65, -1, 1, 66, 1), false));
            entities.updateEntityMetadata(43, List.of(field(9, Float.NaN)));
            assertFalse(view(fixture).hasPickableEntityAtEye(owner));
        }
    }

    @Test
    void jukeboxUpdateAfterPurePredictionComparesThePreviousRecordIgnoringCount() throws Exception {
        var entities = new CompensatedBlockEntities(ac.cult.blocksim.data.InteractionRegistries::defaults);
        var pos = new BlockPos(1, 64, 1);
        int state = DataTables.defaults()
                .registry()
                .with(
                        DataTables.defaults()
                                .registry()
                                .block("minecraft:jukebox")
                                .defaultState(),
                        "has_record",
                        "true");
        var tag = tag("{RecordItem:{id:'minecraft:music_disc_pigstep',count:1},ticks_since_song_started:0L}");
        entities.receive(
                new ac.cult.cultac.network.packet.WorldPackets.BlockEntityUpdate(pos, "minecraft:jukebox", tag), state);
        entities.tick();
        entities.predict(pos, state, entities.snapshot(pos, state));
        var same = tag("{RecordItem:{id:'minecraft:music_disc_pigstep',count:2}}");
        entities.receive(
                new ac.cult.cultac.network.packet.WorldPackets.BlockEntityUpdate(pos, "minecraft:jukebox", same),
                state);
        assertTrue(entities.snapshot(pos, state).data().get("song_playing").getAsBoolean());
        assertEquals(
                1,
                entities.snapshot(pos, state)
                        .data()
                        .get("ticks_since_song_started")
                        .getAsLong());
        entities.predict(pos, state, entities.snapshot(pos, state));
        var changed = tag("{RecordItem:{id:'minecraft:music_disc_cat',count:1}}");
        entities.receive(
                new ac.cult.cultac.network.packet.WorldPackets.BlockEntityUpdate(pos, "minecraft:jukebox", changed),
                state);
        assertFalse(entities.snapshot(pos, state).data().get("song_playing").getAsBoolean());
        assertFalse(entities.snapshot(pos, state).savedData().values().containsKey("ticks_since_song_started"));
    }

    @Test
    void jukeboxPlaybackUsesReceivedLengthAndKeepsUnusedComponentsOpaque() {
        var registries = ac.cult.blocksim.data.InteractionRegistries.defaults()
                .withRegistry(
                        "jukebox_song",
                        java.util.Map.of(
                                "minecraft:pigstep",
                                com.google.gson.JsonParser.parseString(
                                        "{length_in_seconds:0.01,comparator_output:7}")));
        var entities = new CompensatedBlockEntities(() -> registries);
        var pos = new BlockPos(1, 64, 1);
        int state = DataTables.defaults()
                .registry()
                .with(
                        DataTables.defaults()
                                .registry()
                                .block("minecraft:jukebox")
                                .defaultState(),
                        "has_record",
                        "true");
        // Only playback and item identity consume this packet's NBT. The book stays a blob.
        var tag = (ac.cult.blocksim.data.nbt.NbtValue.Compound)
                ac.cult.blocksim.data.nbt.CanonicalSnbt.parse(
                        "{RecordItem:{id:'minecraft:music_disc_pigstep',components:{'minecraft:custom_data':{book:{pages:['unused']}}}},ticks_since_song_started:20L}");
        entities.receive(
                new ac.cult.cultac.network.packet.WorldPackets.BlockEntityUpdate(pos, "minecraft:jukebox", tag), state);
        assertEquals(21, entities.snapshot(pos, state).data().integer("song_end_tick", 0));
        assertTrue(entities.snapshot(pos, state).data().get("song_playing").getAsBoolean());
        var projected = entities.snapshot(pos, state)
                .data()
                .get("RecordItem")
                .getAsJsonObject()
                .getAsJsonObject("components");
        assertFalse(projected.has("minecraft:custom_data"));
        entities.tick();
        assertTrue(entities.snapshot(pos, state).data().get("song_playing").getAsBoolean());
        entities.tick();
        assertFalse(entities.snapshot(pos, state).data().get("song_playing").getAsBoolean());
    }

    private static ac.cult.blocksim.data.nbt.NbtValue.Compound tag(String text) {
        return (ac.cult.blocksim.data.nbt.NbtValue.Compound) ac.cult.blocksim.data.nbt.CanonicalSnbt.parse(text);
    }
}
