package ac.grim.grimac.checks.type;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckInfo;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.StringReturner;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.LastInstance;
import ac.grim.grimac.utils.nmsutil.BlockBreakSpeed;
import ac.grim.grimac.utils.nmsutil.Ray;
import ac.grim.grimac.utils.nmsutil.ReachUtils;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.packet.NmsPacketUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import org.bukkit.GameMode;
import org.bukkit.util.Vector;

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
    private Action lastDiggingAction = Action.STOP_DESTROY_BLOCK;
    // We don't know if it's digging until the tick after a player's look,
    // or the 300 ms after breaking a block successfully
    private final LastInstance lastDigging = new LastInstance(player);

    private double buffer = 0;
    private final double rewardBuffer;
    private final double flagBuffer;


    public AutoClickCheck(GrimPlayer playerData, CheckInfo checkInfo, int samplesBeforeCheck, double rewardBuffer, double flagBuffer) { super(playerData, checkInfo);
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

    private void handleDigAction(ServerboundPlayerActionPacket actionPacket) {
        // Cancel digging is pointless in this game
        // You don't actually cancel the digging
        NmsPacketUtil.PlayerActionData action = NmsPacketUtil.readPlayerAction(actionPacket);
        switch (action.action()) {
            case START_DESTROY_BLOCK:
                double damage = BlockBreakSpeed.getBlockDamage(player, action.blockPosition());
                boolean wasInstabreak = (damage > 1 || (player.gamemode == GameMode.CREATIVE && damage != 0));

                if (wasInstabreak) { // Client is done mining first tick, no more packets
                    diggingLocation = null;
                    lastDiggingAction = Action.STOP_DESTROY_BLOCK;
                } else {
                    diggingLocation = new net.minecraft.core.BlockPos(action.blockPosition());
                    lastDiggingAction = Action.START_DESTROY_BLOCK;
                    lastDigging.reset();
                }
                break;
            case ABORT_DESTROY_BLOCK: // The player doesn't actually cancel digging, this is a lie
                lastDiggingAction = Action.ABORT_DESTROY_BLOCK;
                break;
            case STOP_DESTROY_BLOCK:
                lastDiggingAction = Action.STOP_DESTROY_BLOCK;
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

    @GrimPacketHandler
    public void onPlayerAction(PacketReceiveEvent event, GrimPlayer player, ServerboundPlayerActionPacket packet) {
        handleDigAction(packet);
    }

    @GrimPacketHandler
    public void onSwing(PacketReceiveEvent event, GrimPlayer player, ServerboundSwingPacket packet) {
        handleSwing();
    }

    @Override
    public void onPlayerTickEnd(PacketReceiveEvent event) {
        Vector playerPos = new Vector(player.lastX, player.lastY, player.lastZ);

        // If the player isn't within 10 blocks of the block they are digging, don't bother.
        if (diggingLocation != null && playerPos.distanceSquared(new Vector(diggingLocation.getX(), diggingLocation.getY(), diggingLocation.getZ())) < 100) {
            if (lastDiggingAction == Action.START_DESTROY_BLOCK) { // START_BREAK without FINISH_BREAK or CANCEL_BREAK
                lastDigging.reset();
            } else if (lastDiggingAction == Action.ABORT_DESTROY_BLOCK) { // Buggy cancel digging
                // Brute force eye height because desync
                for (double eyeHeight : player.getPossibleEyeHeights()) {
                    Ray trace = new Ray(player, playerPos.getX(), playerPos.getY() + eyeHeight, playerPos.getZ(), player.xRot, player.yRot);
                    Vector endVec = trace.getPointAtDistance(5);

                    SimpleCollisionBox hitbox = new SimpleCollisionBox(diggingLocation).expand(0.1); // Give some more lenience
                    Vector intercept = ReachUtils.calculateIntercept(hitbox, playerPos, endVec).getFirst();

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
