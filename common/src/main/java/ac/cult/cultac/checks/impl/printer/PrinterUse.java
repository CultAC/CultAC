package ac.cult.cultac.checks.impl.printer;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.grim.grimac.api.config.ConfigManager;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.LongSupplier;
import org.jetbrains.annotations.NotNull;

/**
 * Recognises the wire pattern of the sakura-ryoko litematica-printer (26.x, {@code rotate} on) and turns the noise
 * it makes in other checks into one rare informational alert.
 *
 * <p>Per placement the printer runs, inside {@code LocalPlayer#tick}, after the tick's own movement:
 * <ol>
 *   <li>{@code PrepareAction}: an extra {@code PosRot} at the player's current position carrying an axis-aligned
 *       look (yaw a multiple of 90, or the real yaw; pitch -90, 0 or 90), then a player-input packet;
 *   <li>{@code InteractAction}: {@code useItemOn} and at once {@code useItem}, whose look is the real one.
 * </ol>
 * and its packet mixin rewrites the look of every movement packet while an action is pending. A spoofed extra
 * move followed within {@code gap-ticks} client ticks by a use-on/use pair in one tick is one printer action.
 * {@code min-actions} actions within {@code window-ms} activate the detector; it stays active {@code hold-ms}
 * after the last one.
 *
 * <p>While active, {@link Check#flag} drops flags of the checks in {@code absorbed-checks} when they fall inside
 * the bounds below. Anything outside them still flags normally, and no other check is touched:
 * <ul>
 *   <li>Timer, TickTimer: only when every extra move this tick was a spoofed move at an unchanged position, and
 *       at most {@code max-extra-moves-per-tick} of them;
 *   <li>AimDuplicateLook, BadPacketsJ: only within {@code hold-ms} of a spoofed move (the look they trip on);
 *   <li>RotationPlace, PlaceVisibility: only for a click whose block centre is within {@code max-range} of the
 *       eye, on top of the spoofed-move condition.
 * </ul>
 *
 * <p>Config, under {@code PrinterUse:} in config.yml (all optional): {@code enabled} (true), {@code min-actions}
 * (2), {@code window-ms} (5000), {@code hold-ms} (2000), {@code gap-ticks} (3), {@code max-extra-moves-per-tick}
 * (5, the printer runs at most 10 steps per tick and every second one is a prepare), {@code max-range} (5.5; the
 * printer's own limit is 5.0), {@code alert-interval-ms} (60000), {@code absorbed-checks}.
 */
public final class PrinterUse extends Check implements PostPredictionListener {
    private static final List<String> DEFAULT_ABSORBED =
            List.of("AimDuplicateLook", "BadPacketsJ", "RotationPlace", "PlaceVisibility", "Timer", "TickTimer");
    private static final int MAX_TRACKED_ACTIONS = 256;

    private LongSupplier clock = System::currentTimeMillis;

    private boolean enabledByConfig = true;
    private int minActions = 2;
    private long windowMs = 5000;
    private long holdMs = 2000;
    private int gapTicks = 3;
    private int maxExtraMoves = 5;
    private double maxRange = 5.5;
    private long alertIntervalMs = 60_000;
    private Set<String> absorbed = lowerCase(DEFAULT_ABSORBED);

    private int tick;
    private boolean tickEnded;
    private int movesThisTick;
    private int spoofsThisTick;

    private boolean hasLastPosition;
    private double lastX, lastY, lastZ;
    private float lastYaw = Float.NaN;

    private long lastSpoofTime = Long.MIN_VALUE / 2;
    private int lastSpoofTick = Integer.MIN_VALUE / 2;
    private int useItemOnTick = Integer.MIN_VALUE / 2;
    private boolean placeInRange;

    private final ArrayDeque<Long> actionTimes = new ArrayDeque<>();
    private long lastActionTime = Long.MIN_VALUE / 2;
    private long lastAlert = Long.MIN_VALUE / 2;
    private long totalActions;
    private final Map<String, Integer> absorbedSinceAlert = new TreeMap<>();

    public PrinterUse(CultPlayer player) {
        super(
                player,
                CheckInfo.builder()
                        .name("PrinterUse")
                        .stableKey("cult.printer.use")
                        .description("Used a litematica printer: spoofed look moves followed by use-item pairs")
                        .setback(-1)
                        .build());
        reload();
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        enabledByConfig = config.getBooleanElse("PrinterUse.enabled", true);
        minActions = Math.max(1, config.getIntElse("PrinterUse.min-actions", 2));
        windowMs = config.getIntElse("PrinterUse.window-ms", 5000);
        holdMs = config.getIntElse("PrinterUse.hold-ms", 2000);
        gapTicks = Math.max(0, config.getIntElse("PrinterUse.gap-ticks", 3));
        maxExtraMoves = Math.max(0, config.getIntElse("PrinterUse.max-extra-moves-per-tick", 5));
        maxRange = config.getDoubleElse("PrinterUse.max-range", 5.5);
        alertIntervalMs = config.getIntElse("PrinterUse.alert-interval-ms", 60_000);
        List<String> configured = config.getStringListElse("PrinterUse.absorbed-checks", DEFAULT_ABSORBED);
        // An empty list would silently turn the absorption off; PrinterUse.enabled does that explicitly.
        absorbed = lowerCase(configured == null || configured.isEmpty() ? DEFAULT_ABSORBED : configured);
    }

