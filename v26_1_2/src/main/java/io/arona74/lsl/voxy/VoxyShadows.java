package io.arona74.lsl.voxy;

import io.arona74.lsl.Lsl;
import io.arona74.lsl.LslConfig;
import io.arona74.lsl.mixin.voxy.MdicRendererAccessor;
import io.arona74.lsl.mixin.voxy.RenderPipelineAccessor;
import io.arona74.lsl.mixin.voxy.SectionRendererAccessor;
import io.arona74.lsl.mixin.voxy.VoxyRenderSystemAccessor;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.gl.GlVertexArray;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import static org.lwjgl.opengl.ARBIndirectParameters.GL_PARAMETER_BUFFER_ARB;
import static org.lwjgl.opengl.ARBIndirectParameters.glMultiDrawElementsIndirectCountARB;
import static org.lwjgl.opengl.GL43C.*;

/**
 * {@code voxy_shadows} (experimental, 26.1.2): the Voxy LODs drawn into Iris's shadow map.
 *
 * <p>Voxy never draws during Iris's shadow pass ({@code VoxyRenderSystem.getViewport()} returns null
 * then), and its shader-pack interface has no shadow program, so LODs receive the pack's shadows
 * but never cast any. Here, at the start of Sodium's cut-out pass while Iris draws the shadow map
 * (SodiumShadowMixin), Voxy's own steps run for a separate view built from the shadow camera:
 * <ol>
 *   <li>an empty depth pyramid (nothing is occluded: the sun sees past the player's view),</li>
 *   <li>Voxy's traversal, which picks the LOD sections inside the shadow frustum,</li>
 *   <li>{@code buildDrawCalls}, which turns them into draw commands,</li>
 * </ol>
 * then the commands are drawn into the bound shadow framebuffer with LSL's own depth-only shader:
 * Voxy's vertex shader (so LSL's own vertex patches apply) and a fragment shader that keeps the
 * models' cut-outs (leaves let light through) and drops everything closer than the vanilla render
 * distance, where the real chunks already cast their shadows. Every GL binding touched is restored
 * afterwards. A failure turns the pass off for the rest of the session and is logged once.
 *
 * <p>Shader packs place things in their shadow map their own way (Bliss distorts it for more detail
 * near the player and squeezes its depth by 6), and the LODs must match or their shadows land in
 * the wrong spots: {@code voxy_shadow_projection} picks the transform, AUTO by the pack's name.
 *
 * <p>Known limits: Voxy chooses which faces of a section to draw from the camera position (the
 * player), not the sun, so a shadow can lose a sliver where the sun is low. Bliss's world curvature
 * (CURVATURE_AMOUNT) is deliberately not applied: Bliss curves its shadow casters to match the
 * curved vanilla terrain, but the Voxy pack interface has no vertex hook, so Voxy terrain is drawn
 * flat and flat LOD casters are what match it.
 */
public final class VoxyShadows {
    private VoxyShadows() {}

    private static AbstractRenderPipeline owner;
    private static MDICViewport viewport;
    private static DepthFramebuffer occluder;
    private static Shader shader;
    private static boolean failed;
    private static boolean announced;

    private static final int RADIUS_LOCATION = 10;
    private static final int MODE_LOCATION = 11;
    private static final int DISTORT_A_LOCATION = 12;
    private static final int DISTORT_B_LOCATION = 13;
    /** Iris's selected pack decides AUTO; read again whenever Voxy rebuilds its pipeline (a pack switch does). */
    private static boolean packIsBliss;

    private static final String FRAGMENT = """
        #version 460 core
        layout(binding = 0) uniform sampler2D blockModelAtlas;
        layout(location = 0) in flat uvec4 interData;
        layout(location = 1) in vec2 uv;
        layout(location = 6) in vec3 lslRel;
        layout(location = 10) uniform float lslInnerRadius;

        vec2 getBaseUV() {
            uint face = (interData.x>>4)&7u;
            uint modelId = interData.x>>16;
            vec2 modelUV = vec2(modelId&0xFFu, (modelId>>8)&0xFFu)*(1.0/(256.0));
            return modelUV + (vec2(face>>1, face&1u) * (1.0/(vec2(3.0, 2.0)*256.0)));
        }

        void main() {
            if (dot(lslRel.xz, lslRel.xz) < lslInnerRadius*lslInnerRadius) discard;
            vec2 tile;
            vec2 uv2 = modf(uv, tile)*(1.0/(vec2(3.0,2.0)*256.0));
            if (any(notEqual(clamp(tile, vec2(0), vec2((interData.x>>8)&0xFu, (interData.x>>12)&0xFu)), tile))) discard;
            if ((interData.x&1u)==1u && textureLod(blockModelAtlas, uv2 + getBaseUV(), 0).a <= 0.1f) discard;
        }
        """;

