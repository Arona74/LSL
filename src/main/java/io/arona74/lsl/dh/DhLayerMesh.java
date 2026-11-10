/*
 * Exact layer heights in Distant Horizons LODs.
 *
 * Adapted from Geostep (verdantlayers) 1.3, which is distributed under the MIT License:
 *
 *   Copyright (c) 2026 Verdant Layers contributors
 *
 *   Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 *   and associated documentation files (the "Software"), to deal in the Software without
 *   restriction, including without limitation the rights to use, copy, modify, merge, publish,
 *   distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
 *   Software is furnished to do so, subject to the following conditions:
 *
 *   The above copyright notice and this permission notice shall be included in all copies or
 *   substantial portions of the Software.
 *
 *   THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
 *   BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 *   NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 *   DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *   OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.arona74.lsl.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSource;
import com.seibel.distanthorizons.core.dataObjects.render.textures.BlockTextureRegistry;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPosMutable;
import com.seibel.distanthorizons.core.util.FullDataPointUtil;
import com.seibel.distanthorizons.core.util.RenderDataPointUtil;
import io.arona74.lsl.LslConfig;
import com.seibel.distanthorizons.core.wrapperInterfaces.IWrapperFactory;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import io.arona74.lsl.Lsl;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.shorts.Short2ByteOpenHashMap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Exact layer heights in Distant Horizons LODs.
 *
 * <p>DH builds its meshes from render data whose heights are whole blocks: a 12-bit min and max Y
 * per segment, and a 16-bit vertex Y. A section that holds partial layers is switched to eighths
 * of a block instead:
 * <ul>
 *   <li>{@link #refine} rewrites each of its 64x64 render columns in eighth units. Every visible
 *       partial layer's one-block segment is cut out of DH's column and replaced by a cap
 *       {@code layers/8} high, with the water of a waterlogged layer above it and the vanilla snow
 *       resting on it. Vanilla snow on any other block gets a cap of its own (DH drops snow of 1-3
 *       layers and draws 4-7 as a full block). All other segments are rescaled by 8. The section
 *       is flagged ({@link DhEighths}).</li>
 *   <li>Building that section's mesh (ColumnBufferMixin) multiplies the culling height by 8
 *       (ColumnBoxMixin) and packs each vertex Y as whole block + 3 bits of eighths
 *       ({@link #packVertexY}, LodVertexMixin).</li>
 *   <li>DH's terrain vertex shader, and Iris's when shaders are on, decode the packed Y
 *       ({@link #patchShader}, IrisLayerShader).</li>
 * </ul>
 * Where the eighths are kept in the render data depends on dh_exact_layers_color_bits:
 * <ul>
 *   <li>Off: in DH's 12-bit min and max Y themselves, in eighth units. 12 bits of eighths is 512
 *       blocks, so dimensions taller than that keep DH's own geometry.</li>
 *   <li>On: the Y fields stay whole blocks, as DH writes them, and the eighths of min and max Y
 *       (3 bits each) take the two lowest bits of each colour channel, a colour shift of at most
 *       3/255. ColumnBoxDecodeMixin and ColumnBufferDecodeMixin read {@code whole * 8 + eighths}
 *       at each height read of DH's mesh builder ({@link #meshYMin}), so the mesh code sees the
 *       same eighth units as above, up to DH's own 4096-block limit. Only those call sites: a hook
 *       on DH's global getters slowed every DH thread (mesh builds 7.6 -> 21.6 ms). The same trick
 *       the Tellus fork of DH uses to widen its Y fields, without changing DH's layout.</li>
 * </ul>
 */
public final class DhLayerMesh {
    private DhLayerMesh() {}

    /** True while the mesh of an eighth-unit section is being built on this thread. */
    public static final ThreadLocal<Boolean> BUILDING_EIGHTHS = ThreadLocal.withInitial(() -> false);

    private static final int WIDTH = 64;
    /** Highest eighth-unit Y: 12 bits of eighths, or 12 bits of whole blocks + 3 bits of eighths. */
    private static final int MAX_EIGHTH_Y = 4095;
    private static final int MAX_COLOR_BITS_Y = 4095 * 8 + 7;
    private static final Set<String> WARNED_DIMENSIONS = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean ANNOUNCED = new AtomicBoolean();

