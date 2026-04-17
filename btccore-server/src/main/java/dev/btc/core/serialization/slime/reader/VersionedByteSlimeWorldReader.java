package dev.btc.core.serialization.slime.reader;

import dev.btc.core.api.exceptions.CorruptedWorldException;
import dev.btc.core.api.exceptions.NewerFormatException;
import dev.btc.core.api.loaders.SlimeLoader;
import dev.btc.core.api.world.properties.SlimePropertyMap;
import org.jetbrains.annotations.Nullable;

import java.io.DataInputStream;
import java.io.IOException;

public interface VersionedByteSlimeWorldReader<T> {

    T deserializeWorld(byte version, @Nullable SlimeLoader loader, String worldName, DataInputStream dataStream, SlimePropertyMap propertyMap, boolean readOnly) throws IOException, CorruptedWorldException, NewerFormatException;
}

