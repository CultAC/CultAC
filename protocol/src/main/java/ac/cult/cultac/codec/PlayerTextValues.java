package ac.cult.cultac.codec;

import static ac.cult.cultac.protocol.PlayerTextWire.compound;
import static ac.cult.cultac.protocol.PlayerTextWire.count;
import static ac.cult.cultac.protocol.PlayerTextWire.emptyString;
import static ac.cult.cultac.protocol.PlayerTextWire.nbt;
import static ac.cult.cultac.protocol.PlayerTextWire.string;
import static ac.cult.cultac.protocol.PlayerTextWire.text;

import ac.cult.cultac.protocol.MalformedPacketException;
import ac.cult.cultac.protocol.PacketResourceLimitException;
import ac.cult.cultac.protocol.PlayerTextCodec;
import ac.cult.cultac.protocol.PlayerTextWire.Edits;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.shaded.vialib.api.minecraft.EitherHolder;
import ac.cult.shaded.vialib.api.minecraft.GameProfile;
import ac.cult.shaded.vialib.api.minecraft.Holder;
import ac.cult.shaded.vialib.api.minecraft.ResolvableProfile;
import ac.cult.shaded.vialib.api.minecraft.WolfVariant;
import ac.cult.shaded.vialib.api.minecraft.data.StructuredDataKey;
import ac.cult.shaded.vialib.api.minecraft.entitydata.EntityDataType;
import ac.cult.shaded.vialib.api.minecraft.item.data.Instrument1_21_2;
import ac.cult.shaded.vialib.api.minecraft.item.data.JukeboxPlayable;
import ac.cult.shaded.vialib.api.minecraft.item.data.trim.ArmorTrim1_20_5;
import ac.cult.shaded.vialib.api.minecraft.item.data.trim.ArmorTrim26_3;
import ac.cult.shaded.vialib.api.minecraft.item.data.trim.ArmorTrimMaterial1_20_5;
import ac.cult.shaded.vialib.api.minecraft.item.data.trim.ArmorTrimMaterial26_3;
import ac.cult.shaded.vialib.api.minecraft.item.data.trim.ArmorTrimPattern;
import ac.cult.shaded.vialib.api.minecraft.item.data.trim.ProvidesTrimMaterial1_20_5;
import ac.cult.shaded.vialib.api.type.Type;
import ac.cult.shaded.vialib.api.type.Types;
import ac.cult.shaded.vialib.api.type.types.version.VersionedTypesHolder;
import ac.cult.shaded.vialib.nbt.tag.StringTag;
import ac.cult.shaded.vialib.util.Key;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@link PlayerTextCodec} for one client protocol. Stacks are walked by their framing, as {@link ItemStructureGuard}
 * walks them; each component's type comes from Via's table for the protocol. The components that hold text are read
 * by hand; inline registry entries (trims, instruments, songs) through Via's own type, written back without their text.
 * Every other component is read through Via's type for its length alone.
 *
 * <p>What goes: a stack's name, lore, books and opaque or entity data, with the components; text inside a component
 * kept, as an empty text where it is a text component or an empty compound where it is other data; strings players
 * name things with, as empty strings; a profile's properties, but for its skin on Mojang's texture server; a number
 * resolved from data, as the constant zero. Entity data's text components go empty or, when optional, absent, and the
 * command string of a command block minecart empty.
 */
final class PlayerTextValues implements PlayerTextCodec {
    private static final byte[] NONE = new byte[0];
    /** An optional value sent absent. */
    private static final byte[] ABSENT = {0};

    private static final byte[] EMPTY_COMPOUND = {10, 0};
    /** An empty resolvable profile: partial, with no name, id, properties, textures or model. */
    private static final byte[] EMPTY_PROFILE = {0, 0, 0, 0, 0, 0, 0, 0};

