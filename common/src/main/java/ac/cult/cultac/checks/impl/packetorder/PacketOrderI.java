package ac.cult.cultac.checks.impl.packetorder;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.CultPacketGroup;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.PacketGroup;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.NmsPacketUtil;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.nmsutil.BlockBreakSpeed;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import org.bukkit.GameMode;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;

@CheckData(name = "PacketOrderI", stableKey = "cult.packetorder.input_tick_order", description = "Sent combat, use, release, or digging packets in an invalid tick order", experimental = true)
public class PacketOrderI extends Check implements PostPredictionListener {
    private static final Verbose V = Verbose
            .of("type={str}[, attacking={bool}][, rightClicking={bool}][, picking={bool}][, releasing={bool}], digging={bool}");

    static final int TYPE_INTERACT = 0;
    static final int TYPE_PLACE_USE = 1;
    static final int TYPE_RELEASE = 2;
    static final int TYPE_ATTACK = 3;

    public PacketOrderI(final CultPlayer player) {
        super(player);
    }

    private boolean exemptPlacingWhileDigging;

    private boolean setback;
    // for placing
    private boolean cancelledDigging;

    private BlockPos startedDiggingPos;
    private BlockData startedDiggingState;
    private boolean digging;
    private final ArrayDeque<FlagData> flags = new ArrayDeque<>();

    static String typeName(int type) {
        return switch (type) {
            case TYPE_INTERACT -> "interact";
            case TYPE_PLACE_USE -> "place/use";
            case TYPE_RELEASE -> "release";
            case TYPE_ATTACK -> "attack";
            default -> "unknown";
        };
    }

    /**
     * Each per-field group is gated so only the fields relevant to the type
     * render: attacking for release; rightClicking/picking for release and
     * attack; releasing for everything but release; digging always.
     */
    private Verbose.Writer write(
            int type,
            boolean attacking,
            boolean rightClicking,
            boolean picking,
            boolean releasing,
            boolean digging) {
        boolean release = type == TYPE_RELEASE;
        boolean attack = type == TYPE_ATTACK;
        return V.write(verbose())
                .str(typeName(type))
                .bool(release).bool(attacking)
                .bool(release || attack).bool(rightClicking)
                .bool(release || attack).bool(picking)
                .bool(!release).bool(releasing)
                .bool(digging);
    }


    @CultPacketHandler
    public void onInteract(PacketReceiveEvent event, CultPlayer player, ServerboundInteractPacket packet) {
        if (NmsPacketUtil.readInteract(packet).action() == NmsPacketUtil.InteractAction.ATTACK) {
            onAttack(event, player);
        } else if (player.packetOrderProcessor.isReleasing() || player.packetOrderProcessor.isDigging()) {
            boolean releasing = player.packetOrderProcessor.isReleasing();
            boolean digging = player.packetOrderProcessor.isDigging();
            if (!player.canSkipTicks()) {
                if (flag(write(TYPE_INTERACT, false, false, false, releasing, digging)) && shouldModifyPackets()) {
                    event.setCancelled(true);
                    player.onPacketCancel();
                }
            } else {
                flags.add(new FlagData(TYPE_INTERACT, false, false, false, releasing, digging));
            }
        }

        resetOnCameraSwitch(player);
    }


    @CultPacketHandler
    public void onUseItemOn(PacketReceiveEvent event, CultPlayer player, ServerboundUseItemOnPacket packet) {
        onPlaceUse(event, player);
    }


    @CultPacketHandler
    public void onUseItem(PacketReceiveEvent event, CultPlayer player, ServerboundUseItemPacket packet) {
        onPlaceUse(event, player);
    }

    private void onPlaceUse(PacketReceiveEvent event, CultPlayer player) {
        digging |= cancelledDigging;

        if (startedDiggingPos != null && !digging) {
            // Check this here because we don't know for certain what slot they were using until now.
            // This is because the client doesn't notify the server when changing slots with the number keys,
            // and that the client doesn't sync the hotbar slot when starting to dig.
            // The client does sync on placing and using though, so this is safe.
            double damage = BlockBreakSpeed.getBlockDamage(player, startedDiggingPos, startedDiggingState, false);
            if (damage < 1 && (damage > 0 || player.gamemode != GameMode.CREATIVE)) {
                digging = true;
            }
        }

        if (player.packetOrderProcessor.isReleasing() || digging) {
            boolean releasing = player.packetOrderProcessor.isReleasing();
            if (!player.canSkipTicks()) {
                if (flag(write(TYPE_PLACE_USE, false, false, false, releasing, digging)) && shouldModifyPackets()) {
                    event.setCancelled(true);
                    player.onPacketCancel();
                }
            } else {
                flags.add(new FlagData(TYPE_PLACE_USE, false, false, false, releasing, digging));
            }
        }

        resetOnCameraSwitch(player);
    }


    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundAttackPacket")
    public void onAttackPacket(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
        onAttack(event, player);
        resetOnCameraSwitch(player);
    }


