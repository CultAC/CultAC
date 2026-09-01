package ac.grim.grimac.checks.impl.post;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckInfo;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.network.GrimPacketGroup;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.PacketGroup;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.network.packet.PacketCodecUtil;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import ac.grim.grimac.utils.lists.EvictingQueue;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.event.PacketSendEvent;
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

    public PostCheck(GrimPlayer playerData) { super(playerData, CheckInfo.builder().name("Post").build()); }

    @GrimPacketHandler
    public void onAnimate(PacketSendEvent event, GrimPlayer player, ClientboundAnimatePacket packet) {
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

    private void handleSwing(ServerboundSwingPacket packet) {
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

    @GrimPacketHandler
    @GrimPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, GrimPlayer player, ServerboundMovePlayerPacket packet) {
        handleMovePlayer();
    }

    @GrimPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundClientTickEndPacket")
    public void onClientTickEnd(PacketReceiveEvent event, GrimPlayer player, Packet<?> packet) {
        handleClientTickEnd();
    }

    @GrimPacketHandler
    public void onPong(PacketReceiveEvent event, GrimPlayer player, ServerboundPongPacket packet) {
        if (event.isAcceptedTransactionResponse()) {
            handleClientTickEnd();
        }
    }

    @GrimPacketHandler
    public void onPlayerAbilities(PacketReceiveEvent event, GrimPlayer player, ServerboundPlayerAbilitiesPacket packet) {
        recordPostPacket(packet);
    }

    @GrimPacketHandler
    public void onInteract(PacketReceiveEvent event, GrimPlayer player, ServerboundInteractPacket packet) {
        recordPostPacket(packet);
    }

    @GrimPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundAttackPacket")
    public void onAttack(PacketReceiveEvent event, GrimPlayer player, Packet<?> packet) {
        recordPostPacket(packet);
    }

    @GrimPacketHandler
    public void onSpectatorAction(PacketReceiveEvent event, GrimPlayer player, ServerboundSpectatorActionPacket packet) {
        recordPostPacket(packet);
    }

    @GrimPacketHandler
    public void onSetCarriedItem(PacketReceiveEvent event, GrimPlayer player, ServerboundSetCarriedItemPacket packet) {
        recordPostPacket(packet);
    }

    @GrimPacketHandler
    public void onUseItemOn(PacketReceiveEvent event, GrimPlayer player, ServerboundUseItemOnPacket packet) {
        recordPostPacket(packet);
    }

    @GrimPacketHandler
    public void onUseItem(PacketReceiveEvent event, GrimPlayer player, ServerboundUseItemPacket packet) {
        recordPostPacket(packet);
    }

    @GrimPacketHandler
    public void onPlayerAction(PacketReceiveEvent event, GrimPlayer player, ServerboundPlayerActionPacket packet) {
        recordPostPacket(packet);
    }

    @GrimPacketHandler
    public void onSwing(PacketReceiveEvent event, GrimPlayer player, ServerboundSwingPacket packet) {
        handleSwing(packet);
    }

    @GrimPacketHandler
    public void onPlayerCommand(PacketReceiveEvent event, GrimPlayer player, ServerboundPlayerCommandPacket packet) {
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
