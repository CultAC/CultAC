package ac.grim.grimac.checks.impl.packetorder;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.api.storage.verbose.VerboseTags;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.DeadCheck;
import ac.grim.grimac.network.GrimPacketGroup;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.PacketGroup;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.packet.DecodedPacketReliability;
import ac.grim.grimac.network.packet.NmsPacketUtil;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.nmsutil.EntityTypesCompat;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;

@CheckData(name = "PacketOrderC", stableKey = "grim.packetorder.interact_order", description = "Sent INTERACT and INTERACT_AT entity packets in the wrong order")
@DeadCheck(reason = DeadCheck.Reason.VERSION_GATED, detail = "isApplicable() requires a client older than 26.1.")
public class PacketOrderC extends Check implements CheckListener {
    // Shape index == KIND_* constant value.
    private static final Verbose V = Verbose
            .of("Skipped Interact-At")
            .or("Skipped Interact")
            .or("Skipped Interact (Tick)")
            .or("requiredEntity={sint}, entity={sint}, requiredHand={hand}, hand={hand}, requiredSneaking={bool}, sneaking={bool}");

    static final int KIND_SKIPPED_INTERACT_AT = 0;
    static final int KIND_SKIPPED_INTERACT = 1;
    static final int KIND_SKIPPED_INTERACT_TICK = 2;
    static final int KIND_MISMATCH = 3;

    private boolean sentInteractAt = false;
    private int requiredEntity;
    private InteractionHand requiredHand;
    private boolean requiredSneaking;

    public PacketOrderC(final GrimPlayer player) {
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

    @GrimPacketHandler
    public void onInteract(PacketReceiveEvent event, GrimPlayer player, ServerboundInteractPacket packet) {
        if (!isApplicable()
                || !DecodedPacketReliability.interactionFamilyReliable(player.getClientVersion())) return;

        final NmsPacketUtil.InteractData data = NmsPacketUtil.readInteract(packet);

        final PacketEntity entity = player.compensatedEntities.entityMap.get(data.entityId());

        // For armor stands, vanilla clients send:
        //  - when renaming the armor stand or in spectator mode: INTERACT_AT + INTERACT
        //  - in all other cases: only INTERACT
        // Just exempt armor stands to be safe
        if (entity != null && entity.getType() == EntityTypesCompat.ARMOR_STAND) return;

        final boolean sneaking = data.sneaking();

        switch (data.action()) {
            // INTERACT_AT then INTERACT
            case INTERACT:
                if (!sentInteractAt) {
                    if (flag(writeKind(KIND_SKIPPED_INTERACT_AT)) && shouldModifyPackets()) {
                        event.setCancelled(true);
                        player.onPacketCancel();
                    }
                } else if (data.entityId() != requiredEntity || data.hand() != requiredHand || sneaking != requiredSneaking) {
                    if (flag(V.write(verbose(), KIND_MISMATCH)
                            .sint(requiredEntity)
                            .sint(data.entityId())
                            .uint(VerboseTags.enumId(requiredHand))
                            .uint(VerboseTags.enumId(data.hand()))
                            .bool(requiredSneaking)
                            .bool(sneaking)) && shouldModifyPackets()) {
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

                requiredHand = data.hand();
                requiredEntity = data.entityId();
                requiredSneaking = sneaking;
                sentInteractAt = true;
                break;
            default:
                break;
        }
    }


    @GrimPacketHandler
    @GrimPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, GrimPlayer player, ServerboundMovePlayerPacket packet) {
        if (!isApplicable()) return;

        if (sentInteractAt) {
            sentInteractAt = false;
            flag(writeKind(KIND_SKIPPED_INTERACT_TICK));
        }
    }
}
