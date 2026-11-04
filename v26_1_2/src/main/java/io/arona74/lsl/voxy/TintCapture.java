package io.arona74.lsl.voxy;

import io.arona74.lsl.Lsl;
import io.arona74.lsl.LslConfig;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;

/**
 * How Voxy captures a block's tint colour, made to agree with Polytone.
 *
 * <p>Voxy asks each tint source for its colour once per biome, in a stand-in world at block (0, 0, 0)
 * ({@code ModelFactory.captureColourConstant}). Conquest Reforged's Polytone colormaps for grasses
 * and leaves pick their colour row from the height ({@code y_axis: (POS_Y + 64 + noise) / 512}), so
 * the LODs got the colours for Y = 0, deep underground, instead of the surface band, which made
 * distant fescue and the like far too yellow. And a colormap without biome blending only finds its
 * biome in a real level, which Voxy's stand-in is not.
 *
 * <p>So the capture happens at {@code voxy_tint_height} instead of Y = 0, and a Polytone colormap is
 * sampled directly with Voxy's biome, the way Polytone itself does in a real level
 * ({@code Colormap.sampleColor(level, state, position, biome, item)}). Polytone is reached by
 * reflection: LSL doesn't depend on it.
 */
public final class TintCapture {
    private TintCapture() {}

    /** Every Polytone colour source (colormaps, compounds, layers...) implements this. */
    private static final String COLOR_GETTER = "net.mehvahdjukaar.polytone.content.colormap.IColorGetter";
    private static final java.util.Map<Class<?>, Boolean> KNOWN = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile Method sampleColor;
    private static volatile boolean polytoneBroken;

    public static int colour(BlockTintSource source, BlockState state, BlockAndTintGetter getter, Biome biome) {
        int y = LslConfig.voxyTintHeight;
        if (biome != null && !polytoneBroken && isColormap(source)) {
            try {
                return (int) sampleColor.invoke(source, getter, state, new Vec3(0.5, y + 0.5, 0.5), biome, (ItemStack) null);
            } catch (Throwable t) {
                polytoneBroken = true;
                Lsl.LOGGER.warn("[LSL/Voxy] Couldn't sample a Polytone colormap for Voxy, using its normal path: {}", t.toString());
            }
        }
        return source.colorInWorld(state, getter, new BlockPos(0, y, 0));
    }

    /** A Polytone colour source (and LSL can sample it). */
    public static boolean isColormap(BlockTintSource source) {
        if (polytoneBroken || source == null) return false;
        return KNOWN.computeIfAbsent(source.getClass(), TintCapture::isPolytone);
    }

    private static boolean isPolytone(Class<?> c) {
        Class<?> api = find(c);
        if (api == null) return false;
        if (sampleColor == null) {
            try {
                sampleColor = api.getMethod("sampleColor", BlockAndTintGetter.class, BlockState.class,
                    Vec3.class, Biome.class, ItemStack.class);
            } catch (Throwable t) {
                polytoneBroken = true;
                Lsl.LOGGER.warn("[LSL/Voxy] Polytone's colour sources have changed (no sampleColor), using their normal path");
                return false;
            }
        }
        return true;
    }

    /** The Polytone colour-source interface among {@code c}'s supertypes, or null. */
    private static Class<?> find(Class<?> c) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Class<?> i : k.getInterfaces()) {
                if (i.getName().equals(COLOR_GETTER)) return i;
                Class<?> deeper = find(i);
                if (deeper != null) return deeper;
            }
        }
        return null;
    }
}
