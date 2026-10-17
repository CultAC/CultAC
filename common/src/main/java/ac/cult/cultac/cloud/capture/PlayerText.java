package ac.cult.cultac.cloud.capture;

import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.PlayerTextCodec;
import ac.cult.cultac.protocol.PlayerTextWire;
import ac.cult.cultac.protocol.PlayerTextWire.Edits;
import ac.cult.cultac.protocol.ProtocolCodecs;
import ac.cult.cultac.protocol.ProtocolContext;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.IdTable;
import ac.cult.cultac.protocol.data.ModelRegistryData;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.anticheat.LogUtil;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;

/**
 * Takes what players write out of a packet before it leaves the server:
 *
 * <ul>
 *   <li>from item stacks, wherever they sit (inventories, equipment, entity data, particles, items inside items, the
 *       stacks a client sends with a click or from the creative inventory), an item's custom or display name, its lore,
 *       a book's pages and title and its opaque, entity or block entity data, and text within the components kept;
 *   <li>from entity data, text components and command strings, sent empty or absent when optional, a painting's title
 *       and author sent empty, NBT (a shoulder parrot) as an empty compound, and a profile cut to its skin;
 *   <li>from resource pack pushes, the prompt sent empty and the URL as an empty string;
 *   <li>from tab list entries, a display name sent empty, a chat session sent absent, and properties cut to one
 *       unsigned skin; from team presentation, text components sent empty;
 *   <li>from {@code open_screen}, the window's title, and from {@code player_combat_kill}, the death message, each
 *       sent as an empty text;
 *   <li>from {@code show_dialog}, a dialog sent inline, sent as an empty compound; one named by its registry id stays;
 *   <li>from block entities, in {@code level_chunk_with_light} and {@code block_entity_data}, their data, sent absent.
 * </ul>
 *
 * A clientbound packet goes out only when it is one of these ({@link #CLIENTBOUND}) or carries nothing players write
 * ({@link #TEXT_FREE_CLIENTBOUND}); every other, {@link #WITHHELD_CLIENTBOUND} or not known here, is never sent,
 * whatever the service asks for. A serverbound packet goes out only when it is one of {@link #SERVERBOUND} or
 * {@link #TEXT_FREE_SERVERBOUND}; those the service's capture policy holds private ({@link #PRIVATE_SERVERBOUND}) and
 * those not known here are never sent. A packet with nothing to take is sent as the same array; one that can carry
 * text and does not read to its last byte, within {@link PlayerTextWire#MAX_WORK}, is never sent.
 *
 * <p>Each packet's fields are walked as the client protocol frames them; the values inside (stacks, entity data,
 * particles) by the protocol's {@link PlayerTextCodec}. What goes is cut out of the packet's own bytes, and the rest is
 * sent as it came. Without the protocol's packet ids, no packet is sent. Without that codec, a packet that can carry
 * text and is not withheld goes out as it came, but only on a socket whose connect said {@code textStripped} false, and
 * the intake strips text itself then; on one that said true, it is not sent.
 */