    /** Replaces the wall clock, for tests. */
    public void useClock(LongSupplier clock) {
        this.clock = clock;
    }

    /** An ordinary movement packet, not a teleport response, before any check sees it. */
    public void onMovePlayer(ServerboundMovePlayer packet) {
        if (!tracking()) return;
        rollTick();
        boolean spoof = packet.hasPosition()
                && packet.hasRotation()
                && hasLastPosition
                && packet.x() == lastX
                && packet.y() == lastY
                && packet.z() == lastZ
                && spoofedLook(packet.yaw(), packet.pitch());
        if (spoof) {
            lastSpoofTick = tick;
            lastSpoofTime = clock.getAsLong();
            if (movesThisTick > 0) spoofsThisTick++;
        } else if (packet.hasRotation()) {
            lastYaw = packet.yaw();
        }
        movesThisTick++;
        if (packet.hasPosition()) {
            hasLastPosition = true;
            lastX = packet.x();
            lastY = packet.y();
            lastZ = packet.z();
        }
    }

    public void onUseItemOn(ServerboundUseItemOn packet) {
        if (!tracking()) return;
        rollTick();
        useItemOnTick = tick;
        double eyeY = player.y + Collections.max(player.getPossibleEyeHeights());
        double dx = packet.blockPosition().getX() + 0.5 - player.x;
        double dy = packet.blockPosition().getY() + 0.5 - eyeY;
        double dz = packet.blockPosition().getZ() + 0.5 - player.z;
        placeInRange = Math.sqrt(dx * dx + dy * dy + dz * dz) <= maxRange;
    }

    public void onUseItem(ServerboundUseItem packet) {
        if (!tracking()) return;
        rollTick();
        if (useItemOnTick == tick && tick - lastSpoofTick <= gapTicks) recordAction();
    }

    public void onClientTickEnd() {
        if (!tracking()) return;
        tickEnded = true;
    }

    /** Whether {@code check}'s flag right now is the printer's noise, and so is dropped. */
    public boolean absorbs(Check check) {
        if (check == this || !enabledByConfig || player.isBedrockMovement()) return false;
        String name = check.getCheckName();
        if (name == null || !absorbed.contains(name.toLowerCase(Locale.ROOT))) return false;
        long now = clock.getAsLong();
        if (!active(now)) return false;
        boolean recentSpoof = now - lastSpoofTime <= holdMs;
        boolean inBounds = switch (name) {
            case "Timer", "TickTimer" -> movesThisTick - 1 <= maxExtraMoves && spoofsThisTick >= movesThisTick - 1;
            case "RotationPlace", "PlaceVisibility" -> recentSpoof && placeInRange;
            default -> recentSpoof;
        };
        if (inBounds) absorbedSinceAlert.merge(name, 1, Integer::sum);
        return inBounds;
    }

    public boolean isActive() {
        return active(clock.getAsLong());
    }

    private boolean tracking() {
        return enabledByConfig && !player.isBedrockMovement();
    }

    private void rollTick() {
        if (!tickEnded) return;
        tickEnded = false;
        tick++;
        movesThisTick = 0;
        spoofsThisTick = 0;
    }

    /** PrepareAction's look: a horizontal direction's yaw (a multiple of 90) or the real yaw, with pitch -90, 0 or 90. */
    private boolean spoofedLook(float yaw, float pitch) {
        boolean pitchOk = pitch == -90f || pitch == 0f || pitch == 90f;
        return pitchOk && (yaw % 90f == 0f || yaw == lastYaw);
    }

    private boolean active(long now) {
        prune(now);
        return actionTimes.size() >= minActions && now - lastActionTime <= holdMs;
    }

    private void prune(long now) {
        while (!actionTimes.isEmpty() && now - actionTimes.peekFirst() > windowMs) actionTimes.pollFirst();
    }

    private void recordAction() {
        long now = clock.getAsLong();
        boolean wasActive = active(now);
        if (actionTimes.size() >= MAX_TRACKED_ACTIONS) actionTimes.pollFirst();
        actionTimes.addLast(now);
        lastActionTime = now;
        totalActions++;
        if (actionTimes.size() >= minActions && (!wasActive || now - lastAlert >= alertIntervalMs)) {
            lastAlert = now;
            flag("actions=" + totalActions + " absorbed=" + absorbedSinceAlert);
            absorbedSinceAlert.clear();
        }
    }

    private static Set<String> lowerCase(List<String> names) {
        Set<String> lowered = new HashSet<>();
        for (String name : names) lowered.add(name.toLowerCase(Locale.ROOT));
        return lowered;
    }
}
