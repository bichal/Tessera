//? if fabric {
/*package net.bichal.mods.tessera.client;

import net.bichal.mods.tessera.Tessera;
import net.bichal.mods.tessera.config.Config;
import net.bichal.mods.tessera.config.Setting;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/^*
 * Tessera's settings on Fabric, opened from Mod Menu ({@code TesseraModMenu}). Built from vanilla
 * widgets only. Values are written when the screen closes; a change to any setting that only
 * applies when atlases are compressed again reloads resources. NeoForge uses its own generated
 * screen for the same settings instead.
 ^/
public final class TesseraConfigScreen extends Screen {

    private static final int WIDTH = 220;
    private static final int ROW = 24;

    private final Screen parent;
    private final Config.ReloadSensitiveSnapshot before = Config.ReloadSensitiveSnapshot.capture();

    private int quality = Config.get(Config.COMPRESSION_QUALITY);
    private int dedup = Config.get(Config.DEDUP_SIMILARITY_THRESHOLD);
    private int vramBudget = Config.get(Config.VRAM_BUDGET_TARGET_MB);
    private boolean disableNative = Config.get(Config.DISABLE_NATIVE_COMPRESSION);
    private boolean compressAnimated = Config.get(Config.COMPRESS_ANIMATED_ATLASES);
    private boolean extendedDebug = Config.get(Config.SHOW_EXTENDED_DEBUG_BREAKDOWN);
    private String cacheDirectory = Config.get(Config.CACHE_DIRECTORY);

    public TesseraConfigScreen(Screen parent) {
        super(Component.translatable("tessera.configuration.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int x = this.width / 2 - WIDTH / 2;
        int rows = 8;
        int y = Math.max(34, (this.height - rows * ROW - 28) / 2 + 10);
        addRenderableWidget(new StringWidget(x, y - 22, WIDTH, 9, this.title, this.font));

        addRenderableWidget(new IntSlider(x, y, "compressionQuality", 0, 7, 1, () -> quality, v -> quality = v));
        y += ROW;
        addRenderableWidget(toggle(x, y, "disableNativeCompression", disableNative, v -> disableNative = v));
        y += ROW;
        addRenderableWidget(toggle(x, y, "compressAnimatedAtlases", compressAnimated, v -> compressAnimated = v));
        y += ROW;
        addRenderableWidget(new IntSlider(x, y, "dedupSimilarityThreshold", 0, 64, 1, () -> dedup, v -> dedup = v));
        y += ROW;
        addRenderableWidget(new IntSlider(x, y, "vramBudgetTargetMb", 256, 16384, 64, () -> vramBudget, v -> vramBudget = v));
        y += ROW;

        EditBox directory = new EditBox(this.font, x, y, WIDTH, 20, label("cacheDirectory"));
        directory.setMaxLength(128);
        directory.setValue(cacheDirectory);
        directory.setResponder(value -> cacheDirectory = value);
        directory.setTooltip(Tooltip.create(Component.translatable("tessera.configuration.cacheDirectory.tooltip")));
        addRenderableWidget(directory);
        y += ROW;

        addRenderableWidget(toggle(x, y, "showExtendedDebugBreakdown", extendedDebug, v -> extendedDebug = v));
        y += ROW + 4;

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(x, y, WIDTH, 20)
                .build());
    }

    @Override
    public void onClose() {
        commit(Config.COMPRESSION_QUALITY, quality);
        commit(Config.DISABLE_NATIVE_COMPRESSION, disableNative);
        commit(Config.COMPRESS_ANIMATED_ATLASES, compressAnimated);
        commit(Config.DEDUP_SIMILARITY_THRESHOLD, dedup);
        commit(Config.VRAM_BUDGET_TARGET_MB, vramBudget);
        // An empty or blank name would put the cache in the game directory itself.
        commit(Config.CACHE_DIRECTORY, cacheDirectory.isBlank() ? Config.CACHE_DIRECTORY.getDefault() : cacheDirectory.trim());
        commit(Config.SHOW_EXTENDED_DEBUG_BREAKDOWN, extendedDebug);
        Tessera.applySettingsChange(before, Config.ReloadSensitiveSnapshot.capture());
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    private static <T> void commit(Setting<T> setting, T value) {
        if (!value.equals(setting.get())) {
            setting.set(value);
            setting.save();
        }
    }

    private static Component label(String key) {
        return Component.translatable("tessera.configuration." + key);
    }

    private static CycleButton<Boolean> toggle(int x, int y, String key, boolean initial, java.util.function.Consumer<Boolean> apply) {
        return CycleButton.onOffBuilder(initial)
                .withTooltip(value -> Tooltip.create(Component.translatable("tessera.configuration." + key + ".tooltip")))
                .create(x, y, WIDTH, 20, label(key), (button, value) -> apply.accept(value));
    }

    /^* A slider over {@code min..max} in steps of {@code step}, labelled "name: value". ^/
    private static final class IntSlider extends AbstractSliderButton {

        private final String key;
        private final int min;
        private final int max;
        private final int step;
        private final IntSupplier current;
        private final IntConsumer apply;

        IntSlider(int x, int y, String key, int min, int max, int step, IntSupplier current, IntConsumer apply) {
            super(x, y, WIDTH, 20, Component.empty(), (double) (current.getAsInt() - min) / (max - min));
            this.key = key;
            this.min = min;
            this.max = max;
            this.step = step;
            this.current = current;
            this.apply = apply;
            setTooltip(Tooltip.create(Component.translatable("tessera.configuration." + key + ".tooltip")));
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable("options.generic_value", label(key), Integer.toString(current.getAsInt())));
        }

        @Override
        protected void applyValue() {
            int stepped = min + (int) Math.round(this.value * (max - min) / step) * step;
            apply.accept(Math.min(max, Math.max(min, stepped)));
        }
    }
}
*///?}