    private static final byte[] CONSTANT_ZERO = {1, 0, 0, 0, 0};
    private static final StringTag NO_TEXT = new StringTag("");
    private static final int MAX_STACK_DEPTH = 9; // The root stack and eight nested stacks.
    private static final int MAX_MATCHER_DEPTH = 4;
    private static final int MAX_EFFECT_DEPTH = 8;

    /** How an entity data serializer's value is read: those that are not known to carry no text are not read. */
    enum EntityValue {
        /** Serializers 0 to 8, the same on every version, read by hand. */
        FIRST,
        FREE,
        PARTICLE,
        PARTICLES,
        PAINTING,
        /** NBT, a shoulder parrot's saved entity: sent as an empty compound. */
        COMPOUND,
        PROFILE,
        UNKNOWN
    }

    private enum Kind {
        OTHER,
        /** Taken out: one text component or compound. */
        GONE_NBT,
        GONE_LORE,
        /** Taken out: an entity or block entity's data, its type first from 1.21.9 on. */
        GONE_ENTITY,
        /** Taken out, read through Via's type. */
        GONE,
        PROFILE,
        /** An inline registry entry with a description, read and written through Via's type. */
        INLINE,
        BANNER_PATTERNS,
        PAINTING,
        ATTRIBUTE_MODIFIERS,
        CUSTOM_MODEL_DATA,
        POTION_CONTENTS,
        BEES,
        SIGN_TEXT,
        COMPOSTABLE,
        FUEL,
        PREDICATES,
        CONTAINER,
        STACKS,
        STACK,
        POT_DECORATIONS
    }

    private final ProtocolVersion version;
    private final VersionedTypesHolder types;
    private final StructuredDataKey<?>[] keys;
    private final Kind[] kinds;
    private final EntityDataType[] entityData;
    private final EntityValue[] entityValues;
    private final int itemParticle;
    /** The entity data index of a command block minecart's command. */
    private final int command;
    /** 26.x nested stacks are templates: the item first, then a count that is always positive. */
    private final boolean templates;
    /** 26.x optional templates carry a flag; earlier schemas fold absence into the count. */
    private final boolean optionalTemplates;

    PlayerTextValues(ProtocolVersion version) {
        this.version = version;
        this.types = PrivateCodecService.types(version);
        List<StructuredDataKey<?>> known = new ArrayList<>();
        for (StructuredDataKey<?> key; (key = types.structuredData().key(known.size())) != null; ) known.add(key);
        this.keys = known.toArray(StructuredDataKey<?>[]::new);
        this.kinds = new Kind[keys.length];
        for (int id = 0; id < keys.length; id++) kinds[id] = kind(Key.stripMinecraftNamespace(keys[id].identifier()));
        this.entityData = types.entityDataTypes().values();
        var serializers = PrivateCodecService.metadataTypes(types);
        this.entityValues = new EntityValue[entityData.length];
        for (int id = 0; id < entityData.length; id++) {
            entityValues[id] = entityValue(id, entityData[id].type(), serializers);
        }
        this.itemParticle = PrivateCodecService.itemParticle(version);
        this.command = version.atLeast(ProtocolVersion.V1_21_5) ? 13 : 14;
        this.templates = types.itemTemplate() != types.item();
        this.optionalTemplates = types.optionalItemTemplate() != types.itemTemplate();
    }

    private static EntityValue entityValue(int id, Type<?> type, PrivateCodecService.MetadataTypes serializers) {
        if (id <= 8) return EntityValue.FIRST;
        if (id == serializers.particle()) return EntityValue.PARTICLE;
        if (id == serializers.particles()) return EntityValue.PARTICLES;
        if (id == serializers.painting()) return EntityValue.PAINTING;
        if (type == Types.COMPOUND_TAG) return EntityValue.COMPOUND;
        if (type == Types.RESOLVABLE_PROFILE) return EntityValue.PROFILE;
        if (type == Types.VAR_INT
                || type == Types.OPTIONAL_VAR_INT
                || type == Types.ROTATIONS
                || type == Types.VECTOR3F
                || type == Types.QUATERNION
                || type == Types.BLOCK_POSITION1_14
                || type == Types.OPTIONAL_POSITION_1_14
                || type == Types.OPTIONAL_UUID
                || type == Types.OPTIONAL_GLOBAL_POSITION
                || type == Types.VILLAGER_DATA
                || type == WolfVariant.TYPE) {
            return EntityValue.FREE;
        }
        return EntityValue.UNKNOWN;
    }