public final class PlayerText {
    /** Clientbound packets that can carry what players write, sent without it. */
    static final Set<String> CLIENTBOUND = Set.of(
            "container_set_slot",
            "container_set_content",
            "set_player_inventory",
            "set_cursor_item",
            "set_equipment",
            "set_entity_data",
            "open_screen",
            "player_combat_kill",
            "show_dialog",
            "resource_pack_push",
            "player_info_update",
            "set_player_team",
            "explode",
            "level_particles",
            "block_entity_data",
            "level_chunk_with_light");
    /** Clientbound packets of numbers, positions, flags and registry ids alone, sent as they are. */
    static final Set<String> TEXT_FREE_CLIENTBOUND = Set.of(
            "add_entity",
            "add_experience_orb",
            "add_transient_block",
            "animate",
            "award_stats",
            "block_changed_ack",
            "block_destruction",
            "block_event",
            "block_update",
            "bundle_delimiter",
            "change_difficulty",
            "chunk_batch_finished",
            "chunk_batch_start",
            "chunks_biomes",
            "clear_dialog",
            "clear_titles",
            "container_close",
            "container_set_data",
            "cooldown",
            "damage_event",
            "entity_event",
            "entity_position_sync",
            "forget_level_chunk",
            "game_event",
            "horse_screen_open",
            "hurt_animation",
            "initialize_border",
            "keep_alive",
            "level_event",
            "light_update",
            "login",
            "low_disk_space_warning",
            "mount_screen_open",
            "move_entity_pos",
            "move_entity_pos_rot",
            "move_entity_rot",
            "move_minecart_along_track",
            "move_vehicle",
            "open_book",
            "open_sign_editor",
            "ping",
            "player_abilities",
            "player_combat_end",
            "player_combat_enter",
            "player_info_remove",
            "player_look_at",
            "player_position",
            "player_rotation",
            "pong_response",
            "projectile_power",
            "recipe_book_remove",
            "recipe_book_settings",
            "remove_entities",
            "remove_mob_effect",
            "resource_pack_pop",
            "respawn",
            "rotate_head",
            "section_blocks_update",
            "select_advancements_tab",
            "set_border_center",
            "set_border_lerp_size",
            "set_border_size",
            "set_border_warning_delay",
            "set_border_warning_distance",
            "set_camera",
            "set_chunk_cache_center",
            "set_chunk_cache_radius",
            "set_default_spawn_position",
            "set_entity_link",
            "set_entity_motion",
            "set_experience",
            "set_health",
            "set_held_slot",
            "set_passengers",
            "set_simulation_distance",
            "set_time",
            "set_titles_animation",
            "sound",
            "sound_entity",
            "start_configuration",
            "stop_sound",
            "swing_animation",
            "take_item_entity",
            "teleport_entity",
            "ticking_state",
            "ticking_step",
            "update_attributes",
            "update_mob_effect");
    /**
     * Clientbound packets never sent: chat and command completions, plugin data and cookies, server text plugins fill
     * with what players write (titles, action bars, boss bars, the tab list's header, scoreboards, disconnects, server
     * data and links), stacks and NBT not stripped here (trades, maps, recipes, advancements, tag queries), transfers,
     * and debug and test data.
     */
    static final Set<String> WITHHELD_CLIENTBOUND = Set.of(
            "player_chat",
            "system_chat",
            "disguised_chat",
            "custom_payload",
            "command_suggestions",
            "custom_chat_completions",
            "delete_chat",
            "commands",
            "cookie_request",
            "store_cookie",
            "transfer",
            "disconnect",
            "set_title_text",
            "set_subtitle_text",
            "set_action_bar_text",
            "boss_event",
            "tab_list",
            "set_objective",
            "set_score",
            "reset_score",
            "set_display_objective",
            "server_data",
            "server_links",
            "custom_report_details",
            "merchant_offers",
            "map_item_data",
            "tag_query",
            "update_advancements",
            "update_recipes",
            "update_tags",
            "recipe_book_add",
            "place_ghost_recipe",
            "game_rule_values",
            "post_effects",
            "waypoint",
            "test_instance_block_status",
            "game_test_highlight_pos",
            "debug_sample",
            "debug/block_value",
            "debug/chunk_value",
            "debug/entity_value",
            "debug/event");
    /** Serverbound packets that can carry what players write: the stacks a client sends with a click or creates. */
    static final Set<String> SERVERBOUND = Set.of("container_click", "set_creative_mode_slot");
    /** Serverbound packets of numbers, positions, flags and registry ids alone, sent as they are. */
    static final Set<String> TEXT_FREE_SERVERBOUND = Set.of(
            "accept_teleportation",
            "attack",
            "block_entity_tag_query",
            "bundle_item_selected",
            "change_difficulty",
            "change_game_mode",
            "chunk_batch_received",
            "client_command",
            "client_information",
            "client_tick_end",
            "configuration_acknowledged",
            "container_button_click",
            "container_close",
            "container_slot_state_changed",
            "debug_sample_subscription",
            "debug_subscription_request",
            "entity_tag_query",
            "interact",
            "keep_alive",
            "lock_difficulty",
            "move_player_pos",
            "move_player_pos_rot",
            "move_player_rot",
            "move_player_status_only",
            "move_vehicle",
            "paddle_boat",
            "pick_item",
            "pick_item_from_block",
            "pick_item_from_entity",
            "ping_request",
            "place_recipe",
            "player_abilities",
            "player_action",
            "player_command",
            "player_input",
            "player_loaded",
            "pong",
            "punch",
            "recipe_book_change_settings",
            "recipe_book_seen_recipe",
            "seen_advancements",
            "select_trade",
            "set_beacon",
            "set_carried_item",
            "set_game_rule",
            "spectate_entity",
            "spectator_action",
            "swing",
            "teleport_to_entity",
            "use_item",
            "use_item_on");
    /** Serverbound packets the service's capture policy holds private: chat, commands and plugin data, never sent. */
    static final Set<String> PRIVATE_SERVERBOUND = Set.of(
            "chat",
            "chat_ack",
            "chat_command",
            "chat_command_signed",
            "chat_session_update",
            "command_suggestion",
            "custom_payload",
            "custom_click_action",
            "cookie_response",
            "sign_update",
            "edit_book",
            "rename_item",
            "set_command_block",
            "set_command_minecart",
            "set_jigsaw_block",
            "set_structure_block",
            "set_test_block",
            "test_instance_block_action",
            "jigsaw_generate",
            "resource_pack");

