package ac.grim.grimac.bedrock.replay.offline;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.ShulkerData;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.latency.CompensatedWorld;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

final class OfflineBedrockReplayEvents {
    static final double DEFAULT_SPEED_MULTIPLIER = 20.0D;

    private OfflineBedrockReplayEvents() {
    }

    static ReplayScript load(OfflineBedrockReplayScenario scenario) throws IOException {
        List<TimedEvent> events = new ArrayList<>();
        Path eventFile = scenario.root().resolve("offline-events.ndjson");
        if (Files.isRegularFile(eventFile)) {
            try (BufferedReader reader = Files.newBufferedReader(eventFile, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        events.add(new TimedEvent(JsonParser.parseString(line).getAsJsonObject()));
                    }
                }
            }
        }
        JsonObject replay = scenario.manifest().getAsJsonObject("replay");
        JsonArray manifestEvents = replay == null ? null : replay.getAsJsonArray("offlineEvents");
        if (manifestEvents != null) {
            for (JsonElement element : manifestEvents) {
                events.add(new TimedEvent(element.getAsJsonObject()));
            }
        }
        events.sort(Comparator
                .comparingLong(TimedEvent::tick)
                .thenComparingLong(TimedEvent::sequence));
        return new ReplayScript(List.copyOf(events));
    }

    static long scaledDelayNanos(long captureDelayNanos) {
        if (captureDelayNanos <= 0L) {
            return 0L;
        }
        return Math.max(0L, Math.round(captureDelayNanos / DEFAULT_SPEED_MULTIPLIER));
    }

    static void apply(GrimPlayer player, JsonObject event) throws IOException {
        String type = string(event, "type", "").toLowerCase();
        switch (type) {
            case "velocity", "entity_velocity" -> applyVelocity(player, event);
            case "explosion" -> applyExplosion(player, event);
            case "set_block", "block" -> applySetBlock(player, event);
            case "piston", "block_event" -> applyPiston(player, event);
            case "add_entity", "entity" -> applyAddEntity(player, event);
            case "move_entity", "entity_move" -> applyMoveEntity(player, event);
            case "mount", "passengers" -> applyMount(player, event);
            case "shulker_block", "shulker_box" -> applyShulkerBlock(player, event);
            case "bounding_box_acknowledged", "bounding_box_ack" ->
                    player.bedrockState.applyAcknowledgedBoundingBoxMetadata(
                    (float) doubleValue(event, "width", 0.6D),
                    (float) doubleValue(event, "height", 1.8D));
            default -> throw new IOException("unsupported offline replay event type: " + type);
        }
    }


    private static void applyVelocity(GrimPlayer player, JsonObject event) {
        Vec3 velocity = vector(event, "velocity", "vector", "motion");
        int entityId = intValue(event, "entityId", player.entityID);
        PacketEntity entity = player.compensatedEntities.getEntity(entityId);
        if (entity != null && entity != player.compensatedEntities.getSelf()) {
            entity.deltaMovement = velocity;
            if (player.compensatedEntities.vehicles.applyClientboundVehicleVelocity(entity, velocity)) {
                return;
            }
        }
        int transaction = player.checkManager.getKnockbackHandler()
                .handleDebugPseudoEvent(velocity, true, entityId);
        confirmTransaction(player, transaction);
    }

    private static void applyExplosion(GrimPlayer player, JsonObject event) {
        Vec3 knockback = vector(event, "knockback", "velocity", "vector");
        int transaction = player.checkManager.getExplosionHandler()
                .handleDebugPseudoEvent(knockback, false, -1);
        confirmTransaction(player, transaction);
    }

    private static void applySetBlock(GrimPlayer player, JsonObject event) throws IOException {
        BlockPos pos = blockPos(event);
        BlockState state = blockState(event);
        applyBlockNow(player.compensatedWorld, pos, state);
        player.compensatedWorld.updateBlock(pos.getX(), pos.getY(), pos.getZ(), state);
    }

    private static void applyPiston(GrimPlayer player, JsonObject event) throws IOException {
        BlockPos pos = blockPos(event);
        BlockState state = blockState(event);
        applyBlockNow(player.compensatedWorld, pos, state);
        int transaction = nextTransaction(player, event);
        int triggerType = intValue(event, "triggerType", intValue(event, "action", 0));
        int triggerData = event.has("triggerData")
                ? event.get("triggerData").getAsInt()
                : direction(event, "direction", state).get3DDataValue();
        player.compensatedWorld.pistons.handleBlockEvent(pos, state.getBlock(), triggerType, triggerData, transaction);
        confirmTransaction(player, transaction);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void applyAddEntity(GrimPlayer player, JsonObject event) throws IOException {
        int entityId = intValue(event, "entityId", intValue(event, "id", -1));
        if (entityId < 0) {
            throw new IOException("add_entity requires entityId");
        }
        EntityType entityType = (EntityType) BuiltInRegistries.ENTITY_TYPE.getValue(
                Identifier.parse(string(event, "entityType", string(event, "entity", "minecraft:pig"))));
        Vec3 position = vector(event, "position", "pos", "location");
        float yaw = (float) doubleValue(event, "yaw", 0.0D);
        float pitch = (float) doubleValue(event, "pitch", 0.0D);
        int data = intValue(event, "data", 0);
        player.compensatedEntities.addEntity(entityId, entityType, position, pitch, yaw, data);
        PacketEntity entity = player.compensatedEntities.getEntity(entityId);
        if (entity != null) {
            entity.onGround = booleanValue(event, "onGround", entity.onGround);
        }
    }

    private static void applyMoveEntity(GrimPlayer player, JsonObject event) throws IOException {
        int entityId = intValue(event, "entityId", intValue(event, "id", -1));
        PacketEntity entity = player.compensatedEntities.getEntity(entityId);
        if (entity == null) {
            throw new IOException("unknown entity for move_entity: " + entityId);
        }
        Vec3 position = vector(event, "position", "pos", "location");
        float yaw = (float) doubleValue(event, "yaw", entity.clientPhysicalYaw);
        float pitch = (float) doubleValue(event, "pitch", entity.clientPhysicalPitch);
        entity.setPositionRaw(
                ac.grim.grimac.utils.nmsutil.GetBoundingBox.getPacketEntityBoundingBox(
                        player, position.x, position.y, position.z, entity),
                yaw,
                pitch);
        entity.deltaMovement = vectorOr(event, Vec3.ZERO, "velocity", "delta", "motion");
        entity.onGround = booleanValue(event, "onGround", entity.onGround);
    }

    private static void applyMount(GrimPlayer player, JsonObject event) throws IOException {
        int vehicleId = intValue(event, "vehicleId", -1);
        PacketEntity vehicle = player.compensatedEntities.getEntity(vehicleId);
        if (vehicle == null) {
            throw new IOException("unknown vehicle for mount: " + vehicleId);
        }
        JsonArray passengers = event.getAsJsonArray("passengerIds");
        if (passengers == null) {
            passengers = event.getAsJsonArray("passengers");
        }
        if (passengers == null) {
            player.compensatedEntities.getSelf().mount(vehicle);
            player.compensatedEntities.vehicles.serverPlayerVehicle = vehicleId;
            return;
        }
        for (JsonElement passengerId : passengers) {
            int id = passengerId.getAsInt();
            PacketEntity passenger = id == player.entityID || id == 0
                    ? player.compensatedEntities.getSelf()
                    : player.compensatedEntities.getEntity(id);
            if (passenger != null) {
                passenger.mount(vehicle);
                if (passenger == player.compensatedEntities.getSelf()) {
                    player.compensatedEntities.vehicles.serverPlayerVehicle = vehicleId;
                }
            }
        }
    }

    private static void applyShulkerBlock(GrimPlayer player, JsonObject event) {
        BlockPos pos = blockPos(event);
        boolean open = booleanValue(event, "open", true);
        ShulkerData data = new ShulkerData(pos, player.lastTransactionSent.get(), !open);
        player.compensatedWorld.openShulkerBoxes.remove(data);
        player.compensatedWorld.openShulkerBoxes.add(data);
    }

    private static void applyBlockNow(CompensatedWorld world, BlockPos pos, BlockState state) {
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        long chunkKey = CompensatedWorld.chunkPositionToLong(chunkX, chunkZ);
        CompensatedWorld.CachedChunk chunk = world.chunks.get(chunkKey);
        if (chunk == null) {
            chunk = new CompensatedWorld.CachedChunk(new CompensatedWorld.CachedSection[16], 0);
            world.chunks.put(chunkKey, chunk);
        }
        int sectionIndex = pos.getY() >> 4;
        if (sectionIndex < 0 || sectionIndex >= chunk.sectionCount()) {
            return;
        }
        CompensatedWorld.CachedSection section = chunk.getOrCreateSection(sectionIndex);
        if (section != null) {
            section.setState(CompensatedWorld.CachedChunk.index(pos.getX() & 0xF, pos.getY() & 0xF, pos.getZ() & 0xF), state);
        }
    }

    private static void confirmTransaction(GrimPlayer player, int transaction) {
        if (transaction <= 0) {
            return;
        }
        player.lastTransactionSent.set(Math.max(player.lastTransactionSent.get(), transaction));
        player.lastTransactionReceived.set(Math.max(player.lastTransactionReceived.get(), transaction));
        player.latencyUtils.handleNettySyncTransaction(transaction);
    }

    private static int nextTransaction(GrimPlayer player, JsonObject event) {
        int explicit = intValue(event, "transaction", -1);
        if (explicit > 0) {
            return explicit;
        }
        return player.lastTransactionSent.get() + 1;
    }

    private static BlockState blockState(JsonObject event) throws IOException {
        return OfflineBlockStateParser.parse(string(event, "block", string(event, "state", "minecraft:air")));
    }

    private static BlockPos blockPos(JsonObject event) {
        JsonObject pos = event.getAsJsonObject("position");
        if (pos == null) {
            pos = event.getAsJsonObject("pos");
        }
        return new BlockPos(
                intValue(pos, "x", intValue(event, "x", 0)),
                intValue(pos, "y", intValue(event, "y", 0)),
                intValue(pos, "z", intValue(event, "z", 0)));
    }

    private static Direction direction(JsonObject event, String key, BlockState state) {
        String name = string(event, key, "");
        if (!name.isBlank()) {
            return Direction.valueOf(name.toUpperCase());
        }
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)) {
            return state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING);
        }
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)) {
            return state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING);
        }
        return Direction.NORTH;
    }

    private static Vec3 vector(JsonObject event, String... keys) {
        Vec3 value = vectorOr(event, null, keys);
        if (value == null) {
            return Vec3.ZERO;
        }
        return value;
    }

    private static Vec3 vectorOr(JsonObject event, Vec3 fallback, String... keys) {
        JsonObject object = null;
        for (String key : keys) {
            object = event.getAsJsonObject(key);
            if (object != null) {
                break;
            }
        }
        if (object == null) {
            return fallback;
        }
        return new Vec3(
                doubleValue(object, "x", 0.0D),
                doubleValue(object, "y", 0.0D),
                doubleValue(object, "z", 0.0D));
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsInt() : fallback;
    }

    private static long longValue(JsonObject object, String key, long fallback) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsLong() : fallback;
    }

    private static boolean booleanValue(JsonObject object, String key, boolean fallback) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsBoolean() : fallback;
    }

    private static double doubleValue(JsonObject object, String key, double fallback) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsDouble() : fallback;
    }

    record ReplayScript(List<TimedEvent> events) {
        ReplayScript {
            events = events == null ? List.of() : events;
        }

        Cursor cursor() {
            return new Cursor(events);
        }
    }

    record TimedEvent(JsonObject event) {
        long tick() {
            return intValue(event, "tick", Integer.MIN_VALUE);
        }

        long sequence() {
            return intValue(event, "sequence", 0);
        }
    }

    static final class Cursor {
        private final List<TimedEvent> events;
        private int index;

        private Cursor(List<TimedEvent> events) {
            this.events = events;
        }

        void applyBeforeOrAt(GrimPlayer player, long authInputTick) throws IOException {
            while (index < events.size() && events.get(index).tick() <= authInputTick) {
                apply(player, events.get(index).event());
                index++;
            }
        }

        void applyRemaining(GrimPlayer player) throws IOException {
            while (index < events.size()) {
                apply(player, events.get(index).event());
                index++;
            }
        }
    }
}
