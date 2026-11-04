package io.arona74.lsl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import io.arona74.lsl.config.ConfigOption;
import io.arona74.lsl.config.ConfigOption.Apply;
import io.arona74.lsl.config.ConfigOption.Side;
import io.arona74.lsl.config.ConfigTab;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.arona74.lsl.config.ConfigOption.bool;
import static io.arona74.lsl.config.ConfigOption.choice;
import static io.arona74.lsl.config.ConfigOption.decimal;
import static io.arona74.lsl.config.ConfigOption.integer;

/**
 * {@code config/lsl.json}, built the way AronaLayers-gen builds its layer_config.json: every key is a
 * {@link ConfigOption} declared below (default, range, tab and group, when a change applies), labels
 * and descriptions live in en_us.json ({@code config.lsl.option.<key>[.desc]}), and the file's
 * comments are generated from them. The file is rewritten at startup (values kept, new keys added,
 * unknown keys kept at the end) unless it fails to parse or holds an invalid value: then it is left
 * for the user to fix. The in-game screen (Mod Menu, or {@code /lsl config}) edits the same options.
 *
 * <p>Loaded from the mixin plugins, before any game class: so only Gson and the option registry are
 * touched here, and the values the mixin gates read need a restart.
 *
 * <p>The {@code dh_*} options used to live in AronaLayers-gen's layer_config.json: when lsl.json does
 * not exist yet, their values are taken from there once.
 */
public final class LslConfig {
    private LslConfig() {}

    /** voxy_ground_plants. */
    public enum GroundPlants { AUTO, ON, OFF }
    /** How a full block with a repeating (position-dependent) CTM texture is baked into Voxy. */
    public enum CtmRepeat { SHRINK, AVERAGE, BASE }

    private static final String CONFIG_FILE = "lsl.json";
    private static final String LANG_FILE = "assets/lsl/lang/en_us.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final List<ConfigOption<?>> OPTIONS = new ArrayList<>();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static final String DH = "distanthorizons";
    private static final String VOXY = "voxy";

    // Values. No initializers: the option defaults set them (see loadConfig).
    public static boolean dhExactLayers;
    public static boolean dhExactLayersColorBits;
    public static boolean dhLayerRounding;
    public static int dhLayerRoundingThreshold;
    public static boolean voxyLayerMip;
    public static boolean voxyShapeCulling;
    public static boolean voxyPlantOffset;
    public static GroundPlants voxyGroundPlants;
    public static boolean voxyDrawPlants;
    public static boolean voxyTallPlants;
    public static boolean voxyHideExtBlades;
    public static boolean voxyFullMipmaps;
    public static boolean voxyFlatLayers;
    public static boolean voxySplitLargeQuads;
    public static boolean voxyMatchTints;
    public static boolean voxyCtm;
    public static CtmRepeat voxyCtmRepeat;
    public static int voxyTintHeight;
    public static boolean voxyPlantShading;
    public static boolean voxyPlantShadingWithShaders;
    public static double voxyPlantFaceInset;
    public static double voxyPlantBrightness;
    public static double voxyPlantSaturation;

    private static Path configDir;
    private static boolean loaded;

    /** Registers an option after the last one of its tab, so each tab's options stay together. */
    private static <T> ConfigOption<T> add(ConfigOption.Builder<T> builder) {
        ConfigOption<T> option = builder.build();
        int at = OPTIONS.size();
        while (at > 0 && OPTIONS.get(at - 1).tab.ordinal() > option.tab.ordinal()) at--;
        OPTIONS.add(at, option);
        return option;
    }

    // ========== Options ==========

    // --- Distant Horizons: exact heights ---
    private static final ConfigOption<Boolean> O_DH_EXACT_LAYERS = add(bool("dh_exact_layers", true,
            () -> dhExactLayers, v -> dhExactLayers = v)
            .in(ConfigTab.DISTANT_HORIZONS, "exact").mods(DH).side(Side.BOTH).apply(Apply.RESTART));
    static {
        add(bool("dh_exact_layers_color_bits", true, () -> dhExactLayersColorBits, v -> dhExactLayersColorBits = v)
                .in(ConfigTab.DISTANT_HORIZONS, "exact").requires(O_DH_EXACT_LAYERS).mods(DH)
                .side(Side.CLIENT).apply(Apply.RESTART).advanced());
    }