    public static boolean supportedHeight(int minY, int maxY) {
        return maxY > minY && maxY - minY <= (DhExactFlags.colorBits ? 4095 : 511);
    }

    /** Eighth-unit Y -> whole block in the low 12 bits, eighths in bits 13-15. */
    public static short packVertexY(int eighthY) {
        if (eighthY < 0 || eighthY > MAX_COLOR_BITS_Y) {
            throw new IllegalArgumentException("DH eighth Y outside 12-bit render bounds: " + eighthY);
        }
        return (short) (eighthY >>> 3 | (eighthY & 7) << 13);
    }

    /** Render data point with its min and max Y replaced (both in eighth units). */
    static long withBounds(long data, int min, int max) {
        boolean colorBits = DhExactFlags.colorBits;
        if (min < 0 || max > (colorBits ? MAX_COLOR_BITS_Y : MAX_EIGHTH_Y) || max < min) {
            throw new IllegalArgumentException("Invalid DH eighth bounds " + min + ".." + max);
        }
        long cleared = data & ~(RenderDataPointUtil.MIN_Y_SHIFTED_MASK | RenderDataPointUtil.MAX_Y_SHIFTED_MASK);
        if (!colorBits) {
            return cleared | (long) min << RenderDataPointUtil.MIN_Y_SHIFT | (long) max << RenderDataPointUtil.MAX_Y_SHIFT;
        }
        return withFractions(cleared | (long) (min >>> 3) << RenderDataPointUtil.MIN_Y_SHIFT
            | (long) (max >>> 3) << RenderDataPointUtil.MAX_Y_SHIFT, min & 7, max & 7);
    }

    /** A whole-block data point in eighth units: rescaled, or with zero eighths in its colour bits. */
    private static long scaled(long data) {
        if (data == 0L) return 0L;
        if (DhExactFlags.colorBits) return data & ~FRACTION_BITS;
        return withBounds(data, RenderDataPointUtil.getYMin(data) * 8, RenderDataPointUtil.getYMax(data) * 8);
    }

    /** Min and max Y in eighths of a point this class wrote (outside mesh builds, where DH's getters are not decoded). */
    private static int yMin8(long data) {
        int y = RenderDataPointUtil.getYMin(data);
        return DhExactFlags.colorBits ? y << 3 | minFraction(data) : y;
    }

    private static int yMax8(long data) {
        int y = RenderDataPointUtil.getYMax(data);
        return DhExactFlags.colorBits ? y << 3 | maxFraction(data) : y;
    }

    private static long point(int min, int max, int color, int sky, int light, byte material) {
        return withBounds(RenderDataPointUtil.createDataPoint(1, 0, color, sky, light, material), min, max);
    }

    // ========== Colour-bit encoding (dh_exact_layers_color_bits) ==========

    /**
     * The two lowest bits of blue, green and red (bits 32-33, 40-41, 48-49): min Y eighths in
     * blue 0-1 + green 0, max Y eighths in green 1 + red 0-1.
     */
    private static final long FRACTION_BITS = 0b11L << 32 | 0b11L << 40 | 0b11L << 48;

    private static long withFractions(long data, int min, int max) {
        return (data & ~FRACTION_BITS)
            | (long) (min & 3) << 32 | (long) (min >>> 2 & 1) << 40
            | (long) (max & 1) << 41 | (long) (max >>> 1 & 3) << 48;
    }

    /** DH's height getters as the mesh builder reads them in colour-bit mode: eighth units. */
    public static short meshYMin(long data) {
        return (short) (RenderDataPointUtil.getYMin(data) << 3 | minFraction(data));
    }

    public static short meshYMax(long data) {
        return (short) (RenderDataPointUtil.getYMax(data) << 3 | maxFraction(data));
    }

    public static boolean meshZeroHeight(long data) {
        return RenderDataPointUtil.hasZeroHeight(data) && (!DhExactFlags.colorBits || minFraction(data) == 0 && maxFraction(data) == 0);
    }

    public static int minFraction(long data) {
        return (int) (data >>> 32 & 3) | (int) (data >>> 40 & 1) << 2;
    }

    public static int maxFraction(long data) {
        return (int) (data >>> 41 & 1) | (int) (data >>> 48 & 3) << 1;
    }

    // ========== Refining a section ==========

