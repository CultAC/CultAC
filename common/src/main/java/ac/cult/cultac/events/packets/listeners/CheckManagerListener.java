package ac.cult.cultac.events.packets.listeners;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.prediction.integration.BedrockVehicleControl;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsVehicle;
import ac.cult.cultac.checks.impl.crash.CrashC;
import ac.cult.cultac.checks.impl.movement.GhostBlockMitigator;
import ac.cult.cultac.checks.impl.movement.SetbackBlocker;
import ac.cult.cultac.checks.impl.movement.VehiclePredictionRunner;
import ac.cult.cultac.checks.impl.movement.timer.VehicleTimer;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.vehicle.VehicleGround;
import ac.cult.cultac.checks.type.LegacyPacketEventSemantics;
import ac.cult.cultac.events.packets.blockplace.PlaceHandler;
import ac.cult.cultac.manager.player.CheckManager;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.OpaqueReceiveListener;
import ac.cult.cultac.network.PacketHandlerScanner;
import ac.cult.cultac.network.PacketReceiveHandler;
import ac.cult.cultac.network.PacketRouteBuilder;
import ac.cult.cultac.network.PacketSendHandler;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.util.SpigotConversionUtil;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolRuntime;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundCustomPayload;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSwing;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;
import ac.cult.cultac.utils.anticheat.update.PositionUpdate;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.anticheat.update.RotationUpdate;
import ac.cult.cultac.utils.anticheat.update.VehiclePositionUpdate;
import ac.cult.cultac.utils.blockplace.ClientBlockActions;
import ac.cult.cultac.utils.blockplace.GhostBlock;
import ac.cult.cultac.utils.data.BedrockTranslatedMovementGate;
import ac.cult.cultac.utils.data.HeadRotation;
import ac.cult.cultac.utils.data.PacketStateData;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.data.TeleportAcceptData;
import ac.cult.cultac.utils.data.VehicleTeleportData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityRideable;
import ac.cult.cultac.utils.inventory.InventoryClick;
import ac.cult.cultac.utils.inventory.ItemTypes;
import ac.cult.cultac.utils.latency.CompensatedVehicleState;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.VectorUtils;
import ac.cult.cultac.utils.nmsutil.BlockBreakSpeed;
import ac.cult.cultac.utils.nmsutil.FlightToggle;
import ac.cult.cultac.utils.nmsutil.TraverseBlocks;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Takes every serverbound play packet through CheckManager in packet order: the pre-prediction checks, the packet's
 * effect on the modelled client, then the receive checks. A family with no effect of its own takes the generic route.
 */
public class CheckManagerListener implements OpaqueReceiveListener {
    private static final PacketReceiveHandler<Object> GENERIC_RECEIVE =
            (event, player, packet) -> receiveGeneric(event, player);
    private static final PacketReceiveHandler<Object> EARLY_RECEIVE_FORWARDER = new CheckManagerEarlyReceiveForwarder();
    private static final PacketSendHandler<Object> SEND_FORWARDER = new CheckManagerSendForwarder();

    /** Every serverbound play family the runtime decodes. Each one reaches CheckManager. */
    public static List<PacketType<?>> receiveDispatchPacketTypes(PacketHandlerScanner scanner) {
        ProtocolRuntime runtime = scanner.runtime();
        List<PacketType<?>> types = new ArrayList<>();
        for (PacketType<?> type : runtime.catalog()) {
            if (type.direction() == PacketDirection.SERVERBOUND
                    && type.phases().contains(ConnectionPhase.PLAY)
                    && runtime.supports(type)) {
                types.add(type);
            }
        }
        return List.copyOf(types);
    }

    @Override
    public void registerOpaqueReceivePackets(PacketRouteBuilder registrar, PacketListenerPriority priority) {
        List<PacketType<?>> dedicated = registrar.packetTypes(CheckManagerListener.class, PacketDirection.SERVERBOUND);
        for (PacketType<?> type : receiveDispatchPacketTypes(registrar.scanner())) {
            if (!dedicated.contains(type)) {
                registrar.receiveRoute(type, priority, GENERIC_RECEIVE, PacketRouteBuilder.ReceiveStage.ORDINARY);
            }
        }
    }

    public void registerForwardingEarlyReceivePackets(PacketRouteBuilder registrar, PacketListenerPriority priority) {
        for (var route : receiveDispatchPacketTypes(registrar.scanner())) {
            registrar.earlyReceiveRoute(route, priority, EARLY_RECEIVE_FORWARDER);
        }
    }

    public void registerForwardingSendPackets(PacketRouteBuilder registrar, PacketListenerPriority priority) {
        for (var route : CheckManager.sendDispatchPacketTypes(registrar)) {
            registrar.sendRoute(route, priority, SEND_FORWARDER);
        }
    }

    /** A packet whose only effect on the model is through the checks it reaches. */
    public static void receiveGeneric(PacketReceiveEvent<?> event, CultPlayer player) {
        if (event.getPhase() != ConnectionPhase.PLAY) return;
        breakPassengerRotationPair(event, player);
        try {
            player.checkManager.dispatchPrePredictionReceive(event);
            player.checkManager.dispatchReceiveHandlers(event);
        } finally {
            endPacket(player);
        }
    }

    // LocalPlayer#sendChanges sends a mounted Rot and the root's MoveVehicle back to back on the client's main thread.
    // Only a packet the client sends from another thread can come between them.
    private static void breakPassengerRotationPair(PacketReceiveEvent<?> event, CultPlayer player) {
        if (!LegacyPacketEventSemantics.isAsync(event)) player.packetStateData.breakPassengerRotationPair();
    }

    // A NoFall ground decision staged during this packet's prediction belongs to this packet alone.
    private static void endPacket(CultPlayer player) {
        player.packetStateData.clearDesiredOnGround();
    }

    // In observe-only the codec forwards a cancelled packet unchanged, so the server applies it.
    private static boolean reachesServer(PacketReceiveEvent<?> event) {
        return !event.isCancelled() || CultAPI.INSTANCE.getConfigManager().isObserveOnly();
    }

    @CultPacketHandler
    public void onAcceptTeleportation(
            PacketReceiveEvent<ServerboundAcceptTeleportation> event,
            CultPlayer player,
            ServerboundAcceptTeleportation packet) {
        if (event.getPhase() != ConnectionPhase.PLAY) return;
        if (player.isBedrockMovement()) {
            // Geyser confirms each Java teleport on receipt like the Java client. Before 26.3 the wire carries an
            // ID-only acknowledgement and a PosRot(onGround=false) after it; from 26.3 the acknowledgement carries the
            // position and nothing follows. Bedrock's own acknowledgement is its HANDLE_TELEPORT frame.
            player.packetStateData.bedrockServerResponse = !packet.hasPosition();
            return;
        }
        // The teleport ID acknowledgement interrupts a Rot -> MoveVehicle pair.
        player.packetStateData.clearPendingVehicleMoveAfterPassengerRotation();
        receiveGeneric(event, player);
    }