    // Across every stream, for the status command.
    static final LongAdder stripped = new LongAdder();
    static final LongAdder unreadable = new LongAdder();
    static final LongAdder withheld = new LongAdder();
    static final LongAdder unstripped = new LongAdder();

    private static final int MAX_JSON_DEPTH = 64;
    private static final int MAX_JSON_NODES = 65536;
    /** Skins kept by their encoded texture value; past either bound a value is read again. */
    private static final int MAX_SKINS = 512;

    private static final int MAX_SKIN_KEY = 2048;
    /** A cached texture value that names no skin to keep. */
    private static final String NO_SKIN = "";
    /** No chat session: a tab list entry's own key and Mojang's signature of it stay on the server. */
    private static final byte[] NO_SESSION = {0};

    private static final Pattern SKIN_URL =
            Pattern.compile("https?://textures\\.minecraft\\.net/texture/[0-9a-f]{1,64}");
    private static final Map<Integer, PlayerText> LOADED = new ConcurrentHashMap<>();
    private static final Map<String, String> SKINS = new ConcurrentHashMap<>();
    /** Protocols with packet ids whose codec did not load; once one is here, connects say {@code textStripped} false. */
    private static final Set<Integer> UNSTRIPPED = ConcurrentHashMap.newKeySet();
    /** Protocols CultAC knows whose packet ids did not load, warned about once. */
    private static final Set<Integer> NO_IDS = ConcurrentHashMap.newKeySet();

    private static final byte WITHHELD = 0;
    private static final byte TEXT_FREE = 1;
    private static final byte STRIP = 2;

    /** Null when the protocol has no codecs here. */
    private final ProtocolVersion version;

    private final PlayerTextCodec values;
    /** By packet id: each clientbound packet's identifier, null for one not known here; null when no id is known. */
    private final String[] clientbound;
    /** By packet id: {@link #STRIP}, {@link #TEXT_FREE}, or withheld; null when no id is known. */
    private final byte[] clientboundKinds;
    /** By packet id: each serverbound packet's identifier, null for one not known here; null when no id is known. */
    private final String[] serverbound;

    private final ProtocolContext openScreen;
    private final ProtocolContext combatKill;
    private final ProtocolContext explode;
    private final IdTable blockEntityTypes;

    /** Without ids, no packet is ever sent; without {@code values}, one that can carry text only unstripped. */
    PlayerText(
            int protocol,
            PlayerTextCodec values,
            ToIntFunction<String> clientboundIds,
            ToIntFunction<String> serverboundIds) {
        this(protocol, values, clientboundIds, serverboundIds, null);
    }

