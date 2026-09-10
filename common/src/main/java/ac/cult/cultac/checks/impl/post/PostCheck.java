package ac.cult.cultac.checks.impl.post;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.CultPacketGroup;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.PacketGroup;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.network.packet.PacketCodecUtil;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.lists.EvictingQueue;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.SwingPacketUtil;
import ac.cult.cultac.network.event.PacketSendEvent;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action;

//@CheckData(name = "Post")
public class PostCheck extends Check implements CheckListener, PostPredictionListener {
    private String post;
    private final EvictingQueue<String> flags = new EvictingQueue<>(10);
    private boolean sentFlying = false;
    private int isExemptFromSwingingCheck = Integer.MIN_VALUE;

    public PostCheck(CultPlayer playerData) { super(playerData, CheckInfo.builder().name("Post").build()); }

    @CultPacketHandler
    public void onAnimate(PacketSendEvent event, CultPlayer player, ClientboundAnimatePacket packet) {
        if (packet.getId() == player.entityID) {
            int action = PacketCodecUtil.decodeUnsignedByte(packet.getAction());
            if (action == ClientboundAnimatePacket.SWING_MAIN_HAND ||
                    action == ClientboundAnimatePacket.SWING_OFF_HAND) {
                isExemptFromSwingingCheck = player.lastTransactionSent.get();
            }
        }
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        if (!flags.isEmpty()) {
            if (player.isTickingReliablyFor(3)) {
                for (String pendingFlag : flags) {
                    flag(pendingFlag);
                }
            }

            flags.clear();
        }
    }

    private void handleMovePlayer() {
        // TODO: Rewrite this check to be stronger, as in between tick and and the first transaction?
        if (player.packetStateData.lastPacketWasTeleport) {
            return;
        }
        post = null;
        sentFlying = true;
    }

    private void handleClientTickEnd() {
        if (sentFlying && post != null) {
            flags.add(post);
        }
        post = null;
        sentFlying = false;
    }

    private void recordPostPacket(Object packet) {
        if (sentFlying && post == null) {
            post = packetName(packet);
        }
    }

    private void handleSwing(Packet<?> packet) {
        if (sentFlying && post == null && isExemptFromSwingingCheck < player.lastTransactionReceived.get()) {
            post = packetName(packet);
        }
    }

    private void handlePlayerCommand(ServerboundPlayerCommandPacket packet) {
        if (!sentFlying) {
            return;
        }
        Action action = packet.getAction();
        boolean riding = player.compensatedEntities.getSelf().getRiding() != null;
        // Vanilla LocalPlayer#tick sends passenger Rot/MoveVehicle before sendIsSprintingIfNeeded().
        if (riding && (action == Action.START_SPRINTING || action == Action.STOP_SPRINTING)) {
            return;
        }
        if ((action != Action.START_FALL_FLYING || !riding) && post == null) {
            post = packetName(packet);
        }
    }

    @CultPacketHandler
    @CultPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, CultPlayer player, ServerboundMovePlayerPacket packet) {
        handleMovePlayer();
    }

    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundClientTickEndPacket")
    public void onClientTickEnd(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
        handleClientTickEnd();
    }

    @CultPacketHandler
    public void onPong(PacketReceiveEvent event, CultPlayer player, ServerboundPongPacket packet) {
        if (event.isAcceptedTransactionResponse()) {
            handleClientTickEnd();
        }
    }

    @CultPacketHandler
    public void onPlayerAbilities(PacketReceiveEvent event, CultPlayer player, ServerboundPlayerAbilitiesPacket packet) {
        recordPostPacket(packet);
    }

    @CultPacketHandler
    public void onInteract(PacketReceiveEvent event, CultPlayer player, ServerboundInteractPacket packet) {
        recordPostPacket(packet);
    }

    @CultPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundAttackPacket")
    public void onAttack(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
        recordPostPacket(packet);
    }

    @CultPacketHandler
    public void onSpectatorAction(PacketReceiveEvent event, CultPlayer player, ServerboundSpectatorActionPacket packet) {
        recordPostPacket(packet);
    }

    @CultPacketHandler
    public void onSetCarriedItem(PacketReceiveEvent event, CultPlayer player, ServerboundSetCarriedItemPacket packet) {
        recordPostPacket(packet);
    }

    @CultPacketHandler
    public void onUseItemOn(PacketReceiveEvent event, CultPlayer player, ServerboundUseItemOnPacket packet) {
        recordPostPacket(packet);
    }

    @CultPacketHandler
    public void onUseItem(PacketReceiveEvent event, CultPlayer player, ServerboundUseItemPacket packet) {
        recordPostPacket(packet);
    }

    @CultPacketHandler
    public void onPlayerAction(PacketReceiveEvent event, CultPlayer player, ServerboundPlayerActionPacket packet) {
        recordPostPacket(packet);
    }

    @CultPacketHandler(packetClass = SwingPacketUtil.LEGACY_SWING_PACKET)
    public void onSwing(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
        handleSwing(packet);
    }

    @CultPacketHandler(packetClass = SwingPacketUtil.PUNCH_PACKET)
    public void onPunch(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
        onSwing(event, player, packet);
    }

    @CultPacketHandler
    public void onPlayerCommand(PacketReceiveEvent event, CultPlayer player, ServerboundPlayerCommandPacket packet) {
        handlePlayerCommand(packet);
    }

    // TODO: Move to a utility class?
    private static String packetName(Object packet) {
        String packetName = packet.getClass().getSimpleName();
        if (packetName.startsWith("Serverbound")) {
            packetName = packetName.substring("Serverbound".length());
        } else if (packetName.startsWith("Clientbound")) {
            packetName = packetName.substring("Clientbound".length());
        }
        if (packetName.endsWith("Packet")) {
            packetName = packetName.substring(0, packetName.length() - "Packet".length());
        }

        StringBuilder builder = new StringBuilder(packetName.length() + 4);
        for (int i = 0; i < packetName.length(); i++) {
            char current = packetName.charAt(i);
            if (i > 0 && Character.isUpperCase(current)) {
                char previous = packetName.charAt(i - 1);
                boolean nextIsLower = i + 1 < packetName.length() && Character.isLowerCase(packetName.charAt(i + 1));
                if (Character.isLowerCase(previous) || Character.isDigit(previous) || nextIsLower) {
                    builder.append(' ');
                }
            }
            builder.append(Character.toLowerCase(current));
        }
        return builder.toString();
    }
}
