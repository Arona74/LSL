package io.arona74.lsl.mixin.voxy;

import net.minecraft.client.color.block.BlockColor;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * The game's own colour providers (Roxy hands Voxy a proxy of each; TintCapture needs the real one).
 * NeoForge keeps them in a map by block, not vanilla's IdMapper (Roxy converts it for Voxy).
 */
@Mixin(BlockColors.class)
public interface BlockColorsAccessor {
    @Accessor("blockColors")
    Map<Block, BlockColor> lsl$blockColors();
}