    // 26.1 uses a required entity id; 26.2 also permits a spectator action without a target.
    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundSpectateEntityPacket")
    public void onSpectateEntity(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
        onSpectatorAction(event, player, packet);
    }

    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundSpectatorActionPacket")
    public void onSpectatorAction(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
        onAttack(event, player);
        resetOnCameraSwitch(player);
    }


    @CultPacketHandler
    public void onPlayerAction(PacketReceiveEvent event, CultPlayer player, ServerboundPlayerActionPacket packet) {
        switch (packet.getAction()) {
            case STAB -> onAttack(event, player);
            case RELEASE_USE_ITEM -> {
                if (player.packetOrderProcessor.isAttackingOrStabbing() || player.packetOrderProcessor.isRightClicking() || player.packetOrderProcessor.isPicking() || player.packetOrderProcessor.isDigging()) {
                    boolean attacking = player.packetOrderProcessor.isAttackingOrStabbing();
                    boolean rightClicking = player.packetOrderProcessor.isRightClicking();
                    boolean picking = player.packetOrderProcessor.isPicking();
                    boolean digging = player.packetOrderProcessor.isDigging();
                    if (!player.canSkipTicks()) {
                        if (flag(write(TYPE_RELEASE, attacking, rightClicking, picking, false, digging))) {
                            setback = true;
                        }
                    } else {
                        flags.add(new FlagData(TYPE_RELEASE, attacking, rightClicking, picking, false, digging));
                        setback = true;
                    }
                }
            }

            case START_DESTROY_BLOCK -> {
                if (shouldCheckPlacingWhileDigging()) {
                    cancelledDigging = false; // we don't care about any cancels before this
                    startedDiggingPos = packet.getPos();
                    startedDiggingState = player.compensatedWorld.getBlockDataAt(startedDiggingPos);
                }
            }

            case ABORT_DESTROY_BLOCK -> cancelledDigging = shouldCheckPlacingWhileDigging();

            case STOP_DESTROY_BLOCK -> digging = shouldCheckPlacingWhileDigging();
            default -> {
            }
        }

        resetOnCameraSwitch(player);
    }

    // isTickPacket: movement packets reset unless they answered a teleport
    @CultPacketHandler
    @CultPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, CultPlayer player, ServerboundMovePlayerPacket packet) {
        if (!player.cameraEntity.isSelf() || !player.packetStateData.lastPacketWasTeleport) {
            resetDiggingState();
        }
    }

    // isTickPacket: tick end resets for 1.21.2+ clients when no movement arrived this client tick
    @CultPacketHandler
    public void onClientTickEnd(PacketReceiveEvent event, CultPlayer player, ServerboundClientTickEndPacket packet) {
        if (!player.cameraEntity.isSelf()
                || (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                && !player.packetStateData.receivedMovementThisClientTick)) {
            resetDiggingState();
        }
    }

    private void resetOnCameraSwitch(CultPlayer player) {
        if (!player.cameraEntity.isSelf()) {
            resetDiggingState();
        }
    }

    private void resetDiggingState() {
        cancelledDigging = digging = false;
        startedDiggingPos = null;
        startedDiggingState = null;
    }

    private boolean shouldCheckPlacingWhileDigging() {
        return !exemptPlacingWhileDigging && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_8);
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (!player.canSkipTicks()) {
            if (setback) {
                setbackIfAboveSetbackVL();
                setback = false;
            }
            return;
        }

        if (player.isTickingReliablyFor(3)) {
            for (FlagData data : flags) {
                if (flag(write(data.type(), data.attacking(), data.rightClicking(), data.picking(),
                        data.releasing(), data.digging())) && setback) {
                    setbackIfAboveSetbackVL();
                    setback = false;
                }
            }
        }

        flags.clear();
        setback = false;
    }

    private void onAttack(PacketReceiveEvent event, CultPlayer player) {
        if (player.packetOrderProcessor.isRightClicking() || player.packetOrderProcessor.isPicking() || player.packetOrderProcessor.isReleasing() || player.packetOrderProcessor.isDigging()) {
            boolean rightClicking = player.packetOrderProcessor.isRightClicking();
            boolean picking = player.packetOrderProcessor.isPicking();
            boolean releasing = player.packetOrderProcessor.isReleasing();
            boolean digging = player.packetOrderProcessor.isDigging();
            if (!player.canSkipTicks()) {
                if (flag(write(TYPE_ATTACK, false, rightClicking, picking, releasing, digging)) && shouldModifyPackets()) {
                    event.setCancelled(true);
                    player.onPacketCancel();
                }
            } else {
                flags.add(new FlagData(TYPE_ATTACK, false, rightClicking, picking, releasing, digging));
            }
        }
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        exemptPlacingWhileDigging = config.getBooleanElse(getConfigName() + ".exempt-placing-while-digging", false);
    }

    private record FlagData(
            int type,
            boolean attacking,
            boolean rightClicking,
            boolean picking,
            boolean releasing,
            boolean digging) {}
}
