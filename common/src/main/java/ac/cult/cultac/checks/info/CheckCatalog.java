package ac.cult.cultac.checks.info;

import static ac.cult.cultac.checks.info.CheckCategory.BUILDING;
import static ac.cult.cultac.checks.info.CheckCategory.CHAT;
import static ac.cult.cultac.checks.info.CheckCategory.CLOUD;
import static ac.cult.cultac.checks.info.CheckCategory.COMBAT;
import static ac.cult.cultac.checks.info.CheckCategory.CRASH;
import static ac.cult.cultac.checks.info.CheckCategory.ELYTRA;
import static ac.cult.cultac.checks.info.CheckCategory.INVENTORY;
import static ac.cult.cultac.checks.info.CheckCategory.MOVEMENT;
import static ac.cult.cultac.checks.info.CheckCategory.PACKETS;
import static ac.cult.cultac.checks.info.CheckCategory.VEHICLES;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Every check a player's {@code CheckManager} builds, known without a player so {@code /cult info} can list and
 * explain them. {@code CheckCatalogTest} keeps it equal to what a player builds.
 */
public final class CheckCatalog {
    /**
     * One check's identity as its {@code @CheckData} or {@code CheckInfo} declares it.
     *
     * @param name the check name punishments match and alerts show by default
     * @param configName the prefix of the check's own config keys
     * @param altName the second name punishments match, if any
     */
    public record Entry(
            @NotNull String name,
            @NotNull String configName,
            @Nullable String altName,
            boolean experimental,
            @NotNull CheckCategory category) {
        Entry config(@NotNull String configName) {
            return new Entry(name, configName, altName, experimental, category);
        }

        Entry alt(@NotNull String altName) {
            return new Entry(name, configName, altName, experimental, category);
        }

        /** The alternative name the running check reports; {@code @CheckData} reports a missing one as {@code UNKNOWN}. */
        public @Nullable String runtimeAltName() {
            return altName != null || FROM_CHECK_INFO.contains(name) ? altName : "UNKNOWN";
        }
    }

    /** Checks declared through {@code CheckInfo}, which report a missing alternative name as null. */
    private static final Set<String> FROM_CHECK_INFO = Set.of(
            "AntiExplosion",
            "AntiKB",
            "Angle",
            "AutoClickerLimit",
            "BedrockMovement",
            "BoatOffset",
            "Elytra",
            "EntityControl",
            "EntityReach",
            "FairReach",
            "GroundSpoof",
            "HappyGhastOffset",
            "InputKeys",
            "InvalidStep",
            "ItemUseBurst",
            "KeepSprint",
            "NegativeTimer",
            "NoFall",
            "NoSlow",
            "NoSneakSlow",
            "OmniSprint",
            "Phase",
            "PlayerReach",
            "Post",
            "RiptideOffset",
            "Simulation",
            "Speed",
            "Strafe",
            "Timer",
            "TimerVehicle",
            "VehicleOffset",
            "VerticalOffset");

