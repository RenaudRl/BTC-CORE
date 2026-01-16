package org.mockbukkit.mockbukkit.registry;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Keyed;
import org.bukkit.Registry;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.exception.InternalDataLoadException;
import org.mockbukkit.mockbukkit.exception.ReflectionAccessException;

import com.infernalsuite.asp.plugin.mocks.ArtRegistryMock;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Stream;

public class RegistryAccessMock implements RegistryAccess {
    private final Map<RegistryKey<?>, Registry<?>> registries = new HashMap<>();
    private static final BiMap<RegistryKey<?>, String> CLASS_NAME_KEY_MAP = createClassToKeyConversions();

    @Override
    public <T extends Keyed> Registry<T> getRegistry(Class<T> type) {
        RegistryKey<T> key = determineRegistryKeyFromClass(type);
        if (key != null) {
            return getRegistry(key);
        }
        return findSimpleRegistry(type);
    }

    @Override
    public <T extends Keyed> Registry<T> getRegistry(RegistryKey<T> key) {
        if (registries.containsKey(key)) {
            return (Registry<T>) registries.get(key);
        }
        Registry<T> registry = createRegistry(key);
        registries.put(key, registry);
        return registry;
    }

    private <T extends Keyed> RegistryKey<T> determineRegistryKeyFromClass(Class<T> clazz) {
        return (RegistryKey<T>) CLASS_NAME_KEY_MAP.inverse().get(clazz.getName());
    }

    private static <T extends Keyed> Registry<T> createRegistry(RegistryKey<T> key) {
        if (RegistryKey.PAINTING_VARIANT.equals(key)) {
            return (Registry<T>) new ArtRegistryMock();
        }
        if (RegistryKey.GAME_RULE.equals(key)) {
            return (Registry<T>) new com.infernalsuite.asp.plugin.mocks.SimpleGameRuleRegistry();
        }
        if (getOutlierKeyedRegistryKeys().contains(key)) {
            return new RegistryMock<>(key);
        }
        String className = CLASS_NAME_KEY_MAP.get(key);
        if (className != null) {
            try {
                @SuppressWarnings("unchecked")
                Class<T> clazz = (Class<T>) getClass(className);
                return findSimpleRegistry(clazz);
            } catch (RuntimeException e) {
                // Fallback to generic mock if it's the expected unimplemented exception
                if (e.getMessage() != null && e.getMessage().startsWith("UnimplementedOperationException")) {
                    @SuppressWarnings("unchecked")
                    Class<T> clazz = (Class<T>) getClass(className);
                    return new com.infernalsuite.asp.plugin.mocks.GenericMockRegistry<>(clazz);
                }
                throw e;
            }
        }
        // Fallback for types not strictly mapped but present in outliers list check
        // above covers them
        throw new RuntimeException("Could not create registry for " + key);
    }

    // Extracted from decompiled code
    private static List<RegistryKey<?>> getOutlierKeyedRegistryKeys() {
        return List.of(
                RegistryKey.STRUCTURE,
                RegistryKey.STRUCTURE_TYPE,
                RegistryKey.TRIM_MATERIAL,
                RegistryKey.TRIM_PATTERN,
                RegistryKey.INSTRUMENT,
                RegistryKey.GAME_EVENT,
                RegistryKey.ENCHANTMENT,
                RegistryKey.MOB_EFFECT,
                RegistryKey.DAMAGE_TYPE,
                RegistryKey.ITEM,
                RegistryKey.BLOCK,
                RegistryKey.WOLF_VARIANT,
                RegistryKey.JUKEBOX_SONG,
                RegistryKey.CAT_VARIANT,
                RegistryKey.VILLAGER_PROFESSION,
                RegistryKey.VILLAGER_TYPE,
                RegistryKey.FROG_VARIANT,
                RegistryKey.MAP_DECORATION_TYPE,
                RegistryKey.BANNER_PATTERN,
                RegistryKey.GAME_RULE,
                RegistryKey.MENU);
    }

    private static <T extends Keyed> Registry<T> findSimpleRegistry(Class<T> type) {
        return Stream.of(Registry.class.getDeclaredFields())
                .filter(field -> Registry.class.isAssignableFrom(field.getType()))
                .filter(field -> Modifier.isPublic(field.getModifiers()))
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .filter(field -> genericTypeMatches(field, type))
                .map(RegistryAccessMock::getValue)
                .filter(Objects::nonNull)
                .findAny()
                .map(registry -> (Registry<T>) registry)
                .orElseThrow(
                        () -> new RuntimeException(
                                "UnimplementedOperationException: Could not find registry for interface " + type));
    }

    private static boolean genericTypeMatches(Field field, Class<?> type) {
        Type genericType = field.getGenericType();
        if (genericType instanceof ParameterizedType parameterizedType) {
            return parameterizedType.getActualTypeArguments()[0].equals(type);
        }
        return false;
    }

    private static Registry<?> getValue(Field field) {
        try {
            return (Registry<?>) field.get(null);
        } catch (IllegalAccessException e) {
            throw new ReflectionAccessException(field.getDeclaringClass().getSimpleName() + "." + field.getName());
        }
    }

    private static Class<?> getClass(String name) {
        try {
            return Class.forName(name, false, RegistryAccessMock.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new InternalDataLoadException(e);
        }
    }

    private static BiMap<RegistryKey<?>, String> createClassToKeyConversions() {
        BiMap<RegistryKey<?>, String> map = HashBiMap.create();
        try (InputStream stream = MockBukkit.class
                .getResourceAsStream("/registries/registry_key_class_relation.json")) {
            if (stream == null) {
                // Try getting from root if MockBukkit one fails, or fallback.
                // Since we are running in tests, it should be on classpath because we added it
                // to src/test/resources
                throw new IOException("Could not find registry_key_class_relation.json");
            }
            JsonElement json = JsonParser.parseReader(new InputStreamReader(stream));
            for (Field field : RegistryKey.class.getFields()) {
                if (RegistryKey.class.isAssignableFrom(field.getType())) {
                    try {
                        RegistryKey<?> key = (RegistryKey<?>) field.get(null);
                        if (json.getAsJsonObject().has(key.key().asString())) {
                            String className = json.getAsJsonObject().get(key.key().asString()).getAsString();
                            map.put(key, className);
                        }
                    } catch (IllegalAccessException e) {
                        throw new InternalDataLoadException(e);
                    }
                }
            }
        } catch (IOException e) {
            throw new InternalDataLoadException(e);
        }
        return map;
    }
}