    /** As the four-argument constructor, with {@code protocol}'s data when it is loaded already. */
    private PlayerText(
            int protocol,
            PlayerTextCodec values,
            ToIntFunction<String> clientboundIds,
            ToIntFunction<String> serverboundIds,
            ProtocolData loaded) {
        Set<String> clientboundNames = new HashSet<>(CLIENTBOUND);
        clientboundNames.addAll(TEXT_FREE_CLIENTBOUND);
        clientboundNames.addAll(WITHHELD_CLIENTBOUND);
        this.clientbound = clientboundIds == null ? null : byId(clientboundNames, clientboundIds);
        this.clientboundKinds = clientbound == null ? null : new byte[clientbound.length];
        for (int id = 0; clientbound != null && id < clientbound.length; id++) {
            String name = clientbound[id];
            if (name == null || WITHHELD_CLIENTBOUND.contains(name)) clientboundKinds[id] = WITHHELD;
            else clientboundKinds[id] = TEXT_FREE_CLIENTBOUND.contains(name) ? TEXT_FREE : STRIP;
        }
        Set<String> serverboundNames = new HashSet<>(SERVERBOUND);
        serverboundNames.addAll(TEXT_FREE_SERVERBOUND);
        serverboundNames.addAll(PRIVATE_SERVERBOUND);
        this.serverbound = serverboundIds == null ? null : byId(serverboundNames, serverboundIds);
        ProtocolVersion supported = null;
        ProtocolContext openScreen = null;
        ProtocolContext combatKill = null;
        ProtocolContext explode = null;
        IdTable blockEntityTypes = null;
        if (values != null) {
            try {
                supported = ProtocolVersion.of(protocol);
                ProtocolData data = loaded != null ? loaded : ProtocolData.load(supported);
                openScreen = context(ClientboundPackets.OPEN_SCREEN, data);
                combatKill = context(ClientboundPackets.PLAYER_COMBAT_KILL, data);
                explode = context(ClientboundPackets.EXPLODE, data);
                blockEntityTypes = ModelRegistryData.load(supported).registry("minecraft:block_entity_type");
            } catch (RuntimeException | LinkageError unsupported) {
                values = null;
            }
        }
        this.values = values;
        this.version = values == null ? null : supported;
        this.openScreen = openScreen;
        this.combatKill = combatKill;
        this.explode = explode;
        this.blockEntityTypes = blockEntityTypes;
    }

    /**
     * {@code protocol}'s; its packet ids are CultAC's for it, none when CultAC has none. One that cannot strip is not
     * kept, so the next capture tries again.
     */
    public static PlayerText of(int protocol) {
        PlayerText loaded = LOADED.get(protocol);
        if (loaded != null) return loaded;
        loaded = load(protocol, version -> ProtocolCodecs.decoder().playerText(version));
        if (loaded.values == null) {
            if (loaded.clientbound != null && UNSTRIPPED.add(protocol)) {
                LogUtil.warn("Cloud upload has no player text codecs for protocol " + protocol
                        + "; its packets that can carry text are sent unstripped, and connects say textStripped false");
            } else if (loaded.clientbound == null && known(protocol) && NO_IDS.add(protocol)) {
                LogUtil.warn(
                        "Cloud upload has no packet ids for protocol " + protocol + "; none of its packets are sent");
            }
            return loaded;
        }
        PlayerText raced = LOADED.putIfAbsent(protocol, loaded);
        return raced == null ? loaded : raced;
    }

    private static boolean known(int protocol) {
        for (ProtocolVersion version : ProtocolVersion.values()) {
            if (version.protocol() == protocol) return true;
        }
        return false;
    }

    /** Whether every protocol loaded so far that has packet ids has its codec. */
    public static boolean strips() {
        return UNSTRIPPED.isEmpty();
    }

    /** As {@link #strips()}, once every protocol CultAC knows has been loaded. */
    public static boolean stripsEvery() {
        for (ProtocolVersion version : ProtocolVersion.values()) {
            if (!UNSTRIPPED.contains(version.protocol())) of(version.protocol());
        }
        return strips();
    }