    // --- Distant Horizons: rounding fallback ---
    private static final ConfigOption<Boolean> O_DH_LAYER_ROUNDING = add(bool("dh_layer_rounding", true,
            () -> dhLayerRounding, v -> dhLayerRounding = v)
            .in(ConfigTab.DISTANT_HORIZONS, "rounding").mods(DH).side(Side.BOTH).apply(Apply.DH_LODS));
    static {
        add(integer("dh_layer_rounding_threshold", 4, 1, 8, () -> dhLayerRoundingThreshold, v -> dhLayerRoundingThreshold = v)
                .in(ConfigTab.DISTANT_HORIZONS, "rounding").requires(O_DH_LAYER_ROUNDING).mods(DH)
                .side(Side.BOTH).apply(Apply.DH_LODS));
    }

    // --- Voxy ---
    private static final ConfigOption<Boolean> O_VOXY_LAYER_MIP = add(bool("voxy_layer_mip", true,
            () -> voxyLayerMip, v -> voxyLayerMip = v)
            .in(ConfigTab.VOXY, "lod").mods(VOXY).apply(Apply.RESTART));
    static {
        add(bool("voxy_shape_culling", true, () -> voxyShapeCulling, v -> voxyShapeCulling = v)
                .in(ConfigTab.VOXY, "lod").mods(VOXY).apply(Apply.RESTART));
        add(bool("voxy_hide_ext_blades", true, () -> voxyHideExtBlades, v -> voxyHideExtBlades = v)
                .in(ConfigTab.VOXY, "lod").mods(VOXY).apply(Apply.REJOIN));
        add(bool("voxy_full_mipmaps", true, () -> voxyFullMipmaps, v -> voxyFullMipmaps = v)
                .in(ConfigTab.VOXY, "lod").mods(VOXY).apply(Apply.RESTART));
    }
    private static final ConfigOption<Boolean> O_VOXY_MATCH_TINTS = add(bool("voxy_match_tints", true,
            () -> voxyMatchTints, v -> voxyMatchTints = v)
            .in(ConfigTab.VOXY, "lod").mods(VOXY).apply(Apply.REJOIN));
    static {
        add(integer("voxy_tint_height", 72, -64, 320, () -> voxyTintHeight, v -> voxyTintHeight = v)
                .in(ConfigTab.VOXY, "lod").requires(O_VOXY_MATCH_TINTS).mods(VOXY).apply(Apply.REJOIN));
    }
    private static final ConfigOption<Boolean> O_VOXY_CTM = add(bool("voxy_ctm", true, () -> voxyCtm, v -> voxyCtm = v)
            .in(ConfigTab.VOXY, "lod").mods(VOXY).apply(Apply.REJOIN));
    static {
        add(choice("voxy_ctm_repeat", CtmRepeat.AVERAGE, () -> voxyCtmRepeat, v -> voxyCtmRepeat = v)
                .in(ConfigTab.VOXY, "lod").requires(O_VOXY_CTM).mods(VOXY).apply(Apply.REJOIN));
        add(bool("voxy_split_large_quads", true, () -> voxySplitLargeQuads, v -> voxySplitLargeQuads = v)
                .in(ConfigTab.VOXY, "lod").mods(VOXY).apply(Apply.REJOIN));
        add(bool("voxy_flat_layers", true, () -> voxyFlatLayers, v -> voxyFlatLayers = v)
                .in(ConfigTab.VOXY, "lod").mods(VOXY).apply(Apply.RESTART));
    }
    private static final ConfigOption<Boolean> O_VOXY_PLANT_OFFSET = add(bool("voxy_plant_offset", true,
            () -> voxyPlantOffset, v -> voxyPlantOffset = v)
            .in(ConfigTab.VOXY, "plants").mods(VOXY).apply(Apply.RESTART));
    static {
        add(choice("voxy_ground_plants", GroundPlants.AUTO, () -> voxyGroundPlants, v -> voxyGroundPlants = v)
                .in(ConfigTab.VOXY, "plants").requires(O_VOXY_PLANT_OFFSET).mods(VOXY).apply(Apply.RESTART));
        add(bool("voxy_draw_plants", true, () -> voxyDrawPlants, v -> voxyDrawPlants = v)
                .in(ConfigTab.VOXY, "plants").mods(VOXY).apply(Apply.REJOIN));
        add(decimal("voxy_plant_face_inset", 0.25, 0.0, 0.45, 0.05, () -> voxyPlantFaceInset, v -> voxyPlantFaceInset = v)
                .in(ConfigTab.VOXY, "plants").mods(VOXY).apply(Apply.REJOIN));
        add(bool("voxy_tall_plants", true, () -> voxyTallPlants, v -> voxyTallPlants = v)
                .in(ConfigTab.VOXY, "plants").mods(VOXY).apply(Apply.RESTART));
    }
    private static final ConfigOption<Boolean> O_VOXY_PLANT_SHADING = add(bool("voxy_plant_shading", false,
            () -> voxyPlantShading, v -> voxyPlantShading = v)
            .in(ConfigTab.VOXY, "plants").mods(VOXY).apply(Apply.REJOIN));
    static {
        add(bool("voxy_plant_shading_with_shaders", false, () -> voxyPlantShadingWithShaders, v -> voxyPlantShadingWithShaders = v)
                .in(ConfigTab.VOXY, "plants").requires(O_VOXY_PLANT_SHADING).mods(VOXY).apply(Apply.REJOIN));
        add(decimal("voxy_plant_brightness", 1.0, 0.3, 2.0, 0.05, () -> voxyPlantBrightness, v -> voxyPlantBrightness = v)
                .in(ConfigTab.VOXY, "plants").requires(O_VOXY_PLANT_SHADING).mods(VOXY).apply(Apply.REJOIN));
        add(decimal("voxy_plant_saturation", 1.0, 0.0, 1.5, 0.05, () -> voxyPlantSaturation, v -> voxyPlantSaturation = v)
                .in(ConfigTab.VOXY, "plants").mods(VOXY).apply(Apply.REJOIN));
    }

