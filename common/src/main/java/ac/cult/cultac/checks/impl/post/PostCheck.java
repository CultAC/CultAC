package ac.cult.cultac.checks.impl.post;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAnimate;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSwingAnimation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPacket;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAbilities;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPong;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSetCarriedItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSpectatorAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSwing;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.value.SwingKind;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.lists.EvictingQueue;

// @CheckData(name = "Post")
public class PostCheck extends Check implements CheckListener, PostPredictionListener {
    private String post;
    private final EvictingQueue<String> flags = new EvictingQueue<>(10);
    private boolean sentFlying = false;
    private int isExemptFromSwingingCheck = Integer.MIN_VALUE;

    public PostCheck(CultPlayer playerData) {
        super(playerData, CheckInfo.builder().name("Post").build());
    }

    @CultPacketHandler
    public void onAnimate(PacketSendEvent<ClientboundAnimate> event, CultPlayer player, ClientboundAnimate packet) {
        if (ClientVersion.fromProtocolVersion(ProtocolVersion.V26_3.protocol()).isOlderThan(ClientVersion.V_26_3)
                && packet.entityId() == player.entityID) {
            int action = packet.action();
            if (action == 0 || action == 3) {
                isExemptFromSwingingCheck = player.lastTransactionSent.get();
            }
        }
    }

    @CultPacketHandler
    public void onSwingAnimation(
            PacketSendEvent<ClientboundSwingAnimation> event, CultPlayer player, ClientboundSwingAnimation packet) {
        if (packet.entityId() == player.entityID) {
            isExemptFromSwingingCheck = player.lastTransactionSent.get();
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

    private void recordPostPacket(ServerboundPacket packet) {
        if (sentFlying && post == null) {
            post = packetName(packet);
        }
    }

    private void handleSwing(ServerboundSwing packet) {
        if (sentFlying && post == null && isExemptFromSwingingCheck < player.lastTransactionReceived.get()) {
            post = packet.kind() == SwingKind.PUNCH ? "punch" : "swing";
        }
    }

    private void handlePlayerCommand(ServerboundPlayerCommand packet) {
        if (!sentFlying) {
            return;
        }
        PlayerCommandAction action = packet.action();
        boolean riding = player.compensatedEntities.getSelf().getRiding() != null;
        // Vanilla LocalPlayer#tick sends passenger Rot/MoveVehicle before sendIsSprintingIfNeeded().
        if (riding && (action == PlayerCommandAction.START_SPRINTING || action == PlayerCommandAction.STOP_SPRINTING)) {
            return;
        }
        if ((action != PlayerCommandAction.START_FLYING_WITH_ELYTRA || !riding) && post == null) {
            post = packetName(packet);
        }
    }

    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        handleMovePlayer();
    }

    @CultPacketHandler("serverbound.client_tick_end")
    public void onClientTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        handleClientTickEnd();
    }

    @CultPacketHandler
    public void onPong(PacketReceiveEvent<ServerboundPong> event, CultPlayer player, ServerboundPong packet) {
        if (event.isAcceptedTransactionResponse()) {
            handleClientTickEnd();
        }
    }

    @CultPacketHandler
    public void onPlayerAbilities(
            PacketReceiveEvent<ServerboundPlayerAbilities> event,
            CultPlayer player,
            ServerboundPlayerAbilities packet) {
        recordPostPacket(packet);
    }

    @CultPacketHandler
    public void onInteract(
            PacketReceiveEvent<ServerboundInteract> event, CultPlayer player, ServerboundInteract packet) {
        if ((packet.action() == ac.cult.cultac.protocol.value.InteractAction.ATTACK
                && event.getUser()
                        .getCultConnection()
                        .runtime()
                        .data()
                        .version()
                        .atLeast(ac.cult.cultac.protocol.ProtocolVersion.V26_1))) {
            if (sentFlying && post == null) post = "attack";
        } else {
            recordPostPacket(packet);
        }
    }

    @CultPacketHandler
    public void onSpectatorAction(
            PacketReceiveEvent<ServerboundSpectatorAction> event,
            CultPlayer player,
            ServerboundSpectatorAction packet) {
        recordPostPacket(packet);
    }

    @CultPacketHandler
    public void onSetCarriedItem(
            PacketReceiveEvent<ServerboundSetCarriedItem> event, CultPlayer player, ServerboundSetCarriedItem packet) {
        recordPostPacket(packet);
    }

    @CultPacketHandler
    public void onUseItemOn(
            PacketReceiveEvent<ServerboundUseItemOn> event, CultPlayer player, ServerboundUseItemOn packet) {
        recordPostPacket(packet);
    }

    @CultPacketHandler
    public void onUseItem(PacketReceiveEvent<ServerboundUseItem> event, CultPlayer player, ServerboundUseItem packet) {
        recordPostPacket(packet);
    }

    @CultPacketHandler
    public void onPlayerAction(
            PacketReceiveEvent<ServerboundPlayerAction> event, CultPlayer player, ServerboundPlayerAction packet) {
        recordPostPacket(packet);
    }

    @CultPacketHandler
    public void onSwing(PacketReceiveEvent<ServerboundSwing> event, CultPlayer player, ServerboundSwing packet) {
        handleSwing(packet);
    }

    @CultPacketHandler
    public void onPlayerCommand(
            PacketReceiveEvent<ServerboundPlayerCommand> event, CultPlayer player, ServerboundPlayerCommand packet) {
        handlePlayerCommand(packet);
    }

    // TODO: Move to a utility class?
    private static String packetName(ServerboundPacket packet) {
        String packetName = packet.getClass().getSimpleName().substring("Serverbound".length());

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
