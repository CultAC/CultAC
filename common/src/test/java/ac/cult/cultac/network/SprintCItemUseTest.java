package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.sprint.SprintC;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.events.packets.listeners.PacketPlayerDigging;
import ac.cult.cultac.events.packets.listeners.PacketSelfMetadataListener;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityEvent;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.Test;

/**
 * 1.21.4 LocalPlayer#shouldStopSprinting stops the sprint of a player using an item unless it rides or its eye was
 * under water (LocalPlayer#isUnderWater), and LocalPlayer#canStartSprinting starts none while it uses one. 26.3 keeps
 * the sprint. The use is the capture's offhand use_item while sprint-swimming, in a pool over the fixture's floor.
 */
class SprintCItemUseTest {
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD_SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final int CAMEL = 981;
    private static final float YAW = 30.0F;
    private static final float ITEM_USE = 0.2F;
    private static final float WATER_SPEED = 0.02F;
    private static final float GROUND_FRICTION = 0.6F * 0.91F;
    private static final float SPRINT_SPEED = 0.13F * (0.21600002F / (0.6F * 0.6F * 0.6F));
    private static final float WALK_SPEED = 0.1F * (0.21600002F / (0.6F * 0.6F * 0.6F));

    /** A sprint-swimmer whose eye is under water keeps sprinting through an offhand use. */
    @Test
    void aSwimmerUnderWaterKeepsSprintingWhileUsingAnItem() throws Exception {
        try (var f = pool(ClientVersion.V_1_21_4, 3, "minecraft:shield")) {
            startSprinting(f);
            swim(f, 6, tick -> {
                if (tick == 2) useOffhand(f);
            });
            assertEquals(0.0, sprintC(f).violations, 0.0);
        }
    }

    /** A player under water that starts sprinting while it uses an item. */
    @Test
    void aSprintStartedUnderWaterWhileUsingAnItemFlags() throws Exception {
        try (var f = pool(ClientVersion.V_1_21_4, 3, "minecraft:shield")) {
            f.input(FORWARD);
            swim(f, 10, tick -> {
                if (tick == 2) useOffhand(f);
                if (tick == 5) {
                    f.input(FORWARD_SPRINT);
                    command(f, PlayerCommandAction.START_SPRINTING);
                }
            });
            assertTrue(sprintC(f).violations > 0, "SprintC violations " + sprintC(f).violations);
        }
    }

    /**
     * ClientPacketListener#handleSetEntityData sets the sprint flag of a player using an item under water; nothing in
     * aiStep clears it, and LocalPlayer#sendIsSprintingIfNeeded sends the START_SPRINTING.
     */
    @Test
    void aServerSprintFlagUnderWaterWhileUsingAnItemStartsTheSprint() throws Exception {
        try (var f = pool(ClientVersion.V_1_21_4, 3, "minecraft:shield")) {
            f.input(FORWARD);
            swim(f, 10, tick -> {
                if (tick == 2) useOffhand(f);
                if (tick == 5) {
                    sharedFlags(f, (byte) 0x08);
                    f.input(FORWARD_SPRINT);
                    command(f, PlayerCommandAction.START_SPRINTING);
                }
            });
            assertEquals(0.0, sprintC(f).violations, 0.0);
        }
    }

    /** A wader whose eye is above the water stops sprinting when it uses an item. */
    @Test
    void aWaderWithItsEyeAboveTheWaterSprintingWhileUsingAnItemFlags() throws Exception {
        try (var f = pool(ClientVersion.V_1_21_4, 1, "minecraft:shield")) {
            startSprinting(f);
            swim(f, 6, tick -> {
                if (tick == 2) useOffhand(f);
            });
            assertTrue(sprintC(f).violations > 0, "SprintC violations " + sprintC(f).violations);
        }
    }

    /** A player on dry ground stops sprinting when it uses an item. */
    @Test
    void sprintingOnTheGroundWhileUsingAnItemFlags() throws Exception {
        try (var f = pool(ClientVersion.V_1_21_4, 0, "minecraft:shield")) {
            startSprinting(f);
            walk(f, 6, tick -> {
                if (tick == 2) useOffhand(f);
            });
            assertTrue(sprintC(f).violations > 0, "SprintC violations " + sprintC(f).violations);
        }
    }

