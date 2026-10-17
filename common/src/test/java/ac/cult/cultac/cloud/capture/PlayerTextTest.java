package ac.cult.cultac.cloud.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.IdTable;
import ac.cult.cultac.protocol.data.ModelRegistryData;
import ac.cult.cultac.protocol.data.ProtocolData;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Packets written byte for byte as their protocol frames them, with the text a player wrote in them and without: what
 * {@link PlayerText} keeps of the first is, byte for byte, the second, every field the service reads left as it was.
 */
class PlayerTextTest {
    private static final int PROTOCOL = 777;
    private static final PlayerText text = PlayerText.of(PROTOCOL);

    @Test
    void everyProtocolStripsWithItsOwnCodecs() {
        for (int protocol = 768; protocol <= 777; protocol++) {
            int open = id(protocol, true, "open_screen");
            byte[] titled = bytes(out -> {
                varInt(out, open);
                varInt(out, 1);
                varInt(out, 2);
                text(out, "Bob's chest");
            });
            byte[] kept = PlayerText.of(protocol).clientbound(open, titled, true);
            assertNotNull(kept, "codecs for " + protocol);
            assertEquals(titled.length - "Bob's chest".length(), kept.length, "title of " + protocol);
            assertTrue(id(protocol, false, "edit_book") >= 0);
        }
    }

    @Test
    void aSlotsStackLosesItsNameAndLoreAndKeepsTheRest() {
        assertArrayEquals(slot(false), strip(slot(true)));
    }

    @Test
    void aScreenOpensUnderAnEmptyTitle() {
        int open = id(PROTOCOL, true, "open_screen");
        Function<String, byte[]> screen = title -> bytes(out -> {
            varInt(out, open);
            varInt(out, 4);
            varInt(out, 2);
            text(out, title);
        });
        assertArrayEquals(screen.apply(""), strip(screen.apply("Bob's chest")));
    }

    @Test
    void blockEntitiesLoseTheirDataInChunksAndUpdates() {
        int chunk = id(PROTOCOL, true, "level_chunk_with_light");
        byte[] sign = levelChunk(chunk, true);
        assertArrayEquals(levelChunk(chunk, false), strip(sign));
        byte[] bare = levelChunk(chunk, false);
        assertSame(bare, strip(bare));
        assertNull(strip(Arrays.copyOf(sign, sign.length - 1)));

        int update = id(PROTOCOL, true, "block_entity_data");
        Consumer<ByteArrayOutputStream> head = out -> {
            varInt(out, update);
            for (int i = 0; i < 8; i++) out.write(i);
            varInt(out, 7);
        };
        byte[] written = bytes(out -> {
            head.accept(out);
            out.write(10);
            out.write(9);
            name(out, "messages");
            out.write(8);
            int32(out, 1);
            name(out, "Bob");
            out.write(0);
        });
        assertArrayEquals(
                bytes(out -> {
                    head.accept(out);
                    out.write(0);
                }),
                strip(written));
    }

    @Test
    void chunksOnEveryFramingLoseTheirBlockEntitiesData() {
        for (int protocol : new int[] {768, 769, 770, 776, 777}) {
            int chunk = id(protocol, true, "level_chunk_with_light");
            PlayerText text = PlayerText.of(protocol);
            byte[] sign = levelChunk(protocol, chunk, true);
            assertArrayEquals(
                    levelChunk(protocol, chunk, false), text.clientbound(chunk, sign, true), "chunk " + protocol);
            byte[] bare = levelChunk(protocol, chunk, false);
            assertSame(bare, text.clientbound(chunk, bare, true), "bare chunk " + protocol);
            assertNull(text.clientbound(chunk, Arrays.copyOf(sign, sign.length - 1), true), "cut chunk " + protocol);
        }
    }

    @Test
    void writingAClientSendsIsNeverSent() {
        for (String writing : List.of("sign_update", "edit_book", "rename_item")) {
            byte[] packet = {(byte) id(PROTOCOL, false, writing), 1, 2, 3};
            assertNull(text.serverbound(id(PROTOCOL, false, writing), packet, true), writing);
        }
    }