    /** How serializer {@code id}'s values are read; {@link EntityValue#UNKNOWN} past the table. */
    EntityValue entityValue(int id) {
        return id >= 0 && id < entityValues.length ? entityValues[id] : EntityValue.UNKNOWN;
    }

    private Kind kind(String component) {
        return switch (component) {
            case "custom_name",
                    "item_name",
                    "custom_data",
                    "intangible_projectile",
                    "map_decorations",
                    "bucket_entity_data",
                    "recipes",
                    "container_loot" -> Kind.GONE_NBT;
            case "lore" -> Kind.GONE_LORE;
            case "entity_data", "block_entity_data" -> Kind.GONE_ENTITY;
            case "written_book_content", "writable_book_content", "debug_stick_state", "lock" -> Kind.GONE;
            case "profile" -> Kind.PROFILE;
            case "trim", "instrument", "provides_trim_material", "jukebox_playable" -> Kind.INLINE;
            case "banner_patterns" -> Kind.BANNER_PATTERNS;
            case "painting/variant" -> Kind.PAINTING;
            case "attribute_modifiers" ->
                version.atLeast(ProtocolVersion.V1_21_6) ? Kind.ATTRIBUTE_MODIFIERS : Kind.OTHER;
            case "custom_model_data" -> version.atLeast(ProtocolVersion.V1_21_4) ? Kind.CUSTOM_MODEL_DATA : Kind.OTHER;
            case "potion_contents" -> Kind.POTION_CONTENTS;
            case "bees" -> Kind.BEES;
            case "sign_text_front", "sign_text_back" -> Kind.SIGN_TEXT;
            case "compostable" -> Kind.COMPOSTABLE;
            case "cooking_fuel", "brewing_fuel" -> Kind.FUEL;
            case "can_place_on", "can_break" -> Kind.PREDICATES;
            case "container" -> Kind.CONTAINER;
            case "bundle_contents", "charged_projectiles" -> Kind.STACKS;
            case "use_remainder", "sulfur_cube_content" -> Kind.STACK;
            case "pot_decorations" -> version.atLeast(ProtocolVersion.V26_3) ? Kind.POT_DECORATIONS : Kind.OTHER;
            default -> Kind.OTHER;
        };
    }

    @Override
    public void stack(ByteBuf input, Cuts cuts) {
        stack(input, cuts, 0, 0);
    }

    @Override
    public void creativeStack(ByteBuf input, Cuts cuts) {
        if (!version.atLeast(ProtocolVersion.V1_21_5)) {
            stack(input, cuts, 0, 0);
            return;
        }
        if (Wire.readVarInt(input) <= 0) return;
        Wire.readVarInt(input); // Item.
        components(input, cuts, 1, 0, true, true);
    }

    @Override
    public void entityData(ByteBuf input, Cuts cuts) {
        for (int index; (index = input.readUnsignedByte()) != 0xFF; ) {
            cuts.work(1);
            int serializer = Wire.readVarInt(input);
            int start = input.readerIndex();
            switch (serializer) {
                case 0, 8 -> input.skipBytes(1);
                case 1 -> Wire.readVarInt(input);
                case 2 -> Wire.readVarLong(input);
                case 3 -> input.skipBytes(4);
                case 4 -> {
                    if (index == command) emptyString(input, cuts);
                    else string(input);
                }
                case 5 -> text(input, cuts);
                case 6 -> {
                    if (input.readBoolean()) {
                        nbt(input);
                        cuts.cut(start, input.readerIndex(), ABSENT);
                    }
                }
                case 7 -> stack(input, cuts, 0, 0);
                default -> entityValue(input, cuts, serializer);
            }
        }
    }

