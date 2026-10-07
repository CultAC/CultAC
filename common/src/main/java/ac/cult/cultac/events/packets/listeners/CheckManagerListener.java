package ac.cult.cultac.events.packets.listeners;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.bedrock.prediction.integration.BedrockVehicleControl;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsVehicle;
import ac.cult.cultac.checks.impl.movement.GhostBlockMitigator;
import ac.cult.cultac.checks.impl.movement.SetbackBlocker;
import ac.cult.cultac.checks.impl.movement.timer.VehicleTimer;
import ac.cult.cultac.checks.impl.scaffolding.AirLiquidPlace;
import ac.cult.cultac.events.packets.blockplace.PlaceHandler;
import ac.cult.cultac.manager.player.CheckManager;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.PacketReceiveHandler;
import ac.cult.cultac.network.PacketRouteBuilder;
import ac.cult.cultac.network.PacketSendHandler;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.InventoryPackets.CreativeSlot;
import ac.cult.cultac.network.packet.InventoryPackets.Slot;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.util.SpigotConversionUtil;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundChat;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundChatCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundChatCommandSigned;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundClientCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundClientInformation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundCommandSuggestion;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundCustomPayload;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundEditBook;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundKeepAlive;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPaddleBoat;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAbilities;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPong;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundRenameItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSelectBundleItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSelectTrade;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSetCarriedItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSpectatorAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSwing;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundTeleportToEntity;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
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
import ac.cult.cultac.utils.data.TeleportAcceptData;
import ac.cult.cultac.utils.data.VehicleTeleportData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityRideable;
import ac.cult.cultac.utils.inventory.InventoryClick;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.latency.BlockPredictionAckSender;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.VectorUtils;
import ac.cult.cultac.utils.nmsutil.BlockBreakSpeed;
import ac.cult.cultac.utils.nmsutil.TraverseBlocks;
import java.util.List;
import org.jetbrains.annotations.Nullable;

// TODO: All this stupid one line listeners don't belong here
//  does anything belong here? This class likely should just be deleted.
public class CheckManagerListener implements ac.cult.cultac.network.OpaqueReceiveListener {

    private static final List<PacketType<Opaque>> OPAQUE_INTERVENING = List.of(
            ServerboundPackets.CHUNK_BATCH_RECEIVED,
            ServerboundPackets.COOKIE_RESPONSE,
            ServerboundPackets.PING_REQUEST,
            ServerboundPackets.RESOURCE_PACK,
            ServerboundPackets.PLACE_RECIPE,
            ServerboundPackets.SET_BEACON,
            ServerboundPackets.PICK_ITEM_FROM_BLOCK,
            ServerboundPackets.PICK_ITEM_FROM_ENTITY,
            ServerboundPackets.SIGN_UPDATE);
    private static final List<PacketType<Opaque>> OPAQUE_GENERIC = List.of(
            ServerboundPackets.CONTAINER_SLOT_STATE_CHANGED,
            ServerboundPackets.BLOCK_ENTITY_TAG_QUERY,
            ServerboundPackets.CHANGE_DIFFICULTY,
            ServerboundPackets.CHANGE_GAME_MODE,
            ServerboundPackets.CHAT_ACK,
            ServerboundPackets.CHAT_SESSION_UPDATE,
            ServerboundPackets.DEBUG_SAMPLE_SUBSCRIPTION,
            ServerboundPackets.DEBUG_SUBSCRIPTION_REQUEST,
            ServerboundPackets.ENTITY_TAG_QUERY,
            ServerboundPackets.JIGSAW_GENERATE,
            ServerboundPackets.LOCK_DIFFICULTY,
            ServerboundPackets.RECIPE_BOOK_CHANGE_SETTINGS,
            ServerboundPackets.RECIPE_BOOK_SEEN_RECIPE,
            ServerboundPackets.SEEN_ADVANCEMENTS,
            ServerboundPackets.SET_GAME_RULE,
            ServerboundPackets.SET_JIGSAW_BLOCK,
            ServerboundPackets.SET_TEST_BLOCK,
            ServerboundPackets.TEST_INSTANCE_BLOCK_ACTION,
            ServerboundPackets.SET_COMMAND_BLOCK,
            ServerboundPackets.SET_COMMAND_MINECART,
            ServerboundPackets.SET_STRUCTURE_BLOCK,
            ServerboundPackets.CONTAINER_BUTTON_CLICK,
            ServerboundPackets.PICK_ITEM,
            ServerboundPackets.CONTAINER_CLOSE,
            ServerboundPackets.CONFIGURATION_ACKNOWLEDGED,
            ServerboundPackets.CUSTOM_CLICK_ACTION);
    private static final List<PacketType<Opaque>> OPAQUE_CLIENTTICKEND = List.of(ServerboundPackets.CLIENT_TICK_END);
    private static final List<PacketType<Opaque>> OPAQUE_PLAYERLOADED = List.of(ServerboundPackets.PLAYER_LOADED);
    private static final List<PacketType<Opaque>> OPAQUE_RECEIVE_TYPES = java.util.stream.Stream.of(
                    OPAQUE_INTERVENING, OPAQUE_GENERIC, OPAQUE_CLIENTTICKEND, OPAQUE_PLAYERLOADED)
            .flatMap(List::stream)
            .distinct()
            .toList();

    @Override
    public void registerOpaqueReceivePackets(PacketRouteBuilder registrar, PacketListenerPriority priority) {
        registrar.receiveOpaque(
                OPAQUE_INTERVENING, priority, (event, player, packet) -> processInterveningReceive(event, player));
        registrar.receiveOpaque(
                OPAQUE_GENERIC, priority, (event, player, packet) -> processGenericReceive(event, player));
        registrar.receiveOpaque(
                OPAQUE_CLIENTTICKEND, priority, (event, player, packet) -> processClientTickEndReceive(event, player));
        registrar.receiveOpaque(
                OPAQUE_PLAYERLOADED, priority, (event, player, packet) -> processPlayerLoadedReceive(event, player));
    }

    private static final PacketReceiveHandler<Object> EARLY_RECEIVE_FORWARDER = new CheckManagerEarlyReceiveForwarder();
    private static final PacketSendHandler<Object> SEND_FORWARDER = new CheckManagerSendForwarder();

    public static List<ac.cult.cultac.protocol.PacketType<?>> receiveDispatchPacketTypes(
            ac.cult.cultac.network.PacketHandlerScanner records) {
        var types = new java.util.ArrayList<PacketType<?>>(
                records.packetTypes(CheckManagerListener.class, PacketDirection.SERVERBOUND));
        for (var type : OPAQUE_RECEIVE_TYPES) if (records.runtime().supports(type)) types.add(type);
        return List.copyOf(types);
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

    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        processMovePlayerReceive(event, player, packet);
    }

