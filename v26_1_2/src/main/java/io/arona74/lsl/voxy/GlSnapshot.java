package io.arona74.lsl.voxy;

import static org.lwjgl.opengl.ARBIndirectParameters.GL_PARAMETER_BUFFER_ARB;
import static org.lwjgl.opengl.ARBIndirectParameters.GL_PARAMETER_BUFFER_BINDING_ARB;
import static org.lwjgl.opengl.GL43C.*;

/**
 * The GL state the Voxy shadow pass touches, taken before and put back after, so Iris, Sodium and
 * Minecraft (whose state manager caches what it last bound) find everything as they left it.
 * Indexed buffer bindings keep their ranges.
 */
final class GlSnapshot {
    private static final int BUFFER_SLOTS = 16;
    private static final int TEXTURE_UNITS = 4;

    final int[] viewport = new int[4];
    int drawFramebuffer, readFramebuffer;
    private int program, vertexArray, activeTexture, provokingVertex;
    private int arrayBuffer, drawIndirect, dispatchIndirect, parameterBuffer, depthFunc;
    private boolean depthTest, depthMask, cullFace, blend, stencilTest;
    private final boolean[] colorMask = new boolean[4];
    private final int[] textures = new int[TEXTURE_UNITS], samplers = new int[TEXTURE_UNITS];
    private final int[] ssbo = new int[BUFFER_SLOTS], ubo = new int[BUFFER_SLOTS];
    private final long[] ssboStart = new long[BUFFER_SLOTS], ssboSize = new long[BUFFER_SLOTS];
    private final long[] uboStart = new long[BUFFER_SLOTS], uboSize = new long[BUFFER_SLOTS];

    private GlSnapshot() {}

    static GlSnapshot take() {
        GlSnapshot s = new GlSnapshot();
        glGetIntegerv(GL_VIEWPORT, s.viewport);
        s.drawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        s.readFramebuffer = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        s.program = glGetInteger(GL_CURRENT_PROGRAM);
        s.vertexArray = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        s.provokingVertex = glGetInteger(GL_PROVOKING_VERTEX);
        s.arrayBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
        s.drawIndirect = glGetInteger(GL_DRAW_INDIRECT_BUFFER_BINDING);
        s.dispatchIndirect = glGetInteger(GL_DISPATCH_INDIRECT_BUFFER_BINDING);
        s.parameterBuffer = glGetInteger(GL_PARAMETER_BUFFER_BINDING_ARB);
        s.depthFunc = glGetInteger(GL_DEPTH_FUNC);
        s.depthTest = glIsEnabled(GL_DEPTH_TEST);
        s.depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        s.cullFace = glIsEnabled(GL_CULL_FACE);
        s.blend = glIsEnabled(GL_BLEND);
        s.stencilTest = glIsEnabled(GL_STENCIL_TEST);
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var mask = stack.malloc(4);
            glGetBooleanv(GL_COLOR_WRITEMASK, mask);
            for (int i = 0; i < 4; i++) s.colorMask[i] = mask.get(i) != 0;
        }
        s.activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);
        for (int unit = 0; unit < TEXTURE_UNITS; unit++) {
            glActiveTexture(GL_TEXTURE0 + unit);
            s.textures[unit] = glGetInteger(GL_TEXTURE_BINDING_2D);
            s.samplers[unit] = glGetInteger(GL_SAMPLER_BINDING);
        }
        glActiveTexture(s.activeTexture);
        for (int i = 0; i < BUFFER_SLOTS; i++) {
            s.ssbo[i] = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i);
            s.ssboStart[i] = glGetInteger64i(GL_SHADER_STORAGE_BUFFER_START, i);
            s.ssboSize[i] = glGetInteger64i(GL_SHADER_STORAGE_BUFFER_SIZE, i);
            s.ubo[i] = glGetIntegeri(GL_UNIFORM_BUFFER_BINDING, i);
            s.uboStart[i] = glGetInteger64i(GL_UNIFORM_BUFFER_START, i);
            s.uboSize[i] = glGetInteger64i(GL_UNIFORM_BUFFER_SIZE, i);
        }
        return s;
    }

    void restore() {
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, this.drawFramebuffer);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, this.readFramebuffer);
        glViewport(this.viewport[0], this.viewport[1], this.viewport[2], this.viewport[3]);
        glUseProgram(this.program);
        glBindVertexArray(this.vertexArray);
        glProvokingVertex(this.provokingVertex);
        glBindBuffer(GL_ARRAY_BUFFER, this.arrayBuffer);
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, this.drawIndirect);
        glBindBuffer(GL_DISPATCH_INDIRECT_BUFFER, this.dispatchIndirect);
        glBindBuffer(GL_PARAMETER_BUFFER_ARB, this.parameterBuffer);
        glDepthFunc(this.depthFunc);
        set(GL_DEPTH_TEST, this.depthTest);
        glDepthMask(this.depthMask);
        set(GL_CULL_FACE, this.cullFace);
        set(GL_BLEND, this.blend);
        set(GL_STENCIL_TEST, this.stencilTest);
        glColorMask(this.colorMask[0], this.colorMask[1], this.colorMask[2], this.colorMask[3]);
        for (int unit = 0; unit < TEXTURE_UNITS; unit++) {
            glActiveTexture(GL_TEXTURE0 + unit);
            glBindTexture(GL_TEXTURE_2D, this.textures[unit]);
            glBindSampler(unit, this.samplers[unit]);
        }
        glActiveTexture(this.activeTexture);
        for (int i = 0; i < BUFFER_SLOTS; i++) {
            bind(GL_SHADER_STORAGE_BUFFER, i, this.ssbo[i], this.ssboStart[i], this.ssboSize[i]);
            bind(GL_UNIFORM_BUFFER, i, this.ubo[i], this.uboStart[i], this.uboSize[i]);
        }
    }

    private static void bind(int target, int index, int buffer, long start, long size) {
        if (buffer != 0 && size > 0) glBindBufferRange(target, index, buffer, start, size);
        else glBindBufferBase(target, index, buffer);
    }

    private static void set(int capability, boolean on) {
        if (on) glEnable(capability);
        else glDisable(capability);
    }
}
