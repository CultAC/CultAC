package ac.cult.cultac.checks.impl.packetorder;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.DeadCheck;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.DecodedPacketReliability;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.nmsutil.EntityTypesCompat;
import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.api.storage.verbose.VerboseTags;

@CheckData(
        name = "PacketOrderC",
        stableKey = "cult.packetorder.interact_order",
        description = "Sent INTERACT and INTERACT_AT entity packets in the wrong order")
@DeadCheck(reason = DeadCheck.Reason.VERSION_GATED, detail = "isApplicable() requires a client older than 26.1.")
public class PacketOrderC extends Check implements CheckListener {
    // Shape index == KIND_* constant value.
    private static final Verbose V = Verbose.of("Skipped Interact-At")
            .or("Skipped Interact")
            .or("Skipped Interact (Tick)")
            .or(
                    "requiredEntity={sint}, entity={sint}, requiredHand={hand}, hand={hand}, requiredSneaking={bool}, sneaking={bool}");

    static final int KIND_SKIPPED_INTERACT_AT = 0;
    static final int KIND_SKIPPED_INTERACT = 1;
    static final int KIND_SKIPPED_INTERACT_TICK = 2;
    static final int KIND_MISMATCH = 3;

    private boolean sentInteractAt = false;
    private int requiredEntity;
    private Hand requiredHand;
    private boolean requiredSneaking;

    public PacketOrderC(final CultPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        return player.getClientVersion().isNewerThan(ClientVersion.V_1_7_10) // 1.7 players do not send INTERACT_AT
                && player.getClientVersion().isOlderThan(ClientVersion.V_26_1); // 26.1 players do not send INTERACT
    }

    private Verbose.Writer writeKind(int kind) {
        return V.write(verbose(), kind);
    }

    @CultPacketHandler
    public void onInteract(
            PacketReceiveEvent<ServerboundInteract> event, CultPlayer player, ServerboundInteract packet) {
        if (!isApplicable()
                || !DecodedPacketReliability.interactionFamilyReliable(
                        player.getClientVersion(), player.getObservedProtocol())) return;

        final PacketEntity entity = player.compensatedEntities.entityMap.get(packet.entityId());

        // For armor stands, vanilla clients send:
        //  - when renaming the armor stand or in spectator mode: INTERACT_AT + INTERACT
        //  - in all other cases: only INTERACT
        // Just exempt armor stands to be safe
        if (entity != null && entity.getType() == EntityTypesCompat.ARMOR_STAND) return;

        final boolean sneaking = packet.sneaking();

        switch (packet.action()) {
            // INTERACT_AT then INTERACT
            case INTERACT:
                if (!sentInteractAt) {
                    if (flag(writeKind(KIND_SKIPPED_INTERACT_AT)) && shouldModifyPackets()) {
                        event.setCancelled(true);
                        player.onPacketCancel();
                    }
                } else if (packet.entityId() != requiredEntity
                        || packet.hand() != requiredHand
                        || sneaking != requiredSneaking) {
                    if (flag(V.write(verbose(), KIND_MISMATCH)
                                    .sint(requiredEntity)
                                    .sint(packet.entityId())
                                    .uint(VerboseTags.enumId(requiredHand))
                                    .uint(VerboseTags.enumId(packet.hand()))
                                    .bool(requiredSneaking)
                                    .bool(sneaking))
                            && shouldModifyPackets()) {
                        event.setCancelled(true);
                        player.onPacketCancel();
                    }
                }

                sentInteractAt = false;
                break;
            case INTERACT_AT:
                if (sentInteractAt) {
                    if (flag(writeKind(KIND_SKIPPED_INTERACT)) && shouldModifyPackets()) {
                        event.setCancelled(true);
                        player.onPacketCancel();
                    }
                }

                requiredHand = packet.hand();
                requiredEntity = packet.entityId();
                requiredSneaking = sneaking;
                sentInteractAt = true;
                break;
            default:
                break;
        }
    }

    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        if (!isApplicable()) return;

        if (sentInteractAt) {
            sentInteractAt = false;
            flag(writeKind(KIND_SKIPPED_INTERACT_TICK));
        }
    }
}
