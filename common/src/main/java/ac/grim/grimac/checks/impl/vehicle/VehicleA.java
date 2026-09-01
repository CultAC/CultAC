package ac.grim.grimac.checks.impl.vehicle;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.DeadCheck;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.player.Input;

@CheckData(name = "VehicleA", stableKey = "grim.vehicle.impossible_input", description = "Impossible input values")
@DeadCheck(reason = DeadCheck.Reason.WIRE_UNTRIGGERABLE, detail = "The 26.2 wire carries the input key bitset only; the derived +/-0.98 analog values can never exceed the checked bound.")
public class VehicleA extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("forwards={f32}, sideways={f32}");

    public VehicleA(GrimPlayer player) {
        super(player);
    }


    @GrimPacketHandler
    public void onPlayerInput(PacketReceiveEvent event, GrimPlayer player, ServerboundPlayerInputPacket packet) {
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
