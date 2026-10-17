package ac.cult.cultac.protocol.packet;

import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketCatalog;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.codec.connection.ChatCodec;
import ac.cult.cultac.protocol.codec.connection.ChatCommandCodec;
import ac.cult.cultac.protocol.codec.connection.ChatCommandSignedCodec;
import ac.cult.cultac.protocol.codec.connection.ClientCommandCodec;
import ac.cult.cultac.protocol.codec.connection.ClientInformationCodec;
import ac.cult.cultac.protocol.codec.connection.CommandSuggestionCodec;
import ac.cult.cultac.protocol.codec.connection.CustomPayloadCodec;
import ac.cult.cultac.protocol.codec.connection.EditBookCodec;
import ac.cult.cultac.protocol.codec.connection.IntentionCodec;
import ac.cult.cultac.protocol.codec.connection.PongCodec;
import ac.cult.cultac.protocol.codec.connection.RenameItemCodec;
import ac.cult.cultac.protocol.codec.connection.SelectBundleItemCodec;
import ac.cult.cultac.protocol.codec.connection.SelectTradeCodec;
import ac.cult.cultac.protocol.codec.connection.ServerboundKeepAliveCodec;
import ac.cult.cultac.protocol.codec.connection.SetCarriedItemCodec;
import ac.cult.cultac.protocol.codec.interaction.InteractCodec;
import ac.cult.cultac.protocol.codec.interaction.PlayerActionCodec;
import ac.cult.cultac.protocol.codec.interaction.SpectatorActionCodec;
import ac.cult.cultac.protocol.codec.interaction.SwingCodec;
import ac.cult.cultac.protocol.codec.interaction.TeleportToEntityCodec;
import ac.cult.cultac.protocol.codec.interaction.UseItemCodec;
import ac.cult.cultac.protocol.codec.interaction.UseItemOnCodec;
import ac.cult.cultac.protocol.codec.movement.AcceptTeleportationCodec;
import ac.cult.cultac.protocol.codec.movement.MovePlayerCodec;
import ac.cult.cultac.protocol.codec.movement.PaddleBoatCodec;
import ac.cult.cultac.protocol.codec.movement.PlayerCommandCodec;
import ac.cult.cultac.protocol.codec.movement.PlayerInputCodec;
import ac.cult.cultac.protocol.codec.movement.ServerboundMoveVehicleCodec;
import ac.cult.cultac.protocol.codec.movement.ServerboundPlayerAbilitiesCodec;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundChat;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundChatCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundChatCommandSigned;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundClientCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundClientInformation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundCommandSuggestion;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundCustomPayload;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundEditBook;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundIntention;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundKeepAlive;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPaddleBoat;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAbilities;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPong;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundRenameItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSelectBundleItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSelectTrade;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSetCarriedItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSpectatorAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSwing;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundTeleportToEntity;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import java.util.List;
import java.util.Set;

/**
 * Serverbound families Cult routes, grouped by connection phase. Which names exist on a version comes from
 * that version's protocol data; {@code since} marks codecs verified only from a version onward.
 */
public final class ServerboundPackets {
    private static final PacketCatalog C = PacketCatalog.serverbound();
    private static final PacketCatalog.Scope PLAY = C.in(ConnectionPhase.PLAY);
    private static final PacketCatalog.Scope COMMON = C.in(ConnectionPhase.CONFIGURATION, ConnectionPhase.PLAY);

    // Handshake, login and configuration.
    public static final PacketType<ServerboundIntention> INTENTION =
            C.in(ConnectionPhase.HANDSHAKE).add("intention", ServerboundIntention.class, new IntentionCodec());
    public static final PacketType<Opaque> LOGIN_ACKNOWLEDGED =
            C.in(ConnectionPhase.LOGIN).empty("login_acknowledged");
    public static final PacketType<Opaque> COOKIE_RESPONSE = C.in(
                    ConnectionPhase.LOGIN, ConnectionPhase.CONFIGURATION, ConnectionPhase.PLAY)
            .ignored("cookie_response");
    public static final PacketType<Opaque> FINISH_CONFIGURATION =
            C.in(ConnectionPhase.CONFIGURATION).empty("finish_configuration");
    public static final PacketType<Opaque> CONFIGURATION_ACKNOWLEDGED = PLAY.empty("configuration_acknowledged");
    public static final PacketType<Opaque> PING_REQUEST =
            C.in(ConnectionPhase.STATUS, ConnectionPhase.PLAY).ignored("ping_request");

