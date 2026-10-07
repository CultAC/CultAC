package ac.cult.cultac.checks.type;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSwing;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.anticheat.StringReturner;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.LastInstance;
import ac.cult.cultac.utils.math.Vector3dm;
import ac.cult.cultac.utils.nmsutil.BlockBreakSpeed;
import ac.cult.cultac.utils.nmsutil.Ray;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import java.util.LinkedList;

/*
 * @author Inspired and originally written Sim0n
 * Correct digging logic by DefineOutside
 * Modified for use on 1.9+ clients
 */
public abstract class AutoClickCheck extends Check implements CheckListener, ClientTickEndListener {
    private final LinkedList<Long> samples = new LinkedList<>();

    private final int samplesBeforeCheck;
    private BlockPos diggingLocation = null;
    private PlayerAction lastDiggingAction = PlayerAction.STOP_DESTROY_BLOCK;
    // We don't know if it's digging until the tick after a player's look,
    // or the 300 ms after breaking a block successfully
    private final LastInstance lastDigging = new LastInstance(player);

    private double buffer = 0;
    private final double rewardBuffer;
    private final double flagBuffer;

    public AutoClickCheck(
            CultPlayer playerData,
            CheckInfo checkInfo,
            int samplesBeforeCheck,
            double rewardBuffer,
            double flagBuffer) {
        super(playerData, checkInfo);
        this.samplesBeforeCheck = samplesBeforeCheck;
        this.rewardBuffer = rewardBuffer;
        this.flagBuffer = flagBuffer;
    }

    public void increaseBuffer(StringReturner reason) {
        buffer++;
        if (buffer >= flagBuffer) {
            flag(reason.getString());
        }
        buffer = Math.min(buffer, flagBuffer);
    }

    public void decreaseBuffer() {
        buffer -= rewardBuffer;
        buffer = Math.max(buffer, 0);
    }

    private void handleDigAction(ServerboundPlayerAction action) {
        // Cancel digging is pointless in this game
        // You don't actually cancel the digging
        switch (action.action()) {
            case START_DESTROY_BLOCK:
                BlockPos blockPosition = action.position();
                double damage = BlockBreakSpeed.getBlockDamage(player, blockPosition);
                boolean wasInstabreak = (damage > 1 || (player.gamemode == GameMode.CREATIVE && damage != 0));

                if (wasInstabreak) { // Client is done mining first tick, no more packets
                    diggingLocation = null;
                    lastDiggingAction = PlayerAction.STOP_DESTROY_BLOCK;
                } else {
                    diggingLocation = blockPosition;
                    lastDiggingAction = PlayerAction.START_DESTROY_BLOCK;
                    lastDigging.reset();
                }
                break;
            case ABORT_DESTROY_BLOCK: // The player doesn't actually cancel digging, this is a lie
                lastDiggingAction = PlayerAction.ABORT_DESTROY_BLOCK;
                break;
            case STOP_DESTROY_BLOCK:
                lastDiggingAction = PlayerAction.STOP_DESTROY_BLOCK;
                // 300 ms delay after breaking blocks have animations without sending START_DIG
                if (diggingLocation != null) lastDigging.setRaw(-6);
                diggingLocation = null;
                break;
            default:
                lastDiggingAction = action.action();
                break;
        }
    }

    private void handleSwing() {
        if (!lastDigging.hasOccurredSince(2)) {
            samples.add(System.currentTimeMillis());

            if (samples.size() == samplesBeforeCheck) {
                handle(samples);
                samples.clear();
            }
        }
    }

    @CultPacketHandler
    public void onPlayerAction(
            PacketReceiveEvent<ServerboundPlayerAction> event, CultPlayer player, ServerboundPlayerAction packet) {
        handleDigAction(packet);
    }

    @CultPacketHandler
    public void onSwing(PacketReceiveEvent<ServerboundSwing> event, CultPlayer player, ServerboundSwing packet) {
        handleSwing();
    }

    @Override
    public void onPlayerTickEnd(PacketReceiveEvent event) {
        Vector3dm playerPos = new Vector3dm(player.lastX, player.lastY, player.lastZ);

        // If the player isn't within 10 blocks of the block they are digging, don't bother.
        if (diggingLocation != null
                && playerPos.distanceSquared(
                                new Vector3dm(diggingLocation.getX(), diggingLocation.getY(), diggingLocation.getZ()))
                        < 100) {
            if (lastDiggingAction
                    == PlayerAction.START_DESTROY_BLOCK) { // START_BREAK without FINISH_BREAK or CANCEL_BREAK
                lastDigging.reset();
            } else if (lastDiggingAction == PlayerAction.ABORT_DESTROY_BLOCK) { // Buggy cancel digging
                // Brute force eye height because desync
                for (double eyeHeight : player.getPossibleEyeHeights()) {
                    Ray trace = new Ray(
                            player,
                            playerPos.getX(),
                            playerPos.getY() + eyeHeight,
                            playerPos.getZ(),
                            player.xRot,
                            player.yRot);
                    Vector3dm endVec = trace.getPointAtDistance(5);

                    SimpleCollisionBox hitbox =
                            new SimpleCollisionBox(diggingLocation).expand(0.1); // Give some more lenience
                    Vector3dm intercept = ReachUtils.calculateIntercept(hitbox, playerPos, endVec)
                            .getFirst();

                    if (ReachUtils.isVecInside(hitbox, playerPos) || intercept != null) {
                        lastDigging.reset();
                        return;
                    }
                }
            }
        }
    }

    public abstract void handle(LinkedList<Long> samples);
}