    public static ColumnRenderSource refine(FullDataSourceV2 full, IClientLevelWrapper level, ColumnRenderSource source) {
        if (source == null || source.isEmpty() || ((DhEighths) source).lsl$eighths()) return source;
        if (!supportedHeight(level.getMinHeight(), level.getMaxHeight())) {
            if (WARNED_DIMENSIONS.add(level.getDhIdentifier())) {
                Lsl.LOGGER.warn("[LSL/DH] {} is taller than {} blocks: exact layer heights unavailable there{}",
                    level.getDhIdentifier(), DhExactFlags.colorBits ? 4096 : 512,
                    DhExactFlags.colorBits ? "" : " (dh_exact_layers_color_bits lifts this to 4096)");
            }
            return source;
        }

        Work work = new Work(full, level, source);
        int baseX = DhSectionPos.getMinCornerBlockX(full.getPos());
        int baseZ = DhSectionPos.getMinCornerBlockZ(full.getPos());
        int detail = full.getDataDetailLevel();
        int capCount = 0;
        int slotsBefore = source.maxVerticalSliceCount;

        // The texture palette is rebuilt as columns are rewritten: caps bring their own textures.
        short[] nativePalette = source.texturePalette.toShortArray();
        Short2ByteOpenHashMap palette = new Short2ByteOpenHashMap();
        source.texturePalette.clear();
        source.texturePalette.add((short) 0);
        palette.put((short) 0, (byte) 0);

        for (int x = 0; x < WIDTH; x++) {
            for (int z = 0; z < WIDTH; z++) {
                int stride = source.maxVerticalSliceCount;
                int offset = (x * WIDTH + z) * stride;
                long[] nativeData = source.renderDataContainer.elements();

                int nativeCount = 0;
                while (nativeCount < stride) {
                    long value = nativeData[offset + nativeCount];
                    if (!RenderDataPointUtil.doesDataPointExist(value)
                            || RenderDataPointUtil.getYMax(value) <= RenderDataPointUtil.getYMin(value)) break;
                    nativeCount++;
                }

                collectCaps(work, full, x, z, nativeData, offset, nativeCount, nativePalette);

                if (work.count == 0) {
                    for (int i = 0; i < nativeCount; i++) {
                        nativeData[offset + i] = scaled(nativeData[offset + i]);
                        if (source.hasTextureSetIds()) {
                            short texture = nativePalette[Byte.toUnsignedInt(source.textureSetPaletteIndices.getByte(offset + i))];
                            source.textureSetPaletteIndices.set(offset + i, paletteIndex(source, palette, texture));
                        }
                    }
                    Arrays.fill(nativeData, offset + nativeCount, offset + stride, 0L);
                } else {
                    work.position.setX(baseX + (x << detail));
                    work.position.setZ(baseZ + (z << detail));
                    LongColumn result = mergeCaps(work, full, level, source, nativeData, offset, nativeCount, nativePalette);

                    if (result.size > stride) {
                        grow(source, result.size);
                        stride = source.maxVerticalSliceCount;
                        offset = (x * WIDTH + z) * stride;
                        nativeData = source.renderDataContainer.elements();
                    }
                    System.arraycopy(result.data, 0, nativeData, offset, result.size);
                    Arrays.fill(nativeData, offset + result.size, offset + stride, 0L);
                    if (source.hasTextureSetIds()) {
                        for (int i = 0; i < result.size; i++) {
                            source.textureSetPaletteIndices.set(offset + i, paletteIndex(source, palette, result.textures[i]));
                        }
                    }
                    capCount += work.count;
                    DhStats.CAP_COLUMNS.increment();
                    DhStats.CAPS_MAX_COLUMN.accumulate(work.count);
                }

                int columnIndex = x * WIDTH + z;
                source.voidRenderDataContainer.set(columnIndex, scaled(source.voidRenderDataContainer.getLong(columnIndex)));
            }
        }

        ((DhEighths) source).lsl$eighths(true);
        DhStats.SLOTS_BEFORE.add(slotsBefore);
        DhStats.SLOTS_AFTER.add(source.maxVerticalSliceCount);
        DhStats.SLOTS_MAX.accumulate(source.maxVerticalSliceCount);
        DhStats.CAPS.add(capCount);
        if (capCount > 0 && ANNOUNCED.compareAndSet(false, true)) {
            Lsl.LOGGER.info("[LSL/DH] exact layer heights active ({}: {} layer caps in the first refined section)",
                level.getDhIdentifier(), capCount);
        }
        return source;
    }