    public static void render(Matrix4fc projection, Matrix4fc modelView, double x, double y, double z, FogParameters fog) {
        if (failed || !LslConfig.voxyShadows) return;
        VoxyRenderSystem system = IVoxyRenderSystemHolder.getNullable();
        if (system == null) return;
        GlSnapshot snapshot = null;
        try {
            AbstractRenderPipeline pipeline = ((VoxyRenderSystemAccessor) (Object) system).lsl$pipeline();
            if (!(((RenderPipelineAccessor) pipeline).lsl$sectionRenderer() instanceof MDICSectionRenderer renderer)) return;
            if (owner != pipeline) {
                free();
                owner = pipeline;
                packIsBliss = selectedPack().toLowerCase(java.util.Locale.ROOT).contains("bliss");
            }
            BasicSectionGeometryData geometry = (BasicSectionGeometryData) ((SectionRendererAccessor) renderer).lsl$geometry();
            if (geometry.getSectionCount() == 0) return;

            snapshot = GlSnapshot.take();
            draw(system, pipeline.properties, renderer, geometry, projection, modelView, x, y, z, fog, snapshot);
            if (!announced) {
                announced = true;
                Lsl.LOGGER.info("[LSL/Voxy] LOD shadows drawn into the shadow map ({}x{})", snapshot.viewport[2], snapshot.viewport[3]);
            }
        } catch (Throwable t) {
            failed = true;
            Lsl.LOGGER.error("[LSL/Voxy] LOD shadows failed and are off for this session", t);
        } finally {
            if (snapshot != null) snapshot.restore();
        }
    }

