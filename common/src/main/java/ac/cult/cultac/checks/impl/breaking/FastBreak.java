package ac.cult.cultac.checks.impl.breaking;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.BlockBreakListener;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.network.CultPacketGroup;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.PacketGroup;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.BlockBreak;
import ac.cult.cultac.utils.collisions.ViaClientBlockShapeMappings;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.nmsutil.BlockBreakSpeed;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

// Based loosely off of Hawk BlockBreakSpeedSurvival
// Also based loosely off of NoCheatPlus FastBreak
// Also based off minecraft wiki: https://minecraft.wiki/w/Breaking#Instant_breaking
@CheckData(name = "FastBreak", stableKey = "cult.breaking.fast_break", description = "Breaking blocks too quickly")
public class FastBreak extends Check implements BlockBreakListener {
    private static final Verbose V =
            Verbose.of("[delay={ulong}ms|diff={f64:%.1f}ms, balance={f64:%.1f}ms], type={block}");

    // For some reason these states flag and I don't know why.
    // Better to just exempt to not annoy legit players.
    private static final Set<Block> EXEMPT_STATES = Set.of();

    public FastBreak(CultPlayer player) {
        super(player);
    }

    // The block the player is currently breaking
    BlockPos targetBlockPosition = null;
    // The maximum amount of damage the player deals to the block
    //
    double maximumBlockDamage = 0;
    // The last time a finish digging packet was sent, to enforce 0.3-second delay after non-instabreak
    long lastFinishBreak = 0;
    // The time the player started to break the block, to know how long the player waited until they finished breaking the block
    long startBreak = 0;

    // The buffer to this check
    double blockBreakBalance = 0;
    double blockDelayBalance = 0;

    public void onBlockBreak(BlockBreak blockBreak) {
        if (blockBreak.action == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) { // PE DiggingAction.START_DIGGING

            startBreak = System.currentTimeMillis() - (targetBlockPosition == null ? 50 : 0); // ???
            targetBlockPosition = blockBreak.position;

            // FIXME: getBlockDamage might not return the correct value if the player switched slots before this
            maximumBlockDamage = getClientBlockDamage(blockBreak.block);

            double breakDelay = System.currentTimeMillis() - lastFinishBreak;

            if (breakDelay >= 275) { // Reduce buffer if "close enough"
                blockDelayBalance *= 0.9;
            } else { // Otherwise, increase buffer
                blockDelayBalance += 300 - breakDelay;
            }

            if (blockDelayBalance > 1000) { // If more than a second of advantage
                int type = BuiltInRegistries.BLOCK.getId(blockBreak.block.getBlock());
                if (flag(V.write(verbose()).bool(true).ulong((long) breakDelay).f64(0).f64(0).sint(type)) && shouldModifyPackets()) {
                    blockBreak.cancel();
                }
            }

            clampBalance();
        }

        if (blockBreak.action == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK && targetBlockPosition != null) { // PE DiggingAction.FINISHED_DIGGING
            double predictedTime = Math.ceil(1 / maximumBlockDamage) * 50;
            double realTime = System.currentTimeMillis() - startBreak;
            double diff = predictedTime - realTime;

            clampBalance();

            if (diff < 25) {  // Reduce buffer if "close enough"
                blockBreakBalance *= 0.9;
            } else { // Otherwise, increase buffer
                blockBreakBalance += diff;
            }

            if (blockBreakBalance > 1000) { // If more than a second of advantage
                int type = BuiltInRegistries.BLOCK.getId(blockBreak.block.getBlock());
                if (flag(V.write(verbose()).bool(false).ulong(0).f64(diff).f64(blockBreakBalance).sint(type)) && shouldModifyPackets()) {
                    blockBreak.cancel();
                }
            }

            // also set start time because the breaking netcode is fucked on 1.14.4+
            lastFinishBreak = startBreak = System.currentTimeMillis();
        }
    }

    // Find the most optimal block damage using the animation packet, which is sent at least once a tick when breaking blocks
    // On 1.8 clients, via screws with this packet meaning we must fall back to the 1.8 idle flying packet
    //
    // listen for flying packets because some block breaks can happen before the next animation (somehow???), causing onGround desync
    @CultPacketHandler
    @CultPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, CultPlayer player, ServerboundMovePlayerPacket packet) {
        updateMaximumBlockDamage();
    }


    @CultPacketHandler
    public void onSwing(PacketReceiveEvent event, CultPlayer player, ServerboundSwingPacket packet) {
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9)) {
            updateMaximumBlockDamage();
        }
    }

    private void updateMaximumBlockDamage() {
        if (targetBlockPosition != null) {
            maximumBlockDamage = Math.max(maximumBlockDamage,
                    BlockBreakSpeed.getBlockDamage(player, targetBlockPosition));
        }
    }

    private double getClientBlockDamage(BlockState serverState) {
        BlockState clientState = ViaClientBlockShapeMappings.clientBlockState(player, serverState);
        return BlockBreakSpeed.getBlockDamage(player, player.getInventory().getHeldItem(), clientState);
    }

    private void clampBalance() {
        double balance = Math.max(1000, (player.getTransactionPing()));
        blockBreakBalance = CultMath.clamp(blockBreakBalance, -balance, balance); // Clamp not Math.max in case other logic changes
        blockDelayBalance = CultMath.clamp(blockDelayBalance, -balance, balance);
    }
}
