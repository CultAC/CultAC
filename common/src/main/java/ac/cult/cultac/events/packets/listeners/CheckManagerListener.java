package ac.cult.cultac.events.packets.listeners;

import ac.cult.cultac.checks.impl.badpackets.BadPacketsVehicle;
import ac.cult.cultac.checks.impl.movement.GhostBlockMitigator;
import ac.cult.cultac.checks.impl.movement.SetbackBlocker;
import ac.cult.cultac.checks.impl.movement.timer.VehicleTimer;
import ac.cult.cultac.checks.impl.scaffolding.AirLiquidPlace;
import ac.cult.cultac.events.packets.blockplace.PlaceHandler;
import ac.cult.cultac.manager.player.CheckManager;
import ac.cult.cultac.network.CultPacketGroup;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.PacketGroup;
import ac.cult.cultac.network.PacketRegistrar;
import ac.cult.cultac.network.PacketHandlerScanner;
import ac.cult.cultac.network.PacketReceiveHandler;
import ac.cult.cultac.network.PacketSendHandler;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;
import ac.cult.cultac.utils.anticheat.update.PositionUpdate;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.anticheat.update.RotationUpdate;
import ac.cult.cultac.utils.anticheat.update.VehiclePositionUpdate;
import ac.cult.cultac.utils.blockplace.GhostBlock;
import ac.cult.cultac.utils.blockplace.NmsBlockBreakResolver;
import ac.cult.cultac.utils.data.TeleportAcceptData;
import ac.cult.cultac.utils.data.HeadRotation;
import ac.cult.cultac.utils.data.VehicleTeleportData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityRideable;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.VectorUtils;
import ac.cult.cultac.utils.nmsutil.BlockBreakSpeed;
import ac.cult.cultac.utils.nmsutil.NmsBlockTags;
import ac.cult.cultac.utils.nmsutil.TraverseBlocks;
import ac.cult.cultac.utils.latency.BlockPredictionAckSender;
import ac.cult.cultac.network.protocol.util.SpigotConversionUtil;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.packet.NmsPacketUtil;
import ac.cult.cultac.network.packet.SwingPacketUtil;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket;
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket;
import net.minecraft.network.protocol.game.*;
import org.bukkit.inventory.ItemStack;
import net.minecraft.world.InteractionHand;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.network.ConnectionProtocol;

import java.util.List;

public class CheckManagerListener {
    private static final PacketReceiveHandler<Packet<?>> EARLY_RECEIVE_FORWARDER =
            new CheckManagerEarlyReceiveForwarder();
    private static final PacketSendHandler<Packet<?>> SEND_FORWARDER = new CheckManagerSendForwarder();

    public static List<Class<? extends Packet<?>>> receiveDispatchPacketTypes() {
        return PacketHandlerScanner.receivePacketTypes(CheckManagerListener.class);
    }

    public void registerForwardingEarlyReceivePackets(PacketRegistrar registrar, PacketListenerPriority priority) {
        for (Class<? extends Packet<?>> packetType : receiveDispatchPacketTypes()) {
            registerForwardingEarlyReceivePacket(registrar, priority, packetType);
        }
    }

    private <T extends Packet<?>> void registerForwardingEarlyReceivePacket(
            PacketRegistrar registrar,
            PacketListenerPriority priority,
            Class<T> packetType
    ) {
        registrar.earlyReceive(packetType, priority, EARLY_RECEIVE_FORWARDER);
    }

    public void registerForwardingSendPackets(PacketRegistrar registrar, PacketListenerPriority priority) {
        for (Class<? extends Packet<?>> packetType : CheckManager.sendDispatchPacketTypes()) {
            registerForwardingSendPacket(registrar, priority, packetType);
        }
    }

    private <T extends Packet<?>> void registerForwardingSendPacket(
            PacketRegistrar registrar,
            PacketListenerPriority priority,
            Class<T> packetType
    ) {
        registrar.send(packetType, priority, SEND_FORWARDER);
    }

