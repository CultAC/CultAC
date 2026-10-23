package ac.cult.cultac.checks.impl.breaking;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.BlockBreakListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSwing;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.anticheat.update.BlockBreak;
import ac.cult.cultac.utils.collisions.ViaClientBlockShapeMappings;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.nmsutil.BlockBreakSpeed;
import ac.grim.grimac.api.storage.verbose.Verbose;

// Based loosely off of Hawk BlockBreakSpeedSurvival
// Also based loosely off of NoCheatPlus FastBreak
// Also based off minecraft wiki: https://minecraft.wiki/w/Breaking#Instant_breaking
@CheckData(name = "FastBreak", stableKey = "cult.breaking.fast_break", description = "Breaking blocks too quickly")
public class FastBreak extends Check implements BlockBreakListener {
    private static final Verbose V =
            Verbose.of("[delay={ulong}ms|diff={f64:%.1f}ms, balance={f64:%.1f}ms], type={block}");

    public FastBreak(CultPlayer player) {
        super(player);
    }

    // MultiPlayerGameMode#continueDestroyBlock waits this many ticks after each STOP before it progresses again
    private static final int DESTROY_DELAY_TICKS = 5;
    private static final double MILLISECONDS_PER_TICK = 50;

    // The block the player is currently breaking
    BlockPos targetBlockPosition = null;
    // The maximum amount of damage the player deals to the block
    //
    double maximumBlockDamage = 0;
    // Client ticks, counted by client_tick_end
    int clientTicks = 0;
    // The client tick the next STOP is timed from
    int startTick = 0;
    // Swings received after the START within its own client tick
    int startTickSwings = 0;
    // The destroyDelay ticks the client still has to wait out from startTick
    int startDestroyDelay = 0;
    int lastStopTick = 0;
    boolean receivedStop = false;

    // The buffer to this check
    double blockBreakBalance = 0;

    public void onBlockBreak(BlockBreak blockBreak) {
        if (blockBreak.action == PlayerAction.START_DESTROY_BLOCK) {
            targetBlockPosition = blockBreak.position;

            // FIXME: getBlockDamage might not return the correct value if the player switched slots before this
            maximumBlockDamage = getClientBlockDamage(blockBreak.block);

            startTick = clientTicks;
            startTickSwings = 0;
            // The STOP's destroyDelay runs down by at most one per client tick
            startDestroyDelay = receivedStop
                    ? CultMath.clamp(DESTROY_DELAY_TICKS + 1 - (clientTicks - lastStopTick), 0, DESTROY_DELAY_TICKS)
                    : 0;
        }

        if (blockBreak.action == PlayerAction.STOP_DESTROY_BLOCK && targetBlockPosition != null) {
            // MultiPlayerGameMode#continueDestroyBlock sends STOP only at its target, after adding the state's progress
            if (blockBreak.position.equals(targetBlockPosition)) {
                maximumBlockDamage = Math.max(maximumBlockDamage, getClientBlockDamage(blockBreak.block));
            }
            // Minecraft#startAttack swings once after START; continueAttack swings again in that tick only when
            // continueDestroyBlock ran, the one way progress or a destroyDelay tick happens in the START tick
            double startTickCredit = startTickSwings >= 2 ? 1 : 0;
            double predictedTicks = Math.ceil(1 / maximumBlockDamage) + startDestroyDelay - startTickCredit;
            double diff = (predictedTicks - (clientTicks - startTick)) * MILLISECONDS_PER_TICK;

            clampBalance();

            if (diff < 25) { // Reduce buffer if "close enough"
                blockBreakBalance *= 0.9;
            } else { // Otherwise, increase buffer
                blockBreakBalance += diff;
            }

            if (blockBreakBalance > 1000) { // If more than a second of advantage
                int type = DataTables.defaults().registry().blockIndex(blockBreak.block);
                if (flag(V.write(verbose())
                                .bool(false)
                                .ulong(0)
                                .f64(diff)
                                .f64(blockBreakBalance)
                                .sint(type))
                        && shouldModifyPackets()) {
                    blockBreak.cancel();
                }
            }

            lastStopTick = startTick = clientTicks;
            startTickSwings = 0;
            startDestroyDelay = DESTROY_DELAY_TICKS;
            receivedStop = true;
        }
    }

    @CultPacketHandler("serverbound.client_tick_end")
    public void onClientTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        clientTicks++;
    }

    /** ClientPacketListener#handleLogin's new MultiPlayerGameMode starts its destroyDelay at 0. */
    public void resetDestroyDelay() {
        receivedStop = false;
        lastStopTick = 0;
    }

    // Find the most optimal block damage using the animation packet, which is sent at least once a tick when breaking
    // blocks
    //
    // listen for flying packets because some block breaks can happen before the next animation (somehow???), causing
    // onGround desync
    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        updateMaximumBlockDamage();
    }

    @CultPacketHandler
    public void onSwing(PacketReceiveEvent<ServerboundSwing> event, CultPlayer player, ServerboundSwing packet) {
        if (clientTicks == startTick) startTickSwings++;
        updateMaximumBlockDamage();
    }

    private void updateMaximumBlockDamage() {
        if (targetBlockPosition != null) {
            maximumBlockDamage =
                    Math.max(maximumBlockDamage, BlockBreakSpeed.getBlockDamage(player, targetBlockPosition));
        }
    }

    private double getClientBlockDamage(int serverState) {
        int clientState = ViaClientBlockShapeMappings.clientBlockStateId(player, serverState);
        return BlockBreakSpeed.getBlockDamage(player, player.getInventory().getHeldItem(), clientState);
    }

    private void clampBalance() {
        double balance = Math.max(1000, (player.getTransactionPing()));
        blockBreakBalance =
                CultMath.clamp(blockBreakBalance, -balance, balance); // Clamp not Math.max in case other logic changes
    }
}