    /** The visible partial layers of one column, top down, into {@code work}. */
    private static void collectCaps(Work work, FullDataSourceV2 full, int x, int z, long[] nativeData, int offset, int nativeCount,
                                    short[] nativePalette) {
        work.count = 0;
        LongArrayList column = full.getColumnAtRelPos(x, z);
        if (nativeCount == 0 || column == null) return;
        int visibleIndex = 0;

        for (int i = 0; i < column.size(); i++) {
            long value = column.getLong(i);
            LayerInfo layer = work.info(FullDataPointUtil.getId(value));
            if (layer.eighths == 0) continue;
            int bottom = FullDataPointUtil.getBottomY(value);
            // Vanilla snow on a dry partial layer is drawn with that layer's cap (below); snow on
            // anything else gets a cap of its own.
            if (layer.snow && restsOnDryLayer(work, column, i, bottom)) continue;

            int top = bottom + FullDataPointUtil.getHeight(value);
            // Only the top block of the run: layers do not stack on themselves, so a taller run is
            // DH's downsampling stretching the top block's id over the span it merged (up to the
            // whole column in distant LODs). A cap per block there meant 100+ caps in one column,
            // and the section's slot count grown to fit it, which made mesh builds 20x slower.
            int y = top - 1;
            while (visibleIndex < nativeCount && RenderDataPointUtil.getYMin(nativeData[offset + visibleIndex]) > y) {
                visibleIndex++;
            }
            if (visibleIndex >= nativeCount) return;
            long visible = nativeData[offset + visibleIndex];
            // DH drops snow of 1-3 layers and colours the block under it instead (snow of 4-7 is
            // a full block): such snow is visible when that block's top is.
            int visibleTop = layer.snow && layer.eighths <= 3 ? y : y + 1;
            if (RenderDataPointUtil.getYMax(visible) < visibleTop) continue;

            // Vanilla snow resting on the layer is drawn as part of its cap.
            LayerInfo snow = null;
            long snowValue = 0L;
            if (!layer.wet && i > 0) {
                long above = column.getLong(i - 1);
                if (FullDataPointUtil.getBottomY(above) == y + 1) {
                    LayerInfo candidate = work.info(FullDataPointUtil.getId(above));
                    if (candidate.snow) {
                        snow = candidate;
                        snowValue = above;
                    }
                }
            }
            // DH's own look for this block: its colour, and the texture and material that go with
            // it. Both can come from a plant on top (DH colours the block under a plant like the
            // plant), so they are not the layer's own and certainly not its full block's: a full
            // grass block's texture has dirt sides, which turned every small step brown.
            short texture = work.source.hasTextureSetIds()
                ? nativePalette[Byte.toUnsignedInt(work.source.textureSetPaletteIndices.getByte(offset + visibleIndex))] : 0;
            work.append(y, value, layer, snowValue, snow, RenderDataPointUtil.getColor(visible), texture,
                RenderDataPointUtil.getBlockMaterialId(visible));
        }
    }

    /** Whether the snow at column index {@code i} sits right on a dry partial layer, which draws it. */
    private static boolean restsOnDryLayer(Work work, LongArrayList column, int i, int bottom) {
        if (i + 1 >= column.size()) return false;
        long below = column.getLong(i + 1);
        if (FullDataPointUtil.getBottomY(below) + FullDataPointUtil.getHeight(below) != bottom) return false;
        LayerInfo layer = work.info(FullDataPointUtil.getId(below));
        return layer.eighths > 0 && !layer.snow && !layer.wet;
    }

