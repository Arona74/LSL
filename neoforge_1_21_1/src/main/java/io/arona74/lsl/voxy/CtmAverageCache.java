package io.arona74.lsl.voxy;

import io.arona74.lsl.Lsl;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Results of the repeat-CTM AVERAGE bake ({@link CtmBake#average}), kept for the session and on
 * disk ({@code lsl/ctm_average.bin} in the game folder), so a state is averaged once per setup, not
 * once per world load. Voxy rebakes every model on each world load, and Conquest Reforged has about
 * 2,400 repeat states (about 54,000 piece bakes, 4 s on Voxy's single model thread).
 *
 * <p>Key: a 128-bit hash of the block id, the particle sprite's name, the cycle's periods and what
 * piece (0,0,0) baked to (all 6 faces, pixels and depth). Same key, same textures and geometry,
 * same average. Value: the averaged colour of each face pixel (depth and coverage are piece
 * (0,0,0)'s, already in the output). The whole cache is dropped when the fingerprint changes: LSL's
 * and every mod's version, and the selected resource packs.
 */
public final class CtmAverageCache {
    private CtmAverageCache() {}

    static final int COLOURS = 6 * 16 * 16;
    private static final int MAGIC = 0x4C534C43; // "LSLC"
    private static final int FORMAT = 1;
    /** About 6 KB each; CR fills about 1,700. */
    private static final int MAX_ENTRIES = 16384;

    private record Key(long a, long b) {}

    private static final Map<Key, int[]> ENTRIES = new ConcurrentHashMap<>();
    private static volatile String fingerprint;
    private static volatile boolean loaded;
    private static volatile boolean dirty;
    private static String modsPart;
    private static final ExecutorService SAVER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LSL CTM cache saver");
        t.setDaemon(true);
        return t;
    });

    public static void register() {
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> saveLater());
        NeoForge.EVENT_BUS.addListener((GameShuttingDownEvent event) -> save());
    }

    /** The cache key for this bake, or null if it can't be built (then nothing is cached). */
    static Object key(BlockState state, TextureAtlasSprite sprite, int[] periods, long picture, int bytes) {
        try {
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()) + "|" + sprite.contents().name()
                + "|" + periods[0] + "," + periods[1] + "," + periods[2];
            long a = 0xcbf29ce484222325L, b = 0x9E3779B97F4A7C15L;
            for (byte c : id.getBytes(StandardCharsets.UTF_8)) {
                a = (a ^ (c & 0xFF)) * 0x100000001b3L;
                b = mix(b + (c & 0xFF));
            }
            for (int i = 0; i < bytes; i += 8) {
                long v = org.lwjgl.system.MemoryUtil.memGetLong(picture + i);
                a = (a ^ v) * 0x100000001b3L;
                a ^= a >>> 29;
                b = mix(b ^ v);
            }
            return new Key(a, b);
        } catch (Throwable t) {
            return null;
        }
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }

    /** The cached colours for this key, or null. */
    static int[] get(Object key) {
        if (key == null) return null;
        check();
        return ENTRIES.get((Key) key);
    }

    static void put(Object key, int[] colours) {
        if (key == null) return;
        if (ENTRIES.size() >= MAX_ENTRIES) ENTRIES.clear();
        ENTRIES.put((Key) key, colours);
        dirty = true;
    }

    public static int size() {
        return ENTRIES.size();
    }

    /** Loads the file once; drops everything if the mods or resource packs changed. */
    private static synchronized void check() {
        String now = currentFingerprint();
        if (!loaded) {
            loaded = true;
            fingerprint = now;
            load(now);
        } else if (!now.equals(fingerprint)) {
            Lsl.LOGGER.info("[LSL/Voxy] Resource packs changed: repeat CTM averages start over");
            fingerprint = now;
            ENTRIES.clear();
            dirty = true;
        }
    }

    private static String currentFingerprint() {
        if (modsPart == null) {
            List<String> mods = new ArrayList<>();
            for (var m : ModList.get().getMods()) {
                mods.add(m.getModId() + "@" + m.getVersion());
            }
            mods.sort(Comparator.naturalOrder());
            modsPart = String.join(",", mods);
        }
        String packs;
        try {
            packs = String.join(",", Minecraft.getInstance().getResourcePackRepository().getSelectedIds());
        } catch (Throwable t) {
            packs = "?";
        }
        return modsPart + "|" + packs;
    }

    private static Path file() {
        return FMLPaths.GAMEDIR.get().resolve("lsl").resolve("ctm_average.bin");
    }

    private static void load(String expected) {
        Path file = file();
        if (!Files.isRegularFile(file)) return;
        long start = System.nanoTime();
        try (InputStream raw = Files.newInputStream(file);
             DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw, 1 << 16)))) {
            if (in.readInt() != MAGIC || in.readInt() != FORMAT) return;
            byte[] saved = new byte[in.readInt()];
            in.readFully(saved);
            if (!expected.equals(new String(saved, StandardCharsets.UTF_8))) {
                Lsl.LOGGER.info("[LSL/Voxy] Mods or resource packs changed since the repeat CTM averages were saved: starting over");
                return;
            }
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                Key key = new Key(in.readLong(), in.readLong());
                int[] colours = new int[COLOURS];
                for (int j = 0; j < COLOURS; j++) colours[j] = in.readInt();
                ENTRIES.put(key, colours);
            }
            Lsl.LOGGER.info("[LSL/Voxy] Loaded {} repeat CTM averages in {} ms", n, (System.nanoTime() - start) / 1_000_000);
        } catch (Throwable t) {
            ENTRIES.clear();
            Lsl.LOGGER.warn("[LSL/Voxy] Couldn't read {}, averaging again: {}", file, t.toString());
        }
    }

    private static void saveLater() {
        if (dirty) SAVER.execute(CtmAverageCache::save);
    }

    private static synchronized void save() {
        if (!dirty || fingerprint == null) return;
        dirty = false;
        Path file = file();
        Path tmp = file.resolveSibling("ctm_average.bin.tmp");
        long start = System.nanoTime();
        try {
            Files.createDirectories(file.getParent());
            List<Map.Entry<Key, int[]>> entries = new ArrayList<>(ENTRIES.entrySet());
            try (OutputStream raw = Files.newOutputStream(tmp);
                 DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(raw, 1 << 16)))) {
                out.writeInt(MAGIC);
                out.writeInt(FORMAT);
                byte[] print = fingerprint.getBytes(StandardCharsets.UTF_8);
                out.writeInt(print.length);
                out.write(print);
                out.writeInt(entries.size());
                for (Map.Entry<Key, int[]> e : entries) {
                    out.writeLong(e.getKey().a());
                    out.writeLong(e.getKey().b());
                    for (int c : e.getValue()) out.writeInt(c);
                }
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Lsl.LOGGER.info("[LSL/Voxy] Saved {} repeat CTM averages in {} ms", entries.size(), (System.nanoTime() - start) / 1_000_000);
        } catch (Throwable t) {
            dirty = true;
            Lsl.LOGGER.warn("[LSL/Voxy] Couldn't save {}: {}", file, t.toString());
        }
    }
}
