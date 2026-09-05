package ac.cult.cultac.checks.impl.badpackets;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.DeadCheck;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.NmsPacketUtil;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.phys.Vec3;

@CheckData(name = "BadPacketsU", stableKey = "cult.badpackets.invalid_block_placement", description = "Sent impossible use item packet")
@DeadCheck(reason = DeadCheck.Reason.VERSION_GATED, detail = "Early-returns for clients >= 1.9; the trigger is the pre-1.9 use-item encoding.")
public class BadPacketsU extends Check implements CheckListener {
    private static final Verbose V =
            Verbose.of("xyz={mcpos}, cursor={cursor}, item={bool}, sequence={sint}");

    public BadPacketsU(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onUseItemOn(final PacketReceiveEvent event, CultPlayer player, ServerboundUseItemOnPacket packet) {
        // Supported clients cannot express legacy face-255 item use with this packet.
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9)) return;

        // This packet is always sent at (-1, -1, -1) at (0, 0, 0) on the block
        // except y gets wrapped?
        final int expectedY = player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_8) ? 4095 : 255;

        final NmsPacketUtil.UseItemOnData data = NmsPacketUtil.readUseItemOn(packet);
        final BlockPos pos = data.blockPosition();
        final Vec3 cursor = data.cursor();

        if (pos.getX() != -1
                || pos.getY() != expectedY
                || pos.getZ() != -1
                || cursor.x != 0
                || cursor.y != 0
                || cursor.z != 0
                || data.sequence() != 0
        ) {
            var buf = V.write(verbose())
                    .mcPos(pos.getX(), pos.getY(), pos.getZ())
                    .cursor((float) cursor.x, (float) cursor.y, (float) cursor.z)
                    .bool(true).sint(data.sequence());
            if (flag(buf) && shouldModifyPackets()) {
                player.onPacketCancel();
                event.setCancelled(true);
            }
        }
    }
}