    private void entityValue(ByteBuf input, Cuts cuts, int serializer) {
        switch (entityValue(serializer)) {
            case PARTICLE -> particle(input, cuts);
            case PARTICLES -> {
                int count = count(input, cuts);
                for (int i = 0; i < count; i++) particle(input, cuts);
            }
            case PAINTING -> painting(input, cuts);
            case COMPOUND -> {
                int start = input.readerIndex();
                nbt(input);
                if (input.readerIndex() - start > 2) cuts.cut(start, input.readerIndex(), EMPTY_COMPOUND);
            }
            case PROFILE -> {
                int start = input.readerIndex();
                if (profile(input, cuts, entityData[serializer].type())) {
                    cuts.cut(start, input.readerIndex(), EMPTY_PROFILE);
                }
            }
            case FREE -> {
                Type<?> type = entityData[serializer].type();
                if (type == Types.VAR_INT || type == Types.OPTIONAL_VAR_INT) Wire.readVarInt(input);
                else if (type == Types.ROTATIONS || type == Types.VECTOR3F) input.skipBytes(12);
                else if (type == Types.QUATERNION) input.skipBytes(16);
                else if (type == Types.BLOCK_POSITION1_14) input.skipBytes(8);
                else if (type == Types.OPTIONAL_POSITION_1_14) {
                    if (input.readBoolean()) input.skipBytes(8);
                } else if (type == Types.OPTIONAL_UUID) {
                    if (input.readBoolean()) input.skipBytes(16);
                } else type.read(input);
            }
            case FIRST, UNKNOWN ->
                throw new MalformedPacketException("Entity data serializer " + serializer + " is not known here");
        }
    }

    @Override
    public void particle(ByteBuf input, Cuts cuts) {
        int start = input.readerIndex();
        if (Wire.readVarInt(input) == itemParticle) {
            nested(input, cuts, 0, 0, false);
            return;
        }
        input.readerIndex(start);
        types.particle().read(input);
    }

    /** A stack in packet framing: count, then item and components while the count is positive. */
    private void stack(ByteBuf input, Cuts cuts, int stacks, int matchers) {
        if (stacks >= MAX_STACK_DEPTH) throw limit("nested item stack depth");
        if (Wire.readVarInt(input) <= 0) return;
        Wire.readVarInt(input); // Item.
        components(input, cuts, stacks + 1, matchers, true, false);
    }

    /** A stack inside a component: a 26.x template (item, count), or a stack in packet framing before. */
    private void nested(ByteBuf input, Cuts cuts, int stacks, int matchers, boolean optional) {
        if (optional && optionalTemplates && !input.readBoolean()) return;
        if (!templates) {
            stack(input, cuts, stacks, matchers);
            return;
        }
        if (stacks >= MAX_STACK_DEPTH) throw limit("nested item stack depth");
        Wire.readVarInt(input); // Item.
        Wire.readVarInt(input); // Count.
        components(input, cuts, stacks + 1, matchers, true, false);
    }

    /**
     * Components added, then (in a patch) the types removed: each one taken out goes with its data, and the count of
     * those added drops with it.
     */
    private void components(ByteBuf input, Cuts cuts, int stacks, int matchers, boolean patch, boolean untrusted) {
        int countStart = input.readerIndex();
        int added = Wire.readVarInt(input);
        int countEnd = input.readerIndex();
        int removed = patch ? Wire.readVarInt(input) : 0;
        if (added < 0 || removed < 0 || added > input.readableBytes() || removed > input.readableBytes()) {
            throw new MalformedPacketException(added + " components added, " + removed + " removed");
        }
        cuts.work(added + removed);
        int gone = 0;
        for (int i = 0; i < added; i++) {
            int start = input.readerIndex();
            int id = Wire.readVarInt(input);
            Kind kind = kind(id);
            boolean drop = untrusted
                    ? untrusted(input, cuts, id, kind, start, stacks, matchers)
                    : component(input, cuts, id, kind, stacks, matchers);
            if (drop) {
                cuts.cut(start, input.readerIndex(), NONE);
                gone++;
            }
        }
        for (int i = 0; i < removed; i++) kind(Wire.readVarInt(input));
        if (gone > 0) cuts.cut(countStart, countEnd, varInt(added - gone));
    }