    @Test
    void privatePacketsAreNeverSentInEitherDirection() {
        ProtocolData data = ProtocolData.load(ProtocolVersion.of(PROTOCOL));
        IdTable serverbound = data.packets(ConnectionPhase.PLAY, PacketDirection.SERVERBOUND);
        IdTable clientbound = data.packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND);
        for (String name : List.of("chat", "chat_command", "chat_command_signed", "custom_payload", "sign_update")) {
            assertTrue(serverbound.id("minecraft:" + name) >= 0, name);
        }
        for (String name : List.of("player_chat", "system_chat", "custom_payload", "command_suggestions")) {
            assertTrue(clientbound.id("minecraft:" + name) >= 0, name);
        }
        for (String name : PlayerText.PRIVATE_SERVERBOUND) {
            int id = serverbound.id("minecraft:" + name);
            if (id >= 0) assertNull(text.serverbound(id, new byte[] {(byte) id, 1, 2, 3}, true), name);
        }
        for (String name : PlayerText.WITHHELD_CLIENTBOUND) {
            int id = clientbound.id("minecraft:" + name);
            if (id >= 0) assertNull(text.clientbound(id, new byte[] {(byte) id, 1, 2, 3}, true), name);
        }
    }

    @Test
    void untouchedPacketsKeepTheirArrayAndUnreadableOnesAreDropped() {
        byte[] plain = slot(false);
        assertSame(plain, strip(plain));
        byte[] movement = {(byte) id(PROTOCOL, true, "move_entity_pos"), 1, 2, 3, 4, 5, 6, 7, 1};
        assertSame(movement, strip(movement));

        byte[] written = slot(true);
        long unreadable = PlayerText.unreadable.sum();
        assertNull(strip(Arrays.copyOf(written, written.length - 1)));
        assertNull(strip(Arrays.copyOf(written, written.length + 1)));
        assertEquals(unreadable + 2, PlayerText.unreadable.sum());
    }

    @Test
    void withoutCodecsTextGoesOutOnlyOnAnUnstrippedSocket() {
        PlayerText none = PlayerText.load(PROTOCOL, version -> {
            throw new IllegalStateException("no codecs");
        });
        int open = id(PROTOCOL, true, "open_screen");
        byte[] titled = {(byte) open, 1, 2, 8, 0, 1, 'B'};
        int click = id(PROTOCOL, false, "container_click");
        byte[] clicked = {(byte) click, 0};
        assertNull(none.clientbound(open, titled, true));
        assertNull(none.serverbound(click, clicked, true));
        long unstripped = PlayerText.unstripped.sum();
        assertSame(titled, none.clientbound(open, titled, false));
        assertSame(clicked, none.serverbound(click, clicked, false));
        assertEquals(unstripped + 2, PlayerText.unstripped.sum());

        for (boolean textStripped : new boolean[] {true, false}) {
            for (String name : List.of("player_chat", "set_title_text", "merchant_offers", "transfer")) {
                int id = id(PROTOCOL, true, name);
                assertNull(none.clientbound(id, new byte[] {(byte) id, 0}, textStripped), name);
            }
            for (String name : List.of("chat_command", "sign_update", "edit_book", "custom_payload")) {
                int id = id(PROTOCOL, false, name);
                assertNull(none.serverbound(id, new byte[] {(byte) id, 1, 'l'}, textStripped), name);
            }
            assertNull(none.clientbound(300, new byte[] {0}, textStripped));
            int moved = id(PROTOCOL, true, "move_entity_pos");
            byte[] movement = {(byte) moved, 1, 2, 3, 4, 5, 6, 7, 1};
            assertSame(movement, none.clientbound(moved, movement, textStripped));
            int ended = id(PROTOCOL, false, "client_tick_end");
            byte[] tickEnd = {(byte) ended};
            assertSame(tickEnd, none.serverbound(ended, tickEnd, textStripped));
        }
    }

    @Test
    void aCodecClassThatCannotLoadFailsOpenLikeAMissingCodec() {
        PlayerText broken = PlayerText.load(PROTOCOL, version -> {
            throw new NoClassDefFoundError("codec");
        });
        int open = id(PROTOCOL, true, "open_screen");
        byte[] titled = {(byte) open, 1, 2, 8, 0, 1, 'B'};
        assertNull(broken.clientbound(open, titled, true));
        assertSame(titled, broken.clientbound(open, titled, false));
    }

    @Test
    void anUnreadablePacketIsNeverSent() {
        byte[] written = slot(true);
        int slot = id(PROTOCOL, true, "container_set_slot");
        assertNull(text.clientbound(slot, Arrays.copyOf(written, written.length - 1), false));
    }

    @Test
    void withoutPacketIdsNothingIsSent() {
        PlayerText unknown = PlayerText.of(999);
        assertNotSame(unknown, PlayerText.of(999));
        assertNull(unknown.clientbound(1, new byte[] {1, 0}, false));
        assertNull(unknown.serverbound(1, new byte[] {1, 0}, false));
        assertTrue(PlayerText.strips());
    }

    @Test
    void everyPacketIsClassifiedOnce() {
        for (ProtocolVersion version : ProtocolVersion.values()) {
            ProtocolData data = ProtocolData.load(version);
            assertClassifiedOnce(
                    version + " clientbound ",
                    data.packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND),
                    List.of(PlayerText.CLIENTBOUND, PlayerText.TEXT_FREE_CLIENTBOUND, PlayerText.WITHHELD_CLIENTBOUND));
            assertClassifiedOnce(
                    version + " serverbound ",
                    data.packets(ConnectionPhase.PLAY, PacketDirection.SERVERBOUND),
                    List.of(PlayerText.SERVERBOUND, PlayerText.TEXT_FREE_SERVERBOUND, PlayerText.PRIVATE_SERVERBOUND));
        }
    }

    private static void assertClassifiedOnce(String where, IdTable packets, List<Set<String>> kinds) {
        for (int id = 0; id < packets.size(); id++) {
            String name = packets.name(id).substring("minecraft:".length());
            assertEquals(1, kinds.stream().filter(kind -> kind.contains(name)).count(), where + name);
        }
    }

    @Test
    void aPacketNotKnownHereIsNeverSent() {
        PlayerText text = new PlayerText(
                PROTOCOL, null, name -> name.equals("move_entity_pos") ? 3 : -1, name -> name.equals("swing") ? 3 : -1);
        byte[] moved = {3, 0};
        assertSame(moved, text.clientbound(3, moved, true));
        assertNull(text.clientbound(4, new byte[] {4, 0}, true));
        assertNull(text.clientbound(300, new byte[] {0}, true));
        byte[] swung = {3, 0};
        assertSame(swung, text.serverbound(3, swung, true));
        assertNull(text.serverbound(4, new byte[] {4, 0}, true));
        assertNull(text.serverbound(300, new byte[] {0}, true));
    }

    @Test
    void cookiesTransfersTitlesTradesAndScoresAreNeverSent() {
        for (int protocol = 768; protocol <= 777; protocol++) {
            for (String name : List.of(
                    "store_cookie",
                    "transfer",
                    "tag_query",
                    "map_item_data",
                    "merchant_offers",
                    "set_title_text",
                    "set_subtitle_text",
                    "set_action_bar_text",
                    "set_objective",
                    "set_score")) {
                int id = id(protocol, true, name);
                assertTrue(id >= 0, protocol + " " + name);
                assertNull(
                        PlayerText.of(protocol).clientbound(id, new byte[] {(byte) id, 0}, true),
                        protocol + " " + name);
            }
        }
    }

    /** What the service selects by default: none of it is withheld. */
    @Test
    void theDefaultSelectionIsNeverWithheld() {
        for (String name : List.of(
                "level_chunk_with_light",
                "forget_level_chunk",
                "block_update",
                "section_blocks_update",
                "add_entity",
                "remove_entities",
                "move_entity_pos",
                "move_entity_pos_rot",
                "move_entity_rot",
                "move_minecart_along_track",
                "entity_position_sync",
                "teleport_entity",
                "rotate_head",
                "set_entity_motion",
                "set_entity_data",
                "set_equipment",
                "set_entity_link",
                "set_passengers",
                "entity_event",
                "animate",
                "swing_animation",
                "hurt_animation",
                "damage_event",
                "take_item_entity",
                "update_attributes",
                "update_mob_effect",
                "remove_mob_effect",
                "projectile_power",
                "player_info_update",
                "player_info_remove",
                "ping",
                "keep_alive",
                "set_health",
                "explode",
                "sound",
                "sound_entity",
                "player_position",
                "player_rotation",
                "player_look_at",
                "open_screen",
                "horse_screen_open",
                "mount_screen_open",
                "open_book",
                "open_sign_editor",
                "container_close",
                "cooldown",
                "game_event",
                "set_player_team",
                "bundle_delimiter",
                "block_changed_ack",
                "ticking_state",
                "set_held_slot",
                "container_set_slot",
                "container_set_content",
                "set_player_inventory",
                "set_cursor_item",
                "show_dialog",
                "clear_dialog",
                "resource_pack_push",
                "resource_pack_pop",
                "player_combat_kill",
                "block_destruction",
                "level_event",
                "login",
                "respawn")) {
            assertTrue(PlayerText.CLIENTBOUND.contains(name) || PlayerText.TEXT_FREE_CLIENTBOUND.contains(name), name);
        }
    }

    /** Protocol 768, before stacks a client sends were length-prefixed or hashed. */
    @Test
    void protocol768StripsAClickedStackAndANamedEntity() {
        int protocol = 768;
        PlayerText old = PlayerText.of(protocol);
        int customName = component(protocol, "custom_name");
        int damage = component(protocol, "damage");
        Consumer<ByteArrayOutputStream> renamed = out -> {
            varInt(out, 1);
            varInt(out, 800);
            varInt(out, 2);
            varInt(out, 0);
            varInt(out, damage);
            varInt(out, 5);
            varInt(out, customName);
            text(out, "Bob's blade");
        };
        Consumer<ByteArrayOutputStream> plain = out -> {
            varInt(out, 1);
            varInt(out, 800);
            varInt(out, 1);
            varInt(out, 0);
            varInt(out, damage);
            varInt(out, 5);
        };
        int click = id(protocol, false, "container_click");
        assertArrayEquals(click(click, plain), old.serverbound(click, click(click, renamed), true));

        int entityData = id(protocol, true, "set_entity_data");
        byte[] named = bytes(out -> {
            varInt(out, entityData);
            varInt(out, 9);
            out.write(2);
            varInt(out, 6);
            out.write(1);
            text(out, "Bob's frame");
            out.write(8);
            varInt(out, 7);
            renamed.accept(out);
            out.write(0xFF);
        });
        assertArrayEquals(
                bytes(out -> {
                    varInt(out, entityData);
                    varInt(out, 9);
                    out.write(2);
                    varInt(out, 6);
                    out.write(0);
                    out.write(8);
                    varInt(out, 7);
                    plain.accept(out);
                    out.write(0xFF);
                }),
                old.clientbound(entityData, named, true));
    }

    /** A shulker box holding a named blade and a bundle with a lored gem: what is inside loses its text too. */
    @Test
    void nestedStacksLoseTheirTextAndKeepTheirItems() {
        int slot = id(PROTOCOL, true, "container_set_slot");
        int container = component(PROTOCOL, "container");
        int bundle = component(PROTOCOL, "bundle_contents");
        int customName = component(PROTOCOL, "custom_name");
        int lore = component(PROTOCOL, "lore");
        int damage = component(PROTOCOL, "damage");
        Function<Boolean, byte[]> box = written -> bytes(out -> {
            varInt(out, slot);
            varInt(out, 0);
            varInt(out, 3);
            int16(out, 12);
            varInt(out, 1); // A shulker box.
            varInt(out, 600);
            varInt(out, 1);
            varInt(out, 0);
            varInt(out, container);
            varInt(out, 2);
            out.write(1); // A blade, damaged and named.
            varInt(out, 900);
            varInt(out, 1);
            varInt(out, written ? 2 : 1);
            varInt(out, 0);
            if (written) {
                varInt(out, customName);
                text(out, "Bob's blade");
            }
            varInt(out, damage);
            varInt(out, 7);
            out.write(1); // A bundle holding a gem with lore.
            varInt(out, 901);
            varInt(out, 1);
            varInt(out, 1);
            varInt(out, 0);
            varInt(out, bundle);
            varInt(out, 1);
            varInt(out, 902);
            varInt(out, 16);
            varInt(out, written ? 1 : 0);
            varInt(out, 0);
            if (written) {
                varInt(out, lore);
                varInt(out, 2);
                text(out, "For Alex");
                text(out, "from Bob");
            }
        });
        assertArrayEquals(box.apply(false), strip(box.apply(true)));
    }

    /** A signed book with its custom data: both go with their components, the damage stays. */
    @Test
    void writtenBooksAndCustomDataGoWithTheirComponents() {
        int cursor = id(PROTOCOL, true, "set_cursor_item");
        int book = component(PROTOCOL, "written_book_content");
        int customData = component(PROTOCOL, "custom_data");
        int damage = component(PROTOCOL, "damage");
        byte[] sent = bytes(out -> {
            varInt(out, cursor);
            varInt(out, 1);
            varInt(out, 1100);
            varInt(out, 3);
            varInt(out, 0);
            varInt(out, book);
            string(out, "Diary");
            out.write(0);
            string(out, "Bob");
            varInt(out, 0);
            varInt(out, 2);
            text(out, "Dear diary,");
            out.write(0);
            text(out, "my password is hunter2");
            out.write(1);
            text(out, "my password is *******");
            out.write(1);
            varInt(out, customData);
            out.write(10);
            out.write(8);
            name(out, "owner");
            name(out, "Bob");
            out.write(0);
            varInt(out, damage);
            varInt(out, 2);
        });
        assertArrayEquals(
                bytes(out -> {
                    varInt(out, cursor);
                    varInt(out, 1);
                    varInt(out, 1100);
                    varInt(out, 1);
                    varInt(out, 0);
                    varInt(out, damage);
                    varInt(out, 2);
                }),
                strip(sent));
    }

    /** A head's profile keeps only its skin on Mojang's server; one with a skin elsewhere goes. */
    @Test
    void aProfileKeepsOnlyItsSkin() {
        String skin = "http://textures.minecraft.net/texture/3fa9c0d1e2";
        String minimal = base64("{\"textures\":{\"SKIN\":{\"url\":\"" + skin + "\"}}}");
        String full = base64("{\"timestamp\":1700000000,\"profileName\":\"Bob\",\"textures\":{\"SKIN\":{\"url\":\""
                + skin + "\",\"metadata\":{\"model\":\"slim\"}},\"CAPE\":{\"url\":\""
                + "http://textures.minecraft.net/texture/c4"
                + "\"}}}");
        String elsewhere = base64("{\"textures\":{\"SKIN\":{\"url\":\"http://example.com/bob.png\"}}}");
        for (int protocol : new int[] {768, 777}) {
            boolean resolvable = protocol >= 773;
            int slot = id(protocol, true, "set_player_inventory");
            int profile = component(protocol, "profile");
            int rarity = component(protocol, "rarity");
            Function<Consumer<ByteArrayOutputStream>, byte[]> head = data -> bytes(out -> {
                varInt(out, slot);
                varInt(out, 39);
                varInt(out, 1);
                varInt(out, 1200);
                varInt(out, data == null ? 1 : 2);
                varInt(out, 0);
                if (data != null) {
                    varInt(out, profile);
                    data.accept(out);
                }
                varInt(out, rarity);
                varInt(out, 1);
            });
            byte[] sent = head.apply(out -> {
                if (resolvable) varInt(out, 0);
                out.write(1);
                string(out, "Bob");
                out.write(1);
                int64(out, 0x1234_5678_9ABCL);
                int64(out, 0x0FED_CBA9_8765L);
                varInt(out, 1);
                string(out, "textures");
                string(out, full);
                out.write(1);
                string(out, "c2lnbmF0dXJl");
                if (resolvable) out.writeBytes(new byte[4]);
            });
            assertArrayEquals(
                    head.apply(out -> {
                        if (resolvable) varInt(out, 0);
                        out.write(0);
                        out.write(0);
                        varInt(out, 1);
                        string(out, "textures");
                        string(out, minimal);
                        out.write(0);
                        if (resolvable) out.writeBytes(new byte[4]);
                    }),
                    PlayerText.of(protocol).clientbound(slot, sent, true),
                    "skin kept on " + protocol);
            byte[] foreign = head.apply(out -> {
                if (resolvable) varInt(out, 0);
                out.write(0);
                out.write(0);
                varInt(out, 1);
                string(out, "textures");
                string(out, elsewhere);
                out.write(0);
                if (resolvable) out.writeBytes(new byte[4]);
            });
            assertArrayEquals(
                    head.apply(null),
                    PlayerText.of(protocol).clientbound(slot, foreign, true),
                    "skin gone on " + protocol);
        }
    }

    /** Equipment, a cloud's particles and an explosion's: the stacks in them lose their text, the rest stays. */
    @Test
    void stacksInEquipmentAndParticlesLoseTheirText() {
        int equipment = id(PROTOCOL, true, "set_equipment");
        Function<Boolean, byte[]> equipped = written -> bytes(out -> {
            varInt(out, equipment);
            varInt(out, 42);
            out.write(0x80);
            blade(out, written);
            out.write(5);
            varInt(out, 1);
            varInt(out, 980);
            varInt(out, 0);
            varInt(out, 0);
        });
        assertArrayEquals(equipped.apply(false), strip(equipped.apply(true)));

        int itemParticle = registry(PROTOCOL, "minecraft:particle_type", "minecraft:item");
        int dust = registry(PROTOCOL, "minecraft:particle_type", "minecraft:dust");
        int poof = registry(PROTOCOL, "minecraft:particle_type", "minecraft:poof");
        int entityData = id(PROTOCOL, true, "set_entity_data");
        Function<Boolean, byte[]> cloud = written -> bytes(out -> {
            varInt(out, entityData);
            varInt(out, 77);
            out.write(11);
            varInt(out, 17); // Particles.
            varInt(out, 2);
            varInt(out, dust);
            int32(out, 0xFF0000);
            int32(out, Float.floatToIntBits(1.5f));
            varInt(out, itemParticle);
            template(out, written);
            out.write(0xFF);
        });
        assertArrayEquals(cloud.apply(false), strip(cloud.apply(true)));

        int explode = id(PROTOCOL, true, "explode");
        Function<Boolean, byte[]> blast = written -> bytes(out -> {
            varInt(out, explode);
            int64(out, Double.doubleToLongBits(10.5));
            int64(out, Double.doubleToLongBits(64));
            int64(out, Double.doubleToLongBits(-3.25));
            int32(out, Float.floatToIntBits(4));
            int32(out, 12);
            out.write(1);
            int64(out, Double.doubleToLongBits(0.5));
            int64(out, Double.doubleToLongBits(0.25));
            int64(out, Double.doubleToLongBits(-0.5));
            varInt(out, itemParticle);
            template(out, written);
            varInt(out, 6); // A sound by id.
            varInt(out, 1);
            varInt(out, poof);
            int32(out, Float.floatToIntBits(1));
            int32(out, Float.floatToIntBits(0.5f));
            varInt(out, 3);
            out.write(1);
        });
        assertArrayEquals(blast.apply(false), strip(blast.apply(true)));
    }

    /** Tab names and team presentation go empty; who is listed, their latency and a team's players stay. */
    @Test
    void tabNamesAndTeamPresentationGoEmptyAndMembershipStays() {
        int info = id(PROTOCOL, true, "player_info_update");
        Function<String, byte[]> entry = name -> bytes(out -> {
            varInt(out, info);
            out.write(0x01 | 0x08 | 0x10 | 0x20);
            varInt(out, 1);
            int64(out, 7);
            int64(out, 8);
            string(out, "Bob");
            varInt(out, 0);
            out.write(1);
            varInt(out, 42);
            out.write(1);
            text(out, name);
        });
        assertArrayEquals(entry.apply(""), strip(entry.apply("[Owner] Bob")));

        for (int protocol : new int[] {768, 770, 776, 777}) {
            int team = id(protocol, true, "set_player_team");
            Function<Boolean, byte[]> added = written -> bytes(out -> {
                varInt(out, team);
                string(out, "red");
                out.write(0);
                if (protocol >= 776) {
                    text(out, written ? "Red Team" : "");
                    text(out, written ? "[R] " : "");
                    text(out, written ? " !" : "");
                    varInt(out, 0);
                    varInt(out, 1);
                    out.write(1);
                    varInt(out, 12);
                    out.write(3);
                } else {
                    text(out, written ? "Red Team" : "");
                    out.write(3);
                    if (protocol >= 770) {
                        varInt(out, 0);
                        varInt(out, 1);
                    } else {
                        string(out, "always");
                        string(out, "never");
                    }
                    varInt(out, 12);
                    text(out, written ? "[R] " : "");
                    text(out, written ? " !" : "");
                }
                varInt(out, 2);
                string(out, "Bob");
                string(out, "Alex");
            });
            assertArrayEquals(
                    added.apply(false),
                    PlayerText.of(protocol).clientbound(team, added.apply(true), true),
                    "team " + protocol);
            byte[] joined = bytes(out -> {
                varInt(out, team);
                string(out, "red");
                out.write(3);
                varInt(out, 1);
                string(out, "Steve");
            });
            assertSame(joined, PlayerText.of(protocol).clientbound(team, joined, true), "join " + protocol);
        }
    }

    /** An inline dialog goes as an empty compound and one named by its id stays; a death message goes empty. */
    @Test
    void inlineDialogsAndDeathMessagesGoEmpty() {
        int dialog = id(PROTOCOL, true, "show_dialog");
        byte[] inline = bytes(out -> {
            varInt(out, dialog);
            varInt(out, 0);
            out.write(10);
            out.write(8);
            name(out, "type");
            name(out, "minecraft:notice");
            out.write(8);
            name(out, "title");
            name(out, "Bob says hi");
            out.write(0);
        });
        assertArrayEquals(
                bytes(out -> {
                    varInt(out, dialog);
                    varInt(out, 0);
                    out.write(10);
                    out.write(0);
                }),
                strip(inline));
        byte[] named = bytes(out -> {
            varInt(out, dialog);
            varInt(out, 4);
        });
        assertSame(named, strip(named));

        int kill = id(PROTOCOL, true, "player_combat_kill");
        Function<String, byte[]> death = message -> bytes(out -> {
            varInt(out, kill);
            varInt(out, 42);
            text(out, message);
        });
        assertArrayEquals(death.apply(""), strip(death.apply("Bob was slain by Alex using [Excalibur]")));
    }

    /**
     * A creative stack: its name goes, and a kept component with a name in it is sent with its new length; on 768 the
     * components are not length-prefixed.
     */
    @Test
    void aCreativeStackLosesItsTextAndKeepsItsComponentLengthsRight() {
        int creative = id(PROTOCOL, false, "set_creative_mode_slot");
        int customName = component(PROTOCOL, "custom_name");
        int potion = component(PROTOCOL, "potion_contents");
        byte[] named = bytes(out -> text(out, "Bob's brew"));
        byte[] brew = bytes(out -> {
            out.write(1);
            varInt(out, 5);
            out.write(0);
            varInt(out, 0);
            out.write(1);
            string(out, "Bob's brew");
        });
        byte[] plain = bytes(out -> {
            out.write(1);
            varInt(out, 5);
            out.write(0);
            varInt(out, 0);
            out.write(1);
            string(out, "");
        });
        byte[] sent = bytes(out -> {
            varInt(out, creative);
            int16(out, 36);
            varInt(out, 1);
            varInt(out, 1300);
            varInt(out, 2);
            varInt(out, 0);
            varInt(out, customName);
            varInt(out, named.length);
            out.writeBytes(named);
            varInt(out, potion);
            varInt(out, brew.length);
            out.writeBytes(brew);
        });
        assertArrayEquals(
                bytes(out -> {
                    varInt(out, creative);
                    int16(out, 36);
                    varInt(out, 1);
                    varInt(out, 1300);
                    varInt(out, 1);
                    varInt(out, 0);
                    varInt(out, potion);
                    varInt(out, plain.length);
                    out.writeBytes(plain);
                }),
                text.serverbound(creative, sent, true));

        int old = id(768, false, "set_creative_mode_slot");
        byte[] oldSent = bytes(out -> {
            varInt(out, old);
            int16(out, 36);
            varInt(out, 1);
            varInt(out, 1300);
            varInt(out, 1);
            varInt(out, 0);
            varInt(out, component(768, "custom_name"));
            text(out, "Bob's brew");
        });
        assertArrayEquals(
                bytes(out -> {
                    varInt(out, old);
                    int16(out, 36);
                    varInt(out, 1);
                    varInt(out, 1300);
                    varInt(out, 0);
                    varInt(out, 0);
                }),
                PlayerText.of(768).serverbound(old, oldSent, true));
    }

    /** A command block minecart's command and a text component go; other strings and absent names stay. */
    @Test
    void entityDataLosesCommandsAndTextAndKeepsTheRest() {
        for (int protocol : new int[] {768, 777}) {
            int command = protocol >= 770 ? 13 : 14;
            int entityData = id(protocol, true, "set_entity_data");
            Function<Boolean, byte[]> minecart = written -> bytes(out -> {
                varInt(out, entityData);
                varInt(out, 5);
                out.write(command);
                varInt(out, 4);
                string(out, written ? "/op Bob" : "");
                out.write(command + 1);
                varInt(out, 5);
                text(out, written ? "Last output" : "");
                out.write(2);
                varInt(out, 6);
                out.write(0);
                out.write(20);
                varInt(out, 4);
                string(out, "minecraft:kept");
                out.write(0xFF);
            });
            assertArrayEquals(
                    minecart.apply(false),
                    PlayerText.of(protocol).clientbound(entityData, minecart.apply(true), true),
                    "minecart on " + protocol);
        }
    }

    /** A particle effect showing a named item: the item keeps its count and loses its name, in each framing. */
    @Test
    void anItemParticleLosesItsNameInEveryFraming() {
        for (int protocol : new int[] {768, 769, 776, 777}) {
            int particles = id(protocol, true, "level_particles");
            int itemParticle = registry(protocol, "minecraft:particle_type", "minecraft:item");
            int customName = component(protocol, "custom_name");
            int damage = component(protocol, "damage");
            boolean templates = protocol >= 775;
            Consumer<ByteArrayOutputStream> fields = out -> {
                out.write(1);
                if (protocol >= 769) out.write(0);
                for (int i = 0; i < 3; i++) int64(out, Double.doubleToLongBits(i + 0.5));
                for (int i = 0; i < 3; i++) int32(out, Float.floatToIntBits(0.25f));
                if (protocol >= 777) {
                    for (int i = 0; i < 3; i++) int32(out, Float.floatToIntBits(0.1f));
                    varInt(out, 8);
                    varInt(out, 0);
                } else {
                    int32(out, Float.floatToIntBits(0.1f));
                    int32(out, 8);
                }
            };
            Function<Boolean, byte[]> effect = written -> bytes(out -> {
                varInt(out, particles);
                if (protocol < 777) fields.accept(out);
                varInt(out, itemParticle);
                if (!templates) varInt(out, 1);
                varInt(out, 900);
                if (templates) varInt(out, 1);
                varInt(out, written ? 2 : 1);
                varInt(out, 0);
                varInt(out, damage);
                varInt(out, 4);
                if (written) {
                    varInt(out, customName);
                    text(out, "Bob's blade");
                }
                if (protocol >= 777) fields.accept(out);
            });
            assertArrayEquals(
                    effect.apply(false),
                    PlayerText.of(protocol).clientbound(particles, effect.apply(true), true),
                    "particles on " + protocol);
        }
    }

    /** Tab list entries: identity, game mode, latency and listing stay; a chat session and signatures go. */
    @Test
    void tabListEntriesLoseTheirChatSessionAndKeepOnlyAnUnsignedSkin() {
        String skin = "http://textures.minecraft.net/texture/3fa9c0d1e2";
        String minimal = base64("{\"textures\":{\"SKIN\":{\"url\":\"" + skin + "\"}}}");
        String full = base64("{\"timestamp\":1700000000,\"profileName\":\"Bob\",\"textures\":{\"SKIN\":{\"url\":\""
                + skin + "\"}}}");
        for (int protocol : new int[] {768, 777}) {
            int info = id(protocol, true, "player_info_update");
            Function<Boolean, byte[]> entry = written -> bytes(out -> {
                varInt(out, info);
                out.write(0x01 | 0x02 | 0x04 | 0x08 | 0x10);
                varInt(out, 1);
                int64(out, 7);
                int64(out, 8);
                string(out, "Bob");
                if (written) {
                    varInt(out, 2);
                    string(out, "textures");
                    string(out, full);
                    out.write(1);
                    string(out, "c2lnbmF0dXJl");
                    string(out, "plugin_data");
                    string(out, "Bob's secret");
                    out.write(0);
                    out.write(1);
                    int64(out, 1);
                    int64(out, 2);
                    int64(out, 1_800_000_000_000L);
                    varInt(out, 3);
                    out.writeBytes(new byte[] {1, 2, 3});
                    varInt(out, 2);
                    out.writeBytes(new byte[] {4, 5});
                } else {
                    varInt(out, 1);
                    string(out, "textures");
                    string(out, minimal);
                    out.write(0);
                    out.write(0);
                }
                varInt(out, 1);
                out.write(1);
                varInt(out, 42);
            });
            assertArrayEquals(
                    entry.apply(false),
                    PlayerText.of(protocol).clientbound(info, entry.apply(true), true),
                    "tab entry on " + protocol);
        }
    }

    @Test
    void aResourcePackPushKeepsItsIdAndHashAndLosesItsUrlAndPrompt() {
        int push = id(PROTOCOL, true, "resource_pack_push");
        Function<Boolean, byte[]> pack = written -> bytes(out -> {
            varInt(out, push);
            int64(out, 1);
            int64(out, 2);
            string(out, written ? "https://packs.example/bob?token=abc" : "");
            string(out, "0123456789abcdef0123456789abcdef01234567");
            out.write(1);
            out.write(1);
            text(out, written ? "Bob's pack" : "");
        });
        assertArrayEquals(pack.apply(false), strip(pack.apply(true)));
    }

    /** Inline paintings, mannequin profiles and (before 1.21.9) a shoulder parrot's NBT lose their text. */
    @Test
    void entityDataPaintingsProfilesAndShoulderNbtLoseTheirText() {
        for (int protocol : new int[] {768, 777}) {
            int entityData = id(protocol, true, "set_entity_data");
            int painting = protocol >= 775 ? 34 : 26;
            Function<Boolean, byte[]> framed = written -> bytes(out -> {
                varInt(out, entityData);
                varInt(out, 9);
                out.write(8);
                varInt(out, painting);
                varInt(out, 0);
                varInt(out, 2);
                varInt(out, 1);
                string(out, "minecraft:kebab");
                out.write(1);
                text(out, written ? "Bob's art" : "");
                out.write(1);
                text(out, written ? "Bob" : "");
                out.write(9);
                varInt(out, 1);
                varInt(out, 3);
                out.write(0xFF);
            });
            assertArrayEquals(
                    framed.apply(false),
                    PlayerText.of(protocol).clientbound(entityData, framed.apply(true), true),
                    "painting on " + protocol);
        }

        int entityData = id(768, true, "set_entity_data");
        Function<Boolean, byte[]> shoulder = written -> bytes(out -> {
            varInt(out, entityData);
            varInt(out, 9);
            out.write(19);
            varInt(out, 16);
            out.write(10);
            if (written) {
                out.write(8);
                name(out, "CustomName");
                name(out, "Polly by Bob");
            }
            out.write(0);
            out.write(0xFF);
        });
        assertArrayEquals(
                shoulder.apply(false), PlayerText.of(768).clientbound(entityData, shoulder.apply(true), true));
        byte[] bare = shoulder.apply(false);
        assertSame(bare, PlayerText.of(768).clientbound(entityData, bare, true));

        int mannequin = id(PROTOCOL, true, "set_entity_data");
        Function<Boolean, byte[]> profile = written -> bytes(out -> {
            varInt(out, mannequin);
            varInt(out, 9);
            out.write(17);
            varInt(out, 41);
            if (written) {
                out.write(1);
                int64(out, 1);
                int64(out, 2);
                string(out, "Bob");
                varInt(out, 1);
                string(out, "motto");
                string(out, "Bob was here");
                out.write(0);
                out.write(1);
                string(out, "minecraft:entity/bob");
                out.writeBytes(new byte[3]);
            } else {
                out.writeBytes(new byte[8]);
            }
            out.write(0xFF);
        });
        assertArrayEquals(profile.apply(false), strip(profile.apply(true)));
    }

    /** A stack of a hundred thousand model strings walks past the packet's budget and is not sent. */
    @Test
    void aPacketPastItsWorkBudgetIsNotSent() {
        int creative = id(PROTOCOL, false, "set_creative_mode_slot");
        int model = component(PROTOCOL, "custom_model_data");
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        varInt(data, 0);
        varInt(data, 0);
        varInt(data, 100_000);
        for (int i = 0; i < 100_000; i++) string(data, "x");
        varInt(data, 0);
        byte[] wide = bytes(out -> {
            varInt(out, creative);
            int16(out, 36);
            varInt(out, 1);
            varInt(out, 900);
            varInt(out, 1);
            varInt(out, 0);
            varInt(out, model);
            varInt(out, data.size());
            out.writeBytes(data.toByteArray());
        });
        long unreadable = PlayerText.unreadable.sum();
        assertNull(text.serverbound(creative, wide, true));
        assertEquals(unreadable + 1, PlayerText.unreadable.sum());
    }

    @Test
    void aSkinIsReadOnceAndAnsweredTheSameAfter() {
        String value = base64("{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/ab\"}}}");
        String first = PlayerText.texture(value);
        assertNotNull(first);
        assertSame(first, PlayerText.texture(value));
        String none = base64("{\"textures\":{}}");
        assertNull(PlayerText.texture(none));
        assertNull(PlayerText.texture(none));
    }

    /**
     * A {@code container_set_slot} of a damaged rare sword, named and with two lines of lore when {@code written}: the
     * components in the order a server writes them.
     */
    private static byte[] slot(boolean written) {
        int damage = component(PROTOCOL, "damage");
        int customName = component(PROTOCOL, "custom_name");
        int lore = component(PROTOCOL, "lore");
        int rarity = component(PROTOCOL, "rarity");
        int slot = id(PROTOCOL, true, "container_set_slot");
        return bytes(out -> {
            varInt(out, slot);
            varInt(out, 0);
            varInt(out, 4);
            out.write(0);
            out.write(36);
            varInt(out, 1);
            varInt(out, 900);
            varInt(out, written ? 4 : 2);
            varInt(out, 0);
            varInt(out, damage);
            varInt(out, 5);
            if (written) {
                varInt(out, customName);
                text(out, "Bob's blade");
                varInt(out, lore);
                varInt(out, 2);
                text(out, "first line");
                text(out, "second line");
            }
            varInt(out, rarity);
            varInt(out, 2);
        });
    }

    /** A damaged sword, named when {@code written}, as a 777 stack. */
    private static void blade(ByteArrayOutputStream out, boolean written) {
        varInt(out, 1);
        varInt(out, 900);
        varInt(out, written ? 2 : 1);
        varInt(out, 0);
        if (written) {
            varInt(out, component(PROTOCOL, "custom_name"));
            text(out, "Bob's blade");
        }
        varInt(out, component(PROTOCOL, "damage"));
        varInt(out, 3);
    }

    /** A 26.x item template, a gem with lore when {@code written}. */
    private static void template(ByteArrayOutputStream out, boolean written) {
        varInt(out, 902);
        varInt(out, 1);
        varInt(out, written ? 1 : 0);
        varInt(out, 0);
        if (written) {
            varInt(out, component(PROTOCOL, "lore"));
            varInt(out, 1);
            text(out, "Bob's gem");
        }
    }

    /**
     * A protocol 777 {@code level_chunk_with_light} with two block entities, the first a sign whose data is every NBT tag
     * type when {@code entityData}, else absent; with its light.
     */
    private static byte[] levelChunk(int id, boolean entityData) {
        return levelChunk(PROTOCOL, id, entityData);
    }

    /** As {@link #levelChunk(int, boolean)}, as {@code protocol} frames heightmaps and light masks. */
    private static byte[] levelChunk(int protocol, int id, boolean entityData) {
        return bytes(out -> {
            varInt(out, id);
            int32(out, 3);
            int32(out, -4);
            if (protocol <= 769) {
                out.write(10);
                out.write(12);
                name(out, "MOTION_BLOCKING");
                int32(out, 37);
                for (int i = 0; i < 37; i++) int64(out, -4 * 17L + i);
                out.write(0);
            } else {
                varInt(out, 2);
                for (int type : new int[] {1, 4}) {
                    varInt(out, type);
                    varInt(out, 37);
                    for (int i = 0; i < 37; i++) int64(out, -4 * 17L + i + type);
                }
            }
            varInt(out, 300);
            for (int i = 0; i < 300; i++) out.write(3 * 7 - 4 * 13 + i);
            varInt(out, 2);
            out.write(0x5A);
            int16(out, 64);
            varInt(out, 7);
            if (entityData) signNbt(out);
            else out.write(0);
            out.write(0x01);
            int16(out, -12);
            varInt(out, 300);
            out.write(0);
            for (int mask = 0; mask < 4; mask++) {
                if (protocol >= 777) {
                    varInt(out, 2);
                    out.write(0x0B);
                    out.write(mask);
                } else {
                    varInt(out, 1);
                    int64(out, 0x0B00L | mask);
                }
            }
            varInt(out, 2);
            for (int array = 0; array < 2; array++) {
                varInt(out, 2048);
                for (int i = 0; i < 2048; i++) out.write(i * (array + 3));
            }
            varInt(out, 1);
            varInt(out, 2048);
            for (int i = 0; i < 2048; i++) out.write(i * 5);
        });
    }

    /** A sign-like block entity's data: every NBT tag type, a list of compounds among them. */
    private static void signNbt(ByteArrayOutputStream out) {
        out.write(10);
        out.write(8);
        name(out, "id");
        name(out, "minecraft:sign");
        out.write(1);
        name(out, "glowing");
        out.write(1);
        out.write(2);
        name(out, "short");
        int16(out, -3);
        out.write(3);
        name(out, "int");
        int32(out, 42);
        out.write(4);
        name(out, "long");
        int64(out, 1L << 40);
        out.write(5);
        name(out, "float");
        int32(out, Float.floatToIntBits(1.5f));
        out.write(6);
        name(out, "double");
        int64(out, Double.doubleToLongBits(2.5));
        out.write(7);
        name(out, "bytes");
        int32(out, 3);
        out.write(1);
        out.write(2);
        out.write(3);
        out.write(9);
        name(out, "messages");
        out.write(10);
        int32(out, 2);
        for (int i = 0; i < 2; i++) {
            out.write(8);
            name(out, "text");
            name(out, "line " + i);
            out.write(0);
        }
        out.write(11);
        name(out, "ints");
        int32(out, 2);
        int32(out, 7);
        int32(out, 8);
        out.write(12);
        name(out, "longs");
        int32(out, 1);
        int64(out, 9);
        out.write(9);
        name(out, "empty");
        out.write(0);
        int32(out, 0);
        out.write(0);
    }

    private static byte[] strip(byte[] packet) {
        return text.clientbound(packet[0] & 0x7F | (packet[0] < 0 ? (packet[1] & 0x7F) << 7 : 0), packet, true);
    }

    private static int id(int protocol, boolean clientbound, String name) {
        int id = ProtocolData.load(ProtocolVersion.of(protocol))
                .packets(ConnectionPhase.PLAY, clientbound ? PacketDirection.CLIENTBOUND : PacketDirection.SERVERBOUND)
                .id("minecraft:" + name);
        assertTrue(id >= 0, name);
        return id;
    }

    private static int component(int protocol, String name) {
        return registry(protocol, "minecraft:data_component_type", "minecraft:" + name);
    }

    private static int registry(int protocol, String registry, String name) {
        int id = ModelRegistryData.load(ProtocolVersion.of(protocol))
                .registry(registry)
                .id(name);
        assertTrue(id >= 0, name);
        return id;
    }

    /** A 768 {@code container_click}: the changed slot and the cursor as whole stacks. */
    private static byte[] click(int id, Consumer<ByteArrayOutputStream> stack) {
        return bytes(out -> {
            varInt(out, id);
            varInt(out, 0);
            varInt(out, 1);
            out.write(0);
            out.write(36);
            out.write(0);
            varInt(out, 0);
            varInt(out, 1);
            out.write(0);
            out.write(36);
            stack.accept(out);
            stack.accept(out);
        });
    }

    private static String base64(String json) {
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] bytes(Consumer<ByteArrayOutputStream> writer) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.accept(out);
        return out.toByteArray();
    }

    private static void varInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write(value & 0x7F | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static void int16(ByteArrayOutputStream out, int value) {
        out.write(value >> 8);
        out.write(value);
    }

    private static void int32(ByteArrayOutputStream out, int value) {
        int16(out, value >> 16);
        int16(out, value);
    }

    private static void int64(ByteArrayOutputStream out, long value) {
        int32(out, (int) (value >> 32));
        int32(out, (int) value);
    }

    /** A protocol string: its UTF-8 length as a varint, then its UTF-8. */
    private static void string(ByteArrayOutputStream out, String value) {
        byte[] utf = value.getBytes(StandardCharsets.UTF_8);
        varInt(out, utf.length);
        out.writeBytes(utf);
    }

    /** An NBT name or string payload: its length in two bytes, then its UTF-8. */
    private static void name(ByteArrayOutputStream out, String name) {
        byte[] utf = name.getBytes(StandardCharsets.UTF_8);
        int16(out, utf.length);
        out.writeBytes(utf);
    }

    /** A text component as network NBT: a bare string tag. */
    private static void text(ByteArrayOutputStream out, String text) {
        out.write(8);
        name(out, text);
    }
}
