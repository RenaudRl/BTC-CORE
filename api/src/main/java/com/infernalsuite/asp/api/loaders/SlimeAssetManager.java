package com.infernalsuite.asp.api.loaders;

import org.jetbrains.annotations.Nullable;

/**
 * Manager for handling asset loading via SlimeAssetLoader.
 * Allows bypassing the file system for asset retrieval.
 */
public class SlimeAssetManager {

    private static SlimeAssetLoader loader;

    /**
     * Sets the global SlimeAssetLoader instance.
     * @param assetLoader The loader implementation.
     */
    public static void setLoader(SlimeAssetLoader assetLoader) {
        loader = assetLoader;
    }

    /**
     * Gets the current SlimeAssetLoader.
     * @return The loader, or null if not configured.
     */
    @Nullable
    public static SlimeAssetLoader getLoader() {
        return loader;
    }

    /**
     * Checks if a custom asset loader is configured.
     * @return true if a loader is set.
     */
    public static boolean isUsingSlimeAssets() {
        return loader != null;
    }
}