    private Kind kind(int id) {
        if (id < 0 || id >= kinds.length) throw new MalformedPacketException("Unknown data component " + id);
        return kinds[id];
    }

    /** One component's data; true when the component goes. */
    private boolean component(ByteBuf input, Cuts cuts, int id, Kind kind, int stacks, int matchers) {
        switch (kind) {
            case GONE_NBT -> nbt(input);
            case GONE_LORE -> {
                int lines = count(input, cuts);
                for (int i = 0; i < lines; i++) nbt(input);
            }
            case GONE_ENTITY -> {
                if (version.atLeast(ProtocolVersion.V1_21_9)) Wire.readVarInt(input);
                nbt(input);
            }
            case GONE -> keys[id].type().read(input);
            case PROFILE -> {
                return profile(input, cuts, keys[id].type());
            }
            case INLINE -> inline(input, cuts, keys[id]);
            case BANNER_PATTERNS -> bannerPatterns(input, cuts);
            case PAINTING -> painting(input, cuts);
            case ATTRIBUTE_MODIFIERS -> attributeModifiers(input, cuts);
            case CUSTOM_MODEL_DATA -> customModelData(input, cuts);
            case POTION_CONTENTS -> potionContents(input, cuts);
            case BEES -> bees(input, cuts);
            case SIGN_TEXT -> {
                for (int side = 0; side < 2; side++) {
                    if (side == 1 && !input.readBoolean()) break;
                    for (int line = 0; line < 4; line++) text(input, cuts);
                }
                Wire.readVarInt(input); // Colour.
                input.skipBytes(1); // Glowing.
            }
            case COMPOSTABLE -> resolvable(input, cuts);
            case FUEL -> {
                resolvable(input, cuts);
                resolvable(input, cuts);
            }
            case PREDICATES -> predicates(input, cuts, stacks, matchers);
            case CONTAINER -> {
                int slots = count(input, cuts);
                for (int i = 0; i < slots; i++) nested(input, cuts, stacks, matchers, true);
            }
            case STACKS -> {
                int count = count(input, cuts);
                for (int i = 0; i < count; i++) nested(input, cuts, stacks, matchers, false);
            }
            case STACK -> nested(input, cuts, stacks, matchers, false);
            case POT_DECORATIONS -> {
                for (int side = 0; side < 4; side++) nested(input, cuts, stacks, matchers, true);
            }
            case OTHER -> keys[id].type().read(input);
        }
        return kind == Kind.GONE_NBT || kind == Kind.GONE_LORE || kind == Kind.GONE_ENTITY || kind == Kind.GONE;
    }

    /**
     * A component a client sends length-prefixed: its data read on its own to its prefixed end, and sent with its new
     * length when anything in it is cut.
     */
    private boolean untrusted(ByteBuf input, Cuts cuts, int id, Kind kind, int start, int stacks, int matchers) {
        int typeEnd = input.readerIndex();
        int length = Wire.readVarInt(input);
        if (length < 0 || length > input.readableBytes()) {
            throw new MalformedPacketException("Component length " + length);
        }
        int dataStart = input.readerIndex();
        int dataEnd = dataStart + length;
        ByteBuf data = input.duplicate();
        data.writerIndex(dataEnd);
        Collected inside = new Collected(cuts);
        boolean drop = component(data, inside, id, kind, stacks, matchers);
        if (data.isReadable()) throw new MalformedPacketException("Trailing component data");
        input.readerIndex(dataEnd);
        if (drop || inside.edits.isEmpty()) return drop;
        byte[] clean = inside.edits.apply(input, dataStart, dataEnd);
        ByteArrayOutputStream replacement = new ByteArrayOutputStream(clean.length + 8);
        copy(input, start, typeEnd, replacement);
        replacement.writeBytes(varInt(clean.length));
        replacement.writeBytes(clean);
        cuts.cut(start, dataEnd, replacement.toByteArray());
        return false;
    }