    /** {@code protocol}'s, its codecs from {@code codecs}: with its ids and no codecs when they do not load. */
    static PlayerText load(int protocol, Function<ProtocolVersion, PlayerTextCodec> codecs) {
        ProtocolVersion version;
        ProtocolData data;
        IdTable clientbound;
        IdTable serverbound;
        try {
            version = ProtocolVersion.of(protocol);
            data = ProtocolData.load(version);
            clientbound = data.packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND);
            serverbound = data.packets(ConnectionPhase.PLAY, PacketDirection.SERVERBOUND);
        } catch (RuntimeException unsupported) {
            return new PlayerText(protocol, null, null, null);
        }
        PlayerTextCodec values;
        try {
            values = codecs.apply(version);
        } catch (RuntimeException | LinkageError unavailable) {
            values = null;
        }
        return new PlayerText(
                protocol,
                values,
                name -> clientbound.id("minecraft:" + name),
                name -> serverbound.id("minecraft:" + name),
                data);
    }

    private static ProtocolContext context(PacketType<?> type, ProtocolData data) {
        return new ProtocolContext(
                type, data, ConnectionPhase.PLAY, type.wireNames().getFirst(), 0, 0);
    }

    private static String[] byId(Set<String> names, ToIntFunction<String> ids) {
        int max = -1;
        for (String name : names) max = Math.max(max, ids.applyAsInt(name));
        String[] byId = new String[max + 1];
        for (String name : names) {
            int id = ids.applyAsInt(name);
            if (id >= 0) byId[id] = name;
        }
        return byId;
    }

    /**
     * A clientbound packet, its id first: the same array when it carries nothing players write, a copy without it, or
     * null when it must not be sent. With {@code textStripped} false, a packet that can carry text goes out as it came
     * when this protocol has no codec.
     */
    byte[] clientbound(int id, byte[] packet, boolean textStripped) {
        byte kind =
                clientboundKinds != null && id >= 0 && id < clientboundKinds.length ? clientboundKinds[id] : WITHHELD;
        if (kind == TEXT_FREE) return packet;
        if (kind == WITHHELD) {
            withheld.increment();
            return null;
        }
        if (values == null && !textStripped) return unstripped(packet);
        return counted(packet, strip(true, clientbound[id], packet));
    }

    /** As {@link #clientbound(int, byte[], boolean)}, for a serverbound packet. */
    byte[] serverbound(int id, byte[] packet, boolean textStripped) {
        if (serverbound == null) {
            withheld.increment();
            return null;
        }
        String identifier = id < 0 || id >= serverbound.length ? null : serverbound[id];
        if (identifier == null || PRIVATE_SERVERBOUND.contains(identifier)) {
            withheld.increment();
            return null;
        }
        if (TEXT_FREE_SERVERBOUND.contains(identifier)) return packet;
        if (values == null && !textStripped) return unstripped(packet);
        return counted(packet, strip(false, identifier, packet));
    }

    /** Whether a packet goes out unstripped on a socket that said {@code textStripped} false: this protocol has no codec. */
    boolean unstripped(boolean clientbound, int id) {
        if (values != null || id < 0) return false;
        if (clientbound)
            return clientboundKinds != null && id < clientboundKinds.length && clientboundKinds[id] == STRIP;
        return serverbound != null
                && id < serverbound.length
                && serverbound[id] != null
                && SERVERBOUND.contains(serverbound[id]);
    }

    private static byte[] unstripped(byte[] packet) {
        unstripped.increment();
        return packet;
    }

    private static byte[] counted(byte[] packet, byte[] kept) {
        if (kept == null) unreadable.increment();
        else if (kept != packet) stripped.increment();
        return kept;
    }

    /** One status line. */
    public static String summary() {
        return String.format(
                Locale.ROOT,
                "Player text: %d packets sent without it, %d unreadable not sent, %d withheld, %d sent unstripped",
                stripped.sum(),
                unreadable.sum(),
                withheld.sum(),
                unstripped.sum());
    }

    /**
     * {@code packet} without what players write, the same array when it has none, or null when it does not read to its
     * last byte: such a packet cannot be told clean.
     */
    byte[] strip(boolean clientbound, String identifier, byte[] packet) {
        if (values == null) return null;
        Walk walk = new Walk(Unpooled.wrappedBuffer(packet));
        try {
            Wire.readVarInt(walk.in);
            boolean framed = clientbound ? walk.clientbound(identifier) : walk.serverbound(identifier);
            if (!framed || walk.in.isReadable()) return null;
        } catch (RuntimeException | StackOverflowError unreadable) {
            return null;
        }
        return walk.edits == null ? packet : walk.edits.apply(walk.in, 0, packet.length);
    }

    /** One packet's fields, as its protocol frames them, and what is cut from them. */
    private final class Walk implements PlayerTextCodec.Cuts {
        final ByteBuf in;
        Edits edits;
        private int spent;

        Walk(ByteBuf in) {
            this.in = in;
        }

        @Override
        public void cut(int start, int end, byte[] replacement) {
            work(1);
            if (edits == null) edits = new Edits();
            edits.add(start, end, replacement);
        }

        @Override
        public void work(int units) {
            spent = PlayerTextWire.charge(spent, units);
        }

        @Override
        public String skin(String textures) {
            return texture(textures);
        }

        /** False for a packet with no framing here. */
        boolean clientbound(String identifier) {
            switch (identifier) {
                case "container_set_slot" -> {
                    Wire.readVarInt(in); // Window.
                    Wire.readVarInt(in); // State.
                    in.skipBytes(2); // Slot.
                    values.stack(in, this);
                }
                case "container_set_content" -> {
                    Wire.readVarInt(in); // Window.
                    Wire.readVarInt(in); // State.
                    int slots = count();
                    for (int i = 0; i < slots; i++) values.stack(in, this);
                    values.stack(in, this); // Carried.
                }
                case "set_player_inventory" -> {
                    Wire.readVarInt(in); // Slot.
                    values.stack(in, this);
                }
                case "set_cursor_item" -> values.stack(in, this);
                case "set_equipment" -> {
                    Wire.readVarInt(in); // Entity.
                    int slot;
                    do {
                        slot = in.readUnsignedByte();
                        values.stack(in, this);
                    } while ((slot & 0x80) != 0);
                }
                case "set_entity_data" -> {
                    Wire.readVarInt(in); // Entity.
                    values.entityData(in, this);
                }
                case "open_screen" -> {
                    ClientboundPackets.OPEN_SCREEN.codec().read(in, openScreen);
                    text();
                }
                case "player_combat_kill" -> {
                    ClientboundPackets.PLAYER_COMBAT_KILL.codec().read(in, combatKill);
                    text();
                }
                case "show_dialog" -> {
                    if (Wire.readVarInt(in) == 0) PlayerTextWire.compound(in, this);
                }
                case "resource_pack_push" -> {
                    in.skipBytes(16); // Pack.
                    PlayerTextWire.emptyString(in, this); // URL: it can name the player.
                    string(); // Hash.
                    in.skipBytes(1); // Forced.
                    if (in.readBoolean()) text(); // Prompt.
                }
                case "player_info_update" -> playerInfo();
                case "set_player_team" -> team();
                case "explode" -> {
                    ClientboundPackets.EXPLODE.codec().read(in, explode);
                    values.particle(in, this);
                    sound();
                    if (version.atLeast(ProtocolVersion.V1_21_9)) {
                        int particles = count();
                        for (int i = 0; i < particles; i++) {
                            values.particle(in, this);
                            in.skipBytes(8); // Scaling and speed.
                            Wire.readVarInt(in); // Weight.
                        }
                    }
                    if (version.atLeast(ProtocolVersion.V26_3)) in.skipBytes(1); // Plays its sound.
                }
                case "level_particles" -> {
                    if (version.atLeast(ProtocolVersion.V26_3)) {
                        values.particle(in, this);
                        in.skipBytes(2 + 24 + 12 + 12); // Flags, position, offset and speeds.
                        Wire.readVarInt(in); // Count.
                        Wire.readVarInt(in); // Randomization.
                    } else {
                        in.skipBytes(version.atLeast(ProtocolVersion.V1_21_4) ? 2 : 1); // Flags.
                        in.skipBytes(24 + 12 + 4 + 4); // Position, offset, speed and count.
                        values.particle(in, this);
                    }
                }
                case "block_entity_data" -> {
                    in.skipBytes(8); // Position.
                    blockEntityTypes.name(Wire.readVarInt(in)); // Type: an unknown one throws.
                    PlayerTextWire.absent(in, this);
                }
                case "level_chunk_with_light" -> levelChunk();
                default -> {
                    return false;
                }
            }
            return true;
        }

        /** False for a packet with no framing here. */
        boolean serverbound(String identifier) {
            switch (identifier) {
                case "container_click" -> {
                    Wire.readVarInt(in); // Window.
                    Wire.readVarInt(in); // State.
                    in.skipBytes(3); // Slot and button.
                    Wire.readVarInt(in); // Mode.
                    int changed = count();
                    for (int i = 0; i < changed; i++) {
                        in.skipBytes(2); // Slot.
                        clicked();
                    }
                    clicked(); // Carried.
                }
                case "set_creative_mode_slot" -> {
                    in.skipBytes(2); // Slot.
                    values.creativeStack(in, this);
                }
                default -> {
                    return false;
                }
            }
            return true;
        }

        /** A stack a click sends: whole before 1.21.5, as hashes of its components, with no text, from then on. */
        private void clicked() {
            if (!version.atLeast(ProtocolVersion.V1_21_5)) {
                values.stack(in, this);
                return;
            }
            if (!in.readBoolean()) return;
            Wire.readVarInt(in); // Item.
            Wire.readVarInt(in); // Count.
            int components = count();
            for (int i = 0; i < components; i++) {
                Wire.readVarInt(in); // Type.
                in.skipBytes(4); // Hash.
            }
            int removed = count();
            for (int i = 0; i < removed; i++) Wire.readVarInt(in);
        }

        /** Tab list entries: identity, game mode, latency and listing kept, a display name sent empty. */
        private void playerInfo() {
            int actions = in.readUnsignedByte();
            if (!version.atLeast(ProtocolVersion.V1_21_4)) actions &= 0x7F; // No hat action before 1.21.4.
            int entries = count();
            for (int i = 0; i < entries; i++) {
                in.skipBytes(16); // Player.
                if ((actions & 0x01) != 0) {
                    string(); // Name.
                    properties();
                }
                if ((actions & 0x02) != 0) {
                    int start = in.readerIndex();
                    if (in.readBoolean()) {
                        in.skipBytes(16 + 8); // Session and key expiry.
                        in.skipBytes(length()); // Key.
                        in.skipBytes(length()); // Key signature.
                        cut(start, in.readerIndex(), NO_SESSION);
                    }
                }
                if ((actions & 0x04) != 0) Wire.readVarInt(in); // Game mode.
                if ((actions & 0x08) != 0) in.skipBytes(1); // Listed.
                if ((actions & 0x10) != 0) Wire.readVarInt(in); // Latency.
                if ((actions & 0x20) != 0 && in.readBoolean()) text(); // Display name.
                if ((actions & 0x40) != 0) Wire.readVarInt(in); // List order.
                if ((actions & 0x80) != 0) in.skipBytes(1); // Hat.
            }
        }

        /** A profile's properties: none but its one skin, unsigned, which goes too when it is not on Mojang's server. */
        private void properties() {
            int start = in.readerIndex();
            int properties = count();
            if (properties == 0) return;
            String textures = null;
            boolean skin = true;
            for (int p = 0; p < properties; p++) {
                String name = Wire.readString(in, Wire.MAX_STRING_LENGTH);
                String value = Wire.readString(in, Wire.MAX_STRING_LENGTH);
                if (in.readBoolean()) string(); // Signature.
                if (!name.equals("textures")) continue;
                skin = textures == null;
                textures = value;
            }
            String kept = skin && textures != null ? texture(textures) : null;
            ByteBuf out = Unpooled.buffer();
            try {
                if (kept == null) {
                    Wire.writeVarInt(out, 0);
                } else {
                    Wire.writeVarInt(out, 1);
                    Wire.writeString(out, "textures", Wire.MAX_STRING_LENGTH);
                    Wire.writeString(out, kept, Wire.MAX_STRING_LENGTH);
                    out.writeBoolean(false);
                }
                int length = in.readerIndex() - start;
                if (out.readableBytes() != length || !ByteBufUtil.equals(in, start, out, 0, length)) {
                    cut(start, in.readerIndex(), ByteBufUtil.getBytes(out));
                }
            } finally {
                out.release();
            }
        }

        /** A team: its name, mode, options and players kept, its display name, prefix and suffix sent empty. */
        private void team() {
            string(); // Team.
            int mode = in.readByte();
            if (mode == 0 || mode == 2) {
                if (version.atLeast(ProtocolVersion.V26_2)) {
                    text(); // Display name.
                    text(); // Prefix.
                    text(); // Suffix.
                    Wire.readVarInt(in); // Name tags.
                    Wire.readVarInt(in); // Collisions.
                    if (in.readBoolean()) Wire.readVarInt(in); // Colour.
                    in.skipBytes(1); // Flags.
                } else {
                    text(); // Display name.
                    in.skipBytes(1); // Flags.
                    if (version.atLeast(ProtocolVersion.V1_21_5)) {
                        Wire.readVarInt(in); // Name tags.
                        Wire.readVarInt(in); // Collisions.
                    } else {
                        string(); // Name tags.
                        string(); // Collisions.
                    }
                    Wire.readVarInt(in); // Colour.
                    text(); // Prefix.
                    text(); // Suffix.
                }
            }
            if (mode == 0 || mode == 3 || mode == 4) {
                int players = count();
                for (int i = 0; i < players; i++) string();
            }
        }

        /** A chunk: its block entities' data sent absent, the rest as it came. */
        private void levelChunk() {
            in.skipBytes(8); // Position.
            if (!version.atLeast(ProtocolVersion.V1_21_5)) {
                PlayerTextWire.optionalNbt(in); // Heightmaps.
            } else {
                int heightmaps = count();
                for (int i = 0; i < heightmaps; i++) {
                    Wire.readVarInt(in); // Type.
                    in.skipBytes(Math.multiplyExact(length(), 8));
                }
            }
            in.skipBytes(length()); // Sections.
            int entities = count();
            for (int i = 0; i < entities; i++) {
                in.skipBytes(3); // Packed position.
                Wire.readVarInt(in); // Type.
                PlayerTextWire.absent(in, this);
            }
            for (int i = 0; i < 4; i++) { // Light masks.
                int words = length();
                in.skipBytes(version.atLeast(ProtocolVersion.V26_3) ? words : Math.multiplyExact(words, 8));
            }
            for (int i = 0; i < 2; i++) { // Sky, then block light.
                int arrays = count();
                for (int j = 0; j < arrays; j++) in.skipBytes(length());
            }
        }

        /** A sound by registry id, or inline: its name and an optional fixed range. */
        private void sound() {
            if (Wire.readVarInt(in) != 0) return;
            string();
            if (in.readBoolean()) in.skipBytes(4);
        }

        /** A text component, sent empty. */
        private void text() {
            PlayerTextWire.text(in, this);
        }

        private void string() {
            PlayerTextWire.string(in);
        }

        /** A count of values that each take at least a byte, charged to the walk. */
        private int count() {
            return PlayerTextWire.count(in, this);
        }

        /** A length of bytes skipped whole. */
        private int length() {
            return Wire.readLength(in, in.readableBytes());
        }
    }

    /**
     * A texture property's value cut to its skin's URL alone; null when it names no skin on Mojang's server. Values seen
     * before are answered from {@link #SKINS}.
     */
    static String texture(String encoded) {
        if (encoded.length() > 8192) return null;
        String known = SKINS.get(encoded);
        if (known != null) return known.isEmpty() ? null : known;
        String skin = readTexture(encoded);
        if (encoded.length() <= MAX_SKIN_KEY) {
            if (SKINS.size() >= MAX_SKINS) SKINS.clear();
            SKINS.put(encoded, skin == null ? NO_SKIN : skin);
        }
        return skin;
    }

    private static String readTexture(String encoded) {
        String url;
        try (JsonReader reader = new JsonReader(
                new StringReader(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)))) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) return null;
            url = json(reader, 0, 0, new int[1]);
            if (reader.peek() != JsonToken.END_DOCUMENT
                    || url == null
                    || !SKIN_URL.matcher(url).matches()) {
                return null;
            }
        } catch (IllegalArgumentException | IllegalStateException | IOException invalid) {
            return null;
        }
        String minimal = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}";
        return Base64.getEncoder().encodeToString(minimal.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The skin URL at {@code textures.SKIN.url} under the value the reader is at; {@code path} is how far along that
     * path the value is, {@code depth} how deep in the document, {@code nodes} how many names were read.
     */
    private static String json(JsonReader reader, int path, int depth, int[] nodes) throws IOException {
        if (depth >= MAX_JSON_DEPTH) throw new IllegalStateException("texture JSON too deep");
        String url = null;
        switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                Set<String> names = new HashSet<>();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (!names.add(name)) throw new IllegalArgumentException("duplicate field " + name);
                    if (++nodes[0] > MAX_JSON_NODES) throw new IllegalStateException("texture JSON too large");
                    int next = path == 0 && name.equals("textures")
                            ? 1
                            : path == 1 && name.equals("SKIN") ? 2 : path == 2 && name.equals("url") ? 3 : -1;
                    String child = json(reader, next, depth + 1, nodes);
                    if (child != null) url = child;
                }
                reader.endObject();
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                while (reader.hasNext()) json(reader, -1, depth + 1, nodes);
                reader.endArray();
            }
            case STRING -> {
                String value = reader.nextString();
                if (path == 3) url = value;
            }
            case END_DOCUMENT -> throw new IllegalArgumentException("truncated texture JSON");
            default -> reader.skipValue();
        }
        return url;
    }
}
