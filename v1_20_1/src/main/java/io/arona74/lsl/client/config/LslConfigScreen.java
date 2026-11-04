package io.arona74.lsl.client.config;

import io.arona74.lsl.Lsl;
import io.arona74.lsl.LslConfig;
import io.arona74.lsl.config.ConfigDraft;
import io.arona74.lsl.config.ConfigOption;
import io.arona74.lsl.config.ConfigTab;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 1.20.1 copy of the config screen; the same as the shared one but for drawing its background
 * itself and the mouse wheel's older signature. The in-game config screen: one tab per {@link ConfigTab} along the top, the options of the open
 * tab in the left half, and in the right half a preview of the option under the mouse (an image,
 * its name and its description: {@link ConfigPreview}). Done / Cancel sit in the footer. Edits go
 * into a {@link ConfigDraft} and reach the game only on Done.
 *
 * <p>The option list and the preview panel are drawn by each version's {@link OptionRows} and
 * {@link PreviewPanel}, as drawing differs between versions. Switching tab opens a new screen on
 * the same draft.
 */
public class LslConfigScreen extends Screen {
    private static final int LIST_TOP = 50;
    private static final int FOOTER_HEIGHT = 32;

    /** Remembered for the session, so reopening the screen returns to where the user was. */
    private static ConfigTab lastTab = ConfigTab.DISTANT_HORIZONS;
    private static boolean showAdvanced;

    private final Screen lastScreen;
    private final ConfigDraft draft;
    private final ConfigTab tab;
    private ConfigPreview preview;
    private boolean discard;

    public LslConfigScreen(Screen parent) {
        this(parent, new ConfigDraft(), startTab());
    }

    private LslConfigScreen(Screen parent, ConfigDraft draft, ConfigTab tab) {
        super(Component.translatable("config.lsl.title"));
        this.lastScreen = parent;
        this.draft = draft;
        this.tab = tab;
        lastTab = tab;
    }

    @Override
    protected void init() {
        int titleWidth = font.width(title);
        addRenderableWidget(new StringWidget((width - titleWidth) / 2, 8, titleWidth, 9, title, font));

        List<ConfigTab> tabs = visibleTabs();
        int buttonWidth = Math.min(80, (width - 20) / tabs.size() - 4);
        int x = (width - tabs.size() * (buttonWidth + 4) + 4) / 2;
        for (ConfigTab t : tabs) {
            // The open tab is underlined; a tab whose mod is missing or unsupported is greyed out
            // and says why on hover.
            Component label = Component.translatable(t.langKey());
            if (t == tab) label = label.copy().withStyle(net.minecraft.ChatFormatting.UNDERLINE);
            Button button = addRenderableWidget(Button.builder(label, b -> {
                        if (t != tab) reopen(t);
                    }).bounds(x, 22, buttonWidth, 20).build());
            ConfigTab.Unusable why = t.unusable();
            if (why != null) {
                button.active = false;
                button.setTooltip(Tooltip.create(Component.translatable(why.langKey(), (Object[]) why.args())));
            }
            x += buttonWidth + 4;
        }

        int half = width / 2;
        int listBottom = height - FOOTER_HEIGHT;
        OptionRows list = new OptionRows(Minecraft.getInstance(), half, listBottom - LIST_TOP, LIST_TOP);
        ConfigWidgets widgets = new ConfigWidgets(draft, font);
        fillRows(list, widgets, tab, font);
        addRenderableWidget(list);

        preview = new ConfigPreview(widgets, tab, font);
        preview.setBounds(half + 4, LIST_TOP + 2, width - 10, listBottom - 2);
        addRenderableOnly(new PreviewPanel(preview, font));

        int y = height - 26;
        int fx = width / 2 - 179;
        addRenderableWidget(ScreenCompat.onOff(showAdvanced)
                .create(fx, y, 100, 20, Component.translatable("config.lsl.advanced"), (b, value) -> {
                    showAdvanced = value;
                    reopen(tab);
                })).setTooltip(Tooltip.create(Component.translatable("config.lsl.advanced.tooltip")));
        addRenderableWidget(Button.builder(Component.translatable("config.lsl.reset_tab"), b -> {
                    draft.resetToDefaults(tab);
                    reopen(tab);
                }).bounds(fx + 106, y, 70, 20)
                .tooltip(Tooltip.create(Component.translatable("config.lsl.reset_tab.tooltip"))).build());
        addRenderableWidget(Button.builder(Component.translatable("config.lsl.open_folder"),
                        b -> ScreenCompat.openPath(LslConfig.configDir()))
                .bounds(fx + 182, y, 50, 20)
                .tooltip(Tooltip.create(Component.translatable("config.lsl.open_folder.tooltip"))).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> {
                    discard = true;
                    onClose();
                }).bounds(fx + 238, y, 60, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(fx + 304, y, 60, 20).build());
    }

    /**
     * The open tab's rows: on a world generation tab the warning's heading first, then each group
     * under its heading, one option per row. A block-list box has no label of its own, so its name
     * gets a row above it.
     */
    static void fillRows(OptionRows list, ConfigWidgets widgets, ConfigTab tab, net.minecraft.client.gui.Font font) {
        if (tab.worldgen) list.add(new StringWidget(ConfigWidgets.WIDTH, ConfigWidgets.HEIGHT, ConfigWidgets.worldgenWarning(0), font));
        String group = null;
        for (ConfigOption<?> option : LslConfig.options()) {
            if (option.tab != tab || (option.advanced && !showAdvanced)) continue;
            if (!Objects.equals(option.group, group)) {
                group = option.group;
                if (group != null) {
                    list.add(new StringWidget(ConfigWidgets.WIDTH, ConfigWidgets.HEIGHT,
                            Component.translatable("config.lsl.group." + group), font));
                }
            }
            if (option.kind == ConfigOption.Kind.STRING_LIST) list.add(widgets.label(option));
            list.add(widgets.create(option));
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** The mouse wheel over the preview panel scrolls a long description. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scroll) {
        if (preview != null && preview.contains(mouseX, mouseY)) {
            preview.scroll(scroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scroll);
    }

    private void reopen(ConfigTab target) {
        ScreenCompat.setScreen(new LslConfigScreen(lastScreen, draft, target));
    }

    /** Done and Escape save, as in vanilla's options screens; Cancel discards. */
    @Override
    public void onClose() {
        if (!discard) commit(draft);
        ScreenCompat.setScreen(lastScreen);
    }

    static void commit(ConfigDraft draft) {
        List<ConfigOption<?>> changed = draft.commit();
        List<String> restart = changed.stream()
                .filter(o -> o.apply == ConfigOption.Apply.RESTART)
                .map(o -> o.key)
                .toList();
        if (!restart.isEmpty()) {
            Lsl.LOGGER.info("[LSL] Restart Minecraft for these changes to take effect: {}", restart);
        }
    }

    /** The tab the screen opens on: the last one if still usable, else the first usable one. */
    static ConfigTab startTab() {
        if (lastTab.usable()) return lastTab;
        List<ConfigTab> tabs = visibleTabs();
        for (ConfigTab t : tabs) {
            if (t.usable()) return t;
        }
        return tabs.get(0);
    }

    static List<ConfigTab> visibleTabs() {
        // Every tab is shown; one this version can't use is greyed out (see init).
        return new ArrayList<>(List.of(ConfigTab.values()));
    }
}