    /** DH's column with each cap's block cut out and the caps inserted, sorted top down. */
    private static LongColumn mergeCaps(Work work, FullDataSourceV2 full, IClientLevelWrapper level, ColumnRenderSource source,
                                        long[] nativeData, int offset, int nativeCount, short[] nativePalette) {
        LongColumn remaining = work.remaining;
        LongColumn caps = work.caps;
        LongColumn result = work.result;
        remaining.size = 0;
        caps.size = 0;
        result.size = 0;

        int firstCut = 0;
        for (int i = 0; i < nativeCount; i++) {
            long value = nativeData[offset + i];
            short texture = source.hasTextureSetIds()
                ? nativePalette[Byte.toUnsignedInt(source.textureSetPaletteIndices.getByte(offset + i))] : 0;
            int bottom = RenderDataPointUtil.getYMin(value) * 8;
            int cursor = RenderDataPointUtil.getYMax(value) * 8;
            while (firstCut < work.count && work.bottoms[firstCut] * 8 >= cursor) firstCut++;
            for (int cut = firstCut; cut < work.count; cut++) {
                int cutBottom = work.bottoms[cut] * 8;
                int cutTop = cutBottom + (work.snows[cut] == null ? 8 : 16);
                if (cutTop <= bottom) break;
                if (cutBottom < cursor) {
                    remaining.add(value, texture, Math.max(bottom, cutTop), cursor);
                    cursor = Math.max(bottom, Math.min(cursor, cutBottom));
                }
            }
            remaining.add(value, texture, bottom, cursor);
        }

        for (int i = 0; i < work.count; i++) {
            LayerInfo layer = work.layers[i];
            long value = work.values[i];
            int y = work.bottoms[i];
            int bottom = y * 8;
            int top = bottom + layer.eighths;
            int sky = FullDataPointUtil.getSkyLight(value);
            int light = FullDataPointUtil.getBlockLight(value);
            work.position.setY(y + level.getMinHeight());
            // DH already coloured this block's own segment, biome blending included: reuse it.
            // A full getBlockColor per cap (thousands per section) ran on DH's worker threads and
            // cut LOD generation speed by about five.
            boolean nativeLook = work.colors[i] != 0;
            int color = nativeLook ? work.colors[i] | 0xFF000000
                : level.getBlockColor(work.position, layer.biome, full, layer.appearance) | 0xFF000000;
            short texture = nativeLook ? work.textures[i] : layer.texture;
            byte material = nativeLook ? work.materials[i] : layer.material;
            work.capTextures[i] = texture;

            if (layer.wet) {
                work.water();
                int waterColor = work.waterColors.computeIfAbsent(layer.biome,
                    biome -> level.getBlockColor(work.position, biome, full, work.water));
                caps.add(point(top, bottom + 8, waterColor, sky, light, EDhApiBlockMaterial.WATER.index), work.waterTexture, top, bottom + 8);
            }

            LayerInfo snow = work.snows[i];
            if (snow != null) {
                work.position.setY(y + 1 + level.getMinHeight());
                int snowColor = work.snowColors.computeIfAbsent(snow.biome,
                    biome -> level.getBlockColor(work.position, biome, full, snow.appearance) | 0xFF000000);
                long snowValue = work.snowValues[i];
                int snowTop = top + snow.eighths;
                caps.add(point(top, snowTop, snowColor, FullDataPointUtil.getSkyLight(snowValue),
                    FullDataPointUtil.getBlockLight(snowValue), EDhApiBlockMaterial.SNOW.index), snow.texture, top, snowTop);
            }

            long cap = point(bottom, top, color, sky, light, material);
            work.capPoints[i] = cap;
            caps.add(cap, texture, bottom, top);
        }

        LongColumn natives = LslConfig.dhLayerSides ? paintSides(work, remaining) : remaining;
        int a = 0, b = 0;
        while (a < natives.size || b < caps.size) {
            boolean takeNative = b == caps.size
                || a < natives.size && yMax8(natives.data[a]) >= yMax8(caps.data[b]);
            LongColumn input = takeNative ? natives : caps;
            int index = takeNative ? a++ : b++;
            long value = input.data[index];
            result.add(value, input.textures[index], yMin8(value), yMax8(value));
        }
        return result;
    }

    /**
     * dh_layer_sides: the top of the opaque segment right under each dry cap, from one block below
     * the cap's top down to the cap, takes the cap's look (colour, material, texture, light). Each
     * step of a slope then shows a block's worth of layer on its side, as when DH drew the layer as
     * a full block, instead of a sliver of layer over the block below (dirt, mostly), which turned
     * distant grassy slopes brown. The heights are untouched.
     */
    private static LongColumn paintSides(Work work, LongColumn remaining) {
        LongColumn out = work.sides;
        out.size = 0;
        for (int r = 0; r < remaining.size; r++) {
            long value = remaining.data[r];
            short texture = remaining.textures[r];
            int min = yMin8(value);
            int max = yMax8(value);
            int cap = RenderDataPointUtil.getAlpha(value) == 255 ? capOn(work, max) : -1;
            if (cap < 0) {
                out.add(value, texture, min, max);
                continue;
            }
            LayerInfo layer = work.layers[cap];
            int sideBottom = Math.max(min, max - (8 - layer.eighths));
            out.add(work.capPoints[cap], work.capTextures[cap], sideBottom, max);
            out.add(value, texture, min, sideBottom);
        }
        return out;
    }