    @CultPacketHandler
    public void onPong(PacketReceiveEvent<ServerboundPong> event, CultPlayer player, ServerboundPong packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onAcceptTeleportation(
            PacketReceiveEvent<ServerboundAcceptTeleportation> event,
            CultPlayer player,
            ServerboundAcceptTeleportation packet) {
        if (event.getPhase() != ConnectionPhase.PLAY) return;
        if (player.isBedrockMovement()) {
            // Geyser confirms each Java teleport on receipt like the Java client: this ID, then
            // PosRot(onGround=false). Bedrock's own acknowledgement is its HANDLE_TELEPORT frame.
            player.packetStateData.bedrockServerResponse = true;
            return;
        }
        // The teleport ID acknowledgement interrupts a Rot -> MoveVehicle pair.
        player.packetStateData.clearPendingVehicleMoveAfterPassengerRotation();
        player.checkManager.dispatchDecodedReceiveObservers(event);
        player.checkManager.dispatchNonAsyncReceive(event);
    }

    @CultPacketHandler
    public void onMoveVehicle(
            PacketReceiveEvent<ServerboundMoveVehicle> event, CultPlayer player, ServerboundMoveVehicle packet) {
        processMoveVehicleReceive(event, player, packet);
    }

    @CultPacketHandler
    public void onPlayerInput(
            PacketReceiveEvent<ServerboundPlayerInput> event, CultPlayer player, ServerboundPlayerInput packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onPaddleBoat(
            PacketReceiveEvent<ServerboundPaddleBoat> event, CultPlayer player, ServerboundPaddleBoat packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onSetCreativeModeSlot(PacketReceiveEvent<CreativeSlot> event, CultPlayer player, CreativeSlot packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onContainerClick(PacketReceiveEvent<InventoryClick> event, CultPlayer player, InventoryClick packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onClientInformation(
            PacketReceiveEvent<ServerboundClientInformation> event,
            CultPlayer player,
            ServerboundClientInformation packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onUseItemOn(
            PacketReceiveEvent<ServerboundUseItemOn> event, CultPlayer player, ServerboundUseItemOn packet) {
        processUseItemOnReceive(event, player, packet);
    }

    @CultPacketHandler
    public void onPlayerAction(
            PacketReceiveEvent<ServerboundPlayerAction> event, CultPlayer player, ServerboundPlayerAction packet) {
        processPlayerActionReceive(event, player, packet);
    }

    @CultPacketHandler
    public void onUseItem(PacketReceiveEvent<ServerboundUseItem> event, CultPlayer player, ServerboundUseItem packet) {
        processUseItemReceive(event, player, packet);
    }

    @CultPacketHandler
    public void onCommandSuggestion(
            PacketReceiveEvent<ServerboundCommandSuggestion> event,
            CultPlayer player,
            ServerboundCommandSuggestion packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onChat(PacketReceiveEvent<ServerboundChat> event, CultPlayer player, ServerboundChat packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onChatCommand(
            PacketReceiveEvent<ServerboundChatCommand> event, CultPlayer player, ServerboundChatCommand packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onChatCommandSigned(
            PacketReceiveEvent<ServerboundChatCommandSigned> event,
            CultPlayer player,
            ServerboundChatCommandSigned packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onEditBook(
            PacketReceiveEvent<ServerboundEditBook> event, CultPlayer player, ServerboundEditBook packet) {
        processGenericReceive(event, player);
    }

    // Teleport acknowledgements are handled separately and must not clear the mounted-teleport latch.

    @CultPacketHandler
    public void onSpectatorAction(
            PacketReceiveEvent<ServerboundSpectatorAction> event,
            CultPlayer player,
            ServerboundSpectatorAction packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onRenameItem(
            PacketReceiveEvent<ServerboundRenameItem> event, CultPlayer player, ServerboundRenameItem packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onCustomPayload(
            PacketReceiveEvent<ServerboundCustomPayload> event, CultPlayer player, ServerboundCustomPayload packet) {
        if (event.isCancelled()) return;
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onSelectTrade(
            PacketReceiveEvent<ServerboundSelectTrade> event, CultPlayer player, ServerboundSelectTrade packet) {
        processGenericReceive(event, player);
    }

    // Before 1.21.4, vanilla picks an inventory slot through this packet.

    @CultPacketHandler
    public void onSelectBundleItem(
            PacketReceiveEvent<ServerboundSelectBundleItem> event,
            CultPlayer player,
            ServerboundSelectBundleItem packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onSetCarriedItem(
            PacketReceiveEvent<ServerboundSetCarriedItem> event, CultPlayer player, ServerboundSetCarriedItem packet) {
        processNonMovementReceiveAfterPlayGate(event, player);
    }

    @CultPacketHandler
    public void onInteract(
            PacketReceiveEvent<ServerboundInteract> event, CultPlayer player, ServerboundInteract packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onPlayerAbilities(
            PacketReceiveEvent<ServerboundPlayerAbilities> event,
            CultPlayer player,
            ServerboundPlayerAbilities packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onKeepAlive(
            PacketReceiveEvent<ServerboundKeepAlive> event, CultPlayer player, ServerboundKeepAlive packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onTeleportToEntity(
            PacketReceiveEvent<ServerboundTeleportToEntity> event,
            CultPlayer player,
            ServerboundTeleportToEntity packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onPlayerCommand(
            PacketReceiveEvent<ServerboundPlayerCommand> event, CultPlayer player, ServerboundPlayerCommand packet) {
        // PacketEntityAction runs immediately before this listener. Its rejected
        // glide starts used to terminate at the pre-Via boundary, so they must
        // not enter the ordinary check route. Keep every other ordinary-phase
        // cancellation on the existing same-phase semantics.
        if (event.isCancelled() && packet.action() == PlayerCommandAction.START_FLYING_WITH_ELYTRA) {
            return;
        }
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onClientCommand(
            PacketReceiveEvent<ServerboundClientCommand> event, CultPlayer player, ServerboundClientCommand packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onSwing(PacketReceiveEvent<ServerboundSwing> event, CultPlayer player, ServerboundSwing packet) {
        processGenericReceive(event, player);
    }

    private void processPlayerLoadedReceive(PacketReceiveEvent event, CultPlayer player) {
        if (event.getPhase() != ConnectionPhase.PLAY) return;
        // TODO: This packet is skipped if the client ticks more than 60 times without loading in
        player.packetStateData.playerLoadedIntoLevel = true;
        processNonMovementReceiveAfterPlayGate(event, player);
    }

    private void processGenericReceive(PacketReceiveEvent event, CultPlayer player) {
        if (event.getPhase() != ConnectionPhase.PLAY) return;
        processNonMovementReceiveAfterPlayGate(event, player);
    }

    public void processInterveningReceive(PacketReceiveEvent event, CultPlayer player) {
        if (event.getPhase() != ConnectionPhase.PLAY) return;
        clearPendingVehicleMoveForInterveningPacket(player);
        player.checkManager.dispatchDecodedReceiveObservers(event);
        player.checkManager.dispatchNonAsyncReceive(event);
    }

    private void processNonMovementReceiveAfterPlayGate(PacketReceiveEvent event, CultPlayer player) {
        clearPendingVehicleMoveForInterveningPacket(player);
        dispatchPrePredictionReceive(event, player);
        dispatchReceiveHandlers(event, player);
        clearTransientPacketState(player);
    }

    private void processMovePlayerReceive(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        if (event.getPhase() != ConnectionPhase.PLAY) return;
        if (player.isBedrockMovement() && player.packetStateData.bedrockServerResponse) {
            player.packetStateData.bedrockServerResponse = false;
            if (packet.hasPosition() && packet.hasRotation()) {
                // Geyser's teleport PosRot answers the server, not a Bedrock auth frame.
                event.setTeleportPositionResponse(
                        !player.user.getObservedProtocol().atLeast(ac.cult.cultac.protocol.ProtocolVersion.V26_3));
                if (packet.onGround() && !player.isDisabled() && !player.noModifyPacketPermission) {
                    event.replace(packet.withOnGround(false));
                }
                player.getSetbackTeleportUtil()
                        .setBedrockPaperVisiblePosition(new Vec3(packet.x(), packet.y(), packet.z()));
                return;
            }
        }

        if (player.isBedrockMovement()
                && packet.rotationOnly()
                && player.compensatedEntities.vehicles.hasPlayerPassengerState()) {
            // Geyser sends vehicle movement before rider rotation. Expire an
            // unused decision here if it omitted the vehicle move.
            if (player.packetStateData.bedrockTranslatedMovement.take()
                    == BedrockTranslatedMovementGate.Rejected.INSTANCE) {
                event.setCancelled(true);
                clearTransientPacketState(player);
                return;
            }
        }

        Vec3 position =
                VectorUtils.clampVector(new Vec3(packet.xOr(player.x), packet.yOr(player.y), packet.zOr(player.z)));

        if (player.isBedrockMovement()
                && player.compensatedEntities.vehicles.hasPlayerPassengerState()
                && !(packet.rotationOnly())) {
            // Mounted Bedrock movement may only project rotation here.
            event.setCancelled(true);
            player.getSetbackTeleportUtil().executeForceResync("bedrock mounted player movement");
            clearTransientPacketState(player);
            return;
        }

        if (shouldIgnoreTranslatedBedrockMovement(player, false)) {
            // Auth input owns movement; this projection still passes through the setback gate.
            player.checkManager.getListener(SetbackBlocker.class).onTranslatedBedrockMove(event, packet);
            dispatchReceiveHandlers(event, player);
            if (!event.isCancelled() && event.getPacket().hasPosition()) {
                var projected = event.getPacket();
                player.getSetbackTeleportUtil()
                        .setBedrockPaperVisiblePosition(
                                new Vec3(projected.xOr(player.x), projected.yOr(player.y), projected.zOr(player.z)));
            }
            clearTransientPacketState(player);
            return;
        }

        Vec3 movementPosition = position;
        TeleportAcceptData teleportData;
        if (packet.hasPosition()) {
            // Through 26.2 the intercepted wire carries an ID acknowledgment
            // followed by PosRot; 26.3 carries coordinates in the acknowledgment.
            boolean legacyJavaWire = !player.isBedrockMovement()
                    && !player.user
                            .getCultConnection()
                            .runtime()
                            .data()
                            .version()
                            .atLeast(ac.cult.cultac.protocol.ProtocolVersion.V26_3);
            teleportData = player.isBedrockMovement()
                            || legacyJavaWire
                            || player.getSetbackTeleportUtil().hasIdlessJavaPositionTeleport()
                    ? player.getSetbackTeleportUtil()
                            .checkTeleportQueue(movementPosition.x, movementPosition.y, movementPosition.z)
                    : new TeleportAcceptData();
        } else if (packet.rotationOnly()) {
            teleportData = player.getSetbackTeleportUtil()
                    .checkRotationTeleportQueue(packet.yawOr(player.xRot), packet.pitchOr(player.yRot));
        } else {
            teleportData = new TeleportAcceptData();
        }

        if (packet.hasPosition()
                && teleportData.isMatchedTeleportPosition()
                && teleportData.getTeleportData() != null
                && !player.isBedrockMovement()) {
            // Before 26.3 the PosRot following the teleport ID is itself a
            // server movement packet. Forward the accepted target, never the
            // client's near-match coordinates, including for relative teleports.
            movementPosition = teleportData.getTeleportData().getLocation();
            event.replace(packet.withPosition(movementPosition.x, movementPosition.y, movementPosition.z, false));
        }

        boolean mountedTeleportPosRot = packet.hasPosition()
                && teleportData.isTeleport()
                && !teleportData.isMatchedTeleportPosition()
                && !player.isBedrockMovement();
        // Pre-26.3 ClientPacketListener answers an ID-bearing teleport with a
        // separate PosRot. Via folds it into ACK, not an ordinary position move.
        // Keep this event fact after the transient prediction flags are cleared;
        // idless entity-to-self teleports still consume ordinary movement.
        event.setTeleportPositionResponse(packet.hasPosition()
                && packet.hasRotation()
                && teleportData.isTeleport()
                && teleportData.getTeleportData() != null
                && !teleportData.getTeleportData().isPositionOnly()
                && !player.isBedrockMovement()
                && !player.user.getObservedProtocol().atLeast(ac.cult.cultac.protocol.ProtocolVersion.V26_3));
        player.packetStateData.lastPacketWasTeleport = teleportData.isTeleport();
        player.packetStateData.lastPacketMatchedTeleportPosition = teleportData.isMatchedTeleportPosition();
        player.packetStateData.lastPacketWasOnePointSeventeenDuplicate = isOnePointSeventeenDuplicate(
                player.getClientVersion(),
                player.packetStateData.lastPacketWasTeleport,
                packet.hasPosition(),
                packet.hasRotation(),
                player.compensatedEntities.getSelf().inVehicle(),
                player.packetStateData.packetPlayerOnGround,
                packet.onGround(),
                player.packetStateData.clientSidePosition,
                movementPosition,
                player.getMovementThreshold());
        if (teleportData.isMatchedTeleportPosition()
                && teleportData.getTeleportData() != null
                && player.compensatedEntities.vehicles.hasPendingServerDismount()) {
            player.compensatedEntities.vehicles.applyClientVisibleDismount();
        }

        // LocalPlayer#tick emits Rot, never StatusOnly, while mounted.
        boolean passengerRotationTickPacket = packet.rotationOnly()
                && (player.compensatedEntities.getSelf().inVehicle()
                        || player.compensatedEntities.vehicles
                                .canCurrentPlayerControlServerVehicleForClientTickMovement()
                        || player.compensatedEntities.vehicles.hasPlayerPassengerState()
                        || hasBufferedServerVehiclePassengerRotation(player));
        if (!passengerRotationTickPacket && player.packetStateData.isAwaitingVehicleMoveAfterPassengerRotation()) {
            // LocalPlayer#tick sends the mounted Rot and vehicle move consecutively.
            player.packetStateData.clearPendingVehicleMoveAfterPassengerRotation();
        }
        if (passengerRotationTickPacket && !player.packetStateData.lastPacketWasTeleport) {
            player.packetStateData.markPassengerRotation();
        } else if (player.compensatedEntities.vehicles.serverPlayerVehicle != null
                || player.compensatedEntities.getSelf().inVehicle()) {
            // A position packet breaks the mounted Rot/MoveVehicle pair.
            player.packetStateData.clearPendingVehicleMoveAfterPassengerRotation();
        }

        // A teleport acknowledgement is not LocalPlayer#tick movement.
        if (!player.packetStateData.lastPacketWasTeleport) {
            player.packetStateData.receivedMovementThisClientTick = true;
        }

        dispatchPrePredictionReceive(event, player);

        // The player flagged crasher or timer checks, therefore we must protect predictions against these attacks
        // and not a teleport (it's dangerous to prevent teleports from going through simulation processors)
        if (event.isCancelled() && !player.packetStateData.lastPacketWasTeleport) {
            return;
        }

        if (mountedTeleportPosRot) {
            applyTeleportResponse(player, teleportData, packet.yawOr(player.xRot), packet.pitchOr(player.yRot));
            dispatchReceiveHandlers(event, player);
            clearTransientPacketState(player);
            return;
        }

        if (!shouldIgnoreTranslatedBedrockMovement(player, passengerRotationTickPacket)) {
            handleFlying(
                    player,
                    movementPosition.x,
                    movementPosition.y,
                    movementPosition.z,
                    packet.yawOr(player.yRot),
                    packet.pitchOr(player.xRot),
                    packet.hasPosition(),
                    packet.hasRotation(),
                    packet.onGround(),
                    teleportData);
        }

        // While a violation setback is pending, forward nothing but teleport confirms.
        // SetbackBlocker already cancels position packets in this window pre-prediction;
        // this also catches the packet that caused the setback (which arrives before the
        // setback is pending) and non-position packets the blocker never cancels.
        // Paper adopting any of these lets the setback echo register as an upward move
        // and wipe fallDistance (fall-overshoot-wipe bypass).
        if (player.getSetbackTeleportUtil().isPendingSetback() && !player.packetStateData.lastPacketWasTeleport) {
            event.setCancelled(true);
        }

        dispatchReceiveHandlers(event, player);
        clearTransientPacketState(player);
    }

    private boolean hasBufferedServerVehiclePassengerRotation(CultPlayer player) {
        Integer serverVehicleId = player.compensatedEntities.vehicles.serverPlayerVehicle;
        PacketEntity serverVehicle =
                serverVehicleId == null ? null : player.compensatedEntities.getEntity(serverVehicleId);
        return player.compensatedEntities.vehicles.isVehicleSwitchBufferActiveFor(serverVehicle)
                && player.compensatedEntities.vehicles.canClientAuthoritativelyMoveVisibleRoot(serverVehicle);
    }

    private boolean shouldIgnoreTranslatedBedrockMovement(CultPlayer player, boolean passengerRotationTickPacket) {
        // Bedrock's client-authored movement arrives through PlayerAuthInputPacket.
        // The Geyser-translated Java packet is a protocol projection and must not
        // become a second teleport-acceptance or simulation path.
        return player.isBedrockMovement()
                && !passengerRotationTickPacket
                && !player.compensatedEntities.vehicles.hasPlayerPassengerState();
    }

    private void processMoveVehicleReceive(
            PacketReceiveEvent<ServerboundMoveVehicle> event, CultPlayer player, ServerboundMoveVehicle packet) {
        if (event.getPhase() != ConnectionPhase.PLAY) return;

        ServerboundMoveVehicle vehiclePacket = packet;
        Vec3d position = vehiclePacket.position();
        Vec3 newPos = VectorUtils.clampVector(new Vec3(position.x(), position.y(), position.z()));
        if (player.isBedrockMovement()) {
            var controlledVehicle = BedrockVehicleControl.controlledVehicle(player);
            Integer serverVehicle = player.compensatedEntities.vehicles.serverPlayerVehicle;
            PacketEntity packetRoot = currentVehicleRoot(player, false);
            if (packetRoot == null && serverVehicle != null) {
                packetRoot = player.compensatedEntities.getEntity(serverVehicle);
            }
            Integer teleportVehicleId = packetRoot == null ? serverVehicle : Integer.valueOf(packetRoot.getEntityId());
            TeleportAcceptData teleportData = player.getSetbackTeleportUtil()
                    .checkVehicleTeleportQueue(teleportVehicleId, newPos.x, newPos.y, newPos.z);
            var movementGate = player.packetStateData.bedrockTranslatedMovement;
            if (!teleportData.isTeleport()
                    && (movementGate.isRejected()
                            || player.getSetbackTeleportUtil().blocksBedrockTranslatedMovement())) {
                // A rejection belongs to the auth frame's rider rotation; an
                // older grant expires when a correction starts blocking movement.
                if (!movementGate.isRejected()) movementGate.clear();
                event.setCancelled(true);
                clearTransientPacketState(player);
                return;
            }
            var decision = movementGate.take();
            boolean authorizedVehicleMove = decision instanceof BedrockTranslatedMovementGate.Vehicle permit
                    && controlledVehicle != null
                    && serverVehicle != null
                    && serverVehicle == controlledVehicle.getEntityId()
                    && permit.entityId() == controlledVehicle.getEntityId();
            if (!teleportData.isTeleport() && !authorizedVehicleMove) {
                event.setCancelled(true);
                clearTransientPacketState(player);
                return;
            }
            boolean hasCurrentServerPassengerAuthority = serverVehicle != null
                    && player.compensatedEntities.vehicles.isServerPlayerPassengerOf(serverVehicle);
            boolean blocked = authorizedVehicleMove
                    ? player.getSetbackTeleportUtil().shouldBlockVehicleMovement(true)
                            || controlledVehicle.isDead
                            || player.isInBed
                    : shouldBlockVehicleMovementForSetback(player, teleportData);
            if (!teleportData.isTeleport() && (!hasCurrentServerPassengerAuthority || blocked)) {
                event.setCancelled(true);
            } else if (teleportData.isTeleport() && packetRoot != null && controlledVehicle == null) {
                applyAcceptedVehicleTeleportState(player, packetRoot, newPos, vehiclePacket, teleportData);
            }
            // Geyser emits MoveVehicle both from client-predicted auth input
            // and from its independent session vehicle tick. Neither path is
            // Java client movement, so never route it into Java simulation.
            clearTransientPacketState(player);
            return;
        }

        boolean pairedRiddenTick = player.packetStateData.isAwaitingVehicleMoveAfterPassengerRotation();
        boolean fromClientTick = pairedRiddenTick
                || (player.packetStateData.hasPassengerRotationThisClientTick()
                        && player.packetStateData.clientTickVehicleMovePacketsThisClientTick == 0);
        PacketEntity packetRoot = currentVehicleRoot(player, fromClientTick);
        // LocalPlayer#tick sends Rot -> MoveVehicle only while this client
        // controls the root. For pigs/striders that observes the REAL held item,
        // unlike our carried-slot state, which can lag behind handleKeybinds.
        // A packet-handler correction echo has no preceding tick Rot. Do not
        // use the buffered packet fallback below as evidence of a ridden tick.
        PacketEntity riddenRoot =
                packetRoot == null ? player.compensatedEntities.vehicles.getVelocityMovementVehicle() : packetRoot;
        if (pairedRiddenTick
                && riddenRoot instanceof PacketEntityRideable rideable
                && !rideable.isDead
                && rideable.hasSaddle
                && player.compensatedEntities.vehicles.passengerIndex(rideable) == 0) {
            // Count even if a real tick happens to match a pending teleport's
            // position, or its prediction subsequently requires a correction.
            rideable.boost.tick(player.packetStateData.acceptedClientTick);
        }
        if (!fromClientTick && player.packetStateData.clientTickVehicleMovePacketsThisClientTick == 0) {
            PacketEntity bufferedRoot = bufferedLocalAuthoritativeRoot(player, packetRoot);
            if (bufferedRoot != null) {
                fromClientTick = true;
                packetRoot = bufferedRoot;
            }
        }

        boolean vehicleSwitchBufferPacket = fromClientTick
                && player.compensatedEntities.vehicles.canOpenVehicleSwitchBufferForMovementPacket(packetRoot);
        if (vehicleSwitchBufferPacket) {
            // Minecraft#tick sends carried-slot changes before keybind handling,
            // then LocalPlayer#tick may move with a newly visible vehicle-control
            // mode. Keep the packet on the prediction path and let the bounded
            // vehicle-switch buffer decide how much uncertainty can be absorbed.
            player.compensatedEntities.vehicles.markVehicleSwitchMovementPacketBoundary();
        }

        boolean canAuthoritativelyMove =
                player.compensatedEntities.vehicles.canServerPlayerVehicleBeLocalAuthoritativeForMovementPacket(
                                fromClientTick)
                        || (fromClientTick
                                && player.compensatedEntities.vehicles.isVehicleSwitchBufferActiveFor(packetRoot));
        TeleportAcceptData teleportData = player.getSetbackTeleportUtil()
                .checkVehicleTeleportQueue(
                        packetRoot == null ? null : packetRoot.getEntityId(), newPos.x, newPos.y, newPos.z);
        boolean checkableClientTick =
                fromClientTick && packetRoot != null && canAuthoritativelyMove && !teleportData.isTeleport();

        if (!teleportData.isTeleport() && !checkableClientTick) {
            player.packetStateData.lastPacketWasTeleport = false;
            player.packetStateData.lastPacketMatchedTeleportPosition = false;
            // VehicleTimer historically observed every decoded movement before
            // vehicle-authority rejection short-circuited the packet.
            // This direct callback bypasses CheckManager's Bedrock support gate.
            if (!player.isBedrockMovement()) {
                player.checkManager.getCheck(VehicleTimer.class).onMoveVehicle(event, player, packet);
            }
            event.setCancelled(true);
            player.packetStateData.markVehicleMove(fromClientTick, false);
            if (!shouldBlockVehicleMovementForSetback(player, teleportData)) {
                player.compensatedEntities.vehicles.forceResyncVehicleSwitchBuffer(
                        packetRoot, "vehicle-switch-buffer-invalid-packet");
            }
            if (!shouldBlockVehicleMovementForSetback(player, teleportData) || !fromClientTick) {
                player.checkManager.getCheck(BadPacketsVehicle.class).handleInvalidVehiclePacket(fromClientTick);
            }
            if (fromClientTick) {
                player.packetStateData.clientTickVehicleMovePacketsThisClientTick++;
                player.packetStateData.receivedMovementThisClientTick = true;
            }
            clearTransientPacketState(player);
            return;
        }

        player.packetStateData.lastPacketWasTeleport = teleportData.isTeleport();
        player.packetStateData.lastPacketMatchedTeleportPosition = false;

        // MCP-Reborn LocalPlayer#tick sends passenger Rot immediately before
        // ServerboundMoveVehiclePacket. ClientPacketListener#handleMoveVehicle
        // sends only the vehicle packet from the packet handler, so it must
        // snap state without advancing AbstractBoat#tick state.
        player.packetStateData.markVehicleMove(fromClientTick, checkableClientTick);
        if (fromClientTick) {
            player.packetStateData.clientTickVehicleMovePacketsThisClientTick++;
        }

        if (fromClientTick && !player.packetStateData.lastPacketWasTeleport) {
            player.packetStateData.receivedMovementThisClientTick = true;
        }

        dispatchPrePredictionReceive(event, player);
        // 1.21.2/1.21.3 MoveVehicle has no on-ground field. ViaBackwards
        // 1.21.4 -> 1.21.2 appends literal true when upgrading that packet,
        // so the observed codec's field presence alone is not client evidence.
        // Keep the raw packet intact and use collision-derived ground carry
        // unless both the original client and observed layout report the bit.
        boolean clientReportsOnGround =
                vehiclePacket.hasOnGround() && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_4);
        player.packetStateData.vehicleMovementStartOnGround =
                packetRoot == null ? vehiclePacket.onGround() : packetRoot.onGround;
        player.packetStateData.vehicleMovementOnGroundPresent = clientReportsOnGround;
        Vec3 oldPosition = currentVehiclePhysicalPosition(player, packetRoot, new Vec3(player.x, player.y, player.z));
        if (shouldTickControlledBoatForClientVehiclePacket(packetRoot, checkableClientTick)) {
            player.boatData.tick(player);
        }
        Vec3 physicalMovementStart = oldPosition;
        float physicalYaw = vehiclePacket.yaw();
        float physicalPitch = vehiclePacket.pitch();
        Vec3 physicalNewPos = newPos;
        player.packetStateData.lastPacketProvenVehiclePhysicalMovement =
                checkableClientTick ? physicalNewPos.subtract(oldPosition) : null;
        // The player flagged crasher or timer checks, therefore we must protect predictions against these attacks
        // and not a teleport (it's dangerous to prevent teleports from going through simulation processors)
        if (event.isCancelled() && !player.packetStateData.lastPacketWasTeleport) {
            return;
        }

        if (packetRoot != null) {
            double previousLastX = player.lastX;
            double previousLastY = player.lastY;
            double previousLastZ = player.lastZ;
            double previousX = player.x;
            double previousY = player.y;
            double previousZ = player.z;
            player.lastX = player.x;
            player.lastY = player.y;
            player.lastZ = player.z;

            player.x = physicalNewPos.x;
            player.y = physicalNewPos.y;
            player.z = physicalNewPos.z;

            // Protocols through 1.21.3 do not encode an on-ground bit in
            // ServerboundMoveVehiclePacket. Preserve that absence instead of
            // fabricating false; the simulation can still prove the resulting
            // ground state from the movement collision.
            boolean vehicleOnGround = clientReportsOnGround
                    ? vehiclePacket.onGround()
                    : player.packetStateData.vehicleMovementStartOnGround;
            final VehiclePositionUpdate update = new VehiclePositionUpdate(
                    physicalMovementStart,
                    physicalNewPos,
                    physicalYaw,
                    physicalPitch,
                    vehicleOnGround,
                    clientReportsOnGround,
                    teleportData);
            if (checkableClientTick) {
                // MCP-Reborn ClientPacketListener#handleMoveVehicle/#handleTeleportEntity can echo
                // ServerboundMoveVehiclePacket directly from the packet handler. That packet
                // acknowledges server-authored state; it is not a LocalPlayer#tick vehicle physics result.
                player.checkManager.onVehiclePositionUpdate(update);
                if (shouldBlockVehicleMovementForSetback(player, teleportData)) {
                    event.setCancelled(true);
                    player.lastX = previousLastX;
                    player.lastY = previousLastY;
                    player.lastZ = previousLastZ;
                    player.x = previousX;
                    player.y = previousY;
                    player.z = previousZ;
                    clearTransientPacketState(player);
                    return;
                }
            }

            if (teleportData.isTeleport()) {
                applyAcceptedVehicleTeleportState(player, packetRoot, physicalNewPos, vehiclePacket, teleportData);
            } else if (checkableClientTick) {
                player.compensatedEntities.vehicles.applyVehiclePacketPosition(
                        packetRoot,
                        physicalNewPos,
                        clientReportsOnGround ? vehiclePacket.onGround() : packetRoot.onGround,
                        physicalYaw,
                        physicalPitch);
            }
        }

        dispatchReceiveHandlers(event, player);
        clearTransientPacketState(player);
    }

    private void applyAcceptedVehicleTeleportState(
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
            player.compensatedEntities.vehicles.applyVehiclePacketPosition(
                    packetRoot,
                    position,
                    vehiclePacket.hasOnGround() ? vehiclePacket.onGround() : packetRoot.onGround,
                    vehiclePacket.yaw(),
                    vehiclePacket.pitch());
        }
    }

    private boolean shouldTickControlledBoatForClientVehiclePacket(PacketEntity root, boolean checkableClientTick) {
        return root != null && root.isBoat() && checkableClientTick;
    }

    private boolean shouldBlockVehicleMovementForSetback(CultPlayer player, TeleportAcceptData teleportData) {
        return !teleportData.isTeleport()
                && (player.getSetbackTeleportUtil().hasUnacknowledgedSetbackVehicleTeleport()
                        || player.getSetbackTeleportUtil().shouldBlockVehicleMovement());
    }

    private Vec3 currentVehiclePhysicalPosition(CultPlayer player, PacketEntity root, Vec3 fallback) {
        Vec3 physical = root == null
                ? null
                : root.clientPhysicalPosition == null ? root.desyncClientPos : root.clientPhysicalPosition;
        return physical == null ? fallback : physical;
    }

    private PacketEntity currentVehicleRoot(CultPlayer player, boolean fromClientTick) {
        PacketEntity root = player.compensatedEntities.getSelf().getRiding();
        if (root != null) {
            return root;
        }

        Integer serverVehicleId = player.compensatedEntities.vehicles.serverPlayerVehicle;
        PacketEntity serverVehicle =
                serverVehicleId == null ? null : player.compensatedEntities.getEntity(serverVehicleId);
        boolean sameTickVehicleControlMayBeClientVisible = fromClientTick
                && player.compensatedEntities.vehicles.canOpenVehicleSwitchBufferForMovementPacket(serverVehicle);
        boolean vehicleSwitchBufferMayCover =
                fromClientTick && player.compensatedEntities.vehicles.isVehicleSwitchBufferActiveFor(serverVehicle);
        return serverVehicle != null
                        && (player.compensatedEntities.vehicles
                                        .canServerPlayerVehicleBeLocalAuthoritativeForMovementPacket(fromClientTick)
                                || sameTickVehicleControlMayBeClientVisible
                                || vehicleSwitchBufferMayCover)
                ? serverVehicle
                : null;
    }

    @Nullable
    private PacketEntity bufferedLocalAuthoritativeRoot(CultPlayer player, @Nullable PacketEntity packetRoot) {
        if (isBufferedLocalAuthoritativeRoot(player, packetRoot)) {
            return packetRoot;
        }

        Integer serverVehicleId = player.compensatedEntities.vehicles.serverPlayerVehicle;
        PacketEntity serverVehicle =
                serverVehicleId == null ? null : player.compensatedEntities.getEntity(serverVehicleId);
        return isBufferedLocalAuthoritativeRoot(player, serverVehicle) ? serverVehicle : null;
    }

    private boolean isBufferedLocalAuthoritativeRoot(CultPlayer player, @Nullable PacketEntity root) {
        return player.compensatedEntities.vehicles.isVehicleSwitchBufferActiveFor(root)
                && player.compensatedEntities.vehicles.canClientAuthoritativelyMoveVisibleRoot(root);
    }

    private void processPlayerActionReceive(
            PacketReceiveEvent<ServerboundPlayerAction> event, CultPlayer player, ServerboundPlayerAction packet) {
        if (event.getPhase() != ConnectionPhase.PLAY) return;
        PlayerAction action = packet.action();
        if (action == PlayerAction.START_DESTROY_BLOCK || action == PlayerAction.STOP_DESTROY_BLOCK) {
            player.compensatedWorld.advanceClientPredictionSequence();
        }
        clearPendingVehicleMoveForInterveningPacket(player);
        dispatchPrePredictionReceive(event, player);

        BlockPos blockPosition = packet.position();
        int block = player.compensatedWorld.getBlockStateIdAt(blockPosition);

        if (player.debugBreaks && isBlockBreakAction(action)) {
            player.sendMessage("Break: action=" + action + " state="
                    + DataTables.defaults().registry().serialize(block) + " at "
                    + blockPosition);
        }

        if (action == PlayerAction.STOP_DESTROY_BLOCK) {
            // Not unbreakable
            if (DataTables.defaults().registry().facts(block).destroyTime() != -1.0f) {
                player.compensatedWorld.startPredicting();
                applyClientBreakPrediction(player, blockPosition);
                player.checkManager.getCheck(AirLiquidPlace.class).handleBlockBreak(blockPosition.immutable());
                player.compensatedWorld.stopPredicting(packet.sequence());
            }
        }

        if (action == PlayerAction.START_DESTROY_BLOCK) {
            double damage = BlockBreakSpeed.getBlockDamage(player, blockPosition);

            // Instant breaking, no damage means it is unbreakable by creative players (with swords)
            if (damage >= 1) {
                player.compensatedWorld.startPredicting();
                player.checkManager.getListener(AirLiquidPlace.class).handleBlockBreak(blockPosition.immutable());
                applyClientBreakPrediction(player, blockPosition);
                player.compensatedWorld.stopPredicting(packet.sequence());
            } else if (player.debugBreaks) {
                player.sendMessage("Break start has no immediate world change: damage=" + damage);
            }
        }

        dispatchReceiveHandlers(event, player);
        clearTransientPacketState(player);
    }

    private void processUseItemOnReceive(
            PacketReceiveEvent<ServerboundUseItemOn> event, CultPlayer player, ServerboundUseItemOn packet) {
        if (event.getPhase() != ConnectionPhase.PLAY) return;
        player.compensatedWorld.advanceClientPredictionSequence();
        clearPendingVehicleMoveForInterveningPacket(player);
        dispatchPrePredictionReceive(event, player);

        BlockPos clickedBlock = packet.blockPosition();
        Vec3 cursor = SpigotConversionUtil.fromProtocolVec(packet.cursor());
        Direction blockFace = packet.blockFace();
        Hand hand = packet.hand();
        player.lastBlockPlaceUseItem = System.currentTimeMillis();

        SimItemStack placedWith = player.getInventory().getHandItem(hand);

        BlockPlace blockPlace = new BlockPlace(
                player,
                hand,
                clickedBlock,
                blockFace,
                placedWith,
                TraverseBlocks.getNearestHitResult(player, true),
                packet.sequence());
        blockPlace.setCursor(cursor);

        // Deny fun stuff like teleport bridging
        BlockPos placedAgainst = blockPlace.getPlacedAgainstBlockLocation();
        final GhostBlockMitigator ghostBlockMitigator = player.getGhostBlockMitigator();

        final boolean desyncPos = ghostBlockMitigator.isDesyncPos(placedAgainst);
        if (player.debugPlaces) {
            player.sendMessage("Desync pos: " + desyncPos);
        }

        // Should we call the anticheat placing checks?
        if (!desyncPos
                && (blockPlace.isBlock()
                        || placedWith.getItem() == ac.cult.cultac.utils.inventory.ItemTypes.FIRE_CHARGE
                        || placedWith.getItem() == ac.cult.cultac.utils.inventory.ItemTypes.END_CRYSTAL)
                && !player.compensatedEntities.getSelf().inVehicle()) {
            player.checkManager.onBlockPlace(blockPlace);
            player.checkManager.queuePostFlyingBlockPlace(blockPlace);
        }
        // Mark the two potentially changed blocks as ghost blocks
        ghostBlockMitigator.handlePseudoPlace(new GhostBlock(placedAgainst, placedWith, blockPlace.isBlock()));
        final ac.cult.cultac.utils.latency.CompensatedWorld compensatedWorld = player.compensatedWorld;
        compensatedWorld.markForBlockPrediction(placedAgainst);

        BlockPos normal = blockPlace.getNormalBlockFace();
        BlockPos relative = placedAgainst.offset(normal.getX(), normal.getY(), normal.getZ());
        ghostBlockMitigator.handlePseudoPlace(new GhostBlock(relative, placedWith, blockPlace.isBlock()));
        compensatedWorld.markForBlockPrediction(relative);

        // The player tried placing blocks in air/water or failed other checks
        // Or the player tried playing blocks while pending a teleport
        if (blockPlace.isCancelled()
                || event.isCancelled()
                || player.getSetbackTeleportUtil().isPendingSetback()) {
            // Invalid block place due to placing on a ghost block
            blockPlace.resync();

            if (!event.isCancelled()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }

            BlockPredictionAckSender.sendAck(player, packet.sequence());

            // Stop inventory desync from cancelling place
            if (player.platformPlayer != null) {
                // TODO: Is this unsafe enough to have to run on the main thread?
                if (hand == Hand.MAIN_HAND) {
                    SimItemStack mainHand =
                            ItemUtil.copy(player.platformPlayer.getInventory().getMainHand());
                    player.user.write(new Slot(
                            0, player.getInventory().stateID, 36 + player.packetStateData.lastSlotSelected, mainHand));
                } else {
                    SimItemStack offHand =
                            ItemUtil.copy(player.platformPlayer.getInventory().getOffHand());
                    player.user.write(new Slot(0, player.getInventory().stateID, 45, offHand));
                }
            }

        } else { // Legit place
            PlaceHandler.handleQueuedUseItemOn(player, packet);
        }

        dispatchReceiveHandlers(event, player);
        clearTransientPacketState(player);
    }

    private void processUseItemReceive(
            PacketReceiveEvent<ServerboundUseItem> event, CultPlayer player, ServerboundUseItem packet) {
        if (event.getPhase() != ConnectionPhase.PLAY) return;
        player.compensatedWorld.advanceClientPredictionSequence();
        clearPendingVehicleMoveForInterveningPacket(player);
        dispatchPrePredictionReceive(event, player);

        PlaceHandler.handleQueuedUseItem(player, packet);
        player.lastBlockPlaceUseItem = System.currentTimeMillis();

        dispatchReceiveHandlers(event, player);
        clearTransientPacketState(player);
    }

    public void processClientTickEndReceive(PacketReceiveEvent event, CultPlayer player) {
        if (event.getPhase() != ConnectionPhase.PLAY) return;
        // The Geyser bridge ends each Bedrock client tick after translating its frame. Geyser's
        // tick end here only closes that frame's Java projection.
        if (player.user.getBedrockBridgeConnection() == null) {
            dispatchPrePredictionReceive(event, player);
            dispatchReceiveHandlers(event, player);

            player.packetStateData.acceptedClientTick++;
            player.packetStateData.lastClientTickEndTransaction = player.lastTransactionReceived.get();
            player.serverOpenedInventoryThisTick = false;
            player.packetStateData.carriedItemChangedThisClientTick = false;
        }
        player.packetStateData.receivedMovementThisClientTick = false;
        player.packetStateData.bedrockTranslatedMovement.clear();
        player.packetStateData.clientMovementInputUpdatedThisClientTick = false;
        player.packetStateData.vehicleMovePacketsThisClientTick = 0;
        player.packetStateData.clientTickVehicleMovePacketsThisClientTick = 0;
        player.packetStateData.localAuthoritativeVehicleMovePacketsThisClientTick = 0;
        player.packetStateData.clearMountedMovementTickState();
        if (player.packetStateData.serverFrozenTickStepsRemaining > 0) {
            player.packetStateData.serverFrozenTickStepsRemaining--;
        }

        clearTransientPacketState(player);
    }

    private static void clearPendingVehicleMoveForInterveningPacket(CultPlayer player) {
        if (player.packetStateData.isAwaitingVehicleMoveAfterPassengerRotation()) {
            // LocalPlayer#tick sends the mounted Rot and vehicle move consecutively.
            player.packetStateData.clearPendingVehicleMoveAfterPassengerRotation();
        }
    }

    private void dispatchPrePredictionReceive(PacketReceiveEvent event, CultPlayer player) {
        player.checkManager.dispatchPrePredictionReceive(event);
    }

    private void dispatchReceiveHandlers(PacketReceiveEvent event, CultPlayer player) {
        // Call the packet checks last as they can modify the contents of the packet
        // Such as the NoFall check setting the player to not be on the ground
        player.checkManager.dispatchReceiveHandlers(event);
    }

    private static void clearTransientPacketState(CultPlayer player) {
        // Finally, remove the packet state variables on this packet
        player.packetStateData.lastPacketWasTeleport = false;
        player.packetStateData.lastPacketWasOnePointSeventeenDuplicate = false;
        player.packetStateData.lastPacketMatchedTeleportPosition = false;
        player.packetStateData.lastPacketProvenVehiclePhysicalMovement = null;
        player.packetStateData.clearDesiredOnGround();
    }

    /** Applies a transaction-matched teleport response without producing a movement tick. */
    public static void applyTeleportResponse(CultPlayer player, TeleportAcceptData accepted, float yaw, float pitch) {
        Vec3 previous = new Vec3(player.x, player.y, player.z);
        applyTeleportAcknowledgementLook(player, yaw, pitch, accepted);
        if (accepted.isMatchedTeleportPosition()) {
            if (player.compensatedEntities.vehicles.hasPendingServerDismount()) {
                player.compensatedEntities.vehicles.applyClientVisibleDismount();
            }
            player.checkManager.getSimulationProcessor().applyAcceptedJavaTeleport(accepted);
        }
        player.getSetbackTeleportUtil()
                .onPredictionComplete(new PredictionComplete(new PositionUpdate(
                        previous,
                        new Vec3(player.x, player.y, player.z),
                        yaw,
                        pitch,
                        player.onGround,
                        accepted,
                        null)));
    }

    /** 26.3's coordinate-bearing acknowledgement carries the old teleport PosRot's look state. */
    public static void applyTeleportAcknowledgementLook(CultPlayer player, float yaw, float pitch) {
        applyTeleportAcknowledgementLook(player, yaw, pitch, null);
    }

    private static void applyTeleportAcknowledgementLook(
            CultPlayer player, float yaw, float pitch, TeleportAcceptData accepted) {
        boolean previous = player.packetStateData.lastPacketWasTeleport;
        player.packetStateData.lastPacketWasTeleport = true;
        try {
            if (player.xRot != yaw || player.yRot != pitch) {
                player.lastTickXRot = player.xRot;
                player.lastTickYRot = player.yRot;
            }
            applyRotation(player, yaw, pitch, accepted);
        } finally {
            player.packetStateData.lastPacketWasTeleport = previous;
        }
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
            return new HeadRotation(sent.getFinalYaw(), sent.getFinalPitch());
        }
        return new HeadRotation(previousYaw, previousPitch);
    }

    private static void applyRotation(CultPlayer player, float yaw, float pitch, TeleportAcceptData accepted) {
        boolean changed = player.xRot != yaw || player.yRot != pitch;
        HeadRotation from =
                rotationOrigin(player.xRot, player.yRot, yaw, pitch, player.isBedrockMovement() ? null : accepted);
        player.xRot = yaw;
        player.yRot = pitch;
        RotationUpdate update = new RotationUpdate(
                from, new HeadRotation(player.xRot, player.yRot), player.xRot - from.yaw(), player.yRot - from.pitch());
        if (changed) {
            player.lastRotated = System.currentTimeMillis();
        }
        player.checkManager.onRotationUpdate(update);
    }

    private void handleFlying(
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
        player.serverOpenedInventoryThisTick = false;
        if (hasLook && (player.xRot != yaw || player.yRot != pitch)) {
            player.lastTickXRot = player.xRot;
            player.lastTickYRot = player.yRot;
        }

        // We can set the new pos after the places
        if (hasPosition && !player.packetStateData.lastPacketWasOnePointSeventeenDuplicate) {
            player.packetStateData.clientSidePosition = VectorUtils.clampVector(new Vec3(x, y, z));
            final ac.cult.cultac.manager.player.MovementData movementData = player.getMovementData();
            movementData.handle(x, y, z);
        }

        if (!player.packetStateData.lastPacketWasTeleport
                && !player.packetStateData.lastPacketWasOnePointSeventeenDuplicate
                && !player.packetStateData.lastPacketMatchedTeleportPosition) {
            player.packetStateData.packetPlayerOnGround = onGround;
        }

        if (hasLook) {
            applyRotation(player, yaw, pitch, teleportData);
        }

        player.checkManager.doChecksWithKnownLook();

        if (hasPosition) {
            Vec3 position = new Vec3(x, y, z);
            Vec3 clampVector = VectorUtils.clampVector(position);
            final PositionUpdate update = new PositionUpdate(
                    new Vec3(player.x, player.y, player.z), position, player.xRot, player.yRot, onGround, teleportData);

            final ac.cult.cultac.utils.data.packetentity.PacketEntitySelf selfEntity =
                    player.compensatedEntities.getSelf();
            if (!selfEntity.inVehicle() && !player.packetStateData.lastPacketWasOnePointSeventeenDuplicate) {
                player.lastX = player.x;
                player.lastY = player.y;
                player.lastZ = player.z;

                player.x = clampVector.x;
                player.y = clampVector.y;
                player.z = clampVector.z;

                player.checkManager.onPositionUpdate(update);
            } else if (update.getTeleportData()
                    .isTeleport()) { // Mojang doesn't use their own exit vehicle field to leave vehicles, manually call
                // the setback handler
                final PredictionComplete completion = new PredictionComplete(update);
                player.getSetbackTeleportUtil().onPredictionComplete(completion);
            }
        }

        // Teleport acknowledgements do not consume per-movement state.
        if (!teleportData.isTeleport()) {
            player.vehicleData.wasVehicleSwitch = false;
            player.packetStateData.horseInteractCausedForcedRotation = false;
        }
    }

    static boolean isOnePointSeventeenDuplicate(
            ClientVersion clientVersion,
            boolean teleport,
            boolean hasPosition,
            boolean hasRotation,
            boolean inVehicle,
            boolean previousOnGround,
            boolean packetOnGround,
            Vec3 previousPosition,
            Vec3 packetPosition,
            double movementThreshold) {
        if (teleport
                || clientVersion.isOlderThan(ClientVersion.V_1_17)
                || clientVersion.isNewerThanOrEquals(ClientVersion.V_1_21)
                || !hasPosition
                || !hasRotation) {
            return false;
        }

        if (inVehicle) {
            return true;
        }

        return previousOnGround == packetOnGround
                && previousPosition.distanceToSqr(packetPosition) < movementThreshold * movementThreshold;
    }

    private static void applyClientBreakPrediction(CultPlayer player, BlockPos blockPosition) {
        ClientBlockActions.breakBlock(player, blockPosition);
    }

    private static boolean isBlockBreakAction(PlayerAction action) {
        return action == PlayerAction.START_DESTROY_BLOCK
                || action == PlayerAction.STOP_DESTROY_BLOCK
                || action == PlayerAction.ABORT_DESTROY_BLOCK;
    }

    private static void dispatchPlaySend(PacketSendEvent<?> event, CultPlayer player) {
        if (event.getPhase() != ConnectionPhase.PLAY) {
            return;
        }

        player.checkManager.dispatchSendHandlers(event);
    }

    private static final class CheckManagerEarlyReceiveForwarder implements PacketReceiveHandler<Object> {
        @Override
        public void handle(PacketReceiveEvent event, CultPlayer player, Object packet) {
            if (event.getPhase() == ConnectionPhase.PLAY) {
                player.checkManager.dispatchEarlyReceive(event);
            }
        }
    }

    private static final class CheckManagerSendForwarder implements PacketSendHandler<Object> {
        @Override
        public void handle(PacketSendEvent<Object> event, CultPlayer player, Object packet) {
            dispatchPlaySend(event, player);
        }
    }
}
