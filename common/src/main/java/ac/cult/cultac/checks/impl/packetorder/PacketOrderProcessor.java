package ac.cult.cultac.checks.impl.packetorder;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.DecodedPacketReceiveListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundClientCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundContainerClose;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPong;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSpectatorAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.inventory.InventoryClick;
import lombok.AccessLevel;
import lombok.Getter;
import org.jetbrains.annotations.Contract;

@Getter
public final class PacketOrderProcessor extends Check implements DecodedPacketReceiveListener {
    public PacketOrderProcessor(final CultPlayer player) {
        super(player);
    }

    private boolean openingInventory;
    private boolean dropping;
    private boolean interacting;
    private boolean attacking;
    private boolean releasing;
    private boolean digging;
    private boolean sprinting;
    private boolean sneaking;
    private boolean placing;
    private boolean using;
    private boolean picking;
    private boolean quickMoveClicking;
    private boolean pickUpClicking;
    private boolean leavingBed;
    private boolean startingToGlide;
    private boolean jumpingWithMount;
    private boolean stabbing;

    @Getter(AccessLevel.NONE)
    private boolean acceptedTeleport;
    // 26.3 ClientPacketListener#handleMovePlayer ends a dig with stopDestroyBlock right after the acceptance
    private boolean teleportAbort;

    @Override
    public void onDecodedPacketReceive(PacketReceiveEvent event) {
        teleportAbort = acceptedTeleport
                && event.getPacket() instanceof ServerboundPlayerAction action
                && action.action() == PlayerAction.ABORT_DESTROY_BLOCK;
        acceptedTeleport = event.getPacketType() == ServerboundPackets.ACCEPT_TELEPORTATION
                && !event.isCancelled()
                && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3)
                && !hasInTickAction();
    }

    // Minecraft#runTick handles packets before the tick, so an acceptance follows no packet the tick sent.
    private boolean hasInTickAction() {
        return attacking
                || interacting
                || placing
                || using
                || releasing
                || digging
                || picking
                || stabbing
                || dropping
                || sprinting
                || sneaking;
    }

    @CultPacketHandler
    public void onClientCommand(
            PacketReceiveEvent<ServerboundClientCommand> event, CultPlayer player, ServerboundClientCommand packet) {
        maybeReset(player, false);
    }

    @CultPacketHandler
    public void onInteract(
            PacketReceiveEvent<ServerboundInteract> event, CultPlayer player, ServerboundInteract packet) {
        if (packet.action() == InteractAction.ATTACK) {
            attacking = true;
        } else {
            interacting = true;
        }

        maybeReset(player, false);
    }

    @CultPacketHandler
    public void onSpectatorAction(
            PacketReceiveEvent<ServerboundSpectatorAction> event,
            CultPlayer player,
            ServerboundSpectatorAction packet) {
        // Minecraft#startAttack sends an empty target when the spectator's hit result is a block or a miss
        attacking |= packet.target().isPresent();

        maybeReset(player, false);
    }

    @CultPacketHandler
    public void onPlayerAction(
            PacketReceiveEvent<ServerboundPlayerAction> event, CultPlayer player, ServerboundPlayerAction packet) {
        switch (packet.action()) {
            case DROP_ITEM, DROP_ALL_ITEMS -> dropping = true;
            case RELEASE_USE_ITEM -> releasing = true;

            case STOP_DESTROY_BLOCK, START_DESTROY_BLOCK -> digging = true;
            case ABORT_DESTROY_BLOCK -> digging |= !teleportAbort;
            case STAB -> stabbing = true;
        }

        maybeReset(player, false);
    }

    @CultPacketHandler
    public void onPlayerCommand(
            PacketReceiveEvent<ServerboundPlayerCommand> event, CultPlayer player, ServerboundPlayerCommand packet) {
        switch (packet.action()) {
            case START_SPRINTING, STOP_SPRINTING -> {
                if (!player.inVehicle()) {
                    sprinting = true;
                }
            }

            case RELEASE_SHIFT_KEY, PRESS_SHIFT_KEY -> sneaking = true;

            case STOP_SLEEPING -> leavingBed = true;
            case START_FLYING_WITH_ELYTRA -> startingToGlide = true;

            case OPEN_INVENTORY -> openingInventory = true;
            case START_JUMPING_WITH_HORSE, STOP_JUMPING_WITH_HORSE -> jumpingWithMount = true;
        }

        maybeReset(player, false);
    }

    @CultPacketHandler
    public void onUseItem(PacketReceiveEvent<ServerboundUseItem> event, CultPlayer player, ServerboundUseItem packet) {
        using = true;

        maybeReset(player, false);
    }

    @CultPacketHandler
    public void onUseItemOn(
            PacketReceiveEvent<ServerboundUseItemOn> event, CultPlayer player, ServerboundUseItemOn packet) {
        placing = true;

        maybeReset(player, false);
    }

    @CultPacketHandler("serverbound.pick_item")
    public void onPickItem(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        picking = true;

        maybeReset(player, false);
    }

    @CultPacketHandler
    public void onContainerClick(PacketReceiveEvent<InventoryClick> event, CultPlayer player, InventoryClick packet) {
        switch (packet.clickType()) {
            case QUICK_MOVE -> quickMoveClicking = true;
            case PICKUP, PICKUP_ALL -> pickUpClicking = true;
            default -> {}
        }

        maybeReset(player, false);
    }

    @CultPacketHandler
    public void onContainerClose(
            PacketReceiveEvent<ServerboundContainerClose> event, CultPlayer player, ServerboundContainerClose packet) {
        maybeReset(player, false);
    }

    // isTickPacket: movement packets reset unless they answered a teleport
    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        maybeReset(player, !player.packetStateData.lastPacketWasTeleport);
    }

    // isTickPacket: tick end resets for 1.21.2+ clients when no movement arrived this client tick
    @CultPacketHandler("serverbound.client_tick_end")
    public void onClientTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        maybeReset(player, !player.packetStateData.receivedMovementThisClientTick);
    }

    @CultPacketHandler
    public void onPong(PacketReceiveEvent<ServerboundPong> event, CultPlayer player, ServerboundPong packet) {
        maybeReset(player, false);
    }

    private void maybeReset(CultPlayer player, boolean tickPacket) {
        if (!player.cameraEntity.isSelf() || tickPacket) {
            openingInventory = false;
            dropping = false;
            attacking = false;
            interacting = false;
            releasing = false;
            digging = false;
            placing = false;
            using = false;
            picking = false;
            sprinting = false;
            sneaking = false;
            quickMoveClicking = false;
            pickUpClicking = false;
            leavingBed = false;
            startingToGlide = false;
            jumpingWithMount = false;
            stabbing = false;
        }
    }

    @Contract(pure = true)
    public boolean isRightClicking() {
        return placing || using || interacting;
    }

    @Contract(pure = true)
    public boolean isAttackingOrStabbing() {
        return attacking || stabbing;
    }
}