    public static final List<Entry> ENTRIES = List.of(
            check("AimDuplicateLook", COMBAT),
            check("AimModulo360", COMBAT),
            check("AntiExplosion", COMBAT).config("Explosion"),
            check("AntiKB", COMBAT).config("Knockback").alt("AntiKnockback"),
            check("AttackMobVisibility", COMBAT),
            check("AttackPlayerVisibility", COMBAT),
            check("AutoClickerLimit", COMBAT).config("AutoclickerLimit"),
            check("EntityHitboxes", COMBAT),
            check("EntityReach", COMBAT),
            check("FairReach", COMBAT),
            check("InteractEntityVisibility", COMBAT),
            check("KeepSprint", COMBAT),
            experimental("MultiActionsA", COMBAT),
            experimental("MultiActionsE", COMBAT),
            experimental("MultiActionsF", COMBAT),
            experimental("MultiInteractA", COMBAT),
            experimental("MultiInteractB", COMBAT),
            check("PlayerHitboxes", COMBAT),
            check("PlayerReach", COMBAT),
            check("SelfInteract", COMBAT),
            check("Angle", MOVEMENT),
            check("BedrockMovement", MOVEMENT),
            check("GroundSpoof", MOVEMENT),
            check("InputKeys", MOVEMENT),
            check("InvalidStep", MOVEMENT),
            check("ItemUseBurst", MOVEMENT),
            check("NegativeTimer", MOVEMENT),
            check("NoFall", MOVEMENT),
            check("NoSlow", MOVEMENT),
            check("NoSneakSlow", MOVEMENT),
            check("OmniSprint", MOVEMENT),
            check("Phase", MOVEMENT),
            check("RiptideOffset", MOVEMENT),
            check("Simulation", MOVEMENT),
            check("Speed", MOVEMENT),
            check("SprintA", MOVEMENT),
            experimental("SprintB", MOVEMENT),
            experimental("SprintC", MOVEMENT),
            experimental("SprintD", MOVEMENT),
            experimental("SprintE", MOVEMENT),
            experimental("SprintF", MOVEMENT),
            experimental("SprintG", MOVEMENT),
            experimental("SprintH", MOVEMENT),
            check("Strafe", MOVEMENT),
            check("TickTimer", MOVEMENT),
            check("Timer", MOVEMENT).config("TimerA"),
            check("VerticalOffset", MOVEMENT),
            check("Elytra", ELYTRA),
            check("ElytraA", ELYTRA),
            check("ElytraB", ELYTRA),
            check("ElytraC", ELYTRA),
            experimental("ElytraD", ELYTRA),
            experimental("ElytraE", ELYTRA),
            check("ElytraF", ELYTRA),
            experimental("ElytraG", ELYTRA),
            experimental("ElytraH", ELYTRA),
            experimental("ElytraI", ELYTRA),
            experimental("ElytraJ", ELYTRA),
            check("BoatOffset", VEHICLES),
            check("EntityControl", VEHICLES),
            check("HappyGhastOffset", VEHICLES),
            experimental("MultiActionsG", VEHICLES),
            check("TimerVehicle", VEHICLES),
            check("VehicleA", VEHICLES),
            check("VehicleB", VEHICLES),
            check("VehicleC", VEHICLES),
            experimental("VehicleD", VEHICLES),
            experimental("VehicleE", VEHICLES),
            experimental("VehicleF", VEHICLES),
            check("VehicleOffset", VEHICLES),
            check("AirLiquidBreak", BUILDING),
            check("AirLiquidPlace", BUILDING),
            check("BreakVisibility", BUILDING),
            experimental("DuplicateRotPlace", BUILDING),
            check("FabricatedPlace", BUILDING),
            experimental("FarBreak", BUILDING),
            check("FarPlace", BUILDING),
            check("FastBreak", BUILDING),
            check("InvalidBreak", BUILDING),
            check("InvalidPlaceA", BUILDING),
            check("InvalidPlaceB", BUILDING),
            experimental("MultiActionsB", BUILDING),
            experimental("MultiBreak", BUILDING),
            experimental("MultiPlace", BUILDING),
            experimental("NoSwingBreak", BUILDING),
            check("PlaceVisibility", BUILDING),
            check("PositionBreakA", BUILDING),
            check("PositionBreakB", BUILDING),
            experimental("RotationBreak", BUILDING),
            check("RotationPlace", BUILDING),
            check("WrongBreak", BUILDING),
            check("MultiActionsC", INVENTORY),
            check("MultiActionsD", INVENTORY),
            check("MultiActionsH", INVENTORY),
            check("MultiActionsI", INVENTORY),
            check("MultiActionsJ", INVENTORY),
            check("MultiActionsK", INVENTORY),
            check("MultiActionsL", INVENTORY),
            experimental("MultiActionsM", INVENTORY),
            experimental("MultiActionsN", INVENTORY),
            check("MultiActionsO", INVENTORY),
            experimental("MultiActionsP", INVENTORY),
            experimental("MultiActionsQ", INVENTORY),
            experimental("MultiActionsR", INVENTORY),
            experimental("MultiActionsS", INVENTORY),
            experimental("MultiActionsT", INVENTORY),
            experimental("ChatA", CHAT),
            check("ChatB", CHAT),
            experimental("ChatC", CHAT),
            check("ChatD", CHAT),
            check("ChatE", CHAT),
            check("BadPacketsA", PACKETS),
            check("BadPacketsB", PACKETS),
            experimental("BadPacketsC", PACKETS),
            check("BadPacketsD", PACKETS),
            check("BadPacketsE", PACKETS),
            check("BadPacketsF", PACKETS),
            check("BadPacketsG", PACKETS),
            experimental("BadPacketsH", PACKETS),
            check("BadPacketsI", PACKETS),
            check("BadPacketsJ", PACKETS),
            check("BadPacketsK", PACKETS),
            check("BadPacketsL", PACKETS),
            experimental("BadPacketsM", PACKETS),
            check("BadPacketsN", PACKETS),
            check("BadPacketsO", PACKETS),
            experimental("BadPacketsP", PACKETS),
            check("BadPacketsQ", PACKETS),
            experimental("BadPacketsR", PACKETS),
            check("BadPacketsS", PACKETS),
            check("BadPacketsT", PACKETS),
            check("BadPacketsU", PACKETS),
            experimental("BadPacketsV", PACKETS),
            experimental("BadPacketsVehicle", PACKETS),
            experimental("BadPacketsW", PACKETS),
            check("BadPacketsWindow", PACKETS),
            experimental("BadPacketsX", PACKETS),
            check("BadPacketsY", PACKETS),
            experimental("BadPacketsZ", PACKETS),
            check("ExploitF", PACKETS),
            experimental("PacketOrderA", PACKETS),
            check("PacketOrderB", PACKETS),
            check("PacketOrderC", PACKETS),
            experimental("PacketOrderD", PACKETS),
            experimental("PacketOrderE", PACKETS),
            experimental("PacketOrderF", PACKETS),
            experimental("PacketOrderG", PACKETS),
            experimental("PacketOrderH", PACKETS),
            experimental("PacketOrderI", PACKETS),
            experimental("PacketOrderJ", PACKETS),
            experimental("PacketOrderK", PACKETS),
            experimental("PacketOrderL", PACKETS),
            experimental("PacketOrderM", PACKETS),
            experimental("PacketOrderN", PACKETS),
            experimental("PacketOrderO", PACKETS),
            check("PingA", PACKETS),
            check("Post", PACKETS),
            check("SkinBlinker", PACKETS),
            check("TransactionOrder", PACKETS),
            check("CrashA", CRASH),
            check("CrashB", CRASH),
            check("CrashC", CRASH),
            check("CrashD", CRASH),
            check("CrashE", CRASH),
            check("CrashF", CRASH),
            check("CrashG", CRASH),
            check("CrashH", CRASH),
            check("CrashI", CRASH),
            check("ExploitA", CRASH),
            check("ExploitB", CRASH),
            check("ExploitC", CRASH),
            check("Cloud", CLOUD));

