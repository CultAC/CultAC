package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.state.BedrockBoatProperties;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashSet;
import java.util.Set;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataMap;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;

/** Captures boat metadata for publication at an acknowledged packet boundary. */
record GeyserBoatMetadata(Float width, Float height, Boolean buoyant, Boolean outOfControl, Boolean leashed,
        Boolean gravity, Float baseBuoyancy, Set<String> liquids, Vec3d seat) {
    static GeyserBoatMetadata capture(EntityDataMap data) {
        String json = data.get(EntityDataTypes.BUOYANCY_DATA);
        Boolean gravity = null;
        Float base = null;
        Set<String> liquids = null;
        if (json != null) {
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            // Both keys are sent for clients on either side of the metadata format change.
            object.addProperty("movement_type", "none");
            object.addProperty("simulate_waves", false);
            data.put(EntityDataTypes.BUOYANCY_DATA, object.toString());
            gravity = !object.has("apply_gravity") || object.get("apply_gravity").getAsBoolean();
            base = object.has("base_buoyancy") ? object.get("base_buoyancy").getAsFloat() : 1.0F;
            liquids = new HashSet<>();
            if (object.has("liquid_blocks")) {
                for (var liquid : object.getAsJsonArray("liquid_blocks")) liquids.add(liquid.getAsString());
            }
            liquids = Set.copyOf(liquids);
        }
        var offset = data.get(EntityDataTypes.SEAT_OFFSET);
        return new GeyserBoatMetadata(data.get(EntityDataTypes.WIDTH), data.get(EntityDataTypes.HEIGHT),
                data.get(EntityDataTypes.IS_BUOYANT), data.get(EntityDataTypes.FLAGS) == null ? null
                        : data.getFlag(EntityFlag.OUT_OF_CONTROL), data.get(EntityDataTypes.FLAGS) == null ? null
                        : data.getFlag(EntityFlag.LEASHED), gravity, base, liquids,
                offset == null ? null : new Vec3d(offset.getX(), offset.getY(), offset.getZ()));
    }

    ac.cult.cultac.bedrock.prediction.integration.BedrockReplayBoatMetadata replayable() {
        return new ac.cult.cultac.bedrock.prediction.integration.BedrockReplayBoatMetadata(
                width, height, null, outOfControl, leashed, null, null, null, seat);
    }

    ac.cult.cultac.bedrock.prediction.integration.BedrockReplayBoatMetadata immediate() {
        return new ac.cult.cultac.bedrock.prediction.integration.BedrockReplayBoatMetadata(
                null, null, buoyant, null, null, gravity, baseBuoyancy, liquids, null);
    }

    boolean isEmpty() {
        return width == null && height == null && buoyant == null && outOfControl == null && leashed == null
                && gravity == null && baseBuoyancy == null && liquids == null && seat == null;
    }

    BedrockBoatProperties apply(BedrockBoatProperties previous, long runtimeId) {
        var old = previous == null ? BedrockBoatProperties.initial(runtimeId) : previous;
        return new BedrockBoatProperties(runtimeId, new PlayerDimensionsState(
                width == null ? old.dimensions().width() : width,
                height == null ? old.dimensions().height() : height),
                buoyant == null ? old.buoyant() : buoyant, gravity == null ? old.gravity() : gravity,
                baseBuoyancy == null ? old.baseBuoyancy() : baseBuoyancy,
                liquids == null ? old.liquids() : liquids,
                outOfControl == null ? old.outOfControl() : outOfControl,
                leashed == null ? old.leashed() : leashed, seat == null ? old.seat() : seat);
    }
}
