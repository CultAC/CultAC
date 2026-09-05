package ac.cult.cultac.checks.impl.vehicle;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.DeadCheck;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.player.Input;

@CheckData(name = "VehicleA", stableKey = "cult.vehicle.impossible_input", description = "Impossible input values")
@DeadCheck(reason = DeadCheck.Reason.WIRE_UNTRIGGERABLE, detail = "The 26.2 wire carries the input key bitset only; the derived +/-0.98 analog values can never exceed the checked bound.")
public class VehicleA extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("forwards={f32}, sideways={f32}");

    public VehicleA(CultPlayer player) {
        super(player);
    }


    @CultPacketHandler
    public void onPlayerInput(PacketReceiveEvent event, CultPlayer player, ServerboundPlayerInputPacket packet) {
        final Input input = packet.input();
        final float forwards = input.forward() ? 0.98f : input.backward() ? -0.98f : 0.0f;
        final float sideways = input.left() ? 0.98f : input.right() ? -0.98f : 0.0f;

        if (Math.abs(forwards) > 0.98f || Math.abs(sideways) > 0.98f) {
            if (flag(V.write(verbose()).f32(forwards).f32(sideways)) && shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }
    }
}
