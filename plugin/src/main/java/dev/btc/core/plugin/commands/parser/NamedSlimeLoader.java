package dev.btc.core.plugin.commands.parser;

import dev.btc.core.api.loaders.SlimeLoader;

public record NamedSlimeLoader(String name, SlimeLoader slimeLoader) {
    @Override
    public String toString() {
        return name;
    }
}

