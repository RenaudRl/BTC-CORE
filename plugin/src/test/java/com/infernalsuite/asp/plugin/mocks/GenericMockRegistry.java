package com.infernalsuite.asp.plugin.mocks;

import io.papermc.paper.registry.tag.Tag;
import io.papermc.paper.registry.tag.TagKey;

import net.kyori.adventure.text.Component;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public class GenericMockRegistry<T extends Keyed> implements Registry<T> {

    private final Class<T> type;
    private final Map<NamespacedKey, T> cache = new HashMap<>();
    private final AtomicInteger idCounter = new AtomicInteger(0);

    public GenericMockRegistry(Class<T> type) {
        this.type = type;
    }

    @Override
    public @Nullable T get(@NotNull NamespacedKey key) {
        return cache.computeIfAbsent(key, this::createMock);
    }

    @SuppressWarnings("unchecked")
    private T createMock(NamespacedKey key) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class[] { type },
                new GenericMockHandler(key, idCounter.getAndIncrement()));
    }

    @Override
    public @NotNull Stream<T> stream() {
        return cache.values().stream();
    }

    // Paper start
    @Override
    public @NotNull Stream<NamespacedKey> keyStream() {
        return cache.keySet().stream();
    }
    // Paper end

    @Override
    public @Nullable NamespacedKey getKey(@NotNull T value) {
        return value.getKey();
    }

    @Override
    public int size() {
        return cache.size();
    }

    @NotNull
    @Override
    public Iterator<T> iterator() {
        return cache.values().iterator();
    }

    // Paper - RegistrySet API implementation stub
    public boolean hasTag(TagKey<T> key) {
        return false;
    }

    public Tag<T> getTag(TagKey<T> key) {
        return null;
    }

    public Collection<Tag<T>> getTags() {
        return java.util.Collections.emptyList();
    }

    private class GenericMockHandler implements InvocationHandler {
        private final NamespacedKey key;
        private final int id;

        GenericMockHandler(NamespacedKey key, int id) {
            this.key = key;
            this.id = id;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            switch (name) {
                case "getKey":
                case "key":
                case "assetId":
                    return key;
                case "name":
                    return key.getKey().toUpperCase();
                case "toString":
                    return key.toString();
                case "ordinal":
                case "getId":
                    return id;
                case "hashCode":
                    return key.hashCode();
                case "equals":
                    if (args.length == 1 && args[0] instanceof Keyed k) {
                        return key.equals(k.getKey());
                    }
                    return false;
                case "compareTo":
                    if (args.length == 1 && args[0] instanceof Keyed k) {
                        return key.compareTo(k.getKey());
                    }
                    return 0;
                case "translationKey":
                    return "minecraft." + key.getKey(); // simplified
                case "title":
                case "author":
                    return Component.text(key.getKey());
                case "getSentiment":
                    // Return Attribute.Sentiment.NEUTRAL via reflection if possible, or null?
                    // Return enum constant
                    Class<?> returnType = method.getReturnType();
                    if (returnType.isEnum()) {
                        Object[] consts = returnType.getEnumConstants();
                        if (consts != null && consts.length > 0) {
                            // Try to find NEUTRAL, else first
                            for (Object o : consts) {
                                if (o.toString().equals("NEUTRAL"))
                                    return o;
                            }
                            return consts[0];
                        }
                    }
                    return null;
                case "getBlockWidth":
                case "getBlockHeight":
                    return 1;
            }

            // Default return values
            Class<?> rt = method.getReturnType();
            if (rt == boolean.class)
                return false;
            if (rt == int.class)
                return 0;
            if (rt == long.class)
                return 0L;
            if (rt == float.class)
                return 0.0f;
            if (rt == double.class)
                return 0.0d;
            if (rt == String.class)
                return "";

            return null;
        }
    }
}
