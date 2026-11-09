package io.arona74.lsl.voxy;

import io.arona74.lsl.Compat;
import io.arona74.lsl.LayerInfo;
import io.arona74.lsl.LslConfig;
import net.minecraft.world.level.block.state.BlockState;
import me.cortex.voxy.client.core.util.IrisUtil;
import org.lwjgl.system.MemoryUtil;

/**
 * How plants look in Voxy's LODs.
 *
 * <p>Plant models are usually unshaded (Conquest Reforged's dense grass: {@code "shade": false}, no
 * ambient occlusion), so Voxy draws them at full brightness, while near the player the game's
 * lighting and the resource-pack tinting make them much darker. Two knobs, both applied when Voxy
 * bakes a plant's model:
 * <ul>
 *   <li>{@code voxy_draw_plants}: off leaves plants out of the LODs altogether (the layer or block
 *       under them shows instead), which also removes their shimmer against the ground.</li>
 *   <li>{@code voxy_plant_shading}: mark the model shaded, so Voxy applies its directional face
 *       shading (0.8 / 0.6 on the sides) like any shaded block. This is the fix the AronaLayers Voxy
 *       fork used for grass, extended to every plant.</li>
 *   <li>{@code voxy_plant_brightness} (with shading only): scale the baked colours (1.0 = unchanged, above 1 brightens,
 *       which makes up for the 0.6-0.8 side shading that Shade Plants adds).</li>
 * </ul>
 * A plant is anything on the vanilla plant base class (grass, flowers, ferns, crops, saplings, and
 * Conquest Reforged's plants, which extend it).
 */
public final class PlantLook {
    private PlantLook() {}

    /** SoftwareModelTextureBakery: 6 faces of 16x16 pixels, 8 bytes each (ABGR colour, then depth). */
    private static final int PIXELS = 6 * 16 * 16;
    private static final int GREY_SPREAD = 5;

    /**
     * Layers ({@code voxy_flat_layers}): unshaded, so a layer's sides are as bright as its top. A
     * slope of layers is a staircase of thin side strips; shaded at 0.6-0.8 they draw a dark line at
     * every step, and far away those lines are thinner than a pixel and turn into moire (beaches of
     * sand layers show it most). Returns the bake flags.
     */
    public static int applyLayer(BlockState state, int flags) {
        if (!LslConfig.voxyFlatLayers || isPlant(state)) return flags;
        try {
            if (!LayerInfo.isLayerBlock(state)) return flags;
        } catch (Throwable t) {
            return flags;
        }
        return flags & ~1;
    }

    /** Model flags with {@link ShaderPatch#FLAT_FLAG} for a layer under Flat Layer Shading (shaderpack path). */
    public static int flatLayerFlag(int modelFlags, BlockState state) {
        if (!LslConfig.voxyFlatLayers || state == null || isPlant(state)) return modelFlags;
        try {
            return LayerInfo.isLayerBlock(state) ? modelFlags | ShaderPatch.FLAT_FLAG : modelFlags;
        } catch (Throwable t) {
            return modelFlags;
        }
    }

    /**
     * {@code voxy_plant_saturation} on a plant's tint (Voxy's captured biome or constant colour,
     * 0xAARRGGBB, -1 = none). Tinted plants have grey textures, so the colour lives here.
     */
    public static int saturateTint(BlockState state, int colour) {
        double saturation = LslConfig.voxyPlantSaturation;
        if (colour == -1 || Math.abs(saturation - 1.0) <= 0.001 || state == null || !isPlant(state)) return colour;
        int r = (colour >>> 16) & 0xFF, g = (colour >>> 8) & 0xFF, b = colour & 0xFF;
        double luma = 0.299 * r + 0.587 * g + 0.114 * b;
        return (colour & 0xFF000000) | (mix(luma, r, saturation) << 16) | (mix(luma, g, saturation) << 8) | mix(luma, b, saturation);
    }

