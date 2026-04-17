package dev.btc.core.plugin.mocks;

import io.papermc.paper.registry.tag.Tag;
import io.papermc.paper.registry.tag.TagKey;
import net.kyori.adventure.key.Key;
import org.bukkit.Art;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public class ArtRegistryMock implements Registry<Art> {

    private final Map<NamespacedKey, Art> arts = new HashMap<>();
    private final AtomicInteger idCounter = new AtomicInteger(0);

    @Override
    public @Nullable Art get(@NotNull NamespacedKey key) {
        return arts.computeIfAbsent(key, k -> new MockArt(k, idCounter.getAndIncrement()));
    }

    @Override
    public @NotNull Stream<Art> stream() {
        return arts.values().stream();
    }

    @Override
    public @NotNull Stream<NamespacedKey> keyStream() {
        return arts.keySet().stream();
    }

    @Override
    public int size() {
        return arts.size();
    }

    @NotNull
    @Override
    public Iterator<Art> iterator() {
        return arts.values().iterator();
    }

    @Override
    public @Nullable NamespacedKey getKey(@NotNull Art value) {
        return value.getKey();
    }

    // Paper - RegistrySet API implementation needed to avoid compilation errors on
    // generic interface
    public boolean hasTag(TagKey<Art> key) {
        return false;
    }

    public Tag<Art> getTag(TagKey<Art> key) {
        return null;
    }

    public Collection<Tag<Art>> getTags() {
        return java.util.Collections.emptyList();
    }
}

