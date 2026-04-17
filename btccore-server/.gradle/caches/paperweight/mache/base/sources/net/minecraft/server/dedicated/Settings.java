package net.minecraft.server.dedicated;

import com.google.common.base.MoreObjects;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import net.minecraft.core.RegistryAccess;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public abstract class Settings<T extends Settings<T>> {
    private static final Logger LOGGER = LogUtils.getLogger();
    public final Properties properties;

    public Settings(Properties properties) {
        this.properties = properties;
    }

    public static Properties loadFromFile(Path filePath) {
        try {
            try {
                Properties var13;
                try (InputStream inputStream = Files.newInputStream(filePath)) {
                    CharsetDecoder charsetDecoder = StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
                    Properties map = new Properties();
                    map.load(new InputStreamReader(inputStream, charsetDecoder));
                    var13 = map;
                }

                return var13;
            } catch (CharacterCodingException var9) {
                LOGGER.info("Failed to load properties as UTF-8 from file {}, trying ISO_8859_1", filePath);

                Properties var4;
                try (Reader bufferedReader = Files.newBufferedReader(filePath, StandardCharsets.ISO_8859_1)) {
                    Properties map = new Properties();
                    map.load(bufferedReader);
                    var4 = map;
                }

                return var4;
            }
        } catch (IOException var10) {
            LOGGER.error("Failed to load properties from file: {}", filePath, var10);
            return new Properties();
        }
    }

    public void store(Path filePath) {
        try (Writer bufferedWriter = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8)) {
            this.properties.store(bufferedWriter, "Minecraft server properties");
        } catch (IOException var7) {
            LOGGER.error("Failed to store properties to file: {}", filePath);
        }
    }

    private static <V extends Number> Function<String, @Nullable V> wrapNumberDeserializer(Function<String, V> parseFunc) {
        return string -> {
            try {
                return parseFunc.apply(string);
            } catch (NumberFormatException var3) {
                return null;
            }
        };
    }

    protected static <V> Function<String, @Nullable V> dispatchNumberOrString(IntFunction<@Nullable V> byId, Function<String, @Nullable V> byName) {
        return string -> {
            try {
                return byId.apply(Integer.parseInt(string));
            } catch (NumberFormatException var4) {
                return byName.apply(string);
            }
        };
    }

    public @Nullable String getStringRaw(String key) {
        return (String)this.properties.get(key);
    }

    protected <V> @Nullable V getLegacy(String key, Function<String, V> serializer) {
        String stringRaw = this.getStringRaw(key);
        if (stringRaw == null) {
            return null;
        } else {
            this.properties.remove(key);
            return serializer.apply(stringRaw);
        }
    }

    protected <V> V get(String key, Function<String, @Nullable V> serializer, Function<V, String> deserializer, V defaultValue) {
        String stringRaw = this.getStringRaw(key);
        V object = MoreObjects.firstNonNull(stringRaw != null ? serializer.apply(stringRaw) : null, defaultValue);
        this.properties.put(key, deserializer.apply(object));
        return object;
    }

    protected <V> Settings<T>.MutableValue<V> getMutable(String key, Function<String, @Nullable V> serializer, Function<V, String> deserializer, V defaultValue) {
        String stringRaw = this.getStringRaw(key);
        V object = MoreObjects.firstNonNull(stringRaw != null ? serializer.apply(stringRaw) : null, defaultValue);
        this.properties.put(key, deserializer.apply(object));
        return new Settings<T>.MutableValue<>(key, object, deserializer);
    }

    protected <V> V get(String key, Function<String, @Nullable V> serializer, UnaryOperator<V> modifier, Function<V, String> deserializer, V defaultValue) {
        return this.get(key, string -> {
            V object = serializer.apply(string);
            return object != null ? modifier.apply(object) : null;
        }, deserializer, defaultValue);
    }

    protected <V> V get(String key, Function<String, V> mapper, V value) {
        return this.get(key, mapper, Objects::toString, value);
    }

    protected <V> Settings<T>.MutableValue<V> getMutable(String key, Function<String, V> serializer, V defaultValue) {
        return this.getMutable(key, serializer, Objects::toString, defaultValue);
    }

    protected String get(String key, String defaultValue) {
        return this.get(key, Function.identity(), Function.identity(), defaultValue);
    }

    protected @Nullable String getLegacyString(String key) {
        return this.getLegacy(key, Function.identity());
    }

    protected int get(String key, int defaultValue) {
        return this.get(key, wrapNumberDeserializer(Integer::parseInt), Integer.valueOf(defaultValue));
    }

    protected Settings<T>.MutableValue<Integer> getMutable(String key, int defaultValue) {
        return this.getMutable(key, wrapNumberDeserializer(Integer::parseInt), defaultValue);
    }

    protected Settings<T>.MutableValue<String> getMutable(String key, String defaultValue) {
        return this.getMutable(key, String::new, defaultValue);
    }

    protected int get(String key, UnaryOperator<Integer> modifier, int defaultValue) {
        return this.get(key, wrapNumberDeserializer(Integer::parseInt), modifier, Objects::toString, defaultValue);
    }

    protected long get(String key, long defaultValue) {
        return this.get(key, wrapNumberDeserializer(Long::parseLong), defaultValue);
    }

    protected boolean get(String key, boolean defaultValue) {
        return this.get(key, Boolean::valueOf, defaultValue);
    }

    protected Settings<T>.MutableValue<Boolean> getMutable(String key, boolean defaultValue) {
        return this.getMutable(key, Boolean::valueOf, defaultValue);
    }

    protected @Nullable Boolean getLegacyBoolean(String key) {
        return this.getLegacy(key, Boolean::valueOf);
    }

    protected Properties cloneProperties() {
        Properties map = new Properties();
        map.putAll(this.properties);
        return map;
    }

    protected abstract T reload(RegistryAccess registryAccess, Properties properties);

    public class MutableValue<V> implements Supplier<V> {
        private final String key;
        private final V value;
        private final Function<V, String> serializer;

        MutableValue(final String key, final V value, final Function<V, String> serializer) {
            this.key = key;
            this.value = value;
            this.serializer = serializer;
        }

        @Override
        public V get() {
            return this.value;
        }

        public T update(RegistryAccess registryAccess, V newValue) {
            Properties map = Settings.this.cloneProperties();
            map.put(this.key, this.serializer.apply(newValue));
            return Settings.this.reload(registryAccess, map);
        }
    }
}
