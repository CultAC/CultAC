package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.movement.timer.AbstractTimerCheck;
import ac.cult.cultac.checks.impl.movement.timer.DumbTimer;
import ac.cult.cultac.checks.impl.movement.timer.TimerCheck;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.verbose.VerboseCodecs;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.events.packets.listeners.PacketPlayerDigging;
import ac.cult.cultac.events.packets.listeners.PacketPlayerSteer;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.manager.config.BaseConfigManager;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityMotion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundExplode;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveVehicle;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerInfoUpdate;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerPosition;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPacket;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAbilities;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSetCarriedItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.protocol.value.Relative;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntityPlayer;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.mockito.Mockito;

/** A 26.3 survival client over a stone floor at {@link #FLOOR_Y}, fed packets through the receive listeners. */
final class JavaTickFixture implements AutoCloseable {
    static final int FLOOR_Y = 64;

    final RecordConsumerServices services;
    final RecordReceiveFixture fixture;
    final CultPlayer player;
    final CheckManagerListener listener = new CheckManagerListener();
    private final PacketPlayerSteer steer = new PacketPlayerSteer();
    private final PacketPlayerDigging digging = new PacketPlayerDigging();
    private int teleportId;
    private int useSequence;

    JavaTickFixture() throws Exception {
        this(ProtocolVersion.V26_3, ClientVersion.V_26_3);
    }