    // Configuration and play.
    public static final PacketType<ServerboundClientInformation> CLIENT_INFORMATION =
            COMMON.add("client_information", ServerboundClientInformation.class, new ClientInformationCodec());
    public static final PacketType<ServerboundCustomPayload> CUSTOM_PAYLOAD = COMMON.add(
            "custom_payload",
            ServerboundCustomPayload.class,
            new CustomPayloadCodec(Set.of(ClientboundPackets.BRIDGE_CHANNEL)));
    public static final PacketType<ServerboundKeepAlive> KEEP_ALIVE =
            COMMON.add("keep_alive", ServerboundKeepAlive.class, new ServerboundKeepAliveCodec());
    public static final PacketType<ServerboundPong> PONG = COMMON.add("pong", ServerboundPong.class, new PongCodec());
    public static final PacketType<Opaque> RESOURCE_PACK = COMMON.ignored("resource_pack");
    public static final PacketType<Opaque> CUSTOM_CLICK_ACTION = COMMON.ignored("custom_click_action");

    // Movement.
    public static final PacketType<ServerboundAcceptTeleportation> ACCEPT_TELEPORTATION =
            PLAY.add("accept_teleportation", ServerboundAcceptTeleportation.class, new AcceptTeleportationCodec());
    public static final PacketType<ServerboundMovePlayer> MOVE_PLAYER =
            PLAY.variants("move_player", ServerboundMovePlayer.class, new MovePlayerCodec());
    public static final PacketType<ServerboundMoveVehicle> MOVE_VEHICLE =
            PLAY.add("move_vehicle", ServerboundMoveVehicle.class, new ServerboundMoveVehicleCodec());
    public static final PacketType<ServerboundPaddleBoat> PADDLE_BOAT =
            PLAY.add("paddle_boat", ServerboundPaddleBoat.class, new PaddleBoatCodec());
    public static final PacketType<ServerboundPlayerInput> PLAYER_INPUT =
            PLAY.add("player_input", ServerboundPlayerInput.class, new PlayerInputCodec());
    public static final PacketType<ServerboundPlayerCommand> PLAYER_COMMAND =
            PLAY.add("player_command", ServerboundPlayerCommand.class, new PlayerCommandCodec());
    public static final PacketType<ServerboundPlayerAbilities> PLAYER_ABILITIES =
            PLAY.add("player_abilities", ServerboundPlayerAbilities.class, new ServerboundPlayerAbilitiesCodec());
    public static final PacketType<Opaque> CLIENT_TICK_END = PLAY.empty("client_tick_end");
    public static final PacketType<Opaque> PLAYER_LOADED = PLAY.ignored("player_loaded");

    // Interaction.
    public static final PacketType<ServerboundPlayerAction> PLAYER_ACTION =
            PLAY.add("player_action", ServerboundPlayerAction.class, new PlayerActionCodec());
    public static final PacketType<ServerboundUseItem> USE_ITEM =
            PLAY.add("use_item", ServerboundUseItem.class, new UseItemCodec());
    public static final PacketType<ServerboundUseItemOn> USE_ITEM_ON =
            PLAY.add("use_item_on", ServerboundUseItemOn.class, new UseItemOnCodec());
    public static final PacketType<ServerboundSwing> SWING =
            PLAY.variants("swing", ServerboundSwing.class, new SwingCodec());
    public static final PacketType<ServerboundInteract> INTERACT =
            PLAY.variants("interact", ServerboundInteract.class, new InteractCodec());
    public static final PacketType<ServerboundSpectatorAction> SPECTATOR_ACTION = PLAY.renamed(
            "spectator_action",
            ServerboundSpectatorAction.class,
            new SpectatorActionCodec(),
            "spectate_entity",
            "spectator_action");
    public static final PacketType<ServerboundTeleportToEntity> TELEPORT_TO_ENTITY =
            PLAY.add("teleport_to_entity", ServerboundTeleportToEntity.class, new TeleportToEntityCodec());
    public static final PacketType<Opaque> PICK_ITEM = PLAY.ignored("pick_item");
    public static final PacketType<Opaque> PICK_ITEM_FROM_BLOCK = PLAY.ignored("pick_item_from_block");
    public static final PacketType<Opaque> PICK_ITEM_FROM_ENTITY = PLAY.ignored("pick_item_from_entity");

