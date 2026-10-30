//? if fabric {
/*package net.bichal.mods.tessera.config;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/^*
 * Fabric's settings file, {@code config/tessera.properties}: written with the defaults on
 * first start and read on each start (restart after editing it). Feeds {@link Config}.
 ^/
final class FabricConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("Tessera/Config");

    private record Entry(String key, String comment, String[] value) {
    }

    private static final Properties PROPERTIES = read();
    private static final List<Entry> ENTRIES = new ArrayList<>();

    private FabricConfig() {
    }

    static Setting<Integer> integer(String key, int def, int min, int max, String comment) {
        return setting(key, comment, def, text -> {
            try {
                int parsed = Integer.parseInt(text.trim());
                if (parsed < min || parsed > max) {
                    LOGGER.warn("{}={} is outside {}-{}; using {}", key, parsed, min, max, def);
                    return def;
                }
                return parsed;
            } catch (NumberFormatException e) {
                LOGGER.warn("{}={} is not a number; using {}", key, text, def);
                return def;
            }
        });
    }

    static Setting<Boolean> bool(String key, boolean def, String comment) {
        return setting(key, comment, def, text -> Boolean.parseBoolean(text.trim()));
    }

    static Setting<String> string(String key, String def, String comment) {
        return setting(key, comment, def, String::trim);
    }

    private static <T> Setting<T> setting(String key, String comment, T def, Function<String, T> parse) {
        String stored = PROPERTIES.getProperty(key);
        String[] value = {stored == null ? String.valueOf(def) : stored};
        AtomicReference<T> current = new AtomicReference<>(stored == null ? def : parse.apply(stored));
        ENTRIES.add(new Entry(key, comment, value));
        Setting<T> setting = new Setting<>(current::get, () -> def, v -> {
            current.set(v);
            value[0] = String.valueOf(v);
        }, FabricConfig::write);
        if (stored == null) {
            write();
        }
        return setting;
    }

    /^* The settings file, or null outside a running Fabric game (unit tests): defaults only, nothing written. ^/
    private static Path file() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve("tessera.properties");
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Properties read() {
        Properties props = new Properties();
        Path file = file();
        if (file != null && Files.isRegularFile(file)) {
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(in);
            } catch (IOException e) {
                LOGGER.warn("Could not read {}; using the defaults", file, e);
            }
        }
        return props;
    }

    private static synchronized void write() {
        Path file = file();
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                out.write("# Tessera settings. Read at startup: restart after editing this file.\n");
                for (Entry entry : ENTRIES) {
                    out.write("\n# " + entry.comment() + "\n");
                    out.write(entry.key() + "=" + entry.value()[0] + "\n");
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Could not write {}", file, e);
        }
    }
}
*///?}