    @CultPacketHandler
    public void onContainerClick(PacketReceiveEvent<InventoryClick> event, CultPlayer player, InventoryClick packet) {
        receiveGeneric(event, player);
        if (reachesServer(event)) player.getInventory().onClickForwarded(packet);
        else player.getInventory().resyncCancelledClick(packet);
    }

    @CultPacketHandler
    public void onCustomPayload(
            PacketReceiveEvent<ServerboundCustomPayload> event, CultPlayer player, ServerboundCustomPayload packet) {
        if (event.isCancelled()) {
            if (event.getPhase() == ConnectionPhase.PLAY) breakPassengerRotationPair(event, player);
            return;
        }
        receiveGeneric(event, player);
    }

    @CultPacketHandler
    public void onPlayerCommand(
            PacketReceiveEvent<ServerboundPlayerCommand> event, CultPlayer player, ServerboundPlayerCommand packet) {
        if (event.getPhase() != ConnectionPhase.PLAY) return;
        if (!player.isBedrockMovement() && sentByLocalPlayerTick(packet.action())) {
            player.packetStateData.localPlayerTickInputOrCommandThisClientTick = true;
        }
        // PacketEntityAction runs immediately before this listener. Its rejected glide starts used to terminate at the
        // pre-Via boundary, so they must not enter the ordinary check route.
        if (event.isCancelled() && packet.action() == PlayerCommandAction.START_FLYING_WITH_ELYTRA) {
            breakPassengerRotationPair(event, player);
            return;
        }
        receiveGeneric(event, player);
    }

    // InBedChatScreen sends STOP_SLEEPING and Minecraft#handleKeybinds OPEN_INVENTORY; LocalPlayer#tick sends the rest.
    private static boolean sentByLocalPlayerTick(PlayerCommandAction action) {
        return action != PlayerCommandAction.STOP_SLEEPING && action != PlayerCommandAction.OPEN_INVENTORY;
    }

