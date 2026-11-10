package io.arona74.lsl.voxy;

import io.arona74.lsl.LslConfig;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryUtil;

/**
 * {@code voxy_leaf_depth_shading}: leaves shaded by how deep in the block each baked pixel lies.
 *
 * <p>Up close a canopy is many leaf planes with gaps between them, and the game's smooth lighting
 * (plus a shaderpack's shadows) darkens every leaf further in: most of a tree is leaves in shade,
 * which reads as a deep green with volume. Voxy bakes a block into six flat pictures and lights each
 * one evenly, so its leaves (Conquest Reforged's dense stand-in model included) showed only fully
 * lit leaves: the right colour, but pale and flat next to the trees up close.
 *
 * <p>Each baked pixel keeps the depth at which the bake hit it (top 24 bits, 0 at the face, 1 at
 * the far side of the block). Voxy draws a cut-out face at its nearest pixel, so a pixel's depth
 * beyond that nearest one is how far into the foliage it lies. Spread over the face's own depth
 * range (at least {@link #MIN_SPAN}), the deepest visible leaves are darkened by the full strength,
 * outer leaves not at all. {@code voxy_leaf_brightness} then scales every leaf pixel, outer ones
 * included: evenly lit, those still read lighter than a canopy up close. Done once per leaf state
 * at bake time; nothing changes at draw time.
 */
public final class LeafLook {
    private LeafLook() {}

    private static final int FACE_PIXELS = 16 * 16;
    private static final int FACES = 6;
    private static final double DEPTH_SCALE = (1 << 24) - 1;

    public static boolean isLeaves(BlockState state) {
        return state != null && state.is(BlockTags.LEAVES);
    }

    /**
     * Smallest depth range a face's shading is spread over: a face whose leaves all lie within it
     * gets only part of the strength (a flat face stays flat).
     */
    private static final double MIN_SPAN = 0.25;

    /** Darkens a baked leaf state in place: by pixel depth, and all of it by the leaf brightness. */
    public static void apply(BlockState state, long output) {
        double strength = LslConfig.voxyLeafDepthShading;
        double brightness = LslConfig.voxyLeafBrightness;
        if (strength <= 0.001 && Math.abs(brightness - 1.0) <= 0.001 || !isLeaves(state)) return;
        for (int face = 0; face < FACES; face++) {
            long base = output + (long) face * FACE_PIXELS * 8;
            double nearest = Double.MAX_VALUE, deepest = 0;
            for (int i = 0; i < FACE_PIXELS; i++) {
                long pixel = MemoryUtil.memGetLong(base + (long) i * 8);
                if ((int) pixel >>> 24 == 0) continue; // nothing drawn
                double d = depth(pixel);
                nearest = Math.min(nearest, d);
                deepest = Math.max(deepest, d);
            }
            if (nearest == Double.MAX_VALUE) continue;
            // The deepest visible leaves get the full strength, whatever the stand-in's layer spacing.
            double span = Math.max(MIN_SPAN, deepest - nearest);
            for (int i = 0; i < FACE_PIXELS; i++) {
                long address = base + (long) i * 8;
                long pixel = MemoryUtil.memGetLong(address);
                int colour = (int) pixel;
                if (colour >>> 24 == 0) continue;
                double factor = brightness * (1.0 - strength * Math.min(1.0, (depth(pixel) - nearest) / span));
                if (Math.abs(factor - 1.0) < 0.001) continue;
                int r = scale(colour & 0xFF, factor);
                int g = scale(colour >>> 8 & 0xFF, factor);
                int b = scale(colour >>> 16 & 0xFF, factor);
                MemoryUtil.memPutInt(address, colour & 0xFF000000 | b << 16 | g << 8 | r);
            }
        }
    }

    private static double depth(long pixel) {
        return (pixel >>> 40) / DEPTH_SCALE;
    }

    /** Never turns a lit channel fully black: Voxy reads RGB 0 as "no colour" when checking tints. */
    private static int scale(int channel, double factor) {
        if (channel == 0) return 0;
        return Math.min(255, Math.max(1, (int) Math.round(channel * factor)));
    }
}
