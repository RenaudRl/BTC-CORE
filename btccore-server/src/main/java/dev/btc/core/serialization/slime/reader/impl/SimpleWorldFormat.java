package dev.btc.core.serialization.slime.reader.impl;

import dev.btc.core.api.exceptions.CorruptedWorldException;
import dev.btc.core.api.exceptions.NewerFormatException;
import dev.btc.core.api.loaders.SlimeLoader;
import dev.btc.core.serialization.slime.reader.VersionedByteSlimeWorldReader;
import dev.btc.core.api.world.SlimeWorld;
import dev.btc.core.api.world.properties.SlimePropertyMap;
import org.jetbrains.annotations.Nullable;

import java.io.DataInputStream;
import java.io.IOException;

public class SimpleWorldFormat<S> implements VersionedByteSlimeWorldReader<SlimeWorld> {

    private final dev.btc.core.serialization.SlimeWorldReader<S> data;
    private final VersionedByteSlimeWorldReader<S> reader;

    public SimpleWorldFormat(dev.btc.core.serialization.SlimeWorldReader<S> data, VersionedByteSlimeWorldReader<S> reader) {
        this.data = data;
        this.reader = reader;
    }

    @Override
    public SlimeWorld deserializeWorld(byte version, @Nullable SlimeLoader loader, String worldName, DataInputStream dataStream, SlimePropertyMap propertyMap, boolean readOnly) throws IOException, CorruptedWorldException, NewerFormatException {
        return this.data.readFromData(this.reader.deserializeWorld(version, loader, worldName, dataStream, propertyMap, readOnly));
    }
}

