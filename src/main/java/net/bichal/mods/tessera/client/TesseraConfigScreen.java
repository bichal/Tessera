//? if fabric {
/*package net.bichal.mods.tessera.client;

import net.bichal.mods.tessera.Tessera;
import net.bichal.mods.tessera.config.Config;
import net.bichal.mods.tessera.config.Setting;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/^*
 * Tessera's settings on Fabric, opened from Mod Menu ({@code TesseraModMenu}), laid out like NeoForge's
 * generated screen: this one lists the sections, each section is a screen with its options, and every
 * option has undo and reset buttons. Vanilla widgets only. Values are written when this screen closes;
 * a change to any setting that applies at compression time then reloads resources.
 ^/
public final class TesseraConfigScreen extends Screen {

    private static final int WIDTH = 220;
    private static final int ROW = 24;

    private final Screen parent;
    private final Config.ReloadSensitiveSnapshot before = Config.ReloadSensitiveSnapshot.capture();

    private final Option<Integer> quality = new IntOption("compressionQuality", Config.COMPRESSION_QUALITY, 0, 7, 1);
    private final Option<Boolean> disableNative = new BoolOption("disableNativeCompression", Config.DISABLE_NATIVE_COMPRESSION);
    private final Option<Integer> dedup = new IntOption("dedupSimilarityThreshold", Config.DEDUP_SIMILARITY_THRESHOLD, 0, 64, 1);
    private final Option<Integer> vramBudget = new IntOption("vramBudgetTargetMb", Config.VRAM_BUDGET_TARGET_MB, 256, 16384, 64);
    private final Option<String> cacheDirectory = new TextOption("cacheDirectory", Config.CACHE_DIRECTORY);
    private final Option<Boolean> extendedDebug = new BoolOption("showExtendedDebugBreakdown", Config.SHOW_EXTENDED_DEBUG_BREAKDOWN);

    private final List<Section> sections = List.of(
            new Section("compression", List.of(quality, disableNative)),
            new Section("deduplication", List.of(dedup)),
            new Section("vramBudget", List.of(vramBudget)),
            new Section("cache", List.of(cacheDirectory)),
            new Section("debug", List.of(extendedDebug)));

    public TesseraConfigScreen(Screen parent) {
        super(Component.translatable("tessera.configuration.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int x = this.width / 2 - WIDTH / 2;
        int y = Math.max(34, (this.height - (sections.size() + 1) * ROW - 12) / 2 + 12);
        addRenderableWidget(new StringWidget(x, y - 22, WIDTH, 9, this.title, this.font));
        for (Section section : sections) {
            addRenderableWidget(Button.builder(text(section.key + ".button"), button -> this.minecraft.setScreen(new SectionScreen(this, section)))
                    .bounds(x, y, WIDTH, 20)
                    .tooltip(Tooltip.create(text(section.key + ".tooltip")))
                    .build());
            y += ROW;
        }
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(x, y + 12, WIDTH, 20)
                .build());
    }

    @Override
    public void onClose() {
        for (Section section : sections) {
            for (Option<?> option : section.options) {
                option.commit();
            }
        }
        Tessera.applySettingsChange(before, Config.ReloadSensitiveSnapshot.capture());
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    // The screen of one section, to open it without going through the section buttons (self-test screenshots).
    public Screen sectionScreen(int index) {
        return new SectionScreen(this, sections.get(index));
    }

    private static Component text(String key) {
        return Component.translatable("tessera.configuration." + key);
    }

    // A group of options, shown on its own screen.
    private record Section(String key, List<Option<?>> options) {
    }

    // One section's screen: its options, each with undo and reset, and the same for the whole section.
    private static final class SectionScreen extends Screen {

        private final Screen back;
        private final Section section;

        SectionScreen(Screen back, Section section) {
            super(text(section.key));
            this.back = back;
            this.section = section;
        }

        @Override
        protected void init() {
            int x = this.width / 2 - WIDTH / 2;
            int controlWidth = WIDTH - 2 * 22;
            int y = Math.max(34, (this.height - (section.options.size() + 1) * ROW - 40) / 2 + 12);
            addRenderableWidget(new StringWidget(x, y - 22, WIDTH, 9, this.title, this.font));
            for (Option<?> option : section.options) {
                addRenderableWidget(option.control(this.font, x, y, controlWidth, () -> { }));
                Button undo = Button.builder(Component.literal("↶"), button -> { option.undo(); rebuild(); })
                        .bounds(x + controlWidth + 2, y, 20, 20)
                        .tooltip(Tooltip.create(text("undo")))
                        .build();
                undo.active = option.changed();
                addRenderableWidget(undo);
                Button reset = Button.builder(Component.literal("↺"), button -> { option.reset(); rebuild(); })
                        .bounds(x + controlWidth + 24, y, 20, 20)
                        .tooltip(Tooltip.create(text("reset")))
                        .build();
                reset.active = !option.isDefault();
                addRenderableWidget(reset);
                y += ROW;
            }
            y += 6;
            int half = (WIDTH - 4) / 2;
            Button undoAll = Button.builder(text("undoAll"), button -> { section.options.forEach(Option::undo); rebuild(); })
                    .bounds(x, y, half, 20)
                    .build();
            undoAll.active = section.options.stream().anyMatch(Option::changed);
            addRenderableWidget(undoAll);
            Button resetAll = Button.builder(text("resetAll"), button -> { section.options.forEach(Option::reset); rebuild(); })
                    .bounds(x + half + 4, y, half, 20)
                    .build();
            resetAll.active = section.options.stream().anyMatch(option -> !option.isDefault());
            addRenderableWidget(resetAll);
            addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                    .bounds(x, y + ROW, WIDTH, 20)
                    .build());
        }

        // The controls show the option's value as it is now: build them again.
        private void rebuild() {
            clearWidgets();
            init();
        }

        @Override
        public void onClose() {
            if (this.minecraft != null) {
                this.minecraft.setScreen(back);
            }
        }
    }

    // An edited value of one setting: what is shown, what it was when the screen opened, and its default.
    private abstract static class Option<T> {

        final String key;
        final Setting<T> setting;
        final T original;
        T value;

        Option(String key, Setting<T> setting) {
            this.key = key;
            this.setting = setting;
            this.original = Config.get(setting);
            this.value = original;
        }

        abstract AbstractWidget control(net.minecraft.client.gui.Font font, int x, int y, int width, Runnable changed);

        boolean changed() {
            return !value.equals(original);
        }

        boolean isDefault() {
            return value.equals(setting.getDefault());
        }

        void undo() {
            value = original;
        }

        void reset() {
            value = setting.getDefault();
        }

        void commit() {
            if (!value.equals(setting.get())) {
                setting.set(value);
                setting.save();
            }
        }

        Tooltip tooltip() {
            return Tooltip.create(text(key + ".tooltip"));
        }
    }

    private static final class BoolOption extends Option<Boolean> {

        BoolOption(String key, Setting<Boolean> setting) {
            super(key, setting);
        }

        @Override
        AbstractWidget control(net.minecraft.client.gui.Font font, int x, int y, int width, Runnable changed) {
            return CycleButton.onOffBuilder(value)
                    .withTooltip(v -> tooltip())
                    .create(x, y, width, 20, text(key), (button, v) -> value = v);
        }
    }

    // A slider over min..max in steps of step, labelled "name: value".
    private static final class IntOption extends Option<Integer> {

        private final int min;
        private final int max;
        private final int step;

        IntOption(String key, Setting<Integer> setting, int min, int max, int step) {
            super(key, setting);
            this.min = min;
            this.max = max;
            this.step = step;
        }

        @Override
        AbstractWidget control(net.minecraft.client.gui.Font font, int x, int y, int width, Runnable changed) {
            AbstractSliderButton slider = new AbstractSliderButton(x, y, width, 20, Component.empty(),
                    (double) (value - min) / (max - min)) {
                {
                    setTooltip(tooltip());
                    updateMessage();
                }

                @Override
                protected void updateMessage() {
                    setMessage(Component.translatable("options.generic_value", text(key), Integer.toString(IntOption.this.value)));
                }

                @Override
                protected void applyValue() {
                    int stepped = min + (int) Math.round(this.value * (max - min) / step) * step;
                    IntOption.this.value = Math.min(max, Math.max(min, stepped));
                }
            };
            return slider;
        }
    }

    private static final class TextOption extends Option<String> {

        TextOption(String key, Setting<String> setting) {
            super(key, setting);
        }

        @Override
        AbstractWidget control(net.minecraft.client.gui.Font font, int x, int y, int width, Runnable changed) {
            EditBox box = new EditBox(font, x, y, width, 20, text(key));
            box.setMaxLength(128);
            box.setValue(value);
            // An empty name would put the cache in the game directory itself.
            box.setResponder(v -> value = v.isBlank() ? setting.getDefault() : v.trim());
            box.setTooltip(tooltip());
            return box;
        }
    }
}
*///?}