    private static void draw(VoxyRenderSystem system, RenderProperties properties, MDICSectionRenderer renderer,
                             BasicSectionGeometryData geometry, Matrix4fc projection, Matrix4fc modelView,
                             double x, double y, double z, FogParameters fog, GlSnapshot snapshot) {
        int width = snapshot.viewport[2], height = snapshot.viewport[3];
        if (width <= 0 || height <= 0) return;

        boolean bliss = switch (LslConfig.voxyShadowProjection == null ? LslConfig.ShadowProjection.AUTO : LslConfig.voxyShadowProjection) {
            case BLISS -> true;
            case PLAIN -> false;
            case AUTO -> packIsBliss;
        };
        // Bliss squeezes shadow depth by 6, so its shadow map holds casters 6x further along the sun's
        // direction. Squeezed in the projection, Voxy's traversal culls with that deeper box too.
        Matrix4f shadowProjection = bliss ? new Matrix4f().scale(1, 1, 1.0f / 6.0f).mul(projection) : new Matrix4f(projection);

        if (viewport == null) viewport = renderer.createViewport();
        viewport.setVanillaProjection(projection)
            .setProjection(shadowProjection)
            .setModelView(modelView)
            .setCamera(x, y, z)
            .setScreenSize(width, height)
            .setFogParameters(fog)
            .update();
        viewport.frameId = (viewport.frameId + 1) & 0x7fffffff;

        // Nothing occludes: a depth buffer cleared to "far" for the pyramid and the cull test.
        if (occluder == null) occluder = new DepthFramebuffer();
        occluder.resize(width, height);
        occluder.clear(properties.clearDepth());
        viewport.hiZBuffer.buildMipChain(occluder.getDepthTex().id, width, height);

        ((VoxyRenderSystemAccessor) (Object) system).lsl$traversal().doTraversal(viewport);

        occluder.bind();
        glViewport(0, 0, width, height);
        renderer.buildDrawCalls(viewport);

        // Into the shadow map.
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, snapshot.drawFramebuffer);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, snapshot.readFramebuffer);
        glViewport(snapshot.viewport[0], snapshot.viewport[1], width, height);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(properties.closerEqualDepthCompare());
        glDepthMask(true);
        glColorMask(false, false, false, false);
        glDisable(GL_CULL_FACE);
        glDisable(GL_BLEND);

        if (shader == null) shader = compile(properties);
        shader.bind();
        glUniform1f(RADIUS_LOCATION, Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0f);
        glUniform1i(MODE_LOCATION, bliss ? 1 : 0);
        if (bliss) {
            // Bliss's Shadow_Params.glsl. Iris's shadow projection is an orthographic box whose half
            // width is the shadow distance, which Bliss's d0 depends on.
            float shadowDistance = Math.abs(projection.m00()) > 1e-6f ? 1.0f / Math.abs(projection.m00()) : 128.0f;
            double d0 = 0.04 + (1.0 - Math.max(0.0, Math.min(1.0, shadowDistance - 64.0))) * 0.1;
            double a = Math.exp(d0);
            glUniform1f(DISTORT_A_LOCATION, (float) a);
            glUniform1f(DISTORT_B_LOCATION, (float) ((Math.exp(0.61) - a) * 150.0 / 128.0));
        }

        glBindVertexArray(GlVertexArray.STATIC_VAO);
        glBindBufferBase(GL_UNIFORM_BUFFER, 0, ((MdicRendererAccessor) (Object) renderer).lsl$uniform().id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, geometry.getGeometryBuffer().id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, geometry.getMetadataBuffer().id);
        ((SectionRendererAccessor) renderer).lsl$modelStore().bind(3, 4, 0);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, viewport.positionScratchBuffer.id);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, SharedIndexBuffer.INSTANCE.id());
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, viewport.drawCallBuffer.id);
        glBindBuffer(GL_PARAMETER_BUFFER_ARB, viewport.drawCountCallBuffer.id);

        glMemoryBarrier(GL_COMMAND_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
        glProvokingVertex(GL_FIRST_VERTEX_CONVENTION);
        int maxDraws = Math.min((int) (geometry.getSectionCount() * 4.4 + 128), MDICSectionRenderer.OPAQUE_DRAW_COUNT);
        glMultiDrawElementsIndirectCountARB(GL_TRIANGLES, GL_UNSIGNED_SHORT, 0, 4 * 3, maxDraws, 0);
    }

    /** Voxy's terrain vertex shader passing the camera-relative position on, and the depth-only fragment shader. */
    private static Shader compile(RenderProperties properties) {
        String vertex = ShaderLoader.parse("voxy:lod/gl46/quads3.vert");
        String output = "layout(location = 0) out flat uvec4 interData;";
        String anchor = "interData = quad.attributeData;";
        if (!vertex.contains(output) || !vertex.contains(anchor)) {
            throw new IllegalStateException("Voxy's quads3.vert is not the expected shape");
        }
        vertex = vertex.replace(output, output + """

                layout(location = 6) out vec3 lslRel;
                layout(location = 11) uniform int lslShadowMode;
                layout(location = 12) uniform float lslDistortA;
                layout(location = 13) uniform float lslDistortB;
                """)
            .replace(anchor, anchor + """

                {
                    vec2 lslMask = vec2((cornerId>>1)&1u, cornerId&1u)*quad.lodScale;
                    vec3 lslPoint = quad.basePoint + swizzelDataAxis(quad.axis, vec3(quad.quadSizeAddin*lslMask, 0));
                    lslRel = lslPoint - cameraSubPos;
                }
                if (lslShadowMode == 1) {
                    // Bliss's BiasShadowProjection (its depth squeeze is in the projection already).
                    gl_Position.xy /= log(length(gl_Position.xy) * lslDistortB + lslDistortA) * 1.8;
                }
                """);
        return Shader.make()
            .apply(properties::apply)
            .define("PATCHED_SHADER")
            .addSource(ShaderType.VERTEX, vertex)
            .addSource(ShaderType.FRAGMENT, FRAGMENT)
            .compile();
    }

    /** Iris's selected shaderpack (config/iris.properties), or "" when unknown. */
    private static String selectedPack() {
        java.nio.file.Path file = Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("iris.properties");
        java.util.Properties properties = new java.util.Properties();
        try (var in = java.nio.file.Files.newInputStream(file)) {
            properties.load(in);
            return properties.getProperty("shaderPack", "");
        } catch (Exception e) {
            return "";
        }
    }

    private static void free() {
        if (viewport != null) viewport.delete();
        if (occluder != null) occluder.free();
        if (shader != null) shader.free();
        viewport = null;
        occluder = null;
        shader = null;
    }
}