    // Inventory and items.
    public static final PacketType<ServerboundSetCarriedItem> SET_CARRIED_ITEM =
            PLAY.add("set_carried_item", ServerboundSetCarriedItem.class, new SetCarriedItemCodec());
    public static final PacketType<ServerboundSelectTrade> SELECT_TRADE =
            PLAY.add("select_trade", ServerboundSelectTrade.class, new SelectTradeCodec());
    public static final PacketType<ServerboundSelectBundleItem> SELECT_BUNDLE_ITEM =
            PLAY.add("bundle_item_selected", ServerboundSelectBundleItem.class, new SelectBundleItemCodec());
    public static final PacketType<ServerboundEditBook> EDIT_BOOK =
            PLAY.add("edit_book", ServerboundEditBook.class, new EditBookCodec());
    public static final PacketType<ServerboundRenameItem> RENAME_ITEM =
            PLAY.add("rename_item", ServerboundRenameItem.class, new RenameItemCodec());
    public static final PacketType<ac.cult.cultac.protocol.packet.serverbound.ServerboundContainerClose>
            CONTAINER_CLOSE = PLAY.add(
                    "container_close",
                    ac.cult.cultac.protocol.packet.serverbound.ServerboundContainerClose.class,
                    new ac.cult.cultac.protocol.codec.connection.ContainerCloseCodecs.Serverbound());
    public static final PacketType<Opaque> CONTAINER_BUTTON_CLICK = PLAY.ignored("container_button_click");
    public static final PacketType<Opaque> CONTAINER_SLOT_STATE_CHANGED = PLAY.ignored("container_slot_state_changed");
    public static final PacketType<Opaque> PLACE_RECIPE = PLAY.ignored("place_recipe");
    public static final PacketType<Opaque> RECIPE_BOOK_CHANGE_SETTINGS = PLAY.ignored("recipe_book_change_settings");
    public static final PacketType<Opaque> RECIPE_BOOK_SEEN_RECIPE = PLAY.ignored("recipe_book_seen_recipe");
    public static final PacketType<Opaque> SET_BEACON = PLAY.ignored("set_beacon");

    // Chat and commands.
    public static final PacketType<ServerboundChat> CHAT = PLAY.add("chat", ServerboundChat.class, new ChatCodec());
    public static final PacketType<ServerboundChatCommand> CHAT_COMMAND =
            PLAY.add("chat_command", ServerboundChatCommand.class, new ChatCommandCodec());
    public static final PacketType<ServerboundChatCommandSigned> CHAT_COMMAND_SIGNED =
            PLAY.add("chat_command_signed", ServerboundChatCommandSigned.class, new ChatCommandSignedCodec());
    public static final PacketType<ServerboundClientCommand> CLIENT_COMMAND =
            PLAY.add("client_command", ServerboundClientCommand.class, new ClientCommandCodec());
    public static final PacketType<ServerboundCommandSuggestion> COMMAND_SUGGESTION =
            PLAY.add("command_suggestion", ServerboundCommandSuggestion.class, new CommandSuggestionCodec());
    public static final PacketType<Opaque> CHAT_ACK = PLAY.ignored("chat_ack");
    public static final PacketType<Opaque> CHAT_SESSION_UPDATE = PLAY.ignored("chat_session_update");

    // Routed without a payload consumer.
    public static final PacketType<Opaque> BLOCK_ENTITY_TAG_QUERY = PLAY.ignored("block_entity_tag_query");
    public static final PacketType<Opaque> CHANGE_DIFFICULTY = PLAY.ignored("change_difficulty");
    public static final PacketType<Opaque> CHANGE_GAME_MODE = PLAY.ignored("change_game_mode");
    public static final PacketType<Opaque> CHUNK_BATCH_RECEIVED = PLAY.ignored("chunk_batch_received");
    public static final PacketType<Opaque> DEBUG_SAMPLE_SUBSCRIPTION = PLAY.ignored("debug_sample_subscription");
    public static final PacketType<Opaque> DEBUG_SUBSCRIPTION_REQUEST = PLAY.ignored("debug_subscription_request");
    public static final PacketType<Opaque> ENTITY_TAG_QUERY = PLAY.ignored("entity_tag_query");
    public static final PacketType<Opaque> JIGSAW_GENERATE = PLAY.ignored("jigsaw_generate");
    public static final PacketType<Opaque> LOCK_DIFFICULTY = PLAY.ignored("lock_difficulty");
    public static final PacketType<Opaque> SEEN_ADVANCEMENTS = PLAY.ignored("seen_advancements");
    public static final PacketType<Opaque> SET_COMMAND_BLOCK = PLAY.ignored("set_command_block");
    public static final PacketType<Opaque> SET_COMMAND_MINECART = PLAY.ignored("set_command_minecart");
    public static final PacketType<Opaque> SET_GAME_RULE = PLAY.ignored("set_game_rule");
    public static final PacketType<Opaque> SET_JIGSAW_BLOCK = PLAY.ignored("set_jigsaw_block");
    public static final PacketType<Opaque> SET_STRUCTURE_BLOCK = PLAY.ignored("set_structure_block");
    public static final PacketType<Opaque> SET_TEST_BLOCK = PLAY.ignored("set_test_block");
    public static final PacketType<Opaque> SIGN_UPDATE = PLAY.ignored("sign_update");
    public static final PacketType<Opaque> TEST_INSTANCE_BLOCK_ACTION = PLAY.ignored("test_instance_block_action");

    private static final List<PacketType<?>> ALL = C.types();

    private ServerboundPackets() {}

    public static List<PacketType<?>> all() {
        return ALL;
    }
}
