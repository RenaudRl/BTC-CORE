package com.infernalsuite.asp.api.loaders;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Interface for loading assets (files, configurations, resources)
 * directly from a Slime data source, bypassing the local file system.
 */
public interface SlimeAssetLoader {

    /**
     * Read an asset from the data source.
     *
     * @param path The path of the asset (e.g. "config/settings.yml").
     * @return The asset data as a byte array.
     * @throws IOException if the asset cannot be read or does not exist.
     */
    byte[] readAsset(String path) throws IOException;

    /**
     * Checks if an asset exists in the data source.
     *
     * @param path The path of the asset.
     * @return true if it exists, false otherwise.
     * @throws IOException if the check fails.
     */
    boolean assetExists(String path) throws IOException;

    /**
     * Lists all assets in a given directory path.
     *
     * @param path The directory path (e.g. "config/").
     * @return A list of asset names/paths.
     * @throws IOException if listing fails.
     */
    List<String> listAssets(String path) throws IOException;

    /**
     * Saves an asset to the data source.
     *
     * @param path The path to save the asset to.
     * @param data The asset data.
     * @throws IOException if saving fails.
     */
    void saveAsset(String path, byte[] data) throws IOException;

    /**
     * Deletes an asset from the data source.
     *
     * @param path The path of the asset to delete.
     * @throws IOException if deletion fails.
     */
    void deleteAsset(String path) throws IOException;
    
    /**
     * Gets metadata for an asset.
     * @param path The path of the asset.
     * @return A map of metadata (size, type, modification time, etc).
     * @throws IOException if metadata cannot be retrieved.
     */
    Map<String, Object> getAssetMetadata(String path) throws IOException;
}
