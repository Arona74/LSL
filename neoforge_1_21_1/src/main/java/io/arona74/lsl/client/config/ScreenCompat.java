package io.arona74.lsl.client.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

/**
 * Version seam for the config screen (NeoForge, Minecraft 1.21.1): the Fabric 1.21.1 one without
 * its Fabric command helper.
 */
public final class ScreenCompat {
    private ScreenCompat() {}

    /** The values go in before the initial one, which is looked up among them when it is set. */
    public static <T> CycleButton.Builder<T> cycle(Function<T, Component> format, List<T> values, T initial) {
        return CycleButton.builder(format).withValues(values).withInitialValue(initial);
    }

    public static CycleButton.Builder<Boolean> onOff(boolean initial) {
        return CycleButton.onOffBuilder(initial);
    }

    public static void setScreen(Screen screen) {
        Minecraft.getInstance().setScreen(screen);
    }

    public static void openPath(Path path) {
        net.minecraft.Util.getPlatform().openPath(path);
    }

    /** Runs on the client thread after the current tick, e.g. once the chat screen has closed. */
    public static void schedule(Runnable task) {
        Minecraft.getInstance().tell(task);
    }
}