    /**
     * A profile keeps only its skin's texture, and goes when it names none, or more than one, or one that is not a skin
     * on Mojang's texture server.
     */
    private boolean profile(ByteBuf input, Cuts cuts, Type<?> type) {
        int start = input.readerIndex();
        Object value = type.read(input);
        GameProfile profile =
                value instanceof ResolvableProfile resolvable ? resolvable.profile() : (GameProfile) value;
        String texture = null;
        for (GameProfile.Property property : profile.properties()) {
            if (!"textures".equals(property.name()) || property.value() == null) continue;
            if (texture != null) return true;
            texture = cuts.skin(property.value());
            if (texture == null) return true;
        }
        if (texture == null) return true;
        boolean resolvable = version.atLeast(ProtocolVersion.V1_21_9);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (resolvable) out.write(0); // Partial.
        out.write(0); // No name.
        out.write(0); // No id.
        out.write(1);
        out.writeBytes(utf8("textures"));
        out.writeBytes(utf8(texture));
        out.write(0); // No signature.
        if (resolvable) out.writeBytes(new byte[4]); // No body, cape, elytra or model.
        cuts.cut(start, input.readerIndex(), out.toByteArray());
        return false;
    }

    /** An inline registry entry's description sent empty: its whole value written back through Via's type. */
    @SuppressWarnings("unchecked")
    private static void inline(ByteBuf input, Cuts cuts, StructuredDataKey<?> key) {
        ItemStructureGuard.trimOverrides(input, key);
        int start = input.readerIndex();
        Object value = key.type().read(input);
        Object blanked = blank(value);
        if (blanked == value) return;
        ByteBuf out = Unpooled.buffer();
        try {
            ((Type<Object>) key.type()).write(out, blanked);
            cuts.cut(start, input.readerIndex(), ByteBufUtil.getBytes(out));
        } finally {
            out.release();
        }
    }