    /** On dry ground the shield's use tick sends STOP_SPRINTING; the tick that releases it starts the sprint again. */
    @Test
    void aShieldOnTheGroundStopsTheSprintUntilItsRelease() throws Exception {
        try (var f = pool(ClientVersion.V_1_21_4, 0, "minecraft:shield")) {
            startSprinting(f);
            walk(f, 14, tick -> {
                if (tick == 2) {
                    useOffhand(f);
                    command(f, PlayerCommandAction.STOP_SPRINTING);
                }
                if (tick == 8) {
                    release(f);
                    command(f, PlayerCommandAction.START_SPRINTING);
                }
            });
            assertEquals(0.0, sprintC(f).violations, 0.0);
        }
    }

    /** On dry ground eating stops the sprint; the tick LivingEntity#completeUsingItem ends the use starts it again. */
    @Test
    void eatingOnTheGroundStopsTheSprintUntilTheFoodIsEaten() throws Exception {
        try (var f = pool(ClientVersion.V_1_21_4, 0, "minecraft:cooked_beef")) {
            f.player.food = 10;
            startSprinting(f);
            walk(f, 14, tick -> {
                if (tick == 2) {
                    useOffhand(f);
                    command(f, PlayerCommandAction.STOP_SPRINTING);
                }
                if (tick == 8) {
                    complete(f);
                    command(f, PlayerCommandAction.START_SPRINTING);
                }
            });
            assertEquals(0.0, sprintC(f).violations, 0.0);
        }
    }

    /** 26.3 LocalPlayer#shouldStopRunSprinting keeps the sprint of a player using an item. */
    @Test
    void a26_3ClientKeepsSprintingOnTheGroundWhileUsingAnItem() throws Exception {
        try (var f = pool(ClientVersion.V_26_3, 0, "minecraft:shield")) {
            startSprinting(f);
            walk(f, 6, tick -> {
                if (tick == 2) useOffhand(f);
            });
            assertEquals(0.0, sprintC(f).violations, 0.0);
        }
    }

    /** A camel rider keeps the sprint it rode with through an offhand use. */
    @Test
    void aCamelRiderKeepsItsSprintWhileUsingAnItem() throws Exception {
        try (var f = pool(ClientVersion.V_1_21_4, 0, "minecraft:shield")) {
            Vec3 camelAt = mountCamel(f);
            startSprinting(f);
            for (int tick = 0; tick < 6; tick++) {
                if (tick == 2) useOffhand(f);
                PredictionResult ride = f.rideTick(camelAt, true);
                assertTrue(exact(ride), "ride tick " + tick + ": " + describe(ride));
            }
            assertEquals(0.0, sprintC(f).violations, 0.0);
        }
    }

    /** A camel rider that starts sprinting while it uses an item. */
    @Test
    void aCamelRiderStartingASprintWhileUsingAnItemFlags() throws Exception {
        try (var f = pool(ClientVersion.V_1_21_4, 0, "minecraft:shield")) {
            Vec3 camelAt = mountCamel(f);
            for (int tick = 0; tick < 10; tick++) {
                if (tick == 2) useOffhand(f);
                if (tick == 5) command(f, PlayerCommandAction.START_SPRINTING);
                f.rideTick(camelAt, true);
            }
            assertTrue(sprintC(f).violations > 0, "SprintC violations " + sprintC(f).violations);
        }
    }

    /** LivingEntity#travelInWater from rest at the floor, slowed by 0.9 while sprinting and 0.8 otherwise. */
    private static void swim(JavaTickFixture f, int ticks, IntConsumer before) {
        Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
        Vec3 velocity = Vec3.ZERO;
        for (int tick = 0; tick < ticks; tick++) {
            before.accept(tick);
            float slowdown = f.player.isClientUsingItem() ? ITEM_USE : 1.0F;
            Vec3 delta = velocity.add(ClientMoveInput.of(f, FORWARD, slowdown, WATER_SPEED, YAW));
            position = position.add(delta.x, 0.0, delta.z);
            PredictionResult result = f.tick(position, true);
            assertTrue(exact(result), "tick " + tick + ": " + describe(result));
            float slowDown = f.player.isSprinting ? 0.9F : 0.8F;
            velocity = new Vec3(delta.x * slowDown, 0.0, delta.z * slowDown);
        }
    }

    /** LivingEntity#getFrictionInfluencedSpeed on stone from rest, at the sprinting or walking speed. */
    private static void walk(JavaTickFixture f, int ticks, IntConsumer before) {
        Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
        Vec3 velocity = Vec3.ZERO;
        for (int tick = 0; tick < ticks; tick++) {
            before.accept(tick);
            float slowdown = f.player.isClientUsingItem() ? ITEM_USE : 1.0F;
            float speed = f.player.isSprinting ? SPRINT_SPEED : WALK_SPEED;
            Vec3 delta = velocity.add(ClientMoveInput.of(f, FORWARD, slowdown, speed, YAW));
            position = position.add(delta.x, 0.0, delta.z);
            PredictionResult result = f.tick(position, true);
            assertTrue(exact(result), "tick " + tick + ": " + describe(result));
            velocity = new Vec3(delta.x * GROUND_FRICTION, 0.0, delta.z * GROUND_FRICTION);
        }
    }