    /** The dry cap whose bottom is at eighth-unit {@code y}, or -1. */
    private static int capOn(Work work, int y) {
        for (int i = 0; i < work.count; i++) {
            if (!work.layers[i].wet && work.bottoms[i] * 8 == y) return i;
        }
        return -1;
    }

    /** More vertical slots per column: caps add segments. */
    private static void grow(ColumnRenderSource source, int required) {
        int old = source.maxVerticalSliceCount;
        int next = Math.max(required, old + Math.max(1, old / 2));
        source.renderDataContainer.size(WIDTH * WIDTH * next);
        boolean textured = source.hasTextureSetIds();
        if (textured) source.textureSetPaletteIndices.size(WIDTH * WIDTH * next);
        long[] data = source.renderDataContainer.elements();
        byte[] textures = source.textureSetPaletteIndices.elements();
        for (int column = WIDTH * WIDTH - 1; column >= 0; column--) {
            System.arraycopy(data, column * old, data, column * next, old);
            Arrays.fill(data, column * next + old, (column + 1) * next, 0L);
            if (textured) {
                System.arraycopy(textures, column * old, textures, column * next, old);
                Arrays.fill(textures, column * next + old, (column + 1) * next, (byte) 0);
            }
        }
        source.maxVerticalSliceCount = next;
    }

    private static byte paletteIndex(ColumnRenderSource source, Short2ByteOpenHashMap palette, short texture) {
        if (palette.containsKey(texture)) return palette.get(texture);
        byte index = source.texturePalette.size() < 256 ? (byte) source.texturePalette.size() : 0;
        if (source.texturePalette.size() < 256) source.texturePalette.add(texture);
        palette.put(texture, index);
        return index;
    }

    // ========== Shader ==========

    /** DH's own terrain vertex shaders (GL and Blaze), with the packed Y decoded. */
    public static String patchShader(String path, String source) {
        if (source == null) return null;
        if (!path.equals("assets/distanthorizons/shaders/terrain/gl/vert.vert")
                && !path.equals("assets/distanthorizons/shaders/terrain/blaze/vert.vsh")) return source;
        if (source.contains("lslPosition")) return source;

        String result = source.replace("vPosition.xyz", "lslPosition")
            .replace("vPosition.y", "lslPosition.y")
            .replace("vPos = vPosition;", path.endsWith(".vsh")
                ? "vPos = lslPosition;"
                : "vPos = vec4(lslPosition, float(vPosition.a));");
        String decode = "\n    vec3 lslPosition = vec3(float(vPosition.x), "
            + "float(vPosition.y & 4095u) + float((vPosition.y >> 13u) & 7u) * 0.125, float(vPosition.z));\n";
        int main = result.indexOf("void main()");
        int brace = main < 0 ? -1 : result.indexOf('{', main);
        if (brace < 0) throw new IllegalStateException("Unsupported DH terrain shader: " + path);
        return result.substring(0, brace + 1) + decode + result.substring(brace + 1);
    }

    // ========== Working state ==========

    private static final class LayerInfo {
        static final LayerInfo NONE = new LayerInfo();
        int eighths;
        boolean wet;
        boolean snow;
        byte material;
        short texture;
        IBlockStateWrapper appearance;
        IBiomeWrapper biome;
    }

    private static final class LongColumn {
        long[] data = new long[16];
        short[] textures = new short[16];
        int size;

        void add(long value, short texture, int min, int max) {
            if (max <= min) return;
            if (size == data.length) {
                data = Arrays.copyOf(data, size * 2);
                textures = Arrays.copyOf(textures, size * 2);
            }
            data[size] = withBounds(value, min, max);
            textures[size] = texture;
            size++;
        }
    }

