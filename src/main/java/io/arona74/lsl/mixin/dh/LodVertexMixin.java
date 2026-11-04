/*
 * Adapted from Geostep (verdantlayers) 1.3, MIT License, Copyright (c) 2026 Verdant Layers contributors.
 * The full license text is in compat/dh/DhLayerMesh.java and THIRD_PARTY_NOTICES.md.
 */
package io.arona74.lsl.mixin.dh;

import io.arona74.lsl.dh.DhEighths;
import io.arona74.lsl.dh.DhLayerMesh;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Packs vertex Y as whole block + eighths for sections rewritten in eighths (DhLayerMesh.packVertexY). */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodQuadBuilder", remap = false)
public abstract class LodVertexMixin implements DhEighths {
    @Unique
    private boolean lsl$eighths;

    @Override
    public boolean lsl$eighths() {
        return lsl$eighths;
    }

    @Override
    public void lsl$eighths(boolean eighths) {
        this.lsl$eighths = eighths;
    }

    @ModifyVariable(method = "putVertex", at = @At("HEAD"), argsOnly = true, ordinal = 1, remap = false)
    private short lsl$packY(short y) {
        return lsl$eighths ? DhLayerMesh.packVertexY(y) : y;
    }
}