    JavaTickFixture(ProtocolVersion protocol, ClientVersion client) throws Exception {
        Class.forName(VerboseCodecs.class.getName());
        services = new RecordConsumerServices(protocol);
        fixture = new RecordReceiveFixture(protocol);
        player = fixture.player;
        fixture.user.bind(Mockito.mock(PlatformPlayer.class));
        setField(CultPlayer.class, player, "resolvedClientVersion", client);
        player.gamemode = GameMode.SURVIVAL;
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) player.compensatedWorld.ensureValidationChunkLoaded(x, z);
        }
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y - 1, z), BlockIds.STONE.defaultState());
            }
        }
        // Let the block-change timing uncertainty expire at client tick boundaries.
        for (int tick = 0; tick < 3; tick++) player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        player.getSetbackTeleportUtil().hasFullyLoaded = true;
        resetTimerBalances();
    }

    /** Gives the timer checks ten seconds of client ticks ahead of the wall clock. */
    void resetTimerBalances() throws Exception {
        // These synchronous fixtures exercise packet ordering, not wall-clock pacing.
        setField(
                AbstractTimerCheck.class,
                player.checkManager.getCheck(TimerCheck.class),
                "timerBalanceRealTime",
                System.nanoTime() - 10_000_000_000L);
        DumbTimer dumbTimer = player.checkManager.getListener(DumbTimer.class);
        setField(DumbTimer.class, dumbTimer, "balance", (double) System.nanoTime() - 10_000_000_000L);
        setField(DumbTimer.class, dumbTimer, "limitAbuseOverPing", -1L);
    }

    void place(Vec3 position, Vec3 velocity) {
        player.x = player.lastX = position.x;
        player.y = player.lastY = position.y;
        player.z = player.lastZ = position.z;
        player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, position.x, position.y, position.z);
        boolean onGround = position.y == FLOOR_Y;
        player.onGround = onGround;
        player.lastOnGround = onGround;
        player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.fromBoolean(onGround));
        player.checkManager.getSimulationProcessor().seedStartingVelocity(velocity);
    }

    /** Adds a remote player whose PlayerInfo gives it {@code mode}. */
    void addRemotePlayer(int id, Vec3 at, GameMode mode) {
        UUID profile = UUID.randomUUID();
        var info = new ClientboundPlayerInfoUpdate(
                EnumSet.of(
                        ClientboundPlayerInfoUpdate.Action.ADD_PLAYER,
                        ClientboundPlayerInfoUpdate.Action.UPDATE_GAME_MODE),
                List.of(new ClientboundPlayerInfoUpdate.Entry(profile, mode, Map.of())));
        CompensatedEntities entities = player.compensatedEntities;
        entities.clientPlayerModes.update(info, Set.of(profile), entities.entityMap.values());
        entities.addEntity(id, EntityTypeIds.PLAYER, at, 0, 0, 0);
        ((PacketEntityPlayer) entities.getEntity(id)).profile = profile;
    }

    void input(ServerboundPlayerInput input) {
        var event = event(ServerboundPackets.PLAYER_INPUT, input);
        steer.onPlayerInput(event, player, input);
        listener.onPlayerInput(event, player, input);
    }

    /** One client tick that sends a position: the move and the tick end; the prediction it committed. */
    PredictionResult tick(Vec3 to, boolean onGround) {
        PredictionResult before = player.checkManager.getSimulationProcessor().getLastPrediction();
        move(to, onGround);
        tickEnd();
        PredictionResult result = player.checkManager.getSimulationProcessor().getLastPrediction();
        assertNotNull(result);
        assertTrue(result != before, "the tick committed no prediction");
        return result;
    }

    PacketReceiveEvent<ServerboundMovePlayer> move(Vec3 to, boolean onGround) {
        var packet = new ServerboundMovePlayer(to.x, to.y, to.z, player.xRot, player.yRot, onGround, false, true, true);
        var event = event(ServerboundPackets.MOVE_PLAYER, packet);
        listener.onMovePlayer(event, player, packet);
        return event;
    }

    /** One ridden client tick: the passenger Rot, the vehicle's move and the tick end; the committed prediction. */
    PredictionResult rideTick(Vec3 to, boolean onGround) {
        PredictionResult before = player.checkManager.getSimulationProcessor().getLastPrediction();
        var rot = new ServerboundMovePlayer(0, 0, 0, player.xRot, player.yRot, onGround, false, false, true);
        listener.onMovePlayer(event(ServerboundPackets.MOVE_PLAYER, rot), player, rot);
        moveVehicle(to, player.xRot, player.yRot, onGround);
        tickEnd();
        PredictionResult result = player.checkManager.getSimulationProcessor().getLastPrediction();
        assertNotNull(result);
        assertTrue(result != before, "the tick committed no prediction");
        return result;
    }

    /** A ServerboundMoveVehiclePacket for the root at {@code to}, whether or not the root accepts it. */
    void moveVehicle(Vec3 to, float yaw, float pitch, boolean onGround) {
        var move = new ServerboundMoveVehicle(new Vec3d(to.x, to.y, to.z), yaw, pitch, onGround, true);
        listener.onMoveVehicle(event(ServerboundPackets.MOVE_VEHICLE, move), player, move);
    }

    /** A passive passenger tick: LocalPlayer sends Rot even when the root is not locally controlled. */
    void rideTick() {
        var rot = new ServerboundMovePlayer(0, 0, 0, player.xRot, player.yRot, player.onGround, false, false, true);
        listener.onMovePlayer(event(ServerboundPackets.MOVE_PLAYER, rot), player, rot);
        tickEnd();
    }

    /** A client tick that sends StatusOnly: LocalPlayer#sendPosition withheld the position and rotation. */
    void statusTick(boolean onGround, boolean horizontalCollision) {
        var status = new ServerboundMovePlayer(
                0, 0, 0, player.xRot, player.yRot, onGround, horizontalCollision, false, false);
        listener.onMovePlayer(event(ServerboundPackets.MOVE_PLAYER, status), player, status);
        tickEnd();
    }

    void setPassengers(int vehicle, List<Integer> passengers) {
        var packet = new ClientboundSetPassengers(vehicle, passengers);
        var event = new PacketSendEvent<>(
                fixture.user, ConnectionPhase.PLAY, ClientboundPackets.SET_PASSENGERS, packet, false);
        player.packetEntityReplication.onSetPassengers(event, player, packet);
        event.tasksAfter().forEach(Runnable::run);
    }

    /**
     * ServerPlayer#startRiding as the server sends it: the player_position {@code id} to the rider's seat, the
     * set_passengers and the vehicle's move_vehicle. Returns the ping trailing the teleport.
     */
    int startRiding(int vehicle, int id, Vec3 seat, Vec3 vehicleAt, float yaw, float pitch) {
        var teleport = new ClientboundPlayerPosition(
                id, vec3d(seat), new Vec3d(0, 0, 0), 0, 0, Set.of(Relative.Y_ROT, Relative.X_ROT));
        var teleportEvent = new PacketSendEvent<>(
                fixture.user, ConnectionPhase.PLAY, ClientboundPackets.PLAYER_POSITION, teleport, false);
        new PacketServerTeleport().onPlayerPosition(teleportEvent, player, teleport);
        teleportEvent.getTasksAfterSend().forEach(Runnable::run);
        int trailing = player.lastTransactionSent.get();
        setPassengers(vehicle, List.of(player.entityID));
        serverMoveVehicle(vehicleAt, yaw, pitch);
        return trailing;
    }

    /** A ClientboundMoveVehicle placing the player's vehicle at {@code at}. */
    void serverMoveVehicle(Vec3 at, float yaw, float pitch) {
        var packet = new ClientboundMoveVehicle(vec3d(at), yaw, pitch);
        var event = new PacketSendEvent<>(
                fixture.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_VEHICLE, packet, false);
        new PacketServerTeleport().onMoveVehicle(event, player, packet);
        event.tasksAfter().forEach(Runnable::run);
    }

    /** The client's acceptance of the player_position {@code id}, as a pre-26.3 client sends it. */
    void acceptTeleport(int id) {
        var packet = new ServerboundAcceptTeleportation(id, null, 0, 0);
        new PacketServerTeleport()
                .onAcceptTeleportation(event(ServerboundPackets.ACCEPT_TELEPORTATION, packet), player, packet);
    }

    /** A set_entity_motion for the entity {@code id}. */
    void entityMotion(int id, Vec3 velocity) {
        var packet = new ClientboundEntityMotion(id, vec3d(velocity));
        var event = new PacketSendEvent<>(
                fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ENTITY_MOTION, packet, false);
        player.packetEntityReplication.onSetEntityMotion(event, player, packet);
        event.tasksAfter().forEach(Runnable::run);
    }

    /** Water in every block of the box from {@code min} to {@code max}, settled past the block-change timing. */
    void water(BlockPos min, BlockPos max) {
        for (int x = min.x(); x <= max.x(); x++) {
            for (int y = min.y(); y <= max.y(); y++) {
                for (int z = min.z(); z <= max.z(); z++) {
                    player.compensatedWorld.updateBlock(new BlockPos(x, y, z), BlockIds.WATER.defaultState());
                }
            }
        }
        for (int tick = 0; tick < 3; tick++) player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    void tickEnd() {
        listener.processClientTickEndReceive(
                event(ServerboundPackets.CLIENT_TICK_END, ServerboundPackets.CLIENT_TICK_END.opaqueValue()), player);
    }

    /** A set_entity_motion for the player and its trailing ping; the ping's transaction. */
    int velocity(Vec3 velocity) {
        var motion = new ClientboundEntityMotion(player.entityID, new Vec3d(velocity.x, velocity.y, velocity.z));
        var event = new PacketSendEvent<>(
                fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ENTITY_MOTION, motion, false);
        int proof = player.checkManager.getKnockbackHandler().handleEntityVelocity(event, motion);
        event.getTasksAfterSend().forEach(Runnable::run);
        return proof;
    }

    /** An explosion knocking the player by {@code knockback}, answered through its trailing ping. */
    void explosion(Vec3 knockback) {
        explode(knockback);
        pongThrough(player.lastTransactionSent.get());
    }

    /** An unanswered explosion knocking the player by {@code knockback}; its proof, or -1 when none was sent. */
    int explode(Vec3 knockback) {
        var packet = new ClientboundExplode(
                new Vec3d(player.x, player.y, player.z), new Vec3d(knockback.x, knockback.y, knockback.z));
        var event =
                new PacketSendEvent<>(fixture.user, ConnectionPhase.PLAY, ClientboundPackets.EXPLODE, packet, false);
        int sent = player.lastTransactionSent.get();
        player.checkManager.getExplosionHandler().onExplode(event, player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        return player.lastTransactionSent.get() > sent ? player.lastTransactionSent.get() : -1;
    }

    /** A teleport after a leading ping, answered by the client with a 26.3 acceptance at {@code target}. */
    void teleport(Vec3 target, RelativeFlag flags) {
        teleport(player.sendTrailingTransaction(), target, flags);
    }

    /** A teleport sent after the ping {@code transaction}, accepted once that ping is answered. */
    void teleport(int transaction, Vec3 target, RelativeFlag flags) {
        teleport(transaction, target, flags, 0, 0);
    }

    /** A teleport turning the client from {@code sourceYaw} to {@code finalYaw}. */
    void teleport(int transaction, Vec3 target, RelativeFlag flags, float sourceYaw, float finalYaw) {
        teleport(transaction, target, flags, sourceYaw, 0, finalYaw, 0);
    }

    /** A teleport turning the client from {@code sourceYaw}/{@code sourcePitch} to {@code finalYaw}/{@code finalPitch}. */
    void teleport(
            int transaction,
            Vec3 target,
            RelativeFlag flags,
            float sourceYaw,
            float sourcePitch,
            float finalYaw,
            float finalPitch) {
        teleport(transaction, target, Vec3.ZERO, flags, sourceYaw, sourcePitch, finalYaw, finalPitch);
    }

    /** A teleport whose packet delta movement is {@code delta}. */
    void teleport(
            int transaction,
            Vec3 target,
            Vec3 delta,
            RelativeFlag flags,
            float sourceYaw,
            float sourcePitch,
            float finalYaw,
            float finalPitch) {
        int id = ++teleportId;
        player.getSetbackTeleportUtil()
                .addSentTeleport(
                        target, delta, transaction, flags, false, id, sourceYaw, sourcePitch, finalYaw, finalPitch);
        pongThrough(transaction);
        accept(id, target, finalYaw, finalPitch);
    }

    /** A player_position at an absolute {@code target} and rotation, sent and answered by a 26.3 acceptance. */
    void playerPosition(Vec3 target, Vec3 delta, Set<Relative> relatives) {
        int id = ++teleportId;
        var packet = new ClientboundPlayerPosition(
                id, new Vec3d(target.x, target.y, target.z), new Vec3d(delta.x, delta.y, delta.z), 0, 0, relatives);
        var event = new PacketSendEvent<>(
                fixture.user, ConnectionPhase.PLAY, ClientboundPackets.PLAYER_POSITION, packet, false);
        new PacketServerTeleport().onPlayerPosition(event, player, packet);
        int leading = player.lastTransactionSent.get();
        event.getTasksAfterSend().forEach(Runnable::run);
        pongThrough(leading);
        accept(id, target, 0, 0);
    }

    private void accept(int id, Vec3 target, float yaw, float pitch) {
        var packet = new ServerboundAcceptTeleportation(id, new Vec3d(target.x, target.y, target.z), yaw, pitch);
        var event = event(ServerboundPackets.ACCEPT_TELEPORTATION, packet);
        new PacketServerTeleport().onAcceptTeleportation(event, player, packet);
        assertFalse(event.isCancelled(), "the teleport acceptance was rejected");
    }

    /** ServerboundUseItem through PacketPlayerDigging, then the check listeners, in the production route order. */
    void useItem(Hand hand, float yaw, float pitch) {
        var packet = new ServerboundUseItem(hand, ++useSequence, yaw, pitch);
        var event = event(ServerboundPackets.USE_ITEM, packet);
        digging.onUseItem(event, player, packet);
        listener.onUseItem(event, player, packet);
    }

    /** ServerboundSetCarriedItem through PacketPlayerDigging, then the check listeners. */
    void selectHotbarSlot(int slot) {
        var packet = new ServerboundSetCarriedItem(slot);
        var event = event(ServerboundPackets.SET_CARRIED_ITEM, packet);
        digging.onSetCarriedItem(event, player, packet);
        listener.onSetCarriedItem(event, player, packet);
    }

    /** MultiPlayerGameMode#releaseUsingItem's RELEASE_USE_ITEM through PacketPlayerDigging, then the check listeners. */
    void releaseUsingItem() {
        var packet =
                new ServerboundPlayerAction(PlayerAction.RELEASE_USE_ITEM, new BlockPos(0, 0, 0), Direction.DOWN, 0);
        var event = event(ServerboundPackets.PLAYER_ACTION, packet);
        digging.onPlayerAction(event, player, packet);
        listener.onPlayerAction(event, player, packet);
    }

    void abilities(boolean flying) {
        var packet = new ServerboundPlayerAbilities(flying);
        var event = event(ServerboundPackets.PLAYER_ABILITIES, packet);
        listener.onPlayerAbilities(event, player, packet);
    }

    /** The client answers every ping up to {@code transaction}. */
    void pongThrough(int transaction) {
        for (int id = player.lastTransactionReceived.get() + 1; id <= transaction; id++) {
            player.lastTransactionReceived.set(id);
            player.latencyUtils.handleNettySyncTransaction(id);
        }
    }

    <T extends ServerboundPacket> PacketReceiveEvent<T> event(PacketType<T> type, T packet) {
        return new PacketReceiveEvent<>(fixture.user, ConnectionPhase.PLAY, type, packet);
    }

    static boolean exact(PredictionResult result) {
        return result.getFlags().isEmpty() && !result.isExempt() && result.getOffset() < 1.0E-5;
    }

    static String describe(PredictionResult result) {
        StringBuilder text = new StringBuilder("offset ").append(result.getOffset());
        if (result.isExempt()) text.append(" exempt");
        for (PredictionResult.Flag flag : result.getFlags()) {
            text.append(' ').append(flag.getCheck().getCheckName());
        }
        return text.toString();
    }

    /** The result is judged, not exempted, and {@code check} flags it. */
    static void assertRejected(PredictionResult result, String check) {
        assertFalse(result.isExempt(), describe(result));
        assertTrue(
                result.getFlags().stream()
                        .anyMatch(flag -> flag.getCheck().getCheckName().equals(check)),
                describe(result));
    }

    /** VehicleOffset's own comparison: the reported move against the closest simulated one, input included. */
    static void assertVehicleExact(PredictionResult result, int tick) {
        double offset = result.getTarget().subtract(result.getAcceptedClosestToTarget()).length();
        StringBuilder report =
                new StringBuilder("tick " + tick + " vehicle offset " + offset + " ").append(describe(result));
        for (PredictionResult.Flag flag : result.getFlags()) {
            report.append(' ').append(flag.getVerbose().getString());
        }
        assertTrue(offset < 1.0E-5 && result.getFlags().isEmpty() && !result.isExempt(), report.toString());
    }

    /** Turns on observe-only mode until closed, so a flag sends no setback. */
    static AutoCloseable observeOnly() throws ReflectiveOperationException {
        BaseConfigManager manager = CultAPI.INSTANCE.getConfigManager();
        Field field = BaseConfigManager.class.getDeclaredField("observeOnly");
        field.setAccessible(true);
        boolean original = field.getBoolean(manager);
        field.setBoolean(manager, true);
        return () -> field.setBoolean(manager, original);
    }

    static Vec3d vec3d(Vec3 position) {
        return new Vec3d(position.x, position.y, position.z);
    }

    static void setField(Class<?> owner, Object value, String name, Object setting) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(value, setting);
    }

    @Override
    public void close() throws Exception {
        fixture.close();
        services.close();
    }
}
