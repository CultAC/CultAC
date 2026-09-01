package ac.grim.grimac.checks.impl.movement.timer;

import ac.grim.grimac.checks.CheckInfo;
import ac.grim.grimac.checks.DeadCheck;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.network.GrimPacketGroup;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.PacketGroup;
import ac.grim.grimac.network.packet.NmsPacketUtil;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket;
import org.bukkit.GameMode;

//@CheckData(name = "NegativeTimer", configName = "NegativeTimer", setback = 10)
@DeadCheck(reason = DeadCheck.Reason.DEAD_BY_CONSTRUCTION, detail = "Never constructed; its only flag call site is commented out.")
public class NegativeTimerCheck extends AbstractTimerCheck implements PostPredictionListener {

    int ticksWithoutNewTransactionOrLook = 0;
    int lastTransactionReceivedLastTick = 0;
    float lastXRot = 0;
    float lastYRot = 0;

    public NegativeTimerCheck(GrimPlayer grimPlayer) { super(grimPlayer, CheckInfo.builder().name("NegativeTimer").setback(10).build());
        this.timerBalanceRealTime = System.nanoTime() + this.clockDrift;
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        // We can't negative timer check a 1.9+ player who is standing still.
        if (predictionComplete.isExempt() || !player.isTickingReliablyFor(2)) {
            this.timerBalanceRealTime = System.nanoTime() + this.clockDrift;
        }

        // clock drift is 1200e6
        // We are flagging at only 184 ms behind real time
        //
        // The player's clock is therefore 1100 ms ahead of the server's clock, somehow?
        if (this.timerBalanceRealTime < this.knownPlayerClockTime - this.clockDrift) {
            int lostMS = (int) ((System.nanoTime() - timerBalanceRealTime) / 1e6);

            // TODO: This check is broken in two ways. can't figure out how or reproduce it.
            // On 1.9+ we aren't good enough about detecting a missed idle packet
            // On 1.8 I think some weird client screwed a bit with the tick loop
            //flag("-" + lostMS);

            timerBalanceRealTime += 50e6;
        }
    }

    private void recordNegativeMovePlayerPacket(final PacketReceiveEvent event, ServerboundMovePlayerPacket packet) {
        recordTimerEvent(event, false, shouldCountMovePlayerForTimer());
        handleMovePlayer(packet);
        clampTimerBalanceToDrift();
    }

    private void recordNegativeClientTickEndPacket(final PacketReceiveEvent event) {
        recordTimerEvent(event, false, shouldCountClientTickEndForTimer());
        clampTimerBalanceToDrift();
    }

    private void recordNegativeTransactionResponse(final PacketReceiveEvent event) {
        recordTimerEvent(event, true, false);
        this.ticksWithoutNewTransactionOrLook = 0;
        clampTimerBalanceToDrift();
    }

    private void clampTimerBalanceToDrift() {
        // Don't let the player get ahead by spamming movements then doing negative timer
        this.timerBalanceRealTime = Math.min(this.timerBalanceRealTime, System.nanoTime() + this.clockDrift);
    }

    private void handleMovePlayer(ServerboundMovePlayerPacket movePacket) {
        NmsPacketUtil.MovePlayerData flying = NmsPacketUtil.readMovePlayer(movePacket, player);

        float newXRot = flying.hasRotationChanged() ? flying.yaw() : lastXRot;
        float newYRot = flying.hasRotationChanged() ? flying.pitch() : lastYRot;
        this.lastTransactionReceivedLastTick = player.lastTransactionReceived.get();

        if (newXRot != this.lastXRot || newYRot != this.lastYRot) {
            this.lastXRot = newXRot;
            this.lastYRot = newYRot;
            this.ticksWithoutNewTransactionOrLook = 0;
        } else {
            ++this.ticksWithoutNewTransactionOrLook;

            // The player MIGHT have experienced tick skipping here, reset
            // (PONGS aren't responded to or new inputs taken when the client is ticking multiple times per frame)
            if (this.ticksWithoutNewTransactionOrLook >= 9) {
                //Bukkit.broadcastMessage("Resetting timer balance due to tick skipping");
                timerBalanceRealTime = System.nanoTime() + clockDrift;
            }
        }
    }

    @GrimPacketHandler
    @GrimPacketGroup(PacketGroup.SERVERBOUND_PLAYER_MOVEMENT)
    public void onMovePlayer(PacketReceiveEvent event, GrimPlayer player, ServerboundMovePlayerPacket packet) {
        if (player.isBedrockMovement()) {
            return;
        }
        recordNegativeMovePlayerPacket(event, packet);
    }

    @GrimPacketHandler(packetClass = "net.minecraft.network.protocol.game.ServerboundClientTickEndPacket")
    public void onClientTickEnd(PacketReceiveEvent event, GrimPlayer player, net.minecraft.network.protocol.Packet<?> packet) {
        recordNegativeClientTickEndPacket(event);
    }

    @GrimPacketHandler
    public void onPong(PacketReceiveEvent event, GrimPlayer player, ServerboundPongPacket packet) {
        recordNegativeTransactionResponse(event);
    }

    @GrimPacketHandler
    public void onContainerSlotStateChanged(PacketReceiveEvent event, GrimPlayer player, ServerboundContainerSlotStateChangedPacket packet) {
        recordNegativeTransactionResponse(event);
    }

    public void onBedrockAuthInput(PacketReceiveEvent event) {
        recordTimerEvent(event, false, player.gamemode != GameMode.SPECTATOR);
        clampTimerBalanceToDrift();
    }

    @Override
    protected boolean shouldCountMovePlayerForTimer() {
        return super.shouldCountMovePlayerForTimer() && player.gamemode != GameMode.SPECTATOR;
    }

    @Override
    protected boolean shouldCountClientTickEndForTimer() {
        return super.shouldCountClientTickEndForTimer() && player.gamemode != GameMode.SPECTATOR;
    }

    @Override
    public void doCheck(final PacketReceiveEvent event) {
        // We don't know if the player is ticking stable, therefore we must wait until prediction
        // determines this.  Do nothing here!

    }

    @Override
    public void reload() {
        super.reload(); this.clockDrift = (long) (getConfig().getDoubleElse(getConfigName() + ".drift", 1200.0) * 1e6);
    }
}