    /** {@code value} with every inline entry's description empty; the same object when it has no inline entry. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object blank(Object value) {
        return switch (value) {
            case Holder holder -> holder.isDirect() ? Holder.of(blank(holder.value())) : holder;
            case EitherHolder either -> either.hasKey() ? either : blankEither(either);
            case ArmorTrim1_20_5 trim -> {
                Object material = blank(trim.material());
                Object pattern = blank(trim.pattern());
                yield material == trim.material() && pattern == trim.pattern()
                        ? trim
                        : new ArmorTrim1_20_5((Holder) material, (Holder) pattern, trim.showInTooltip());
            }
            case ArmorTrim26_3 trim -> {
                Object material = blank(trim.material());
                Object pattern = blank(trim.pattern());
                yield material == trim.material() && pattern == trim.pattern()
                        ? trim
                        : new ArmorTrim26_3((Holder) material, (Holder) pattern);
            }
            case ProvidesTrimMaterial1_20_5 provided -> {
                Object material = blank(provided.material());
                yield material == provided.material()
                        ? provided
                        : new ProvidesTrimMaterial1_20_5((EitherHolder) material);
            }
            case JukeboxPlayable playable -> {
                Object song = blank(playable.song());
                yield song == playable.song()
                        ? playable
                        : new JukeboxPlayable((EitherHolder) song, playable.showInTooltip());
            }
            case ArmorTrimMaterial1_20_5 material ->
                new ArmorTrimMaterial1_20_5(
                        material.assetName(),
                        material.itemId(),
                        material.itemModelIndex(),
                        material.overrideArmorMaterials(),
                        NO_TEXT);
            case ArmorTrimMaterial26_3 material -> new ArmorTrimMaterial26_3(material.paletteId(), NO_TEXT);
            case ArmorTrimPattern pattern ->
                new ArmorTrimPattern(pattern.assetName(), pattern.itemId(), NO_TEXT, pattern.decal());
            case Instrument1_21_2 instrument ->
                new Instrument1_21_2(
                        instrument.soundEvent(),
                        instrument.useDuration(),
                        instrument.range(),
                        instrument.durabilityDamage(),
                        NO_TEXT);
            case JukeboxPlayable.JukeboxSong song ->
                new JukeboxPlayable.JukeboxSong(
                        song.soundEvent(), NO_TEXT, song.lengthInSeconds(), song.comparatorOutput());
            default ->
                throw new IllegalStateException(
                        "No inline entry in " + value.getClass().getSimpleName());
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object blankEither(EitherHolder either) {
        Object holder = blank(either.holder());
        return holder == either.holder() ? either : EitherHolder.of((Holder) holder);
    }

    private static void bannerPatterns(ByteBuf input, Cuts cuts) {
        int layers = count(input, cuts);
        for (int i = 0; i < layers; i++) {
            if (Wire.readVarInt(input) == 0) {
                string(input); // Asset.
                emptyString(input, cuts); // Translation key.
            }
            Wire.readVarInt(input); // Colour.
        }
    }

    private static void painting(ByteBuf input, Cuts cuts) {
        if (Wire.readVarInt(input) != 0) return;
        Wire.readVarInt(input); // Width.
        Wire.readVarInt(input); // Height.
        string(input); // Asset.
        for (int i = 0; i < 2; i++) {
            if (input.readBoolean()) text(input, cuts); // Title, then author.
        }
    }

    private static void attributeModifiers(ByteBuf input, Cuts cuts) {
        int modifiers = count(input, cuts);
        for (int i = 0; i < modifiers; i++) {
            Wire.readVarInt(input); // Attribute.
            string(input); // Modifier id.
            input.skipBytes(8); // Amount.
            Wire.readVarInt(input); // Operation.
            Wire.readVarInt(input); // Slot.
            if (Wire.readVarInt(input) == 2) text(input, cuts); // An override's text.
        }
    }

    private static void customModelData(ByteBuf input, Cuts cuts) {
        input.skipBytes(Math.multiplyExact(length(input), 4)); // Floats.
        input.skipBytes(length(input)); // Flags.
        int strings = count(input, cuts);
        for (int i = 0; i < strings; i++) emptyString(input, cuts);
        input.skipBytes(Math.multiplyExact(length(input), 4)); // Colours.
    }

    private static void potionContents(ByteBuf input, Cuts cuts) {
        if (input.readBoolean()) Wire.readVarInt(input); // Potion.
        if (input.readBoolean()) input.skipBytes(4); // Colour.
        int effects = count(input, cuts);
        for (int i = 0; i < effects; i++) {
            Wire.readVarInt(input); // Effect.
            effect(input, 0);
        }
        if (input.readBoolean()) emptyString(input, cuts); // Custom name.
    }

    private static void effect(ByteBuf input, int depth) {
        if (depth >= MAX_EFFECT_DEPTH) throw limit("hidden effect depth");
        Wire.readVarInt(input); // Amplifier.
        Wire.readVarInt(input); // Duration.
        input.skipBytes(3); // Ambient, particles, icon.
        if (input.readBoolean()) effect(input, depth + 1);
    }

    private void bees(ByteBuf input, Cuts cuts) {
        int bees = count(input, cuts);
        for (int i = 0; i < bees; i++) {
            if (version.atLeast(ProtocolVersion.V1_21_9)) Wire.readVarInt(input); // Entity type.
            compound(input, cuts);
            Wire.readVarInt(input); // Ticks in hive.
            Wire.readVarInt(input); // Minimum ticks in hive.
        }
    }

    /** A number resolved from data goes as the constant zero. */
    private static void resolvable(ByteBuf input, Cuts cuts) {
        int start = input.readerIndex();
        if (input.readBoolean()) {
            input.skipBytes(4);
            return;
        }
        string(input);
        cuts.cut(start, input.readerIndex(), CONSTANT_ZERO);
    }

