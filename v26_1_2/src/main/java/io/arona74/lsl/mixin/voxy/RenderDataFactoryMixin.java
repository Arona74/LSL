package io.arona74.lsl.mixin.voxy;

import io.arona74.lsl.LslConfig;
import io.arona74.lsl.voxy.BlockTables;
import io.arona74.lsl.voxy.PlantShift;
import io.arona74.lsl.voxy.ShaderPatch;
import io.arona74.lsl.voxy.TallPlants;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.building.RenderDataFactory;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * After the mesher has turned the section's ids into partial quad words (LOD0 only): writes each
 * plant's drop into them (PlantShift), then adds the upper slices of tall plants (TallPlants).
 */
@Mixin(value = RenderDataFactory.class, remap = false)
public abstract class RenderDataFactoryMixin {
    @Shadow @Final private WorldEngine world;
    @Shadow @Final private ModelFactory modelMan;
    @Shadow @Final private long[] sectionData;
    @Shadow @Final private int[] nonOpaqueMasks;

    @Unique private WorldSection lsl$section;

    @Inject(method = "generateMesh", at = @At("HEAD"))
    private void lsl$captureSection(WorldSection section, CallbackInfoReturnable<BuiltSection> cir) {
        this.lsl$section = section;
    }

    @Inject(method = "prepareSectionData", at = @At("RETURN"), cancellable = true)
    private void lsl$plants(long[] rawSectionData, CallbackInfoReturnable<Integer> cir) {
        WorldSection section = this.lsl$section;
        if (section == null || section.lvl != 0) return;
        int result = cir.getReturnValue();
        if (result < 0) return; // a model is not baked yet: Voxy retries the section
        BlockTables tables = BlockTables.of(this.world.getMapper());
        boolean shift = LslConfig.voxyPlantOffset && ShaderPatch.usable();
        if (shift) PlantShift.apply(rawSectionData, this.sectionData, tables);
        if (!LslConfig.voxyTallPlants) return;

        WorldSection under = null;
        try {
            under = this.world.acquireIfExists(0, section.x, section.y - 1, section.z);
            long[] below = under != null ? under._unsafeGetRawDataArray() : null;
            int extra = TallPlants.apply(rawSectionData, below, this.sectionData, this.nonOpaqueMasks,
                this.modelMan._unsafeRawAccess(), this.modelMan, tables, shift);
            if (extra != 0) cir.setReturnValue(extra < 0 ? extra : result | extra);
        } finally {
            if (under != null) under.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
        }
    }
}