    private CheckCatalog() {}

    private static Entry check(String name, CheckCategory category) {
        return new Entry(name, name, null, false, category);
    }

    private static Entry experimental(String name, CheckCategory category) {
        return new Entry(name, name, null, true, category);
    }

    /** The check whose name, config name or alternative name is {@code query}, ignoring case. */
    public static @Nullable Entry find(@NotNull String query) {
        for (Entry entry : ENTRIES) {
            if (entry.name().equalsIgnoreCase(query)
                    || entry.configName().equalsIgnoreCase(query)
                    || (entry.altName() != null && entry.altName().equalsIgnoreCase(query))) return entry;
        }
        return null;
    }

    /** The checks in {@code category}, in catalog order. */
    public static @NotNull List<Entry> in(@NotNull CheckCategory category) {
        List<Entry> entries = new ArrayList<>();
        for (Entry entry : ENTRIES) {
            if (entry.category() == category) entries.add(entry);
        }
        return entries;
    }

    /** The checks whose name contains {@code query}, ignoring case. */
    public static @NotNull List<Entry> containing(@NotNull String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        List<Entry> entries = new ArrayList<>();
        for (Entry entry : ENTRIES) {
            if (entry.name().toLowerCase(Locale.ROOT).contains(lower)) entries.add(entry);
        }
        return entries;
    }
}
