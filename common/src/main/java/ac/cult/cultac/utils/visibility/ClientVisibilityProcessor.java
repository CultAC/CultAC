package ac.cult.cultac.utils.visibility;

import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.type.ClientTickEndListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSetCarriedItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import java.util.List;

/** One selection lifecycle: Minecraft#pick precedes every action in handleKeybinds. */
public final class ClientVisibilityProcessor extends CultProcessor implements CheckListener, ClientTickEndListener {
    private final ClientVisibility visibility;
    private boolean positionReported;
    private boolean movedThisTick;

    public ClientVisibilityProcessor(CultPlayer player) {
        super(player);
        visibility = new ClientVisibility(player);
    }

    @CultPacketHandler
    public void onMove(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        if (player.isBedrockMovement()) return;
        positionReported = packet.hasPosition();
        movedThisTick = true;
    }

    @CultPacketHandler
    public void onSetCarriedItem(
            PacketReceiveEvent<ServerboundSetCarriedItem> event, CultPlayer player, ServerboundSetCarriedItem packet) {
        if (!player.isBedrockMovement()) visibility.onSlotChange(packet.slot());
    }

    @CultPacketHandler
    public void onUseItem(PacketReceiveEvent<ServerboundUseItem> event, CultPlayer player, ServerboundUseItem packet) {
        beginSelection();
    }

    @CultPacketHandler
    public void onUseItemOn(
            PacketReceiveEvent<ServerboundUseItemOn> event, CultPlayer player, ServerboundUseItemOn packet) {
        beginSelection();
    }

    @CultPacketHandler
    public void onDig(
            PacketReceiveEvent<ServerboundPlayerAction> event, CultPlayer player, ServerboundPlayerAction packet) {
        // Drops, swaps and releases can also change the held item after this tick's pick.
        beginSelection();
    }

    @CultPacketHandler
    public void onInteract(
            PacketReceiveEvent<ServerboundInteract> event, CultPlayer player, ServerboundInteract packet) {
        if (player.compensatedEntities.getEntity(packet.entityId()) != null) beginSelection();
    }

    private void beginSelection() {
        if (!player.isBedrockMovement()) visibility.beginSelection();
    }

    public boolean blockFaceHidden(BlockPos position, Direction face) {
        if (untrackedCamera()) return false;
        return player.compensatedWorld.withSelectionSnapshot(
                () -> visibility.blockFaceHidden(position, face, positionError()));
    }

    public boolean entityHidden(PacketEntity entity) {
        if (untrackedView(entity)) return false;
        return player.compensatedWorld.withSelectionSnapshot(() -> visibility.entityHidden(entity, positionError()));
    }

    public List<ClientVisibility.ItemPick> attackItemPicks() {
        return player.isBedrockMovement() ? List.of() : visibility.attackItemPicks();
    }

    public boolean entityInteractionHidden(PacketEntity entity) {
        if (untrackedView(entity)) return false;
        return player.compensatedWorld.withSelectionSnapshot(
                () -> visibility.entityInteractionHidden(entity, positionError()));
    }

    // A target or camera riding outside the local player's own riding tree has no simulated position.
    private boolean untrackedView(PacketEntity target) {
        return player.compensatedEntities.isUntrackedRider(target) || untrackedCamera();
    }

    private boolean untrackedCamera() {
        if (player.cameraEntity.isSelf()) return false;
        for (PacketEntity camera : player.cameraEntity.getPossibilities()) {
            if (player.compensatedEntities.isUntrackedRider(camera)) return true;
        }
        return false;
    }

    private double positionError() {
        return positionReported ? 0 : player.getMovementThreshold();
    }

    @Override
    public void onPlayerTickEnd(PacketReceiveEvent event) {
        if (player.isBedrockMovement()) return;
        if (!movedThisTick) positionReported = false;
        movedThisTick = false;
        visibility.endSelection();
    }
}