    /** {@code can_place_on} and {@code can_break}: block predicates, their data matched as empty compounds. */
    private void predicates(ByteBuf input, Cuts cuts, int stacks, int matchers) {
        int predicates = count(input, cuts);
        for (int i = 0; i < predicates; i++) {
            if (input.readBoolean()) holderSet(input, cuts); // Blocks.
            if (input.readBoolean()) {
                int properties = count(input, cuts);
                for (int p = 0; p < properties; p++) {
                    string(input); // Name.
                    if (input.readBoolean()) {
                        string(input); // Exact value.
                    } else {
                        if (input.readBoolean()) string(input); // Minimum.
                        if (input.readBoolean()) string(input); // Maximum.
                    }
                }
            }
            if (input.readBoolean()) compound(input, cuts);
            if (!version.atLeast(ProtocolVersion.V1_21_5)) continue;
            if (matchers >= MAX_MATCHER_DEPTH) throw limit("component matcher depth");
            components(input, cuts, stacks, matchers + 1, false, false);
            int partial = count(input, cuts);
            for (int p = 0; p < partial; p++) {
                if (version.atLeast(ProtocolVersion.V1_21_11)) input.readBoolean(); // A predicate's or a component's.
                Wire.readVarInt(input); // Type.
                compound(input, cuts);
            }
        }
        if (!version.atLeast(ProtocolVersion.V1_21_5)) input.skipBytes(1); // Shown in the tooltip.
    }

    private static void holderSet(ByteBuf input, Cuts cuts) {
        int count = Wire.readVarInt(input);
        if (count == 0) {
            string(input); // Tag.
            return;
        }
        if (count < 0 || count - 1 > input.readableBytes()) throw new MalformedPacketException("Holder set " + count);
        cuts.work(count - 1);
        for (int i = 1; i < count; i++) Wire.readVarInt(input);
    }

    /** A length of bytes skipped whole. */
    private static int length(ByteBuf input) {
        return Wire.readLength(input, input.readableBytes());
    }

    private static PacketResourceLimitException limit(String what) {
        return new PacketResourceLimitException("Player text walk exceeds its " + what);
    }

    private static void copy(ByteBuf input, int start, int end, ByteArrayOutputStream out) {
        try {
            input.getBytes(start, out, end - start);
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    private static byte[] varInt(int value) {
        byte[] out = new byte[5];
        int length = 0;
        while ((value & ~0x7F) != 0) {
            out[length++] = (byte) (value & 0x7F | 0x80);
            value >>>= 7;
        }
        out[length++] = (byte) value;
        return Arrays.copyOf(out, length);
    }

    private static byte[] utf8(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        byte[] length = varInt(bytes.length);
        byte[] out = Arrays.copyOf(length, length.length + bytes.length);
        System.arraycopy(bytes, 0, out, length.length, bytes.length);
        return out;
    }

    /** The cuts inside one length-prefixed component, applied to its data alone, charged to the packet's walk. */
    private static final class Collected implements Cuts {
        private final Cuts outer;
        private final Edits edits = new Edits();

        Collected(Cuts outer) {
            this.outer = outer;
        }

        @Override
        public void cut(int start, int end, byte[] replacement) {
            outer.work(1);
            edits.add(start, end, replacement);
        }

        @Override
        public String skin(String textures) {
            return outer.skin(textures);
        }

        @Override
        public void work(int units) {
            outer.work(units);
        }
    }
}