    @CultPacketHandler("serverbound.player_loaded")
    public void onPlayerLoaded(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        if (event.getPhase() != ConnectionPhase.PLAY) return;
        // ClientPacketListener#notifyPlayerLoaded sends this as it sets clientLoaded, before this tick's player tick.
        player.packetStateData.playerLoadedIntoLevel = true;
        receiveGeneric(event, player);
    }

    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        if (event.getPhase() != ConnectionPhase.PLAY) return;
        try {
            // Reject raw non-finite values before teleport matching or last-sent tick state can consume them.
            player.checkManager.getCheck(CrashC.class).onMovePlayer(event, player, packet);
            if (event.isCancelled()) {
                breakPassengerRotationPair(event, player);
                return;
            }
            if (player.isBedrockMovement()) receiveBedrockMovePlayer(event, player, packet);
            else receiveJavaMovePlayer(event, player, packet);
        } finally {
            endPacket(player);
        }
    }

    private static void receiveJavaMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        PacketStateData state = player.packetStateData;
        SetbackTeleportUtil setbacks = player.getSetbackTeleportUtil();
        // Through 26.2 the intercepted wire carries an ID acknowledgement followed by PosRot; 26.3 carries the
        // coordinates in the acknowledgement.
        boolean legacyWire = !player.user.getObservedProtocol().atLeast(ProtocolVersion.V26_3);
        Vec3 position =
                VectorUtils.clampVector(new Vec3(packet.xOr(player.x), packet.yOr(player.y), packet.zOr(player.z)));
        TeleportAcceptData teleport;
        if (packet.hasPosition()) {
            if (legacyWire && packet.hasRotation()) setbacks.javaTeleports().posRotReceived();
            teleport = legacyWire || setbacks.javaTeleports().hasIdlessJavaPositionTeleport()
                    ? setbacks.javaTeleports().checkTeleportQueue(position.x, position.y, position.z)
                    : new TeleportAcceptData();
        } else if (packet.rotationOnly()) {
            teleport = setbacks.javaTeleports().checkRotationTeleportQueue(packet.yaw(), packet.pitch());
        } else {
            teleport = new TeleportAcceptData();
        }
        if (teleport.isTeleport()) {
            event.setAnswersTeleport(true);
            state.teleportedThisClientTick = true;
            if (packet.hasPosition()) {
                receiveJavaTeleportPosition(event, player, packet, teleport, legacyWire);
                return;
            }
        }

        // LocalPlayer#tick emits Rot, never StatusOnly, while mounted.
        CompensatedVehicleState vehicles = player.compensatedEntities.vehicles;
        boolean passengerRotation = packet.rotationOnly()
                && (player.compensatedEntities.getSelf().inVehicle()
                        || vehicles.canServerPlayerVehicleBeLocalAuthoritative()
                        || vehicles.hasClientPlayerPassengerState()
                        || isBufferedLocalAuthoritativeRoot(player, vehicles.serverVehicleEntity()));
        updatePassengerRotationPair(player, passengerRotation, teleport.isTeleport());

        // A teleport acknowledgement is not LocalPlayer#tick movement.
        if (!teleport.isTeleport()) {
            // Before the pre-prediction checks: a setback they raise rejects this movement.
            setbacks.movementReceived();
            state.receivedMovementThisClientTick = true;
            // Only LocalPlayer#tick sends movement, and it requires ClientPacketListener#hasClientLoaded.
            state.playerLoadedIntoLevel = true;
            if (packet.hasPosition()) state.lastSentTickPosition = position;
            if (packet.hasRotation()) {
                state.lastSentTickYaw = packet.yaw();
                state.lastSentTickPitch = packet.pitch();
                if (!passengerRotation) {
                    state.lastSendPositionYaw = packet.yaw();
                    state.lastSendPositionPitch = packet.pitch();
                }
            }
            player.sprintState.onClientTickEnd();
            // LocalPlayer#sendPosition sends this tick's START/STOP_SPRINTING before its movement.
            onAiStepObserved(player);
        }

        player.checkManager.dispatchPrePredictionReceive(event);

        // The player flagged crasher or timer checks, therefore we must protect predictions against these attacks
        // and not a teleport (it's dangerous to prevent teleports from going through simulation processors)
        if (event.isCancelled() && !teleport.isTeleport()) return;

        // Receive handlers such as Phase check this movement after its prediction pass. Its safe position commits
        // once they have run, so a setback they raise rolls back past it.
        setbacks.holdSafePosition();
        try {
            boolean lastSentLook = !packet.hasRotation() && keepsLastSentRotation(player, teleport);
            handleFlying(
                    player,
                    position.x,
                    position.y,
                    position.z,
                    lastSentLook ? state.lastSendPositionYaw : packet.yawOr(player.xRot),
                    lastSentLook ? state.lastSendPositionPitch : packet.pitchOr(player.yRot),
                    packet.hasPosition(),
                    packet.hasRotation() || lastSentLook,
                    packet.onGround(),
                    teleport);

            // While a violation setback is pending, forward nothing but teleport confirms.
            // Prediction and packet checks still observe Java movement before the deferred
            // SetbackBlocker runs. Also cancel non-position packets and the packet that
            // caused the setback, so none of them can reach Paper in this window.
            // Paper adopting any of these lets the setback echo register as an upward move
            // and wipe fallDistance (fall-overshoot-wipe bypass).
            if (setbacks.isPendingSetback() && !teleport.isTeleport()) event.setCancelled(true);

            player.checkManager.dispatchReceiveHandlers(event);
        } finally {
            setbacks.releaseSafePosition();
        }
        player.checkManager.getSetbackBlocker().onJavaMovePlayerAfterChecks(event);
    }

    /**
     * A movement packet answering a positioned teleport: the PosRot ClientPacketListener#handleMovePlayer sends before
     * 26.3, or an idless 26.3 teleport's answer. It is not LocalPlayer#tick movement.
     */
    private static void receiveJavaTeleportPosition(
            PacketReceiveEvent<ServerboundMovePlayer> event,
            CultPlayer player,
            ServerboundMovePlayer packet,
            TeleportAcceptData teleport,
            boolean legacyWire) {
        // Via folds the pre-26.3 PosRot into the acknowledgement, not an ordinary position move; idless 26.3 teleports
        // still consume ordinary movement.
        event.setTeleportPositionResponse(legacyWire
                && packet.hasRotation()
                && !teleport.getTeleportData().isPositionOnly());
        Vec3 forwarded = teleport.getForwardedPosition();
        if (forwarded != null) {
            // The answer is itself a server movement packet. Forward the accepted target, or the seat a passenger
            // stayed in, never the client's coordinates, including a relative teleport's near match.
            event.replace(packet.withPosition(forwarded.x, forwarded.y, forwarded.z, false));
        }
        updatePassengerRotationPair(player, false, true);
        player.checkManager.dispatchPrePredictionReceive(event);
        applyTeleportResponse(
                player, teleport, packet.yawOr(player.xRot), packet.pitchOr(player.yRot), packet.hasRotation());
        player.checkManager.dispatchReceiveHandlers(event);
    }

    // LocalPlayer#sendChanges sends a mounted Rot immediately before the root's MoveVehicle.
    private static void updatePassengerRotationPair(CultPlayer player, boolean passengerRotation, boolean teleport) {
        PacketStateData state = player.packetStateData;
        if (passengerRotation && !teleport) {
            state.markPassengerRotation();
            return;
        }
        if (!passengerRotation) state.breakPassengerRotationPair();
        if (player.compensatedEntities.vehicles.serverPlayerVehicle != null
                || player.compensatedEntities.getSelf().inVehicle()) {
            // A position packet breaks the mounted Rot/MoveVehicle pair.
            state.clearPendingVehicleMoveAfterPassengerRotation();
        }
    }

    // LocalPlayer#sendPosition sends no rotation while the player's equals the last one it sent; a teleport
    // acceptance or a cancelled move can leave the simulated rotation elsewhere. An equal one is no rotation update.
    private static boolean keepsLastSentRotation(CultPlayer player, TeleportAcceptData teleport) {
        PacketStateData state = player.packetStateData;
        return !teleport.isTeleport()
                && !Float.isNaN(state.lastSendPositionYaw)
                && (state.lastSendPositionYaw != player.xRot || state.lastSendPositionPitch != player.yRot);
    }

    /**
     * Geyser's Java projection of a Bedrock frame. Bedrock's client-authored movement arrives through
     * PlayerAuthInputPacket, so this packet never becomes a second teleport acceptance or simulation path; only a
     * rider's look reaches the checks.
     */
    private static void receiveBedrockMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        PacketStateData state = player.packetStateData;
        boolean answersTeleport = state.bedrockServerResponse;
        state.bedrockServerResponse = false;
        if (answersTeleport && packet.hasPosition() && packet.hasRotation()) {
            // Geyser's teleport PosRot answers the server, not a Bedrock auth frame.
            event.setTeleportPositionResponse(true);
            if (packet.onGround() && !player.isDisabled() && !player.noModifyPacketPermission) {
                event.replace(packet.withOnGround(false));
            }
            player.getSetbackTeleportUtil()
                    .bedrockTeleports()
                    .setBedrockPaperVisiblePosition(new Vec3(packet.x(), packet.y(), packet.z()));
            return;
        }

        if (!player.compensatedEntities.vehicles.hasPlayerPassengerState()) {
            // Auth input owns movement; this projection still passes through the setback gate.
            player.checkManager.getListener(SetbackBlocker.class).onTranslatedBedrockMove(event, packet);
            player.checkManager.dispatchReceiveHandlers(event);
            if (!event.isCancelled() && event.getPacket().hasPosition()) {
                var projected = event.getPacket();
                player.getSetbackTeleportUtil()
                        .bedrockTeleports()
                        .setBedrockPaperVisiblePosition(
                                new Vec3(projected.xOr(player.x), projected.yOr(player.y), projected.zOr(player.z)));
            }
            return;
        }
        if (!packet.rotationOnly()) {
            // Mounted Bedrock movement may only project rotation here.
            event.setCancelled(true);
            player.getSetbackTeleportUtil().executeForceResync("bedrock mounted player movement");
            return;
        }
        // Geyser sends vehicle movement before rider rotation. Expire an unused decision here if it omitted the
        // vehicle move.
        if (state.bedrockTranslatedMovement.take() == BedrockTranslatedMovementGate.Rejected.INSTANCE) {
            event.setCancelled(true);
            return;
        }

        SetbackTeleportUtil setbacks = player.getSetbackTeleportUtil();
        setbacks.movementReceived();
        state.receivedMovementThisClientTick = true;
        state.playerLoadedIntoLevel = true;
        player.sprintState.onClientTickEnd();
        player.checkManager.dispatchPrePredictionReceive(event);
        if (event.isCancelled()) return;
        setbacks.holdSafePosition();
        try {
            handleFlying(
                    player,
                    player.x,
                    player.y,
                    player.z,
                    packet.yaw(),
                    packet.pitch(),
                    false,
                    true,
                    packet.onGround(),
                    new TeleportAcceptData());
            if (setbacks.isPendingSetback()) event.setCancelled(true);
            player.checkManager.dispatchReceiveHandlers(event);
        } finally {
            setbacks.releaseSafePosition();
        }
    }

    // LocalPlayer#aiStep ran: the sprint state settles, and a respawned LocalPlayer's KeyboardInput#tick read the held
    // shift its kept lastSentInput holds.
    private static void onAiStepObserved(CultPlayer player) {
        if (!player.localPlayerTicks()) return;
        if (player.packetStateData.respawnShiftPending) {
            player.packetStateData.respawnShiftPending = false;
            player.isSneaking = true;
        }
        // Noncontrolling passengers skip sendIsSprintingIfNeeded, but still run aiStep: a vehicle that
        // cannot sprint clears the local flag and, if it was set, the modifier. Keep the announcement separate.
        PacketEntity vehicle = player.compensatedEntities.getSelf().getRiding();
        if (vehicle != null) {
            player.onMountedClientTick(vehicle);
            player.sprintState.onClientTick(player.isClientSprinting());
            return;
        }
        if (player.compensatedEntities.getSelf().inVehicle()) return;
        player.onFootClientTick();
        player.sprintState.onClientTick(player.isSprinting);
    }

    // Player#tick advances the attack ticker every client tick, including Entity#rideTick.
    private static void onLocalPlayerAttackTick(CultPlayer player) {
        if (!player.localPlayerTicks()) return;
        player.attackStrength.onClientTick(
                player.getInventory().getHeldItem(), player.packetStateData.carriedItemChangedThisClientTick);
    }

    @CultPacketHandler
    public void onMoveVehicle(
            PacketReceiveEvent<ServerboundMoveVehicle> event, CultPlayer player, ServerboundMoveVehicle packet) {
        if (event.getPhase() != ConnectionPhase.PLAY) return;
        try {
            if (player.checkManager.getCheck(CrashC.class).rejectMoveVehicle(event, packet)) {
                breakPassengerRotationPair(event, player);
                return;
            }
            Vec3d position = packet.position();
            Vec3 newPos = VectorUtils.clampVector(new Vec3(position.x(), position.y(), position.z()));
            if (player.isBedrockMovement()) receiveBedrockMoveVehicle(event, player, packet, newPos);
            else receiveJavaMoveVehicle(event, player, packet, newPos);
        } finally {
            endPacket(player);
        }
    }

    /**
     * Geyser emits MoveVehicle both from client-predicted auth input and from its independent session vehicle tick.
     * Neither is Java client movement, so it never reaches Java simulation.
     */
    private static void receiveBedrockMoveVehicle(
            PacketReceiveEvent<ServerboundMoveVehicle> event,
            CultPlayer player,
            ServerboundMoveVehicle packet,
            Vec3 newPos) {
        CompensatedVehicleState vehicles = player.compensatedEntities.vehicles;
        SetbackTeleportUtil setbacks = player.getSetbackTeleportUtil();
        PacketEntity controlledVehicle = BedrockVehicleControl.controlledVehicle(player);
        Integer serverVehicle = vehicles.serverPlayerVehicle;
        PacketEntity riding = player.compensatedEntities.getSelf().getRiding();
        PacketEntity packetRoot = riding != null ? riding : vehicles.serverVehicleEntity();
        Integer teleportVehicleId = packetRoot == null ? serverVehicle : Integer.valueOf(packetRoot.getEntityId());
        TeleportAcceptData teleport =
                setbacks.vehicleTeleports().checkVehicleTeleportQueue(teleportVehicleId, newPos.x, newPos.y, newPos.z);
        BedrockTranslatedMovementGate movementGate = player.packetStateData.bedrockTranslatedMovement;
        if (!teleport.isTeleport() && (movementGate.isRejected() || setbacks.blocksBedrockTranslatedMovement())) {
            // A rejection belongs to the auth frame's rider rotation; an
            // older grant expires when a correction starts blocking movement.
            if (!movementGate.isRejected()) movementGate.clear();
            event.setCancelled(true);
            return;
        }
        var decision = movementGate.take();
        if (teleport.isTeleport()) {
            if (packetRoot != null && controlledVehicle == null) {
                applyAcceptedVehicleTeleportState(player, packetRoot, newPos, packet, teleport);
            }
            return;
        }
        // BedrockVehicleControl#controlledVehicle requires a living root: a vehicle with health still moves under its
        // rider, while the dead flag can outlive it.
        boolean authorizedVehicleMove = decision instanceof BedrockTranslatedMovementGate.Vehicle permit
                && controlledVehicle != null
                && serverVehicle != null
                && serverVehicle == controlledVehicle.getEntityId()
                && permit.entityId() == controlledVehicle.getEntityId();
        if (!authorizedVehicleMove
                || !vehicles.isServerPlayerPassengerOf(serverVehicle)
                || setbacks.shouldBlockVehicleMovement(true)
                || player.isInBed) {
            event.setCancelled(true);
        }
    }

    private static void receiveJavaMoveVehicle(
            PacketReceiveEvent<ServerboundMoveVehicle> event,
            CultPlayer player,
            ServerboundMoveVehicle packet,
            Vec3 newPos) {
        PacketStateData state = player.packetStateData;
        CompensatedVehicleState vehicles = player.compensatedEntities.vehicles;
        SetbackTeleportUtil setbacks = player.getSetbackTeleportUtil();
        // MCP-Reborn LocalPlayer#sendChanges sends the passenger Rot immediately before ServerboundMoveVehiclePacket.
        // ClientPacketListener#handleMoveVehicle and #handleTeleportEntity echo MoveVehicle alone from the packet
        // handler, which snaps state without advancing AbstractBoat#tick state.
        boolean paired = state.isAwaitingVehicleMoveAfterPassengerRotation();
        boolean firstTickMove = !state.clientTickVehicleMoveThisClientTick;
        boolean fromClientTick = paired || (state.hasPassengerRotationThisClientTick() && firstTickMove);
        PacketEntity root = currentVehicleRoot(player, fromClientTick);
        if (!fromClientTick && firstTickMove) {
            // While the vehicle-switch buffer covers a root this client controls, its move counts as this tick's.
            PacketEntity buffered = bufferedLocalAuthoritativeRoot(player, root);
            if (buffered != null) {
                fromClientTick = true;
                root = buffered;
            }
        }
        if (fromClientTick && vehicles.canOpenVehicleSwitchBufferForMovementPacket(root)) {
            // Minecraft#tick sends carried-slot changes before keybind handling,
            // then LocalPlayer#tick may move with a newly visible vehicle-control
            // mode. Keep the packet on the prediction path and let the bounded
            // vehicle-switch buffer decide how much uncertainty can be absorbed.
            vehicles.markVehicleSwitchMovementPacketBoundary();
        }
        // After the boundary above, which can open the buffer for this root.
        boolean canMove = vehicles.canServerPlayerVehicleBeLocalAuthoritativeForMovementPacket(fromClientTick)
                || (fromClientTick && vehicles.isVehicleSwitchBufferActiveFor(root));
        TeleportAcceptData teleport = setbacks.vehicleTeleports()
                .checkVehicleTeleportQueue(
                        root == null ? null : root.getEntityId(), newPos.x, newPos.y, newPos.z, paired);
        boolean tickMove = fromClientTick && root != null && canMove && !teleport.isTeleport();
        if (!teleport.isTeleport() && !tickMove) {
            rejectVehicleMove(event, player, packet, root, fromClientTick);
            return;
        }

        if (teleport.isTeleport()) {
            event.setAnswersTeleport(true);
            state.teleportedThisClientTick = true;
        }
        state.markVehicleMove(tickMove);
        if (fromClientTick) {
            state.clientTickVehicleMoveThisClientTick = true;
            if (!teleport.isTeleport()) state.receivedMovementThisClientTick = true;
        }

        player.checkManager.dispatchPrePredictionReceive(event);
        // A timer rejection must take effect before interpolation, boat state, or pig/strider boost advances.
        if (event.isCancelled() && !teleport.isTeleport()) return;
        // A paired move is a client tick move, never a teleport answer, so it has a root.
        if (paired
                && root instanceof PacketEntityRideable rideable
                // Pig#tickRidden and Strider#tickRidden tick the boost inside travelRidden, gated on isAlive().
                && rideable.actionAlive
                && rideable.hasSaddle
                && vehicles.passengerIndex(rideable) == 0) {
            // Count even if its prediction subsequently requires a correction.
            rideable.boost.tick(state.acceptedClientTick);
        }
        // 1.21.2/1.21.3 MoveVehicle has no on-ground field. ViaBackwards
        // 1.21.4 -> 1.21.2 appends literal true when upgrading that packet,
        // so the observed codec's field presence alone is not client evidence.
        // Keep the raw packet intact and use collision-derived ground carry
        // unless both the original client and observed layout report the bit.
        boolean clientReportsOnGround =
                packet.hasOnGround() && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_4);
        state.vehicleMovementStartOnGround = root == null ? packet.onGround() : root.onGround;
        state.vehicleMovementOnGroundPresent = clientReportsOnGround;
        if (root != null) {
            if (tickMove && firstTickMove) root.interpolateBeforeLocalTravel(player);
            Vec3 from = currentVehiclePhysicalPosition(root, new Vec3(player.x, player.y, player.z));
            if (tickMove && root.isBoat()) player.boatData.tick(player);
            float yaw = vehicles.saddleHandOverTravelYaw(root, packet.yaw());
            float pitch = packet.pitch();
            double lastX = player.lastX, lastY = player.lastY, lastZ = player.lastZ;
            double x = player.x, y = player.y, z = player.z;
            player.lastX = x;
            player.lastY = y;
            player.lastZ = z;
            player.x = newPos.x;
            player.y = newPos.y;
            player.z = newPos.z;
            if (tickMove) {
                // Protocols through 1.21.3 do not encode an on-ground bit in
                // ServerboundMoveVehiclePacket. Preserve that absence instead of
                // fabricating false; the simulation can still prove the resulting
                // ground state from the movement collision.
                boolean reportedOnGround =
                        clientReportsOnGround ? packet.onGround() : state.vehicleMovementStartOnGround;
                VehiclePositionUpdate update =
                        new VehiclePositionUpdate(from, newPos, yaw, pitch, reportedOnGround, clientReportsOnGround);
                setbacks.holdSafePosition();
                try {
                    player.checkManager.onVehiclePositionUpdate(update);
                } finally {
                    setbacks.releaseSafePosition();
                }
                Boolean correctedOnGround = player.checkManager
                        .getListener(VehiclePredictionRunner.class)
                        .takeCorrectedOnGround();
                if (setbackBlocksVehicleMovement(player)) {
                    event.setCancelled(true);
                    player.lastX = lastX;
                    player.lastY = lastY;
                    player.lastZ = lastZ;
                    player.x = x;
                    player.y = y;
                    player.z = z;
                    return;
                }
                boolean onGround = correctedOnGround != null
                        ? correctedOnGround
                        : clientReportsOnGround ? packet.onGround() : root.onGround;
                if (correctedOnGround != null) correctOnGroundClaim(event, player, packet, onGround);
                vehicles.applyControlledVehicleMove(root, newPos, onGround, yaw, pitch);
            } else {
                applyAcceptedVehicleTeleportState(player, root, newPos, packet, teleport);
                // ServerboundMoveVehiclePacket#fromEntity echoes the root's onGround, which the response handler
                // took from the server or left as it was. The server reads the echo's bit, so it gets that one.
                if (clientReportsOnGround) correctOnGroundClaim(event, player, packet, root.onGround);
            }
        }

        player.checkManager.dispatchReceiveHandlers(event);
    }

    /** A MoveVehicle that is neither a teleport answer nor a checkable LocalPlayer#tick move of this client's root. */
    private static void rejectVehicleMove(
            PacketReceiveEvent<ServerboundMoveVehicle> event,
            CultPlayer player,
            ServerboundMoveVehicle packet,
            @Nullable PacketEntity root,
            boolean fromClientTick) {
        PacketStateData state = player.packetStateData;
        // VehicleTimer observes every decoded movement, also the moves rejected here before the checks.
        player.checkManager.getCheck(VehicleTimer.class).onMoveVehicle(event, player, packet);
        event.setCancelled(true);
        state.markVehicleMove(false);
        // forceResyncVehicleSwitchBuffer can start the setback the second test reads.
        if (!setbackBlocksVehicleMovement(player)) {
            player.compensatedEntities.vehicles.forceResyncVehicleSwitchBuffer(
                    root, "vehicle-switch-buffer-invalid-packet");
        }
        if (!setbackBlocksVehicleMovement(player) || !fromClientTick) {
            player.checkManager.getCheck(BadPacketsVehicle.class).handleInvalidVehiclePacket(fromClientTick);
        }
        if (fromClientTick) {
            if (root != null && !state.clientTickVehicleMoveThisClientTick) root.interpolateBeforeLocalTravel(player);
            state.clientTickVehicleMoveThisClientTick = true;
            state.receivedMovementThisClientTick = true;
        }
    }

    private static void applyAcceptedVehicleTeleportState(
            CultPlayer player,
            PacketEntity packetRoot,
            Vec3 position,
            ServerboundMoveVehicle vehiclePacket,
            TeleportAcceptData teleportData) {
        VehicleTeleportData vehicleTeleportData = teleportData.getVehicleTeleportData();
        if (vehicleTeleportData != null && vehicleTeleportData.getEntityId() == packetRoot.getEntityId()) {
            player.compensatedEntities.vehicles.applyAcceptedVehicleTeleportEntityState(
                    vehicleTeleportData.getEntityId(),
                    vehicleTeleportData.getPosition(),
                    vehicleTeleportData.getYaw(),
                    vehicleTeleportData.getPitch(),
                    vehicleTeleportData.getOnGround(),
                    vehicleTeleportData.getDeltaMovement(),
                    false);
        } else {
            // MCP-Reborn ClientPacketListener#handleMoveVehicle snaps only position and rotation (Entity#absSnapTo),
            // so its ServerboundMoveVehiclePacket#fromEntity echo carries the onGround the root's last tick left. The
            // echo cannot change it; Geyser writes the Bedrock packet from its own state.
            boolean onGround;
            if (player.isBedrockMovement() && vehiclePacket.hasOnGround()) {
                onGround = vehiclePacket.onGround();
            } else {
                SetBackData setback = teleportData.getSetback();
                DesyncStatus horseOnGround = setback == null ? null : setback.getHorseOnGround();
                // The last tick's onGround belongs to the movement this setback rejected; the horse leaves the setback
                // with the onGround of the move that reached its position, for every version's next tick.
                onGround = horseOnGround == null ? packetRoot.onGround : horseOnGround.determinePessimistically();
                if (horseOnGround != null) {
                    player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.fromBoolean(onGround));
                }
            }
            player.compensatedEntities.vehicles.applyVehiclePacketPosition(
                    packetRoot, position, onGround, vehiclePacket.yaw(), vehiclePacket.pitch());
        }
    }

    /** Forwards {@code packet} with the onGround the engine holds for the root when the claim differs. */
    private static void correctOnGroundClaim(
            PacketReceiveEvent<ServerboundMoveVehicle> event,
            CultPlayer player,
            ServerboundMoveVehicle packet,
            boolean onGround) {
        if (packet.onGround() == onGround
                || !player.checkManager.getCheck(VehicleGround.class).shouldModifyPackets()) return;
        event.replace(new ServerboundMoveVehicle(
                packet.position(), packet.yaw(), packet.pitch(), onGround, packet.hasOnGround()));
    }

    private static boolean setbackBlocksVehicleMovement(CultPlayer player) {
        SetbackTeleportUtil setbacks = player.getSetbackTeleportUtil();
        return setbacks.vehicleTeleports().hasUnacknowledgedSetbackVehicleTeleport()
                || setbacks.shouldBlockVehicleMovement();
    }

    private static Vec3 currentVehiclePhysicalPosition(PacketEntity root, Vec3 fallback) {
        Vec3 physical = root.clientPhysicalPosition == null ? root.desyncClientPos : root.clientPhysicalPosition;
        return physical == null ? fallback : physical;
    }

    @Nullable
    private static PacketEntity currentVehicleRoot(CultPlayer player, boolean fromClientTick) {
        PacketEntity root = player.compensatedEntities.getSelf().getRiding();
        if (root != null) return root;

        CompensatedVehicleState vehicles = player.compensatedEntities.vehicles;
        PacketEntity serverVehicle = vehicles.serverVehicleEntity();
        return serverVehicle != null
                        && (vehicles.canServerPlayerVehicleBeLocalAuthoritativeForMovementPacket(fromClientTick)
                                || fromClientTick
                                        && (vehicles.canOpenVehicleSwitchBufferForMovementPacket(serverVehicle)
                                                || vehicles.isVehicleSwitchBufferActiveFor(serverVehicle)))
                ? serverVehicle
                : null;
    }

    @Nullable
    private static PacketEntity bufferedLocalAuthoritativeRoot(CultPlayer player, @Nullable PacketEntity packetRoot) {
        if (isBufferedLocalAuthoritativeRoot(player, packetRoot)) return packetRoot;
        PacketEntity serverVehicle = player.compensatedEntities.vehicles.serverVehicleEntity();
        return isBufferedLocalAuthoritativeRoot(player, serverVehicle) ? serverVehicle : null;
    }

    private static boolean isBufferedLocalAuthoritativeRoot(CultPlayer player, @Nullable PacketEntity root) {
        return player.compensatedEntities.vehicles.isVehicleSwitchBufferActiveFor(root)
                && player.compensatedEntities.vehicles.canClientAuthoritativelyMoveVisibleRoot(root);
    }

    @CultPacketHandler
    public void onPlayerAction(
            PacketReceiveEvent<ServerboundPlayerAction> event, CultPlayer player, ServerboundPlayerAction packet) {
        if (event.getPhase() != ConnectionPhase.PLAY) return;
        PlayerAction action = packet.action();
        boolean predictedDig = action == PlayerAction.START_DESTROY_BLOCK || action == PlayerAction.STOP_DESTROY_BLOCK;
        if (predictedDig) player.compensatedWorld.advanceClientPredictionSequence();
        breakPassengerRotationPair(event, player);
        try {
            player.checkManager.dispatchPrePredictionReceive(event);
            if (predictsBreak(player, packet)) {
                player.compensatedWorld.startPredicting();
                try {
                    ClientBlockActions.breakBlock(player, packet.position());
                } finally {
                    player.compensatedWorld.stopPredicting(packet.sequence());
                }
            }
            player.checkManager.dispatchReceiveHandlers(event);
            var reconciler = player.blockActionReconciler;
            if (predictedDig) {
                // BlockStatePredictionHandler keeps the predicted break until a block-changed ack reaches its sequence.
                // abortServerDestroy can forward a rejected STOP as an ABORT, so reaching the server is read after it.
                if (!reachesServer(event)) reconciler.abortServerDestroy(event, packet);
                if (reachesServer(event)) reconciler.forwarded(packet.sequence());
                else reconciler.rejectDig(packet);
            }
            if (reachesServer(event)) reconciler.digReachedServer(event.getPacket());
        } finally {
            endPacket(player);
        }
    }

    // The client destroys the block at once when STOP finishes a breakable block, or START breaks it instantly. No
    // damage means it is unbreakable by creative players (with swords).
    private static boolean predictsBreak(CultPlayer player, ServerboundPlayerAction packet) {
        BlockPos position = packet.position();
        return switch (packet.action()) {
            case STOP_DESTROY_BLOCK ->
                DataTables.defaults()
                                .registry()
                                .facts(player.compensatedWorld.getBlockStateIdAt(position))
                                .destroyTime()
                        != -1.0f;
            case START_DESTROY_BLOCK -> BlockBreakSpeed.getBlockDamage(player, position) >= 1;
            default -> false;
        };
    }

    @CultPacketHandler
    public void onUseItemOn(
            PacketReceiveEvent<ServerboundUseItemOn> event, CultPlayer player, ServerboundUseItemOn packet) {
        if (event.getPhase() != ConnectionPhase.PLAY) return;
        player.compensatedWorld.advanceClientPredictionSequence();
        breakPassengerRotationPair(event, player);
        try {
            player.checkManager.dispatchPrePredictionReceive(event);
            Hand hand = packet.hand();
            player.lastBlockPlaceUseItem = System.currentTimeMillis();

            SimItemStack placedWith = player.getInventory().getHandItem(hand);
            BlockPlace blockPlace = new BlockPlace(
                    player,
                    hand,
                    packet.blockPosition(),
                    packet.blockFace(),
                    placedWith,
                    player.compensatedWorld.withSelectionSnapshot(
                            () -> TraverseBlocks.getNearestHitResult(player, true)),
                    packet.sequence());
            blockPlace.setCursor(SpigotConversionUtil.fromProtocolVec(packet.cursor()));

            // Deny fun stuff like teleport bridging
            BlockPos placedAgainst = blockPlace.getPlacedAgainstBlockLocation();
            GhostBlockMitigator ghostBlockMitigator = player.getGhostBlockMitigator();

            // Should we call the anticheat placing checks?
            if (!ghostBlockMitigator.isDesyncPos(placedAgainst)
                    && (blockPlace.isBlock()
                            || placedWith.getItem() == ItemTypes.FIRE_CHARGE
                            || placedWith.getItem() == ItemTypes.END_CRYSTAL)
                    && !player.compensatedEntities.getSelf().inVehicle()) {
                player.checkManager.onBlockPlace(blockPlace);
                player.checkManager.queuePostFlyingBlockPlace(blockPlace);
            }
            // The player tried placing blocks in air/water or failed other checks
            // Or the player tried playing blocks while pending a teleport
            if (!CultAPI.INSTANCE.getConfigManager().isObserveOnly()
                    && (blockPlace.isCancelled()
                            || event.isCancelled()
                            || player.getSetbackTeleportUtil().isPendingSetback())) {
                // Invalid block place due to placing on a ghost block
                blockPlace.resync();

                if (!event.isCancelled()) {
                    event.setCancelled(true);
                    player.onPacketCancel();
                }

                player.blockActionReconciler.rejectPlace(packet);
            } else { // Legit place
                ghostBlockMitigator.handlePseudoPlace(new GhostBlock(placedAgainst, placedWith, blockPlace.isBlock()));
                player.compensatedWorld.markForBlockPrediction(placedAgainst);
                BlockPos normal = blockPlace.getNormalBlockFace();
                BlockPos relative = placedAgainst.offset(normal.getX(), normal.getY(), normal.getZ());
                ghostBlockMitigator.handlePseudoPlace(new GhostBlock(relative, placedWith, blockPlace.isBlock()));
                player.compensatedWorld.markForBlockPrediction(relative);
                PlaceHandler.handleQueuedUseItemOn(player, packet);
            }

            player.checkManager.dispatchReceiveHandlers(event);
            if (reachesServer(event)) player.blockActionReconciler.forwarded(packet.sequence());
        } finally {
            endPacket(player);
        }
    }

    @CultPacketHandler
    public void onUseItem(PacketReceiveEvent<ServerboundUseItem> event, CultPlayer player, ServerboundUseItem packet) {
        if (event.getPhase() != ConnectionPhase.PLAY) return;
        player.compensatedWorld.advanceClientPredictionSequence();
        breakPassengerRotationPair(event, player);
        try {
            player.checkManager.dispatchPrePredictionReceive(event);
            PlaceHandler.handleQueuedUseItem(player, packet);
            player.lastBlockPlaceUseItem = System.currentTimeMillis();
            player.checkManager.dispatchReceiveHandlers(event);
            if (reachesServer(event)) player.blockActionReconciler.forwarded(packet.sequence());
        } finally {
            endPacket(player);
        }
    }

    @CultPacketHandler("serverbound.client_tick_end")
    public void onClientTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        processClientTickEndReceive(event, player);
    }

    /** Minecraft#tick ends every client tick with this packet; ClientTickEndTimeout also closes a stalled one. */
    public static void processClientTickEndReceive(PacketReceiveEvent<?> event, CultPlayer player) {
        if (event.getPhase() != ConnectionPhase.PLAY) return;
        if (!event.isSynthetic()) player.clientTickEndTimeout.onTickEnd(System.nanoTime());
        breakPassengerRotationPair(event, player);
        PacketStateData state = player.packetStateData;
        try {
            // The Geyser bridge ends each Bedrock client tick after translating its frame. Geyser's tick end here only
            // closes that frame's Java projection.
            if (!player.isBedrockMovement() && !endJavaClientTick(event, player)) return;
            state.receivedMovementThisClientTick = false;
            state.bedrockTranslatedMovement.clear();
            state.clearMountedMovementTickState();
            if (state.serverFrozenTickStepsRemaining > 0) state.serverFrozenTickStepsRemaining--;
            state.teleportedThisClientTick = false;
        } finally {
            endPacket(player);
        }
    }

    /**
     * Ends a Java client tick in its order: the timer judges the boundary, then the look, LocalPlayer#aiStep's effects
     * and the receive checks, then the tick counters and per-tick state. Returns false when the timer cancelled the
     * boundary, which then ends no tick.
     */
    private static boolean endJavaClientTick(PacketReceiveEvent<?> event, CultPlayer player) {
        PacketStateData state = player.packetStateData;
        // The timer must judge the boundary before it advances idle physics, attack cooldown, or the tick index.
        player.checkManager.dispatchPrePredictionReceive(event);
        if (event.isCancelled()) return false;
        // A tick whose LocalPlayer#sendPosition sent no movement ends with its look unchanged.
        player.checkManager.doChecksWithKnownLook();
        // LocalPlayer#aiStep finished before ServerboundClientTickEndPacket.
        onAiStepObserved(player);
        onLocalPlayerAttackTick(player);
        player.checkManager.dispatchReceiveHandlers(event);
        boolean ticked = player.localPlayerTicks();
        // FlightToggle reads this tick's ClientSprintState#flagBeforeAiStep, which onClientTickEnd releases.
        if (ticked) FlightToggle.onClientTick(player);
        player.sprintState.onClientTickEnd();

        player.endCountedClientTick();
        if (ticked) state.localPlayerTickedSinceSpawn = true;
        // ClientLevel#tickPassenger runs Entity#rideTick even while LocalPlayer#tick waits for the client to load.
        if (ticked || player.compensatedEntities.getSelf().inVehicle()) {
            state.lastDeltaConsumedTickEndTransaction = player.lastTransactionReceived.get();
        }
        state.heldSlotEcho = -1;
        state.heldSlotSetWithScreenOpen = false;
        state.flyingStartBeforeTravel = false;
        state.flightStopThisClientTick = false;
        state.localPlayerTickInputOrCommandThisClientTick = false;
        state.vehicleMovedThisClientTick = false;
        state.clientTickVehicleMoveThisClientTick = false;
        return true;
    }

    /**
     * Applies a transaction-matched teleport response without producing a movement tick. Before 26.3 the response is
     * ClientPacketListener#handleMovePlayer's PosRot; from 26.3 it is ServerboundAcceptTeleportationPacket, which
     * carries the same position and look and no PosRot follows. Both reach checks as the same teleport position and
     * rotation update, so no check depends on which packet carried it.
     */
    public static void applyTeleportResponse(CultPlayer player, TeleportAcceptData accepted, float yaw, float pitch) {
        applyTeleportResponse(player, accepted, yaw, pitch, true);
    }

    private static void applyTeleportResponse(
            CultPlayer player, TeleportAcceptData accepted, float yaw, float pitch, boolean hasLook) {
        player.packetStateData.teleportedThisClientTick = true;
        if (accepted.isMatchedTeleportPosition()) {
            if (player.compensatedEntities.vehicles.hasClientVisiblePendingDismount()) {
                player.compensatedEntities.vehicles.applyClientVisibleDismount();
            }
            Vec3 target = accepted.getTeleportData().getLocation();
            handleFlying(player, target.x, target.y, target.z, yaw, pitch, true, hasLook, false, accepted);
            return;
        }
        // ClientPacketListener#handleMovePlayer leaves a passenger in place and only takes the look.
        if (hasLook) {
            if (player.xRot != yaw || player.yRot != pitch) {
                player.lastTickXRot = player.xRot;
                player.lastTickYRot = player.yRot;
            }
            applyRotation(player, yaw, pitch, accepted);
        }
        Vec3 position = new Vec3(player.x, player.y, player.z);
        player.getSetbackTeleportUtil()
                .onPredictionComplete(new PredictionComplete(new PositionUpdate(
                        position, position, player.xRot, player.yRot, player.onGround, accepted, null)));
    }

    static HeadRotation rotationOrigin(
            float previousYaw, float previousPitch, float yaw, float pitch, TeleportAcceptData accepted) {
        var sent = accepted == null ? null : accepted.getTeleportData();
        if (accepted != null
                && accepted.isTeleport()
                && sent != null
                && (!sent.isSentWhileVehicle() || sent.isRotationOnly())
                && yaw == sent.getFinalYaw()
                && pitch == sent.getFinalPitch()) {
            if (sent.getPlayerRotation() != null) {
                // The turn from the last report to the rotation handleRotatePlayer started from is the player's.
                return new HeadRotation(
                        RotationUpdate.finiteFloat((double) sent.getFinalYaw() - sent.getSourceYaw() + previousYaw),
                        RotationUpdate.finiteFloat(
                                (double) sent.getFinalPitch() - sent.getSourcePitch() + previousPitch));
            }
            return new HeadRotation(sent.getFinalYaw(), sent.getFinalPitch());
        }
        return new HeadRotation(previousYaw, previousPitch);
    }

    private static void applyRotation(CultPlayer player, float yaw, float pitch, TeleportAcceptData accepted) {
        boolean changed = player.xRot != yaw || player.yRot != pitch;
        HeadRotation from = rotationOrigin(player.xRot, player.yRot, yaw, pitch, accepted);
        player.yawBeforeLook = Float.isNaN(player.mountYaw) ? player.xRot : player.mountYaw;
        player.mountYaw = Float.NaN;
        player.xRot = yaw;
        player.yRot = pitch;
        RotationUpdate update = new RotationUpdate(
                from,
                new HeadRotation(player.xRot, player.yRot),
                player.xRot - from.yaw(),
                player.yRot - from.pitch(),
                accepted.isTeleport());
        if (changed) {
            player.lastRotated = System.currentTimeMillis();
        }
        player.checkManager.onRotationUpdate(update);
    }

    private static void handleFlying(
            CultPlayer player,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            boolean hasPosition,
            boolean hasLook,
            boolean onGround,
            TeleportAcceptData teleportData) {
        boolean teleport = teleportData.isTeleport();
        // A teleport response is handled in the same packet phase as a screen the server opened; tick end clears it.
        if (!teleport) player.serverOpenedInventoryThisTick = false;
        if (hasLook && (player.xRot != yaw || player.yRot != pitch)) {
            player.lastTickXRot = player.xRot;
            player.lastTickYRot = player.yRot;
        }

        // We can set the new pos after the places
        if (hasPosition) {
            player.packetStateData.clientSidePosition = VectorUtils.clampVector(new Vec3(x, y, z));
            player.getMovementData().handle(x, y, z);
        }

        if (!teleport) player.packetStateData.packetPlayerOnGround = onGround;

        if (hasLook) applyRotation(player, yaw, pitch, teleportData);

        player.checkManager.doChecksWithKnownLook();

        if (hasPosition) {
            Vec3 position = new Vec3(x, y, z);
            Vec3 clampVector = VectorUtils.clampVector(position);
            PositionUpdate update = new PositionUpdate(
                    new Vec3(player.x, player.y, player.z), position, player.xRot, player.yRot, onGround, teleportData);

            if (!player.compensatedEntities.getSelf().inVehicle()) {
                player.lastX = player.x;
                player.lastY = player.y;
                player.lastZ = player.z;

                player.x = clampVector.x;
                player.y = clampVector.y;
                player.z = clampVector.z;

                player.checkManager.onPositionUpdate(update);
            } else if (teleport) {
                // Mojang doesn't use their own exit vehicle field to leave vehicles, manually call the setback handler
                player.getSetbackTeleportUtil().onPredictionComplete(new PredictionComplete(update));
            }
        }

        // Teleport acknowledgements do not consume per-movement state.
        if (!teleport) player.vehicleData.wasVehicleSwitch = false;
    }

    private static final class CheckManagerEarlyReceiveForwarder implements PacketReceiveHandler<Object> {
        @Override
        public void handle(PacketReceiveEvent event, CultPlayer player, Object packet) {
            if (event.getPhase() != ConnectionPhase.PLAY) return;
            if (packet instanceof ServerboundSwing swing) {
                player.serverSwingEcho.classify(swing.hand(), player.lastTransactionReceived.get());
            }
            player.checkManager.dispatchEarlyReceive(event);
            // An early cancel ends this packet's dispatch before the ordinary route.
            if (event.isCancelled()) breakPassengerRotationPair(event, player);
            if (!reachesServer(event)) player.blockActionReconciler.rejectEarly(packet);
        }
    }

    private static final class CheckManagerSendForwarder implements PacketSendHandler<Object> {
        @Override
        public void handle(PacketSendEvent<Object> event, CultPlayer player, Object packet) {
            if (event.getPhase() == ConnectionPhase.PLAY) player.checkManager.dispatchSendHandlers(event);
        }
    }
}
