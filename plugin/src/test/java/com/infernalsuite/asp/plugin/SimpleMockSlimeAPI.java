package com.infernalsuite.asp.plugin;

import com.infernalsuite.asp.api.AdvancedSlimePaperAPI;
import com.infernalsuite.asp.api.exceptions.*;
import com.infernalsuite.asp.api.loaders.SlimeLoader;
import com.infernalsuite.asp.api.loaders.SlimeSerializationAdapter;
import com.infernalsuite.asp.api.world.SlimeWorld;
import com.infernalsuite.asp.api.world.SlimeWorldInstance;
import com.infernalsuite.asp.api.world.properties.SlimePropertyMap;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

public class SimpleMockSlimeAPI implements AdvancedSlimePaperAPI {
    @Override
    public SlimeWorld readWorld(SlimeLoader loader, String worldName, boolean readOnly, SlimePropertyMap propertyMap) throws UnknownWorldException, IOException, CorruptedWorldException, NewerFormatException {
        return null;
    }

    @Override
    public SlimeWorldInstance getLoadedWorld(String worldName) {
        return null;
    }

    @Override
    public List<SlimeWorldInstance> getLoadedWorlds() {
        return Collections.emptyList();
    }

    @Override
    public SlimeWorldInstance loadWorld(SlimeWorld world, boolean callWorldLoadEvent) throws IllegalArgumentException {
        return null;
    }

    @Override
    public boolean worldLoaded(SlimeWorld world) {
        return false;
    }

    @Override
    public void saveWorld(SlimeWorld world) throws IOException {
    }

    @Override
    public void migrateWorld(String worldName, SlimeLoader currentLoader, SlimeLoader newLoader) throws IOException, WorldAlreadyExistsException, UnknownWorldException {
    }

    @Override
    public SlimeWorld createEmptyWorld(String worldName, boolean readOnly, SlimePropertyMap propertyMap, @Nullable SlimeLoader loader) {
        return null;
    }

    @Override
    public SlimeWorld readVanillaWorld(File worldDir, String worldName, @Nullable SlimeLoader loader) throws InvalidWorldException, WorldLoadedException, WorldTooBigException, IOException, WorldAlreadyExistsException {
        return null;
    }

    @Override
    public SlimeSerializationAdapter getSerializer() {
        return null;
    }

    @Override
    public SlimeWorld cloneUnloadedWorld(String cloneWorldName, String worldName, SlimeLoader loader, SlimePropertyMap propertyMap) throws UnknownWorldException, IOException, WorldAlreadyExistsException {
        return null;
    }
}
