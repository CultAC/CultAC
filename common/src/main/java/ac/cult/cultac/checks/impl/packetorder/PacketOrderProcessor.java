package ac.cult.cultac.checks.impl.packetorder;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketGroup;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.PacketGroup;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.NmsPacketUtil;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.CultMath;
import lombok.Getter;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import org.jetbrains.annotations.Contract;

@Getter
public final class PacketOrderProcessor extends Check implements CheckListener {
    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(SharedConstants.getProtocolVersion());

    public PacketOrderProcessor(final CultPlayer player) {
        super(player);
    }

    private boolean openingInventory; // only pre 1.12 clients on pre 1.12 servers
    private boolean swapping;
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
    private boolean clickingInInventory;
    private boolean closingInventory;
    private boolean quickMoveClicking;
    private boolean pickUpClicking;
    private boolean leavingBed;
    private boolean startingToGlide;
    private boolean jumpingWithMount;
    private boolean stabbing;


    @CultPacketHandler
    public void onClientCommand(PacketReceiveEvent event, CultPlayer player, ServerboundClientCommandPacket packet) {

        if (SERVER_VERSION.getProtocolVersion() < 335
                && packet.getAction().name().equals("OPEN_INVENTORY_ACHIEVEMENT")) {
            openingInventory = true;
        }

        maybeReset(player, false);
    }


    @CultPacketHandler
    public void onInteract(PacketReceiveEvent event, CultPlayer player, ServerboundInteractPacket packet) {
        if (NmsPacketUtil.readInteract(packet).action() == NmsPacketUtil.InteractAction.ATTACK) {
            attacking = true;
        } else {
            interacting = true;
        }

        maybeReset(player, false);
    }


    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundAttackPacket")
    public void onAttack(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
        attacking = true;

        maybeReset(player, false);
    }


    // 26.1 uses a required entity id; 26.2 also permits a spectator action without a target.
    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundSpectateEntityPacket")
    public void onSpectateEntity(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
        onSpectatorAction(event, player, packet);
    }

    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundSpectatorActionPacket")
    public void onSpectatorAction(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
        attacking = true;

        maybeReset(player, false);
    }


    @CultPacketHandler
    public void onPlayerAction(PacketReceiveEvent event, CultPlayer player, ServerboundPlayerActionPacket packet) {
        switch (packet.getAction()) {
            case SWAP_ITEM_WITH_OFFHAND -> swapping = true;

            case DROP_ITEM, DROP_ALL_ITEMS -> dropping = true;
            case RELEASE_USE_ITEM -> releasing = true;

            case STOP_DESTROY_BLOCK, ABORT_DESTROY_BLOCK, START_DESTROY_BLOCK -> digging = true;
            case STAB -> stabbing = true;
        }

        maybeReset(player, false);
    }


    @CultPacketHandler
    public void onPlayerCommand(PacketReceiveEvent event, CultPlayer player, ServerboundPlayerCommandPacket packet) {
        switch (NmsPacketUtil.readPlayerCommand(packet).action()) {
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

    public void handleLegacySneakAction() {
        sneaking = true;
        maybeReset(player, false);
    }

    @CultPacketHandler
    public void onUseItem(PacketReceiveEvent event, CultPlayer player, ServerboundUseItemPacket packet) {
        using = true;

        maybeReset(player, false);
    }


    @CultPacketHandler
    public void onUseItemOn(PacketReceiveEvent event, CultPlayer player, ServerboundUseItemOnPacket packet) {
        placing = true;

        maybeReset(player, false);
    }


    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundPickItemPacket")
    public void onPickItem(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
        picking = true;

        maybeReset(player, false);
    }


    @CultPacketHandler
    public void onContainerClick(PacketReceiveEvent event, CultPlayer player, ServerboundContainerClickPacket packet) {
        clickingInInventory = true;

        switch (NmsPacketUtil.readContainerClick(packet).clickType()) {
            case QUICK_MOVE -> quickMoveClicking = true;
            case PICKUP, PICKUP_ALL -> pickUpClicking = true;
            default -> {
            }
        }

        maybeReset(player, false);
    }


    @CultPacketHandler
    public void onContainerClose(PacketReceiveEvent event, CultPlayer player, ServerboundContainerClosePacket packet) {
        closingInventory = true;

        maybeReset(player, false);
    }

    // isTickPacket: movement packets reset unless they answered a teleport
    @CultPacketHandler
    @CultPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, CultPlayer player, ServerboundMovePlayerPacket packet) {
        maybeReset(player, !player.packetStateData.lastPacketWasTeleport);
    }

    // isTickPacket: tick end resets for 1.21.2+ clients when no movement arrived this client tick
    @CultPacketHandler
    public void onClientTickEnd(PacketReceiveEvent event, CultPlayer player, ServerboundClientTickEndPacket packet) {
        maybeReset(player, player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                && !player.packetStateData.receivedMovementThisClientTick);
    }


    @CultPacketHandler
    public void onPong(PacketReceiveEvent event, CultPlayer player, ServerboundPongPacket packet) {
        maybeReset(player, false);
    }

    private void maybeReset(CultPlayer player, boolean tickPacket) {
        if (!player.cameraEntity.isSelf() || tickPacket
                || player.getClientVersion().isOlderThan(ClientVersion.V_1_21_2)
                && !player.compensatedWorld.isChunkLoaded(CultMath.floor(player.x) >> 4, CultMath.floor(player.z) >> 4)) {
            openingInventory = false;
            swapping = false;
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
            clickingInInventory = false;
            closingInventory = false;
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