    private static final class Work {
        final FullDataSourceV2 full;
        final IClientLevelWrapper level;
        final ColumnRenderSource source;
        final LayerInfo[] infos;
        final DhBlockPosMutable position = new DhBlockPosMutable();
        final LongColumn remaining = new LongColumn();
        final LongColumn caps = new LongColumn();
        final LongColumn result = new LongColumn();
        final LongColumn sides = new LongColumn();
        int[] bottoms = new int[16];
        long[] values = new long[16];
        /** Each cap's render point (mergeCaps), for paintSides. */
        long[] capPoints = new long[16];
        short[] capTextures = new short[16];
        /** DH's own texture and material for each cap's block (collectCaps). */
        short[] textures = new short[16];
        byte[] materials = new byte[16];
        long[] snowValues = new long[16];
        LayerInfo[] layers = new LayerInfo[16];
        LayerInfo[] snows = new LayerInfo[16];
        int[] colors = new int[16];
        /** Water and snow colours, once per biome per section. */
        final java.util.Map<IBiomeWrapper, Integer> waterColors = new java.util.HashMap<>();
        final java.util.Map<IBiomeWrapper, Integer> snowColors = new java.util.HashMap<>();
        IBlockStateWrapper water;
        short waterTexture;
        int count;

        Work(FullDataSourceV2 full, IClientLevelWrapper level, ColumnRenderSource source) {
            this.full = full;
            this.level = level;
            this.source = source;
            this.infos = new LayerInfo[full.mapping.size()];
        }

        /** What a mapping id is: a partial layer, vanilla snow, or neither. */
        LayerInfo info(int id) {
            if (id < 0 || id >= infos.length) return LayerInfo.NONE;
            LayerInfo cached = infos[id];
            if (cached != null) return cached;

            IBlockStateWrapper wrapped = full.mapping.getBlockStateWrapper(id);
            if (!(wrapped.getWrappedMcObject() instanceof BlockState state)) return infos[id] = LayerInfo.NONE;
            boolean snow = state.getBlock() == Blocks.SNOW;
            int eighths = snow ? state.getValue(SnowLayerBlock.LAYERS) : DhLayers.partialEighths(state);
            if (eighths == 0 || eighths >= 8) return infos[id] = LayerInfo.NONE;

            LayerInfo info = new LayerInfo();
            info.snow = snow;
            info.eighths = eighths;
            info.biome = full.mapping.getBiomeWrapper(id);
            info.wet = !snow && DhLayers.isWaterlogged(state);
            BlockState appearance = snow ? state.setValue(SnowLayerBlock.LAYERS, 8) : DhLayers.appearanceOf(state);
            info.appearance = (IBlockStateWrapper) DhApi.Delayed.wrapperFactory.getBlockStateWrapper(new Object[]{appearance}, level);
            info.material = snow ? EDhApiBlockMaterial.SNOW.index : info.appearance.getMaterialId();
            if (source.hasTextureSetIds()) {
                info.texture = BlockTextureRegistry.INSTANCE.getOrRegisterBlockStateSetId(info.appearance);
            }
            return infos[id] = info;
        }

        void append(int bottom, long value, LayerInfo layer, long snowValue, LayerInfo snow, int nativeColor,
                    short nativeTexture, byte nativeMaterial) {
            if (count == bottoms.length) {
                int size = count * 2;
                colors = Arrays.copyOf(colors, size);
                bottoms = Arrays.copyOf(bottoms, size);
                values = Arrays.copyOf(values, size);
                capPoints = Arrays.copyOf(capPoints, size);
                capTextures = Arrays.copyOf(capTextures, size);
                textures = Arrays.copyOf(textures, size);
                materials = Arrays.copyOf(materials, size);
                snowValues = Arrays.copyOf(snowValues, size);
                layers = Arrays.copyOf(layers, size);
                snows = Arrays.copyOf(snows, size);
            }
            bottoms[count] = bottom;
            values[count] = value;
            layers[count] = layer;
            snowValues[count] = snowValue;
            snows[count] = snow;
            colors[count] = nativeColor;
            textures[count] = nativeTexture;
            materials[count] = nativeMaterial;
            count++;
        }

        void water() {
            if (water != null) return;
            water = ((IWrapperFactory) DhApi.Delayed.wrapperFactory).getWaterBlockStateWrapper(level);
            if (source.hasTextureSetIds()) waterTexture = BlockTextureRegistry.INSTANCE.getOrRegisterBlockStateSetId(water);
        }
    }
}
