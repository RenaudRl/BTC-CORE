package dev.btc.core.serialization;

import dev.btc.core.api.SlimeNMSBridge;
import dev.btc.core.api.exceptions.CorruptedWorldException;
import dev.btc.core.api.exceptions.NewerFormatException;
import dev.btc.core.api.loaders.SlimeLoader;
import dev.btc.core.api.loaders.SlimeSerializationAdapter;
import dev.btc.core.api.utils.SlimeFormat;
import dev.btc.core.api.world.SlimeWorld;
import dev.btc.core.api.world.SlimeWorldInstance;
import dev.btc.core.api.world.properties.SlimePropertyMap;
import dev.btc.core.serialization.slime.SlimeSerializer;
import dev.btc.core.serialization.slime.reader.SlimeWorldReaderRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

public class SlimeSerializationAdapterImpl implements SlimeSerializationAdapter {

    @Override
    public byte[] serializeWorld(@NotNull SlimeWorld slimeWorld) {
        if(slimeWorld instanceof SlimeWorldInstance) {
            throw new IllegalArgumentException("SlimeWorldInstances cannot be serialized directly. Use SlimeWorldInstance.getSerializableCopy() instead.");
        }
        return SlimeSerializer.serialize(slimeWorld);
    }

    @Override
    public @NotNull SlimeWorld deserializeWorld(@NotNull String worldName, byte[] serializedWorld, @Nullable SlimeLoader loader, @NotNull SlimePropertyMap propertyMap, boolean readOnly) throws CorruptedWorldException, NewerFormatException, IOException {
        SlimeWorld slimeWorld = SlimeWorldReaderRegistry.readWorld(loader, worldName, serializedWorld, propertyMap, loader == null || readOnly);
        return SlimeNMSBridge.instance().getSlimeDataConverter().applyDataFixers(slimeWorld);
    }

    @Override
    public int getSlimeFormat() {
        return SlimeFormat.SLIME_VERSION;
    }

}

