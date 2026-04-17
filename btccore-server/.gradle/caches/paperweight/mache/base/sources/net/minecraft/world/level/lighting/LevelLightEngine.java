package net.minecraft.world.level.lighting;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LightChunkGetter;
import org.jspecify.annotations.Nullable;

public class LevelLightEngine implements LightEventListener {
    public static final int LIGHT_SECTION_PADDING = 1;
    public static final LevelLightEngine EMPTY = new LevelLightEngine();
    protected final LevelHeightAccessor levelHeightAccessor;
    private final @Nullable LightEngine<?, ?> blockEngine;
    private final @Nullable LightEngine<?, ?> skyEngine;

    public LevelLightEngine(LightChunkGetter lightChunkGetter, boolean blockLight, boolean skyLight) {
        this.levelHeightAccessor = lightChunkGetter.getLevel();
        this.blockEngine = blockLight ? new BlockLightEngine(lightChunkGetter) : null;
        this.skyEngine = skyLight ? new SkyLightEngine(lightChunkGetter) : null;
    }

    private LevelLightEngine() {
        this.levelHeightAccessor = LevelHeightAccessor.create(0, 0);
        this.blockEngine = null;
        this.skyEngine = null;
    }

    @Override
    public void checkBlock(BlockPos pos) {
        if (this.blockEngine != null) {
            this.blockEngine.checkBlock(pos);
        }

        if (this.skyEngine != null) {
            this.skyEngine.checkBlock(pos);
        }
    }

    @Override
    public boolean hasLightWork() {
        return this.skyEngine != null && this.skyEngine.hasLightWork() || this.blockEngine != null && this.blockEngine.hasLightWork();
    }

    @Override
    public int runLightUpdates() {
        int i = 0;
        if (this.blockEngine != null) {
            i += this.blockEngine.runLightUpdates();
        }

        if (this.skyEngine != null) {
            i += this.skyEngine.runLightUpdates();
        }

        return i;
    }

    @Override
    public void updateSectionStatus(SectionPos pos, boolean isEmpty) {
        if (this.blockEngine != null) {
            this.blockEngine.updateSectionStatus(pos, isEmpty);
        }

        if (this.skyEngine != null) {
            this.skyEngine.updateSectionStatus(pos, isEmpty);
        }
    }

    @Override
    public void setLightEnabled(ChunkPos chunkPos, boolean lightEnabled) {
        if (this.blockEngine != null) {
            this.blockEngine.setLightEnabled(chunkPos, lightEnabled);
        }

        if (this.skyEngine != null) {
            this.skyEngine.setLightEnabled(chunkPos, lightEnabled);
        }
    }

    @Override
    public void propagateLightSources(ChunkPos chunkPos) {
        if (this.blockEngine != null) {
            this.blockEngine.propagateLightSources(chunkPos);
        }

        if (this.skyEngine != null) {
            this.skyEngine.propagateLightSources(chunkPos);
        }
    }

    public LayerLightEventListener getLayerListener(LightLayer lightLayer) {
        if (lightLayer == LightLayer.BLOCK) {
            return (LayerLightEventListener)(this.blockEngine == null ? LayerLightEventListener.DummyLightLayerEventListener.INSTANCE : this.blockEngine);
        } else {
            return (LayerLightEventListener)(this.skyEngine == null ? LayerLightEventListener.DummyLightLayerEventListener.INSTANCE : this.skyEngine);
        }
    }

    public String getDebugData(LightLayer lightLayer, SectionPos sectionPos) {
        if (lightLayer == LightLayer.BLOCK) {
            if (this.blockEngine != null) {
                return this.blockEngine.getDebugData(sectionPos.asLong());
            }
        } else if (this.skyEngine != null) {
            return this.skyEngine.getDebugData(sectionPos.asLong());
        }

        return "n/a";
    }

    public LayerLightSectionStorage.SectionType getDebugSectionType(LightLayer lightLayer, SectionPos sectionPos) {
        if (lightLayer == LightLayer.BLOCK) {
            if (this.blockEngine != null) {
                return this.blockEngine.getDebugSectionType(sectionPos.asLong());
            }
        } else if (this.skyEngine != null) {
            return this.skyEngine.getDebugSectionType(sectionPos.asLong());
        }

        return LayerLightSectionStorage.SectionType.EMPTY;
    }

    public void queueSectionData(LightLayer lightLayer, SectionPos sectionPos, @Nullable DataLayer dataLayer) {
        if (lightLayer == LightLayer.BLOCK) {
            if (this.blockEngine != null) {
                this.blockEngine.queueSectionData(sectionPos.asLong(), dataLayer);
            }
        } else if (this.skyEngine != null) {
            this.skyEngine.queueSectionData(sectionPos.asLong(), dataLayer);
        }
    }

    public void retainData(ChunkPos pos, boolean retain) {
        if (this.blockEngine != null) {
            this.blockEngine.retainData(pos, retain);
        }

        if (this.skyEngine != null) {
            this.skyEngine.retainData(pos, retain);
        }
    }

    public int getRawBrightness(BlockPos pos, int amount) {
        int i = this.skyEngine == null ? 0 : this.skyEngine.getLightValue(pos) - amount;
        int i1 = this.blockEngine == null ? 0 : this.blockEngine.getLightValue(pos);
        return Math.max(i1, i);
    }

    public boolean lightOnInColumn(long columnPos) {
        return this.blockEngine == null
            || this.blockEngine.storage.lightOnInColumn(columnPos) && (this.skyEngine == null || this.skyEngine.storage.lightOnInColumn(columnPos));
    }

    public int getLightSectionCount() {
        return this.levelHeightAccessor.getSectionsCount() + 2;
    }

    public int getMinLightSection() {
        return this.levelHeightAccessor.getMinSectionY() - 1;
    }

    public int getMaxLightSection() {
        return this.getMinLightSection() + this.getLightSectionCount();
    }
}
