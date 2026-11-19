package ac.cult.cultac.cloud.diag;

import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.events.packets.PacketPlayerAbilities;
import ac.cult.cultac.events.packets.PacketWorldBorder;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntitySelf;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * The client state the engine holds for a player when a bundle is cut: abilities, game mode, self entity data,
 * movement attributes and effects, vehicle, camera, world border, level loading and transactions. One line per topic,
 * each {@code state <topic>} followed by {@code <key> <value>} pairs; a topic whose read fails prints
 * {@code state <topic> unreadable}. Entities appear by id and type only. Runs on the player's event loop, once per cut.
 */
final class EngineStateSnapshot {
    /** The most UTF-8 bytes of the snapshot in a bundle's header. */
    static final int MAX_BYTES = 1536;

    private static final MovementEffect[] EFFECTS = {
        MovementEffect.LEVITATION,
        MovementEffect.SLOW_FALLING,
        MovementEffect.JUMP_BOOST,
        MovementEffect.SPEED,
        MovementEffect.SLOWNESS,
        MovementEffect.DOLPHINS_GRACE
    };

    private EngineStateSnapshot() {}

    static String render(CultPlayer player) {
        PacketWorldBorder border = null;
        PacketPlayerAbilities abilities = null;
        try {
            border = player.checkManager.getWorldBorder();
            abilities = player.checkManager.getListener(PacketPlayerAbilities.class);
        } catch (RuntimeException unreadable) {
            // Their topics print unreadable.
        }
        return render(player, border, abilities);
    }

    static String render(
            CultPlayer player, @Nullable PacketWorldBorder border, @Nullable PacketPlayerAbilities abilities) {
        StringBuilder out = new StringBuilder(768);
        topic(out, "abilities", line -> abilities(line, player, abilities));
        topic(out, "gamemode", line -> line.append(" applied ")
                .append(player.gamemode)
                .append(" sent ")
                .append(player.serverGamemode));
        topic(out, "self", line -> self(line, player));
        topic(out, "attributes", line -> attributes(line, player.compensatedEntities.getSelf()));
        topic(out, "effects", line -> effects(line, player.compensatedEntities.getSelf()));
        topic(out, "border", line -> border(line, border));
        topic(out, "level", line -> line.append(" loaded ")
                .append(player.packetStateData.playerLoadedIntoLevel)
                .append(" ticked-since-spawn ")
                .append(player.packetStateData.localPlayerTickedSinceSpawn));
        topic(out, "transactions", line -> line.append(" sent ")
                .append(player.lastTransactionSent.get())
                .append(" received ")
                .append(player.lastTransactionReceived.get()));
        topic(out, "vehicle", line -> vehicle(line, player.compensatedEntities.getSelf()));
        topic(out, "camera", line -> camera(line, player));
        return out.toString();
    }

    private interface Topic {
        void write(StringBuilder line);
    }

    private static void topic(StringBuilder out, String name, Topic topic) {
        int start = out.length();
        if (start > 0) out.append('\n');
        out.append("state ").append(name);
        int body = out.length();
        try {
            topic.write(out);
        } catch (RuntimeException unreadable) {
            out.setLength(body);
            out.append(" unreadable");
        }
    }

    private static void abilities(StringBuilder line, CultPlayer player, @Nullable PacketPlayerAbilities abilities) {
        line.append(" mayfly ").append(player.canFly);
        if (abilities != null) line.append(" sent-mayfly ").append(abilities.lastSentCanFly());
        line.append(" flying ")
                .append(player.isFlying)
                .append(" fly-speed ")
                .append(player.flySpeed)
                .append(" invulnerable ")
                .append(player.isInvulnerable)
                .append(" instabuild ")
                .append(player.canInstabuild);
    }

    private static void self(StringBuilder line, CultPlayer player) {
        line.append(" no-gravity ")
                .append(!player.compensatedEntities.getSelf().hasGravity)
                .append(" pose ")
                .append(player.pose)
                .append(" fall-flying ")
                .append(player.isGliding)
                .append(" swimming ")
                .append(player.swimState.swimming())
                .append(" sneaking ")
                .append(player.isSneaking)
                .append(" sprinting ")
                .append(player.sprintState.flag())
                .append(" frozen-ticks ")
                .append(player.powderSnowFrozenTicks);
    }

    private static void attributes(StringBuilder line, PacketEntitySelf self) {
        line.append(" gravity ")
                .append(self.gravity)
                .append(" movement-speed ")
                .append(self.playerSpeed)
                .append(" jump-strength ")
                .append(self.jumpStrength)
                .append(" step-height ")
                .append(self.stepHeightAttribute)
                .append(" scale ")
                .append(self.scale)
                .append(" movement-efficiency ")
                .append(self.movementEfficiency)
                .append(" sneaking-speed ")
                .append(self.sneakingSpeed.value());
    }

    private static void effects(StringBuilder line, PacketEntitySelf self) {
        Map<MovementEffect, Integer> potions = self.potionsMap;
        int listed = 0;
        if (potions != null) {
            for (MovementEffect effect : EFFECTS) {
                Integer amplifier = potions.get(effect);
                if (amplifier == null) continue;
                line.append(' ').append(effect.name().toLowerCase(Locale.ROOT)).append(' ').append(amplifier);
                listed++;
            }
        }
        if (listed == 0) line.append(" none");
    }

    private static void vehicle(StringBuilder line, PacketEntitySelf self) {
        PacketEntity riding = self.getRiding();
        line.append(" riding ");
        if (riding == null) line.append("none");
        else entity(line, riding);
        line.append(" passengers ");
        entities(line, self.passengers);
    }

    private static void camera(StringBuilder line, CultPlayer player) {
        if (player.cameraEntity.isSelf()) {
            line.append(" self");
            return;
        }
        line.append(' ');
        entities(line, player.cameraEntity.getPossibilities());
    }

    private static void border(StringBuilder line, @Nullable PacketWorldBorder border) {
        if (border == null) throw new IllegalStateException();
        line.append(" center ")
                .append(border.getCenterX())
                .append(' ')
                .append(border.getCenterZ())
                .append(" size ")
                .append(border.getCurrentDiameter())
                .append(" target ")
                .append(border.getTargetDiameter());
        long ticks = border.getRemainingLerpTicks();
        if (ticks >= 0) line.append(" remaining ").append(ticks).append('t');
        else line.append(" remaining ").append(border.getRemainingLerpMillis()).append("ms");
        line.append(" absolute-max ").append(border.getAbsoluteMaxSize());
    }

    private static void entities(StringBuilder line, List<PacketEntity> entities) {
        if (entities.isEmpty()) {
            line.append("none");
            return;
        }
        for (int i = 0; i < entities.size(); i++) {
            if (i > 0) line.append(',');
            entity(line, entities.get(i));
        }
    }

    private static void entity(StringBuilder line, PacketEntity entity) {
        line.append(entity.getEntityId()).append(':').append(typeKey(entity.getType()));
    }

    private static String typeKey(int type) {
        List<EntityTypes.Type> types = EntityTypes.defaults().types();
        EntityTypes.Type known = type >= 0 && type < types.size() ? types.get(type) : null;
        return known == null ? "type-" + type : known.key();
    }
}
