package io.arona74.lsl.voxy;

import io.arona74.lsl.Lsl;
import io.arona74.lsl.LslConfig;
import io.arona74.lsl.mixin.voxy.BlockColorsAccessor;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How Voxy captures a block's tint colour, made to agree with Polytone (Minecraft 1.21.1, Voxy
 * through Roxy).
 *
 * <p>Voxy asks each block's colour provider for its colour once per biome, in a stand-in world at
 * block (0, 0, 0) ({@code ModelFactory.captureColourConstant}). Conquest Reforged's Polytone
 * colormaps for grasses and leaves pick their colour row from the height, so the LODs got the
 * colours for Y = 0, deep underground; and a colormap without biome blending only finds its biome
 * in a real level, which Voxy's stand-in is not.
 *
 * <p>So the capture happens at {@link #HEIGHT}, and a Polytone colormap is sampled directly with
 * Voxy's biome, the way Polytone does in a real level ({@code IColorGetter.sampleColor}). Roxy
 * wraps each provider in a proxy that picks the model's biome tint index before calling it, so
 * LSL wraps the real provider underneath ({@link #wrap}): it sees the index
 * Roxy chose. A provider that sorts colormaps by tint index ({@code IndexCompoundColorGetter}) is
 * resolved with that index. Polytone is reached by reflection: LSL doesn't depend on it.
 */
public final class TintCapture {
    private TintCapture() {}

    /** Height the colours are taken at: a typical surface. */
    public static final int HEIGHT = 72;

    /**
     * Polytone's colour-source interface. Older Polytone (3.7, as in Conquest Reforged's 1.21.1 pack)
     * keeps its colormaps in {@code polytone.colormap}; newer Polytone (and the 26.x builds) in
     * {@code polytone.content.colormap}, with a {@code sampleColor} that also takes the level and an
     * exact position. Both are recognised.
     */
    private static final String[] PACKAGES = {"net.mehvahdjukaar.polytone.colormap.", "net.mehvahdjukaar.polytone.content.colormap."};
    private static final String COLOR_GETTER = "IColorGetter";
    /** Polytone's wrapper around a plain BlockColor: colour it the normal way. */
    private static final String OF_BLOCK = "IColorGetter$OfBlock";
    private static final String BY_INDEX = "IndexCompoundColorGetter";

    private static final Map<Class<?>, Boolean> KNOWN = new ConcurrentHashMap<>();
    private static volatile Method sampleColor;
    /** {@link #sampleColor} is the newer form, {@code (level, state, Vec3 position, biome, item)}. */
    private static volatile boolean sampleWithLevel;
    private static volatile Method getters;
    private static volatile boolean polytoneBroken;

    /** Voxy's biome while it captures a colour (set by ModelTintMixin), else null. */
    private static final ThreadLocal<Biome> BIOME = new ThreadLocal<>();

    public static void beginCapture(Biome biome) {
        BIOME.set(biome);
    }

    public static void endCapture() {
        BIOME.remove();
    }

    /** The provider Voxy gets for a block: a Polytone colormap wrapped so a capture samples it with Voxy's biome. */
    public static Object wrap(Object provider) {
        if (!LslConfig.voxyMatchTints || !(provider instanceof BlockColor colour) || !isColormap(colour)) return provider;
        return new Sampled(colour);
    }

    private record Sampled(BlockColor provider) implements BlockColor {
        @Override
        public int getColor(BlockState state, BlockAndTintGetter getter, BlockPos pos, int tintIndex) {
            Biome biome = BIOME.get();
            if (biome != null && !polytoneBroken) {
                try {
                    Object source = byIndex(this.provider, tintIndex);
                    if (source == null) return -1;
                    if (!isPolytoneClass(source.getClass(), OF_BLOCK)) {
                        return sampleWithLevel
                            ? (int) sampleColor.invoke(source, getter, state, Vec3.atCenterOf(pos), biome, (ItemStack) null)
                            : (int) sampleColor.invoke(source, state, pos, biome, (ItemStack) null);
                    }
                } catch (Throwable t) {
                    polytoneBroken = true;
                    Lsl.LOGGER.warn("[LSL/Voxy] Couldn't sample a Polytone colormap for Voxy, using its normal path: {}", t.toString());
                }
            }
            return this.provider.getColor(state, getter, pos, tintIndex);
        }
    }

    /** The colour source a provider uses for {@code tintIndex} (as IndexCompoundColorGetter picks it), or the provider itself. */
    private static Object byIndex(Object provider, int tintIndex) throws ReflectiveOperationException {
        if (!isPolytoneClass(provider.getClass(), BY_INDEX)) return provider;
        if (getters == null) getters = provider.getClass().getMethod("getGetters");
        Int2ObjectMap<?> map = (Int2ObjectMap<?>) getters.invoke(provider);
        Object source = map.get(tintIndex);
        return source != null ? source : map.get(-1);
    }

    /** Whether {@code state}'s block is coloured by a Polytone colour source. */
    public static boolean isColormap(BlockState state) {
        BlockColor provider = provider(state);
        return provider != null && isColormap(provider);
    }

    /** The game's own colour provider for the block (not Roxy's proxy), or null. */
    public static BlockColor provider(BlockState state) {
        try {
            return ((BlockColorsAccessor) Minecraft.getInstance().getBlockColors()).lsl$blockColors().get(state.getBlock());
        } catch (Throwable t) {
            return null;
        }
    }

    /** A Polytone colour source (and LSL can sample it). */
    public static boolean isColormap(BlockColor source) {
        if (polytoneBroken || source == null || source instanceof Sampled) return false;
        return KNOWN.computeIfAbsent(source.getClass(), TintCapture::isPolytone);
    }

    private static boolean isPolytone(Class<?> c) {
        Class<?> api = find(c);
        if (api == null) return false;
        if (sampleColor == null) {
            try {
                sampleColor = api.getMethod("sampleColor", BlockState.class, BlockPos.class, Biome.class, ItemStack.class);
                sampleWithLevel = false;
            } catch (NoSuchMethodException older) {
                try {
                    sampleColor = api.getMethod("sampleColor", BlockAndTintGetter.class, BlockState.class, Vec3.class,
                        Biome.class, ItemStack.class);
                    sampleWithLevel = true;
                } catch (Throwable t) {
                    polytoneBroken = true;
                    Lsl.LOGGER.warn("[LSL/Voxy] Polytone's colour sources have changed (no sampleColor), using their normal path");
                    return false;
                }
            }
        }
        return true;
    }

    /** Whether {@code c} is Polytone's class {@code simpleName} (in either of its colormap packages). */
    private static boolean isPolytoneClass(Class<?> c, String simpleName) {
        for (String p : PACKAGES) if (c.getName().equals(p + simpleName)) return true;
        return false;
    }

    /** The Polytone colour-source interface among {@code c}'s supertypes, or null. */
    private static Class<?> find(Class<?> c) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Class<?> i : k.getInterfaces()) {
                if (isPolytoneClass(i, COLOR_GETTER)) return i;
                Class<?> deeper = find(i);
                if (deeper != null) return deeper;
            }
        }
        return null;
    }
}