    /** A client in water {@code depth} blocks deep over the floor, holding {@code offhand}, its keys forward. */
    private static JavaTickFixture pool(ClientVersion client, int depth, String offhand) throws Exception {
        var f = new JavaTickFixture(ProtocolVersion.V26_3, client);
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                for (int y = FLOOR_Y; y < FLOOR_Y + depth; y++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), BlockIds.WATER.defaultState());
                }
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.player.getInventory()
                .inventory
                .getInventoryStorage()
                .setItem(Inventory.SLOT_OFFHAND, OfflineCultTestBootstrap.item(offhand));
        f.player.xRot = YAW;
        f.player.yRot = 20.0F;
        f.place(new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO);
        f.input(FORWARD);
        return f;
    }

    /** Seats the player on a saddled camel standing on the floor; the camel's position. */
    private static Vec3 mountCamel(JavaTickFixture f) {
        Vec3 camelAt = new Vec3(0.5, FLOOR_Y, 3.5);
        f.place(camelAt.add(0, 1, 0), Vec3.ZERO);
        f.player.compensatedEntities.addEntity(CAMEL, EntityTypeIds.CAMEL, camelAt, 0, 0, 0);
        ((PacketEntityHorse) f.player.compensatedEntities.getEntity(CAMEL)).hasSaddle = true;
        f.setPassengers(CAMEL, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
        return camelAt;
    }

    private static void startSprinting(JavaTickFixture f) {
        f.input(FORWARD_SPRINT);
        command(f, PlayerCommandAction.START_SPRINTING);
    }

    private static void command(JavaTickFixture f, PlayerCommandAction action) {
        var packet = new ServerboundPlayerCommand(f.player.entityID, action, 0);
        new PacketEntityAction().onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, packet), f.player, packet);
    }

    private static int sequence = 82;

    private static void useOffhand(JavaTickFixture f) {
        ItemUseTestSupport.useOffhand(f.player, sequence++);
        assertTrue(f.player.isClientUsingItem(), "the client is not using the offhand item");
    }

    private static void release(JavaTickFixture f) {
        var release =
                new ServerboundPlayerAction(PlayerAction.RELEASE_USE_ITEM, new BlockPos(0, 0, 0), Direction.DOWN, 0);
        var event = f.event(ServerboundPackets.PLAYER_ACTION, release);
        new PacketPlayerDigging().onPlayerAction(event, f.player, release);
        f.player.actionManager.onPlayerAction(event, f.player, release);
        assertFalse(f.player.isClientUsingItem(), "still using after the release");
    }

    /** Entity event 9, LivingEntity#completeUsingItem's echo, applied by its trailing ping. */
    private static void complete(JavaTickFixture f) {
        var packet = new ClientboundEntityEvent(f.player.entityID, (byte) 9);
        var event = ItemUseTestSupport.sendEvent(f.player, ClientboundPackets.ENTITY_EVENT, packet);
        f.player.packetEntityReplication.onEntityEvent(event, f.player, packet);
        var ping = (ClientboundPing) event.writesAfter().getLast().packet();
        event.tasksAfter().forEach(Runnable::run);
        assertTrue(f.player.addTransactionResponse(ping.id()));
        assertFalse(f.player.isClientUsingItem(), "still using after the completion");
    }

    /** Self entity data carrying the shared flags, applied by its trailing proof between client ticks. */
    private static void sharedFlags(JavaTickFixture f, byte flags) {
        var packet = new EntityMetadata(
                f.player.entityID,
                List.of(new EntityMetadata.Entry(
                        WatchableIndexUtil.ENTITY_SHARED_FLAGS, flags, ByteBuffer.allocate(0))));
        var event = new PacketSendEvent<>(f.player.user, ConnectionPhase.PLAY, metadataType(), packet, false);
        new PacketSelfMetadataListener().onSetEntityData(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    @SuppressWarnings("unchecked")
    private static PacketType<EntityMetadata> metadataType() {
        return (PacketType<EntityMetadata>) TestProtocolRuntime.catalog().stream()
                .filter(type -> type.recordClass() == EntityMetadata.class)
                .findFirst()
                .orElseThrow();
    }

    private static SprintC sprintC(JavaTickFixture f) {
        return f.player.checkManager.getCheck(SprintC.class);
    }
}
