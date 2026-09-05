package ac.cult.cultac.checks.impl.breaking;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.api.storage.verbose.VerboseTags;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.BlockBreakListener;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.BlockBreak;
import ac.cult.cultac.utils.nmsutil.BlockBreakSpeed;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.level.block.state.BlockState;

@CheckData(name = "WrongBreak", stableKey = "cult.breaking.wrong_break", description = "Sent block break progress for a different block than the one being mined")
public class WrongBreak extends Check implements BlockBreakListener {
    private static final Verbose V =
            Verbose.of("action={digging}, last=[{mcpos}|null], pos={mcpos}");
    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(SharedConstants.getProtocolVersion());


    private final int exemptedY = player.getClientVersion().isOlderThan(ClientVersion.V_1_8) ? 255 : (SERVER_VERSION.isNewerThanOrEquals(ClientVersion.V_1_14) ? -1 : 4095);
    private boolean lastBlockWasInstantBreak = false;
    private BlockPos lastBlock, lastCancelledBlock, lastLastBlock = null;

    public WrongBreak(final CultPlayer player) {
        super(player);
    }

    // The client sometimes sends a weird cancel packet
    private boolean shouldExempt(final BlockState block, int yPos) {
        // lastLastBlock is always null when this happens, and lastBlock isn't
        if (lastLastBlock != null || lastBlock == null)
            return false;

        // on pre 1.14.4 clients, the YPos of this packet is always the same
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_14_4) && yPos != exemptedY)
            return false;
        // and if this block is not an instant break
        return player.getClientVersion().isOlderThan(ClientVersion.V_1_14_4)
                // getBlockDamage might not return the correct value if the player
                // switched slots before this, check all slots just to be safe
                || !BlockBreakSpeed.couldInstantlyBreakBlock(player, block);
    }

    public void onBlockBreak(BlockBreak blockBreak) {
        if (blockBreak.action == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) { // PE DiggingAction.START_DIGGING
            final BlockPos pos = blockBreak.position;

            // getBlockDamage might not return the correct value if the player
            // switched slots before this, check all slots just to be safe
            lastBlockWasInstantBreak = BlockBreakSpeed.couldInstantlyBreakBlock(player, blockBreak.block);
            lastCancelledBlock = null;
            lastLastBlock = lastBlock;
            lastBlock = pos;
        }

        if (blockBreak.action == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK) { // PE DiggingAction.CANCELLED_DIGGING
            final BlockPos pos = blockBreak.position;

            if (!shouldExempt(blockBreak.block, pos.getY()) && !pos.equals(lastBlock)) {
                // https://github.com/GrimAnticheat/Grim/issues/1512
                if (player.getClientVersion().isOlderThan(ClientVersion.V_1_14_4) || (!lastBlockWasInstantBreak && pos.equals(lastCancelledBlock))) {
                    var buf = V.write(verbose()).uint(VerboseTags.enumId(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK))
                            .bool(lastBlock != null)
                            .mcPos(lastBlock == null ? 0 : lastBlock.getX(), lastBlock == null ? 0 : lastBlock.getY(), lastBlock == null ? 0 : lastBlock.getZ())
                            .mcPos(pos.getX(), pos.getY(), pos.getZ());
                    if (flag(buf)) {
                        if (shouldModifyPackets()) {
                            blockBreak.cancel();
                        }
                    }
                }
            }

            lastCancelledBlock = pos;
            lastLastBlock = null;
            lastBlock = null;
            return;
        }

        if (blockBreak.action == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) { // PE DiggingAction.FINISHED_DIGGING
            final BlockPos pos = blockBreak.position;

            // when a player looks away from the mined block, they send a cancel, and if they look at it again, they don't send another start. (thanks mojang!)
            if (!pos.equals(lastCancelledBlock) && (!lastBlockWasInstantBreak || player.getClientVersion().isOlderThan(ClientVersion.V_1_14_4)) && !pos.equals(lastBlock)) {
                var buf = V.write(verbose()).uint(VerboseTags.enumId(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK))
                        .bool(lastBlock != null)
                        .mcPos(lastBlock == null ? 0 : lastBlock.getX(), lastBlock == null ? 0 : lastBlock.getY(), lastBlock == null ? 0 : lastBlock.getZ())
                        .mcPos(pos.getX(), pos.getY(), pos.getZ());
                if (flag(buf)) {
                    if (shouldModifyPackets()) {
                        blockBreak.cancel();
                    }
                }
            }

            // 1.14.4+ clients don't send another start break in protected regions
            if (player.getClientVersion().isOlderThan(ClientVersion.V_1_14_4)) {
                lastCancelledBlock = null;
                lastLastBlock = null;
                lastBlock = null;
            }
        }
    }
}
