package ac.cult.cultac.checks.impl.breaking;

import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.engine.BlockSimClientVersion;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.interaction.MiningSpeed;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.blockplace.ClientBlockActions;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.nmsutil.BlockBreakSpeed;
import ac.cult.cultac.utils.nmsutil.ClientMiningState;

/**
 * {@link BlockBreakSpeed#getBlockDamage(CultPlayer, SimItemStack, int)} for one player, answering a repeat of the last
 * inputs with the last result. MiningSpeed#progress reads only the binding's immutable simulator, the model state, the
 * stack's immutable components, the mining state and the fatigue form, so equal inputs give the same value.
 */
public final class BlockDamageMemo {
    private ClientComponentRegistries.SimulationActions binding;
    private Components components;
    private BlockSimClientVersion fatigue;
    private int modelState;
    private boolean onGround;
    private boolean eyesInWater;
    private long efficiency;
    private long blockBreakSpeed;
    private long submergedSpeed;
    private int haste;
    private int conduitPower;
    private int miningFatigue;
    private double damage;

    public double damage(CultPlayer player, SimItemStack tool, int block) {
        if (player.gamemode == GameMode.CREATIVE || tool == null) {
            return BlockBreakSpeed.getBlockDamage(player, tool, block);
        }
        var binding = ClientBlockActions.binding(player);
        var mining = binding.simulator().miningSpeed();
        int modelState = binding.states().toModel(block);
        MiningSpeed.State state = ClientMiningState.of(player);
        BlockSimClientVersion fatigue = ClientMiningState.fatigueForm(player, binding);
        Components components = tool.components();
        if (binding == this.binding
                && components == this.components
                && fatigue == this.fatigue
                && modelState == this.modelState
                && sameState(state)) {
            return damage;
        }
        double result = mining.progress(tool, modelState, state, fatigue);
        this.binding = binding;
        this.components = components;
        this.fatigue = fatigue;
        this.modelState = modelState;
        onGround = state.onGround();
        eyesInWater = state.eyesInWater();
        efficiency = Double.doubleToRawLongBits(state.efficiency());
        blockBreakSpeed = Double.doubleToRawLongBits(state.blockBreakSpeed());
        submergedSpeed = Double.doubleToRawLongBits(state.submergedSpeed());
        haste = state.haste();
        conduitPower = state.conduitPower();
        miningFatigue = state.fatigue();
        damage = result;
        return result;
    }

    private boolean sameState(MiningSpeed.State state) {
        return state.onGround() == onGround
                && state.eyesInWater() == eyesInWater
                && Double.doubleToRawLongBits(state.efficiency()) == efficiency
                && Double.doubleToRawLongBits(state.blockBreakSpeed()) == blockBreakSpeed
                && Double.doubleToRawLongBits(state.submergedSpeed()) == submergedSpeed
                && state.haste() == haste
                && state.conduitPower() == conduitPower
                && state.fatigue() == miningFatigue;
    }
}