    @CultPacketHandler
    @CultPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, CultPlayer player, ServerboundMovePlayerPacket packet) {
        processMovePlayerReceive(event, player, packet);
    }

    @CultPacketHandler
    public void onPong(PacketReceiveEvent event, CultPlayer player, ServerboundPongPacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onAcceptTeleportation(PacketReceiveEvent event, CultPlayer player, ServerboundAcceptTeleportationPacket packet) {
        if (event.getConnectionState() != ConnectionProtocol.PLAY) return;
        // PacketServerTeleport already consumed the real ID and armed its paired
        // PosRot. This packet interrupts Rot -> MoveVehicle, but not its own echo.
        player.packetStateData.clearPendingVehicleMoveAfterPassengerRotation();
        player.checkManager.dispatchDecodedReceiveObservers(event);
        player.checkManager.dispatchOrderedReceive(event);
    }

    @CultPacketHandler
    public void onChunkBatchReceived(PacketReceiveEvent event, CultPlayer player, ServerboundChunkBatchReceivedPacket packet) {
        processInterveningReceive(event, player);
    }

    @CultPacketHandler
    public void onCookieResponse(PacketReceiveEvent event, CultPlayer player, ServerboundCookieResponsePacket packet) {
        processInterveningReceive(event, player);
    }

    @CultPacketHandler
    public void onPingRequest(PacketReceiveEvent event, CultPlayer player, ServerboundPingRequestPacket packet) {
        processInterveningReceive(event, player);
    }

    @CultPacketHandler
    public void onResourcePack(PacketReceiveEvent event, CultPlayer player, ServerboundResourcePackPacket packet) {
        processInterveningReceive(event, player);
    }

    @CultPacketHandler
    public void onContainerSlotStateChanged(PacketReceiveEvent event, CultPlayer player, ServerboundContainerSlotStateChangedPacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onMoveVehicle(PacketReceiveEvent event, CultPlayer player, ServerboundMoveVehiclePacket packet) {
        processMoveVehicleReceive(event, player, packet);
    }

    @CultPacketHandler
    public void onPlayerInput(PacketReceiveEvent event, CultPlayer player, ServerboundPlayerInputPacket packet) {
        if (player.isBedrockMovement()
                && player.packetStateData.hasPendingRejectedBedrockTranslatedMovement()) {
            // InputCache.processInputs sends changed Java input before
            // BedrockMovePlayer's single movement projection. A rejected raw
            // auth frame must not update Java input state, but its decision is
            // consumed only by the following Rot/MovePlayer projection.
            event.setCancelled(true);
            clearPendingVehicleMoveForInterveningPacket(player);
            clearTransientPacketState(player);
            return;
        }
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onPaddleBoat(PacketReceiveEvent event, CultPlayer player, ServerboundPaddleBoatPacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onSetCreativeModeSlot(PacketReceiveEvent event, CultPlayer player, ServerboundSetCreativeModeSlotPacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onContainerClick(PacketReceiveEvent event, CultPlayer player, ServerboundContainerClickPacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onClientInformation(PacketReceiveEvent event, CultPlayer player, ServerboundClientInformationPacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onUseItemOn(PacketReceiveEvent event, CultPlayer player, ServerboundUseItemOnPacket packet) {
        processUseItemOnReceive(event, player, packet);
    }

    @CultPacketHandler
    public void onPlayerAction(PacketReceiveEvent event, CultPlayer player, ServerboundPlayerActionPacket packet) {
        processPlayerActionReceive(event, player, packet);
    }

    @CultPacketHandler
    public void onUseItem(PacketReceiveEvent event, CultPlayer player, ServerboundUseItemPacket packet) {
        processUseItemReceive(event, player, packet);
    }

    @CultPacketHandler
    public void onCommandSuggestion(PacketReceiveEvent event, CultPlayer player, ServerboundCommandSuggestionPacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onChat(PacketReceiveEvent event, CultPlayer player, ServerboundChatPacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onChatCommand(PacketReceiveEvent event, CultPlayer player, ServerboundChatCommandPacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onChatCommandSigned(PacketReceiveEvent event, CultPlayer player, ServerboundChatCommandSignedPacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onEditBook(PacketReceiveEvent event, CultPlayer player, ServerboundEditBookPacket packet) {
        processGenericReceive(event, player);
    }

    // Teleport acknowledgements are handled separately and must not clear the mounted-teleport latch.
    @CultPacketHandler
    public void onBlockEntityTagQuery(PacketReceiveEvent event, CultPlayer player, ServerboundBlockEntityTagQueryPacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onChangeDifficulty(PacketReceiveEvent event, CultPlayer player, ServerboundChangeDifficultyPacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onChangeGameMode(PacketReceiveEvent event, CultPlayer player, ServerboundChangeGameModePacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onChatAck(PacketReceiveEvent event, CultPlayer player, ServerboundChatAckPacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onChatSessionUpdate(PacketReceiveEvent event, CultPlayer player, ServerboundChatSessionUpdatePacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onConfigurationAcknowledged(PacketReceiveEvent event, CultPlayer player, ServerboundConfigurationAcknowledgedPacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onCustomClickAction(PacketReceiveEvent event, CultPlayer player, ServerboundCustomClickActionPacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onDebugSubscriptionRequest(PacketReceiveEvent event, CultPlayer player, ServerboundDebugSubscriptionRequestPacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onEntityTagQuery(PacketReceiveEvent event, CultPlayer player, ServerboundEntityTagQueryPacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onJigsawGenerate(PacketReceiveEvent event, CultPlayer player, ServerboundJigsawGeneratePacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onLockDifficulty(PacketReceiveEvent event, CultPlayer player, ServerboundLockDifficultyPacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onRecipeBookChangeSettings(PacketReceiveEvent event, CultPlayer player, ServerboundRecipeBookChangeSettingsPacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onRecipeBookSeenRecipe(PacketReceiveEvent event, CultPlayer player, ServerboundRecipeBookSeenRecipePacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onSeenAdvancements(PacketReceiveEvent event, CultPlayer player, ServerboundSeenAdvancementsPacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onSetGameRule(PacketReceiveEvent event, CultPlayer player, ServerboundSetGameRulePacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onSetJigsawBlock(PacketReceiveEvent event, CultPlayer player, ServerboundSetJigsawBlockPacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onSetTestBlock(PacketReceiveEvent event, CultPlayer player, ServerboundSetTestBlockPacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onSpectatorAction(PacketReceiveEvent event, CultPlayer player, ServerboundSpectatorActionPacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onTestInstanceBlockAction(PacketReceiveEvent event, CultPlayer player, ServerboundTestInstanceBlockActionPacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onRenameItem(PacketReceiveEvent event, CultPlayer player, ServerboundRenameItemPacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onCustomPayload(PacketReceiveEvent event, CultPlayer player, ServerboundCustomPayloadPacket packet) {
        if (event.isCancelled()) return;
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onSetCommandBlock(PacketReceiveEvent event, CultPlayer player, ServerboundSetCommandBlockPacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onSetCommandMinecart(PacketReceiveEvent event, CultPlayer player, ServerboundSetCommandMinecartPacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onSetStructureBlock(PacketReceiveEvent event, CultPlayer player, ServerboundSetStructureBlockPacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onPlaceRecipe(PacketReceiveEvent event, CultPlayer player, ServerboundPlaceRecipePacket packet) {
        processInterveningReceive(event, player);
    }

    @CultPacketHandler
    public void onContainerButtonClick(PacketReceiveEvent event, CultPlayer player, ServerboundContainerButtonClickPacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onSelectTrade(PacketReceiveEvent event, CultPlayer player, ServerboundSelectTradePacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onSetBeacon(PacketReceiveEvent event, CultPlayer player, ServerboundSetBeaconPacket packet) {
        processInterveningReceive(event, player);
    }

    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket")
    public void onPickItemFromBlock(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
        processInterveningReceive(event, player);
    }

    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundPickItemFromEntityPacket")
    public void onPickItemFromEntity(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
        processInterveningReceive(event, player);
    }

    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundSelectBundleItemPacket")
    public void onSelectBundleItem(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onSetCarriedItem(PacketReceiveEvent event, CultPlayer player, ServerboundSetCarriedItemPacket packet) {
        processNonMovementReceiveAfterPlayGate(event, player);
    }

    @CultPacketHandler
    public void onContainerClose(PacketReceiveEvent event, CultPlayer player, ServerboundContainerClosePacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onInteract(PacketReceiveEvent event, CultPlayer player, ServerboundInteractPacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundAttackPacket")
    public void onAttack(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onClientTickEnd(PacketReceiveEvent event, CultPlayer player, ServerboundClientTickEndPacket packet) {
        processClientTickEndReceive(event, player);
    }

    @CultPacketHandler
    public void onSignUpdate(PacketReceiveEvent event, CultPlayer player, ServerboundSignUpdatePacket packet) {
        processInterveningReceive(event, player);
    }

    @CultPacketHandler
    public void onPlayerAbilities(PacketReceiveEvent event, CultPlayer player, ServerboundPlayerAbilitiesPacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onKeepAlive(PacketReceiveEvent event, CultPlayer player, ServerboundKeepAlivePacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onTeleportToEntity(PacketReceiveEvent event, CultPlayer player, ServerboundTeleportToEntityPacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onPlayerCommand(PacketReceiveEvent event, CultPlayer player, ServerboundPlayerCommandPacket packet) {
        // PacketEntityAction runs immediately before this listener. Its rejected
        // glide starts used to terminate at the pre-Via boundary, so they must
        // not enter the ordinary check route. Keep every other ordinary-phase
        // cancellation on the existing same-phase semantics.
        if (event.isCancelled()
                && packet.getAction() == ServerboundPlayerCommandPacket.Action.START_FALL_FLYING) {
            return;
        }
        processGenericReceive(event, player);
    }

    @CultPacketHandler
    public void onClientCommand(PacketReceiveEvent event, CultPlayer player, ServerboundClientCommandPacket packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler(packetClass = SwingPacketUtil.LEGACY_SWING_PACKET)
    public void onSwing(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
        processGenericReceive(event, player);
    }

    @CultPacketHandler(packetClass = SwingPacketUtil.PUNCH_PACKET)
    public void onPunch(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
        onSwing(event, player, packet);
    }

    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket")
    public void onPlayerLoaded(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
        processPlayerLoadedReceive(event, player);
    }

    private void processPlayerLoadedReceive(PacketReceiveEvent event, CultPlayer player) {
        if (event.getConnectionState() != ConnectionProtocol.PLAY) return;
        // TODO: This packet is skipped if the client ticks more than 60 times without loading in
        player.packetStateData.playerLoadedIntoLevel = true;
        processNonMovementReceiveAfterPlayGate(event, player);
    }

    private void processGenericReceive(PacketReceiveEvent event, CultPlayer player) {
        if (event.getConnectionState() != ConnectionProtocol.PLAY) return;
        processNonMovementReceiveAfterPlayGate(event, player);
    }

    private void processInterveningReceive(PacketReceiveEvent event, CultPlayer player) {
        if (event.getConnectionState() != ConnectionProtocol.PLAY) return;
        clearPendingVehicleMoveForInterveningPacket(player);
        player.checkManager.dispatchDecodedReceiveObservers(event);
        player.checkManager.dispatchOrderedReceive(event);
    }

    private void processNonMovementReceiveAfterPlayGate(PacketReceiveEvent event, CultPlayer player) {
        clearPendingVehicleMoveForInterveningPacket(player);
        dispatchPrePredictionReceive(event, player);
        dispatchReceiveHandlers(event, player);
        clearTransientPacketState(player);
    }

    private void processMovePlayerReceive(PacketReceiveEvent event, CultPlayer player, ServerboundMovePlayerPacket packet) {
        if (event.getConnectionState() != ConnectionProtocol.PLAY) return;

        if (player.isBedrockMovement()
                && packet instanceof ServerboundMovePlayerPacket.Rot
                && player.compensatedEntities.vehicles.hasPlayerPassengerState()
                && player.packetStateData.hasPendingRejectedBedrockTranslatedMovement()) {
            // Non-client-predicted vehicles do not emit an auth-derived
            // MoveVehicle. Their mandatory Rot is therefore the frame boundary
            // that consumes a rejected raw auth decision.
            player.packetStateData.consumeBedrockTranslatedMovementPermit();
            event.setCancelled(true);
            clearTransientPacketState(player);
            return;
        }

        Vec3 position = VectorUtils.clampVector(new Vec3(
                packet.getX(player.x),
                packet.getY(player.y),
                packet.getZ(player.z)
        ));

        if (!player.packetStateData.hasPendingBedrockTranslatedMovementDecision()
                && packet instanceof ServerboundMovePlayerPacket.PosRot
                && player.getSetbackTeleportUtil().matchesPendingBedrockTeleportPosition(position)) {
            // Geyser's exact Java echo does not prove Bedrock processed the teleport.
            player.packetStateData.lastPacketWasTeleport = true;
            player.packetStateData.lastPacketMatchedTeleportPosition = false;
            dispatchPrePredictionReceive(event, player);
            dispatchReceiveHandlers(event, player);
            if (!event.isCancelled()) {
                // Keep the Paper-visible anchor in step with the accepted echo.
                player.getSetbackTeleportUtil().setBedrockPaperVisiblePosition(position);
            }
            clearTransientPacketState(player);
            return;
        }

        if (player.isBedrockMovement()
                && player.compensatedEntities.vehicles.hasPlayerPassengerState()
                && !(packet instanceof ServerboundMovePlayerPacket.Rot)) {
            // Mounted Bedrock movement may only project rotation here.
            event.setCancelled(true);
            player.getSetbackTeleportUtil().executeForceResync("bedrock mounted player movement");
            clearTransientPacketState(player);
            return;
        }

        boolean mountedTeleportPosRot = !player.isBedrockMovement()
                && consumeMountedTeleportPosRot(player, packet);

        if (shouldIgnoreTranslatedBedrockMovement(player, false)) {
            // Auth input owns movement; this projection still passes through the setback gate.
            player.checkManager.getListener(SetbackBlocker.class)
                    .onTranslatedBedrockMove(event, packet);
            dispatchReceiveHandlers(event, player);
            if (!event.isCancelled() && packet.hasPosition()) {
                // Capture only positions accepted by Paper.
                player.getSetbackTeleportUtil().setBedrockPaperVisiblePosition(position);
            }
            clearTransientPacketState(player);
            return;
        }

        Vec3 movementPosition = position;
        TeleportAcceptData teleportData;
        // A mounted teleport's paired PosRot is acknowledgement state, not movement.
        if (packet.hasPosition()) {
            teleportData = mountedTeleportPosRot
                    ? new TeleportAcceptData()
                    : player.getSetbackTeleportUtil().checkTeleportQueue(movementPosition.x, movementPosition.y, movementPosition.z);
        } else if (packet instanceof ServerboundMovePlayerPacket.Rot) {
            teleportData = player.getSetbackTeleportUtil().checkRotationTeleportQueue(
                    packet.getYRot(player.xRot),
                    packet.getXRot(player.yRot)
            );
        } else {
            teleportData = new TeleportAcceptData();
        }

        player.packetStateData.lastPacketWasTeleport = teleportData.isTeleport() || mountedTeleportPosRot;
        player.packetStateData.lastPacketMatchedTeleportPosition = teleportData.isMatchedTeleportPosition();
        player.packetStateData.lastPacketWasOnePointSeventeenDuplicate = isOnePointSeventeenDuplicate(
                player.getClientVersion(),
                player.packetStateData.lastPacketWasTeleport,
                packet.hasPosition(),
                packet.hasRotation(),
                player.compensatedEntities.getSelf().inVehicle(),
                player.packetStateData.packetPlayerOnGround,
                packet.isOnGround(),
                player.packetStateData.clientSidePosition,
                movementPosition,
                player.getMovementThreshold()
        );
        if (teleportData.isTeleport()
                && teleportData.getTeleportData() != null
                && player.compensatedEntities.vehicles.hasPendingServerDismount()) {
            player.compensatedEntities.vehicles.applyClientVisibleDismount();
        }

        // LocalPlayer#tick emits Rot, never StatusOnly, while mounted.
        boolean passengerRotationTickPacket = packet instanceof ServerboundMovePlayerPacket.Rot
                && (player.compensatedEntities.getSelf().inVehicle()
                || player.compensatedEntities.vehicles.canCurrentPlayerControlServerVehicleForClientTickMovement()
                || player.compensatedEntities.vehicles.hasPlayerPassengerState()
                || hasBufferedServerVehiclePassengerRotation(player));
        if (!passengerRotationTickPacket
                && player.packetStateData.isAwaitingVehicleMoveAfterPassengerRotation()) {
            // LocalPlayer#tick sends the mounted Rot and vehicle move consecutively.
            player.packetStateData.clearPendingVehicleMoveAfterPassengerRotation();
        }
        if (passengerRotationTickPacket && !player.packetStateData.lastPacketWasTeleport) {
            player.packetStateData.markPassengerRotation();
        } else if (player.compensatedEntities.vehicles.serverPlayerVehicle != null || player.compensatedEntities.getSelf().inVehicle()) {
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
            recordMountedTeleportPosRot(player, position, packet);
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
                    packet.getYRot(player.yRot),
                    packet.getXRot(player.xRot),
                    packet.hasPosition(),
                    packet.hasRotation(),
                    packet.isOnGround(),
                    teleportData
            );
        }

        // While a violation setback is pending, forward nothing but teleport confirms.
        // SetbackBlocker already cancels position packets in this window pre-prediction;
        // this also catches the packet that caused the setback (which arrives before the
        // setback is pending) and non-position packets the blocker never cancels.
        // Paper adopting any of these lets the setback echo register as an upward move
        // and wipe fallDistance (fall-overshoot-wipe bypass).
        if (player.getSetbackTeleportUtil().isPendingSetback()
                && !player.packetStateData.lastPacketWasTeleport) {
            event.setCancelled(true);
        }

        dispatchReceiveHandlers(event, player);
        clearTransientPacketState(player);
    }

    private boolean consumeMountedTeleportPosRot(CultPlayer player, ServerboundMovePlayerPacket packet) {
        boolean pending = player.packetStateData.consumeMountedTeleportPosRotPending();
        // ClientPacketListener#handleMovePlayer sends AcceptTeleportation then
        // PosRot consecutively, even while mounting and before transaction pongs.
        return pending && packet instanceof ServerboundMovePlayerPacket.PosRot;
    }

    private boolean hasBufferedServerVehiclePassengerRotation(CultPlayer player) {
        Integer serverVehicleId = player.compensatedEntities.vehicles.serverPlayerVehicle;
        PacketEntity serverVehicle = serverVehicleId == null ? null : player.compensatedEntities.getEntity(serverVehicleId);
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

    private void recordMountedTeleportPosRot(CultPlayer player, Vec3 position, ServerboundMovePlayerPacket packet) {
        // A mounted position echo does not move the client or its root vehicle.
        // Never use its coordinates to seed prediction, reach, or fall distance.
        if (packet.hasRotation()) {
            float yaw = packet.getYRot(player.xRot);
            float pitch = packet.getXRot(player.yRot);
            player.xRot = yaw; player.yRot = pitch;
        }
    }

    private void processMoveVehicleReceive(PacketReceiveEvent event, CultPlayer player, ServerboundMoveVehiclePacket packet) {
        if (event.getConnectionState() != ConnectionProtocol.PLAY) return;

        NmsPacketUtil.MoveVehicleData vehiclePacket = NmsPacketUtil.readMoveVehicle(packet);
        Vec3 newPos = VectorUtils.clampVector(vehiclePacket.position());
        if (player.isBedrockMovement()) {
            if (player.packetStateData.hasPendingRejectedBedrockTranslatedMovement()) {
                // Keep the rejection until the mandatory mounted Rot boundary.
                // If a fixed-rate Geyser tick interleaves before translation,
                // failing closed for that boundary packet prevents it from
                // consuming the decision ahead of the auth-derived move.
                event.setCancelled(true);
                clearTransientPacketState(player);
                return;
            }
            Integer serverVehicle = player.compensatedEntities.vehicles.serverPlayerVehicle;
            PacketEntity packetRoot = currentVehicleRoot(player, false);
            if (packetRoot == null && serverVehicle != null) {
                packetRoot = player.compensatedEntities.getEntity(serverVehicle);
            }
            Integer teleportVehicleId = packetRoot == null
                    ? serverVehicle
                    : Integer.valueOf(packetRoot.getEntityId());
            TeleportAcceptData teleportData = player.getSetbackTeleportUtil()
                    .checkVehicleTeleportQueue(
                            teleportVehicleId,
                            newPos.x,
                            newPos.y,
                            newPos.z);
            boolean hasCurrentServerPassengerAuthority = serverVehicle != null
                    && player.compensatedEntities.vehicles.isServerPlayerPassengerOf(serverVehicle);
            if (!teleportData.isTeleport()
                    && (!hasCurrentServerPassengerAuthority
                    || shouldBlockVehicleMovementForSetback(player, teleportData))) {
                // Geyser owns mounted vehicle simulation, but server mount and
                // Cult-owned correction boundaries still apply.
                event.setCancelled(true);
            } else if (teleportData.isTeleport() && packetRoot != null) {
                applyAcceptedVehicleTeleportState(
                        player, packetRoot, newPos, vehiclePacket, teleportData);
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
        PacketEntity riddenRoot = packetRoot == null
                ? player.compensatedEntities.vehicles.getVelocityMovementVehicle() : packetRoot;
        if (pairedRiddenTick && riddenRoot instanceof PacketEntityRideable rideable
                && !rideable.isDead && rideable.hasSaddle
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

        boolean canAuthoritativelyMove = player.compensatedEntities.vehicles
                .canServerPlayerVehicleBeLocalAuthoritativeForMovementPacket(fromClientTick)
                || (fromClientTick && player.compensatedEntities.vehicles.isVehicleSwitchBufferActiveFor(packetRoot));
        TeleportAcceptData teleportData = player.getSetbackTeleportUtil()
                .checkVehicleTeleportQueue(packetRoot == null ? null : packetRoot.getEntityId(), newPos.x, newPos.y, newPos.z);
        boolean checkableClientTick = fromClientTick
                && packetRoot != null
                && canAuthoritativelyMove
                && !teleportData.isTeleport();

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
                player.compensatedEntities.vehicles.forceResyncVehicleSwitchBuffer(packetRoot,
                        "vehicle-switch-buffer-invalid-packet");
            }
            if (!shouldBlockVehicleMovementForSetback(player, teleportData) || !fromClientTick) {
                player.checkManager.getCheck(BadPacketsVehicle.class)
                        .handleInvalidVehiclePacket(fromClientTick);
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
        player.packetStateData.vehicleMovementStartOnGround = packetRoot == null ? vehiclePacket.onGround() : packetRoot.onGround;
        player.packetStateData.vehicleMovementOnGroundPresent = vehiclePacket.hasOnGround();
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
            boolean vehicleOnGround = vehiclePacket.hasOnGround()
                    ? vehiclePacket.onGround()
                    : player.packetStateData.vehicleMovementStartOnGround;
            final VehiclePositionUpdate update = new VehiclePositionUpdate(physicalMovementStart, physicalNewPos,
                    physicalYaw, physicalPitch, vehicleOnGround, vehiclePacket.hasOnGround(), teleportData);
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
                applyAcceptedVehicleTeleportState(
                        player, packetRoot, physicalNewPos, vehiclePacket, teleportData);
            } else if (checkableClientTick) {
                player.compensatedEntities.vehicles.applyVehiclePacketPosition(packetRoot, physicalNewPos,
                        vehiclePacket.onGround(), physicalYaw, physicalPitch);
            }
        }

        dispatchReceiveHandlers(event, player);
        clearTransientPacketState(player);
    }

    private void applyAcceptedVehicleTeleportState(
            CultPlayer player,
            PacketEntity packetRoot,
            Vec3 position,
            NmsPacketUtil.MoveVehicleData vehiclePacket,
            TeleportAcceptData teleportData
    ) {
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
                    packetRoot, position, vehiclePacket.onGround(), vehiclePacket.yaw(), vehiclePacket.pitch());
        }
    }

    private boolean shouldTickControlledBoatForClientVehiclePacket(PacketEntity root,
                                                                   boolean checkableClientTick) {
        return root != null
                && root.isBoat()
                && checkableClientTick;
    }

    private boolean shouldBlockVehicleMovementForSetback(CultPlayer player, TeleportAcceptData teleportData) {
        return !teleportData.isTeleport()
                && (player.getSetbackTeleportUtil().hasUnacknowledgedSetbackVehicleTeleport()
                || player.getSetbackTeleportUtil().shouldBlockVehicleMovement());
    }

    private Vec3 currentVehiclePhysicalPosition(CultPlayer player, PacketEntity root, Vec3 fallback) {
        Vec3 physical = root == null ? null : root.clientPhysicalPosition == null ? root.desyncClientPos : root.clientPhysicalPosition;
        return physical == null ? fallback : physical;
    }

    private PacketEntity currentVehicleRoot(CultPlayer player, boolean fromClientTick) {
        PacketEntity root = player.compensatedEntities.getSelf().getRiding();
        if (root != null) {
            return root;
        }

        Integer serverVehicleId = player.compensatedEntities.vehicles.serverPlayerVehicle;
        PacketEntity serverVehicle = serverVehicleId == null ? null : player.compensatedEntities.getEntity(serverVehicleId);
        boolean sameTickVehicleControlMayBeClientVisible = fromClientTick
                && player.compensatedEntities.vehicles.canOpenVehicleSwitchBufferForMovementPacket(serverVehicle);
        boolean vehicleSwitchBufferMayCover = fromClientTick
                && player.compensatedEntities.vehicles.isVehicleSwitchBufferActiveFor(serverVehicle);
        return serverVehicle != null
                && (player.compensatedEntities.vehicles.canServerPlayerVehicleBeLocalAuthoritativeForMovementPacket(fromClientTick)
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
        PacketEntity serverVehicle = serverVehicleId == null ? null : player.compensatedEntities.getEntity(serverVehicleId);
        return isBufferedLocalAuthoritativeRoot(player, serverVehicle) ? serverVehicle : null;
    }

    private boolean isBufferedLocalAuthoritativeRoot(CultPlayer player, @Nullable PacketEntity root) {
        return player.compensatedEntities.vehicles.isVehicleSwitchBufferActiveFor(root)
                && player.compensatedEntities.vehicles.canClientAuthoritativelyMoveVisibleRoot(root);
    }

    private void processPlayerActionReceive(PacketReceiveEvent event, CultPlayer player, ServerboundPlayerActionPacket packet) {
        if (event.getConnectionState() != ConnectionProtocol.PLAY) return;
        Action action = packet.getAction();
        if (action == Action.START_DESTROY_BLOCK || action == Action.STOP_DESTROY_BLOCK) {
            player.compensatedWorld.advanceClientPredictionSequence();
        }
        clearPendingVehicleMoveForInterveningPacket(player);
        dispatchPrePredictionReceive(event, player);

        BlockPos blockPosition = packet.getPos();
        BlockData block = player.compensatedWorld.getBlockDataAt(blockPosition);

        if (player.debugBreaks && isBlockBreakAction(action)) {
            player.sendMessage("Break: action=" + action + " state=" + block.getAsString(false) + " at " + blockPosition);
        }

        if (action == Action.STOP_DESTROY_BLOCK) {
            // Not unbreakable
            if (NmsBlockTags.toNmsState(block).getDestroySpeed(player.compensatedWorld, blockPosition) != -1.0f) {
                player.compensatedWorld.startPredicting();
                applyClientBreakPrediction(player, blockPosition);
                player.checkManager.getCheck(AirLiquidPlace.class).handleBlockBreak(new BlockPos(blockPosition));
                player.compensatedWorld.stopPredicting(packet);
            }
        }

        if (action == Action.START_DESTROY_BLOCK) {
            double damage = BlockBreakSpeed.getBlockDamage(player, blockPosition);

            //Instant breaking, no damage means it is unbreakable by creative players (with swords)
            if (damage >= 1) {
                player.compensatedWorld.startPredicting();
                player.checkManager.getListener(AirLiquidPlace.class).handleBlockBreak(new BlockPos(blockPosition));
                applyClientBreakPrediction(player, blockPosition);
                player.compensatedWorld.stopPredicting(packet);
            } else if (player.debugBreaks) {
                player.sendMessage("Break start has no immediate world change: damage=" + damage);
            }
        }

        dispatchReceiveHandlers(event, player);
        clearTransientPacketState(player);
    }

    private void processUseItemOnReceive(PacketReceiveEvent event, CultPlayer player, ServerboundUseItemOnPacket packet) {
        if (event.getConnectionState() != ConnectionProtocol.PLAY) return;
        player.compensatedWorld.advanceClientPredictionSequence();
        clearPendingVehicleMoveForInterveningPacket(player);
        dispatchPrePredictionReceive(event, player);

        NmsPacketUtil.UseItemOnData use = NmsPacketUtil.readUseItemOn(packet);
        BlockPos clickedBlock = use.blockPosition();
        Vec3 cursor = use.cursor();
        BlockFace blockFace = use.blockFace();
        player.lastBlockPlaceUseItem = System.currentTimeMillis();

        ItemStack placedWith = player.getInventory().getHandItem(use.hand());

        BlockPlace blockPlace = new BlockPlace(player, use.hand(), clickedBlock, blockFace, placedWith,
                TraverseBlocks.getNearestHitResult(player, null, true), use.sequence());
        blockPlace.setCursor(cursor);

        // Deny fun stuff like teleport bridging
        BlockPos placedAgainst = blockPlace.getPlacedAgainstBlockLocation();
        final GhostBlockMitigator ghostBlockMitigator = player.getGhostBlockMitigator();

        final boolean desyncPos = ghostBlockMitigator.isDesyncPos(placedAgainst);
        if (player.debugPlaces) { player.sendMessage("Desync pos: " + desyncPos); }

        // Should we call the anticheat placing checks?
        if (!desyncPos && (blockPlace.isBlock() || placedWith.getType() == Material.FIRE_CHARGE || placedWith.getType() == Material.END_CRYSTAL) && !player.compensatedEntities.getSelf().inVehicle()) {
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
        if (blockPlace.isCancelled() || event.isCancelled() || player.getSetbackTeleportUtil().isPendingSetback()) {
            // Invalid block place due to placing on a ghost block
            blockPlace.resync();

            if (!event.isCancelled()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }

            BlockPredictionAckSender.sendAck(player, use.sequence());

            // Stop inventory desync from cancelling place
            if (player.bukkitPlayer != null) {
                // TODO: Is this unsafe enough to have to run on the main thread?
                if (use.hand() == InteractionHand.MAIN_HAND) {
                    ItemStack mainHand = ItemUtil.copy(player.bukkitPlayer.getInventory().getItemInHand());
                    player.user.sendPacket(new ClientboundContainerSetSlotPacket(0, player.getInventory().stateID, 36 + player.packetStateData.lastSlotSelected, SpigotConversionUtil.toNmsItemStack(mainHand)));
                } else {
                    ItemStack offHand = ItemUtil.copy(player.bukkitPlayer.getInventory().getItemInOffHand());
                    player.user.sendPacket(new ClientboundContainerSetSlotPacket(0, player.getInventory().stateID, 45, SpigotConversionUtil.toNmsItemStack(offHand)));
                }
            }

        } else { // Legit place
            PlaceHandler.handleQueuedUseItemOn(player, packet);
        }

        dispatchReceiveHandlers(event, player);
        clearTransientPacketState(player);
    }

    private void processUseItemReceive(PacketReceiveEvent event, CultPlayer player, ServerboundUseItemPacket packet) {
        if (event.getConnectionState() != ConnectionProtocol.PLAY) return;
        player.compensatedWorld.advanceClientPredictionSequence();
        clearPendingVehicleMoveForInterveningPacket(player);
        dispatchPrePredictionReceive(event, player);

        PlaceHandler.handleQueuedUseItem(player, packet);
        player.lastBlockPlaceUseItem = System.currentTimeMillis();

        dispatchReceiveHandlers(event, player);
        clearTransientPacketState(player);
    }

    private void processClientTickEndReceive(PacketReceiveEvent event, CultPlayer player) {
        if (event.getConnectionState() != ConnectionProtocol.PLAY) return;
        dispatchPrePredictionReceive(event, player);
        dispatchReceiveHandlers(event, player);

        player.packetStateData.acceptedClientTick++;
        player.packetStateData.lastClientTickEndTransaction = player.lastTransactionReceived.get();
        player.packetStateData.receivedMovementThisClientTick = false;
        player.serverOpenedInventoryThisTick = false;
        player.packetStateData.clearBedrockTranslatedMovementPermit();
        player.packetStateData.clientMovementInputUpdatedThisClientTick = false;
        player.packetStateData.carriedItemChangedThisClientTick = false;
        player.packetStateData.vehicleMovePacketsThisClientTick = 0;
        player.packetStateData.clientTickVehicleMovePacketsThisClientTick = 0;
        player.packetStateData.localAuthoritativeVehicleMovePacketsThisClientTick = 0;
        player.packetStateData.clearMountedMovementTickState();
        if (player.packetStateData.serverFrozenTickStepsRemaining > 0) {
            player.packetStateData.serverFrozenTickStepsRemaining--;
        }

        clearTransientPacketState(player);
    }

    private void clearPendingVehicleMoveForInterveningPacket(CultPlayer player) {
        // A mounted teleport's acknowledgement packets are consecutive.
        player.packetStateData.clearMountedTeleportPosRotPending();
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

    private void clearTransientPacketState(CultPlayer player) {
        // Finally, remove the packet state variables on this packet
        player.packetStateData.lastPacketWasTeleport = false;
        player.packetStateData.lastPacketWasOnePointSeventeenDuplicate = false;
        player.packetStateData.lastPacketMatchedTeleportPosition = false;
        player.packetStateData.lastPacketProvenVehiclePhysicalMovement = null;
        player.packetStateData.clearBedrockTranslatedCanonicalGround();
        player.packetStateData.clearDesiredOnGround();
    }

    private void handleFlying(CultPlayer player, double x, double y, double z, float yaw, float pitch, boolean hasPosition, boolean hasLook, boolean onGround, TeleportAcceptData teleportData) {
        player.serverOpenedInventoryThisTick = false;

        // We can't set the look if this is actually the stupidity packet
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
            HeadRotation from = new HeadRotation(player.xRot, player.yRot);
            player.xRot = yaw; player.yRot = pitch;
            RotationUpdate update = new RotationUpdate(
                    from,
                    new HeadRotation(player.xRot, player.yRot),
                    player.xRot - from.yaw(),
                    player.yRot - from.pitch()
            );
            if (update.getDeltaXRot() != 0 || update.getDeltaYRot() != 0) {
                player.lastRotated = System.currentTimeMillis();
            }
            player.checkManager.onRotationUpdate(update);
        }

        player.checkManager.doChecksWithKnownLook();

        if (hasPosition) {
            Vec3 position = new Vec3(x, y, z);
            Vec3 clampVector = VectorUtils.clampVector(position);
            final PositionUpdate update = new PositionUpdate(new Vec3(player.x, player.y, player.z), position, player.xRot, player.yRot, onGround, teleportData);

            final ac.cult.cultac.utils.data.packetentity.PacketEntitySelf selfEntity = player.compensatedEntities.getSelf();
            if (!selfEntity.inVehicle() && !player.packetStateData.lastPacketWasOnePointSeventeenDuplicate) {
                player.lastX = player.x;
                player.lastY = player.y;
                player.lastZ = player.z;

                player.x = clampVector.x;
                player.y = clampVector.y;
                player.z = clampVector.z;

                player.checkManager.onPositionUpdate(update);
            } else if (update.getTeleportData().isTeleport()) { // Mojang doesn't use their own exit vehicle field to leave vehicles, manually call the setback handler
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
            double movementThreshold
    ) {
        if (teleport
                // The compact raw-NMS version enum does not expose 1.17;
                // 1.18.2 is its first supported member in the affected range.
                || clientVersion.isOlderThan(ClientVersion.V_1_18_2)
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
        NmsBlockBreakResolver.applyBlockBreak(player, blockPosition);
    }

    private static boolean isBlockBreakAction(Action action) {
        return action == Action.START_DESTROY_BLOCK
                || action == Action.STOP_DESTROY_BLOCK
                || action == Action.ABORT_DESTROY_BLOCK;
    }

    private static void dispatchPlaySend(PacketSendEvent event, CultPlayer player) {
        if (event.getConnectionState() != ConnectionProtocol.PLAY) {
            return;
        }

        player.checkManager.dispatchSendHandlers(event);
    }

    private static final class CheckManagerEarlyReceiveForwarder implements PacketReceiveHandler<Packet<?>> {
        @Override
        public void handle(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
            if (event.getConnectionState() == ConnectionProtocol.PLAY) {
                player.checkManager.dispatchEarlyReceive(event);
            }
        }
    }

    private static final class CheckManagerSendForwarder implements PacketSendHandler<Packet<?>> {
        @Override
        public void handle(PacketSendEvent event, CultPlayer player, Packet<?> packet) {
            dispatchPlaySend(event, player);
        }
    }
}
