package io.arona74.lsl;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Minecraft API differences for the shared code ({@code ResourceLocation} era, {@code BushBlock} plants); NeoForge 1.21.1. */
public final class Compat {
    private Compat() {}

    /** This module carries the Voxy hooks (Voxy 0.2.16 through Roxy; see VoxyGate). */
    public static final boolean VOXY_HOOKS = true;

    /** A resource id. */
    public static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    /** Registry id of a block, as {@code "namespace:path"}, or null. */
    public static String blockId(Block block) {
        var key = BuiltInRegistries.BLOCK.getKey(block);
        return key == null ? null : key.toString();
    }

    /** The block registered under {@code "namespace:path"}, or {@link Blocks#AIR}. */
    public static Block blockFromId(String id) {
        ResourceLocation parsed = ResourceLocation.tryParse(id);
        if (parsed == null) return Blocks.AIR;
        return BuiltInRegistries.BLOCK.get(parsed);
    }

    /** The vanilla plant base class (CR plants, flowers, grass...). */
    public static boolean isPlantBase(Block block) {
        return block instanceof net.minecraft.world.level.block.BushBlock;
    }
}
