package com.infernalsuite.asp.plugin.mocks;

import com.google.common.collect.Maps;
import org.bukkit.GameRule;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.Map;
import java.util.stream.Stream;

public class SimpleGameRuleRegistry implements Registry<GameRule> {

    private final Map<NamespacedKey, GameRule> rules = Maps.newHashMap();

    @Override
    public @Nullable GameRule get(@NotNull NamespacedKey key) {
        if (!rules.containsKey(key)) {
            // Dynamically create GameRule if missing to satisfy initialization
            try {
                String name = key.getKey();
                Class<?> type = Boolean.class;
                if (name.equals("randomTickSpeed") || name.equals("spawnRadius") ||
                        name.equals("maxEntityCramming") || name.equals("maxCommandChainLength") ||
                        name.equals("playersSleepingPercentage")) {
                    type = Integer.class;
                }

                java.lang.reflect.Constructor<GameRule> constructor = GameRule.class.getDeclaredConstructor();
                constructor.setAccessible(true);
                GameRule rule = constructor.newInstance();

                System.out.println("GameRule fields:");
                for (java.lang.reflect.Field f : GameRule.class.getDeclaredFields()) {
                    System.out.println(f.getName() + " : " + f.getType());
                }

                rules.put(key, rule);
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }
        return rules.get(key);
    }

    @Override
    public @NotNull Stream<GameRule> stream() {
        return rules.values().stream();
    }

    @Override
    public @NotNull Iterator<GameRule> iterator() {
        return rules.values().iterator();
    }

    public int size() {
        return rules.size();
    }

    public @NotNull Stream<NamespacedKey> keyStream() {
        return rules.keySet().stream();
    }

    public @NotNull java.util.Collection<io.papermc.paper.registry.tag.Tag<GameRule>> getTags() {
        return java.util.Collections.emptyList();
    }

    public io.papermc.paper.registry.tag.Tag<GameRule> getTag(io.papermc.paper.registry.tag.TagKey<GameRule> key) {
        return null; // Return null as we don't support tags in this simple registry
    }

    public boolean hasTag(io.papermc.paper.registry.tag.TagKey<GameRule> key) {
        return false;
    }

    public @org.jetbrains.annotations.Nullable org.bukkit.NamespacedKey getKey(@NotNull GameRule value) {
        // Iterate to find key
        for (java.util.Map.Entry<org.bukkit.NamespacedKey, GameRule> entry : rules.entrySet()) {
            if (entry.getValue().equals(value)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public void register(GameRule value) {
        // GameRule doesn't expose getKey() easily via Keyed interface in all versions,
        // but let's assume valid key.
        // Wait, GameRule implements Keyed?
        // If not, we need to map name to key.
        // GameRule has getName().
        // NamespacedKey is minecraft:name.
        if (value != null) {
            rules.put(NamespacedKey.minecraft(value.getName()), value);
        }
    }
}