    /** Baked plant pixels (ABGR) toward their grey, for plants coloured by their texture. */
    private static void saturateTexture(long output, double saturation) {
        for (int i = 0; i < PIXELS; i++) {
            long address = output + (long) i * 8;
            int colour = MemoryUtil.memGetInt(address);
            if ((colour >>> 24) == 0) continue;
            int r = colour & 0xFF, g = (colour >>> 8) & 0xFF, b = (colour >>> 16) & 0xFF;
            double luma = 0.299 * r + 0.587 * g + 0.114 * b;
            // Voxy's partial tint colours only near-grey pixels (channels within 0.02 = 5/255): a
            // coloured pixel keeps a spread above that, so it never starts taking the tint.
            int spread = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
            double s = spread > GREY_SPREAD ? Math.max(saturation, (GREY_SPREAD + 2.0) / spread) : saturation;
            int nr = keepLit(r, mix(luma, r, s)), ng = keepLit(g, mix(luma, g, s)), nb = keepLit(b, mix(luma, b, s));
            MemoryUtil.memPutInt(address, (colour & 0xFF000000) | (nb << 16) | (ng << 8) | nr);
        }
    }

    private static int mix(double grey, int channel, double saturation) {
        return Math.min(255, Math.max(0, (int) Math.round(grey + (channel - grey) * saturation)));
    }

    /** Voxy reads RGB 0 as "no colour" when checking tints: a lit channel stays lit. */
    private static int keepLit(int before, int after) {
        return before != 0 && after == 0 ? 1 : after;
    }

    public static boolean isPlant(BlockState state) {
        return Compat.isPlantBase(state.getBlock());
    }

    /** Adjusts a baked plant in place; returns the bake flags, shaded bit added when asked. */
    public static int apply(BlockState state, long output, int flags) {
        if (!isPlant(state)) return flags;
        if (!LslConfig.voxyDrawPlants) {
            // Nothing baked: every face reads as empty, so Voxy draws no quad for the plant at all.
            MemoryUtil.memSet(output, 0, (long) PIXELS * 8);
            return flags;
        }
        double saturation = LslConfig.voxyPlantSaturation;
        if (Math.abs(saturation - 1.0) > 0.001) saturateTexture(output, saturation);
        if (!shadePlants()) return flags;
        flags |= 1;
        // Brightness goes with shading: it is there to tune how dark the shading makes plants.
        double brightness = LslConfig.voxyPlantBrightness;
        if (Math.abs(brightness - 1.0) > 0.001) scaleColours(output, brightness);
        return flags;
    }

    /**
     * {@code voxy_plant_face_inset}: Voxy draws each side picture of a cut-out model as one flat quad
     * at the model's nearest pixel to that side. Conquest Reforged's dense plants reach almost to the
     * block edge, so their pictures stood about 1/16 block from the cell boundary, nearly in the
     * plane of a neighbouring layer's side, and the two fought as the camera moved. The sides are
     * pushed in to at least the inset (the picture itself is unchanged, only where it is drawn).
     * Depth order: Direction 3D value, -1 = nothing drawn.
     */
    public static float[] insetFaces(float[] depths, BlockState state) {
        double inset = LslConfig.voxyPlantFaceInset;
        if (depths == null || depths.length < 6 || inset <= 0.001 || state == null || !isPlant(state)) return depths;
        for (int face = 2; face < 6; face++) {
            if (depths[face] >= 0 && depths[face] < inset) depths[face] = (float) inset;
        }
        return depths;
    }

    /** Shade Plants, unless a shaderpack is on and it isn't asked for there (shaders light plants themselves). */
    private static boolean shadePlants() {
        if (!LslConfig.voxyPlantShading) return false;
        if (LslConfig.voxyPlantShadingWithShaders) return true;
        try {
            return !IrisUtil.irisShaderPackEnabled();
        } catch (Throwable t) {
            return true;
        }
    }

    private static void scaleColours(long output, double factor) {
        for (int i = 0; i < PIXELS; i++) {
            long address = output + (long) i * 8;
            int colour = MemoryUtil.memGetInt(address);
            if ((colour >>> 24) == 0) continue; // nothing drawn here
            int r = scale(colour & 0xFF, factor);
            int g = scale((colour >>> 8) & 0xFF, factor);
            int b = scale((colour >>> 16) & 0xFF, factor);
            MemoryUtil.memPutInt(address, (colour & 0xFF000000) | (b << 16) | (g << 8) | r);
        }
    }

    /**
     * Clamped to 255 when brightening. Never turns a lit channel fully black: Voxy reads RGB 0 as
     * "no colour" when checking tints.
     */
    private static int scale(int channel, double factor) {
        if (channel == 0) return 0;
        return Math.min(255, Math.max(1, (int) Math.round(channel * factor)));
    }
}
