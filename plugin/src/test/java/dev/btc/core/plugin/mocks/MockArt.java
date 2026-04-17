package dev.btc.core.plugin.mocks;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Art;
import org.bukkit.NamespacedKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class MockArt implements Art {
    private final NamespacedKey key;
    private final int id;
    private final String name;

    public MockArt(NamespacedKey key, int id) {
        this.key = key;
        this.id = id;
        this.name = key.getKey().toUpperCase();
    }

    @Override
    public int getBlockWidth() {
        return 1;
    }

    @Override
    public int getBlockHeight() {
        return 1;
    }

    @Override
    public int getId() {
        return id;
    }

    @Override
    public @NotNull NamespacedKey getKey() {
        return key;
    }

    @Override
    public @Nullable Component title() {
        return Component.text(name);
    }

    @Override
    public @Nullable Component author() {
        return Component.text("MockArt");
    }

    @Override
    public @NotNull Key assetId() {
        return key;
    }

    @Override
    public int compareTo(@NotNull Art o) {
        return this.key.compareTo(o.getKey());
    }

    @Override
    public @NotNull String name() {
        return name;
    }

    @Override
    public int ordinal() {
        return id;
    }
}

