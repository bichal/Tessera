package net.bichal.mods.tessera.config;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One configurable value, whichever loader stores it (NeoForge's {@code ModConfigSpec} or
 * Fabric's {@code config/tessera.properties}).
 */
public final class Setting<T> {

    private final Supplier<T> getter;
    private final Supplier<T> defaultValue;
    private final Consumer<T> setter;
    private final Runnable saver;

    public Setting(Supplier<T> getter, Supplier<T> defaultValue, Consumer<T> setter, Runnable saver) {
        this.getter = getter;
        this.defaultValue = defaultValue;
        this.setter = setter;
        this.saver = saver;
    }

    public T get() {
        return getter.get();
    }

    public T getDefault() {
        return defaultValue.get();
    }

    public void set(T value) {
        setter.accept(value);
    }

    public void save() {
        saver.run();
    }
}