    static {
        // Defaults until load() reads the file, so nothing ever sees an unset field.
        for (ConfigOption<?> option : OPTIONS) option.reset();
    }

    // ========== Access ==========

    /** Every option, in file and screen order. */
    public static List<ConfigOption<?>> options() {
        return Collections.unmodifiableList(OPTIONS);
    }

    /** The folder holding lsl.json (the game's config folder). */
    public static Path configDir() {
        return configDir;
    }

    /** Resolves {@code voxy_ground_plants} against whether AronaLayers-gen is present. */
    public static boolean groundPlants(boolean aronaLayersGenLoaded) {
        return switch (voxyGroundPlants == null ? GroundPlants.AUTO : voxyGroundPlants) {
            case ON -> true;
            case OFF -> false;
            case AUTO -> aronaLayersGenLoaded;
        };
    }

    /** Called after the screen saved new values, to drop what was derived from the old ones. */
    public static void onChange(Runnable listener) {
        LISTENERS.add(listener);
    }

    public static void onValuesChanged() {
        for (Runnable listener : LISTENERS) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                Lsl.LOGGER.warn("[LSL] config listener failed: {}", e.toString());
            }
        }
    }

    // ========== Loading and saving ==========

    /** Loads once (the first caller wins): the mixin plugins and entrypoints all ask for it. */
    public static synchronized void load(Path dir) {
        if (loaded) return;
        loaded = true;
        configDir = dir;
        loadConfig();
    }

    /**
     * Resets every option to its default, then applies the file's values. The file is then rewritten
     * in the current layout unless it could not be read or held an invalid value.
     */
    private static void loadConfig() {
        for (ConfigOption<?> option : OPTIONS) option.reset();

        Path file = configDir.resolve(CONFIG_FILE);
        JsonObject extra = new JsonObject();
        boolean rewrite = true;
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonObject json = GSON.fromJson(reader, JsonObject.class);
                if (json != null) rewrite = applyJson(json, extra);
            } catch (IOException | JsonParseException e) {
                Lsl.LOGGER.error("[LSL] Could not read {}, using defaults until it is fixed: {}", file, e.getMessage());
                rewrite = false;
            }
        } else {
            importFromGen();
        }
        if (rewrite) write(extra);
    }

    /** First start: the dh_* values AronaLayers-gen's layer_config.json held, when there is one. */
    private static void importFromGen() {
        Path gen = configDir.resolve("aronalayersgen").resolve("layer_config.json");
        if (!Files.exists(gen)) return;
        try (Reader reader = Files.newBufferedReader(gen, StandardCharsets.UTF_8)) {
            JsonObject json = GSON.fromJson(reader, JsonObject.class);
            if (json == null) return;
            List<String> taken = new ArrayList<>();
            for (ConfigOption<?> option : OPTIONS) {
                if (option.key.startsWith("dh_") && json.has(option.key) && applyValue(option, json.get(option.key))) {
                    taken.add(option.key);
                }
            }
            if (!taken.isEmpty()) Lsl.LOGGER.info("[LSL] Took {} from AronaLayers-gen's layer_config.json", taken);
        } catch (IOException | RuntimeException e) {
            Lsl.LOGGER.warn("[LSL] Could not read {}: {}", gen, e.getMessage());
        }
    }

    /** @return false when a value was invalid, so the file must not be overwritten */
    private static boolean applyJson(JsonObject json, JsonObject extra) {
        boolean valid = true;
        for (ConfigOption<?> option : OPTIONS) {
            if (json.has(option.key)) valid &= applyValue(option, json.get(option.key));
        }
        // Keys this version does not know (a newer version, or a typo) are kept at the end.
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("_") || OPTIONS.stream().anyMatch(o -> o.key.equals(key))) continue;
            Lsl.LOGGER.warn("[LSL] Unknown key '{}' in {} is ignored", key, CONFIG_FILE);
            extra.add(key, entry.getValue());
        }
        return valid;
    }

    private static <T> boolean applyValue(ConfigOption<T> option, JsonElement element) {
        try {
            option.set(option.fromJson(element));
            return true;
        } catch (RuntimeException e) {
            Lsl.LOGGER.error("[LSL] Invalid value {} for '{}' ({}), using the default {}",
                    element, option.key, e.getMessage(), option.format(option.defaultValue));
            return false;
        }
    }

    /** Writes the current values. */
    public static void save() {
        write(new JsonObject());
    }

    private static void write(JsonObject extra) {
        Path file = configDir.resolve(CONFIG_FILE);
        String text = GSON.toJson(toJson(extra)) + "\n";
        try {
            if (Files.exists(file) && Files.readString(file, StandardCharsets.UTF_8).equals(text)) return;
            Files.createDirectories(configDir);
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Lsl.LOGGER.error("[LSL] Failed to save config to {}", file, e);
        }
    }

    private static JsonObject toJson(JsonObject extra) {
        Map<String, String> lang = englishText();
        JsonObject json = new JsonObject();
        json.addProperty("_comment", "LSL - Layer Supported LOD settings. Edit them here or in game (Mod Menu, or the"
                + " /lsl config command). LSL rewrites this file at startup and on save: your values are kept,"
                + " comments are refreshed and options added by an update appear with their default.");
        ConfigTab tab = null;
        for (ConfigOption<?> option : OPTIONS) {
            if (option.tab != tab) {
                tab = option.tab;
                json.addProperty("_section_" + tab.id(),
                        "=== " + lang.getOrDefault("config.lsl.section." + tab.id(), tab.id()).toUpperCase(Locale.ROOT) + " ===");
            }
            json.addProperty("_comment_" + option.key, comment(option, lang));
            json.add(option.key, valueJson(option));
        }
        for (Map.Entry<String, JsonElement> entry : extra.entrySet()) json.add(entry.getKey(), entry.getValue());
        return json;
    }

    private static <T> JsonElement valueJson(ConfigOption<T> option) {
        return option.toJson(option.get());
    }

    private static <T> String comment(ConfigOption<T> option, Map<String, String> lang) {
        StringBuilder text = new StringBuilder(lang.getOrDefault(option.descriptionKey(), ""));
        if (!option.mods.isEmpty()) {
            text.append(' ').append(String.format(lang.getOrDefault("config.lsl.file.mods", "Needs %s."),
                    String.join(" or ", option.mods)));
        }
        for (ConfigOption.Requirement requirement : option.requirements) {
            text.append(' ').append(String.format(lang.getOrDefault("config.lsl.file.requires", "Only used while %s is %s."),
                    requirement.option().key, requirement.value()));
        }
        if (option.kind == ConfigOption.Kind.INT || option.kind == ConfigOption.Kind.DOUBLE) {
            text.append(' ').append(String.format(lang.getOrDefault("config.lsl.file.range", "Range %s to %s."),
                    trim(option.min), trim(option.max)));
        } else if (option.kind == ConfigOption.Kind.ENUM) {
            text.append(' ').append(String.format(lang.getOrDefault("config.lsl.file.values", "One of %s."),
                    String.join(", ", Arrays.stream(option.enumValues()).map(v -> ((Enum<?>) v).name()).toList())));
        }
        text.append(' ').append(lang.getOrDefault(option.apply.langKey(), ""));
        text.append(' ').append(String.format(lang.getOrDefault("config.lsl.file.default", "Default: %s."),
                option.format(option.defaultValue)));
        return text.toString().trim();
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    /**
     * The English strings, read from the bundled language file so a dedicated server (which never
     * loads client language assets) still writes the same comments the screen shows.
     */
    private static Map<String, String> englishText() {
        try (InputStream in = LslConfig.class.getClassLoader().getResourceAsStream(LANG_FILE)) {
            if (in == null) return Map.of();
            JsonObject json = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            Map<String, String> map = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) map.put(entry.getKey(), entry.getValue().getAsString());
            return map;
        } catch (IOException | JsonParseException e) {
            Lsl.LOGGER.warn("[LSL] Could not read {}: {}", LANG_FILE, e.getMessage());
            return Map.of();
        }
    }
}
