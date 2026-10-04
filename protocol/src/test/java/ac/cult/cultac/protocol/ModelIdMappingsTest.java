package ac.cult.cultac.protocol;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.data.ModelIdMappings;
import ac.cult.cultac.protocol.data.ModelRegistryData;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelIdMappingsTest {
    private static final List<ProtocolVersion> VERSIONS = List.of(ProtocolVersion.values());

    @Test
    void everyIdentityStageRetainsExactNativeIdsAndRejectsOutOfBounds() {
        for (var version : VERSIONS) {
            var data = ModelRegistryData.load(version);
            var mappings = ModelIdMappings.project(version, version);
            assertEquals(data.blockStates().size(), mappings.blockStateCount());
            assertEquals(data.registry("minecraft:block").size(), mappings.blockCount());
            assertEquals(data.registry("minecraft:item").size(), mappings.itemCount());
            assertEquals(data.registry("minecraft:entity_type").size(), mappings.entityCount());
            for (int id = 0; id < mappings.blockStateCount(); id++) assertEquals(id, mappings.blockState(id));
            for (int id = 0; id < mappings.blockCount(); id++) assertEquals(id, mappings.block(id));
            for (int id = 0; id < mappings.itemCount(); id++) assertEquals(id, mappings.item(id));
            for (int id = 0; id < mappings.entityCount(); id++) assertEquals(id, mappings.entity(id));
            assertThrows(MalformedPacketException.class, () -> mappings.blockState(-1));
            assertThrows(MalformedPacketException.class, () -> mappings.block(mappings.blockCount()));
            assertThrows(MalformedPacketException.class, () -> mappings.item(mappings.itemCount()));
            assertThrows(MalformedPacketException.class, () -> mappings.entity(mappings.entityCount()));
            assertThrows(IllegalArgumentException.class, () -> ModelIdMappings.load(version, version));
            assertSame(mappings, ModelIdMappings.project(version, version));
        }
    }

    @Test
    void everyOfficialBlockAndItemIdHasAValidatedTargetAcrossEveryForwardPair() {
        var models = VERSIONS.stream().map(ModelRegistryData::load).toList();
        for (int source = 0; source < VERSIONS.size(); source++)
            for (int target = source + 1; target < VERSIONS.size(); target++) {
                var mappings = ModelIdMappings.load(VERSIONS.get(source), VERSIONS.get(target));
                var old = models.get(source);
                var newer = models.get(target);
                assertEquals(old.blockStates().size(), mappings.blockStateCount());
                assertEquals(old.registry("minecraft:item").size(), mappings.itemCount());
                for (int id = 0; id < mappings.blockStateCount(); id++) {
                    int mapped = mappings.blockState(id);
                    assertTrue(mapped >= 0 && mapped < newer.blockStates().size());
                    int exact = newer.blockStateId(old.blockStateName(id));
                    if (exact >= 0) assertEquals(exact, mapped, old.blockStateName(id));
                }
                for (int id = 0; id < mappings.itemCount(); id++) {
                    int mapped = mappings.item(id);
                    assertTrue(mapped >= 0
                            && mapped < newer.registry("minecraft:item").size());
                    int exact = newer.registry("minecraft:item")
                            .id(old.registry("minecraft:item").name(id));
                    if (exact >= 0) assertEquals(exact, mapped);
                }
                assertSame(mappings, ModelIdMappings.load(VERSIONS.get(source), VERSIONS.get(target)));
            }
    }

    @Test
    void rawEntityMappingsPreserveRegisteredIdsAndRemovalsWithoutVisualFallbacks() {
        for (var source : VERSIONS)
            for (var target : VERSIONS) {
                var original = ModelRegistryData.load(source).registry("minecraft:entity_type");
                var destination = ModelRegistryData.load(target).registry("minecraft:entity_type");
                var mappings = ModelIdMappings.project(source, target);
                assertEquals(original.size(), mappings.entityCount());
                for (int id = 0; id < original.size(); id++) {
                    int projected = mappings.entity(id);
                    assertTrue(projected >= -1 && projected < destination.size());
                    int existing = destination.id(original.name(id));
                    if (existing >= 0) assertEquals(existing, projected, original.name(id));
                }
            }
        var newer = ModelRegistryData.load(ProtocolVersion.V26_3).registry("minecraft:entity_type");
        int copper = newer.id("minecraft:copper_golem");
        assertTrue(copper >= 0);
        int mapped = ModelIdMappings.project(ProtocolVersion.V26_3, ProtocolVersion.V1_21_3)
                .entity(copper);
        assertEquals(
                "minecraft:frog",
                ModelRegistryData.load(ProtocolVersion.V1_21_3)
                        .registry("minecraft:entity_type")
                        .name(mapped));
    }

    @Test
    void renamedChainAndHeartPropertiesFollowTheUpstreamMappings() {
        var old = ModelRegistryData.load(ProtocolVersion.V1_21_3);
        var newer = ModelRegistryData.load(ProtocolVersion.V1_21_11);
        var mappings = ModelIdMappings.load(old.version(), newer.version());
        int chain = old.blockStateId("minecraft:chain[axis=x,waterlogged=true]");
        assertEquals("minecraft:iron_chain[axis=x,waterlogged=true]", newer.blockStateName(mappings.blockState(chain)));
        int heart = old.blockStateId("minecraft:creaking_heart[axis=y,creaking=active]");
        assertEquals(
                "minecraft:creaking_heart[axis=y,creaking_heart_state=awake,natural=true]",
                newer.blockStateName(mappings.blockState(heart)));
        assertEquals(
                "minecraft:iron_chain",
                newer.registry("minecraft:item")
                        .name(mappings.item(old.registry("minecraft:item").id("minecraft:chain"))));
        assertThrows(MalformedPacketException.class, () -> mappings.blockState(-1));
        assertThrows(MalformedPacketException.class, () -> mappings.item(Integer.MAX_VALUE));
    }
}
