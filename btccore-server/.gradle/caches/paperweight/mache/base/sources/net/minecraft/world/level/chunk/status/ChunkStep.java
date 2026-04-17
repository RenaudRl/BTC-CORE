package net.minecraft.world.level.chunk.status;

import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.util.profiling.jfr.JvmProfiler;
import net.minecraft.util.profiling.jfr.callback.ProfiledDuration;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.jspecify.annotations.Nullable;

public record ChunkStep(
    ChunkStatus targetStatus, ChunkDependencies directDependencies, ChunkDependencies accumulatedDependencies, int blockStateWriteRadius, ChunkStatusTask task
) {
    public int getAccumulatedRadiusOf(ChunkStatus status) {
        return status == this.targetStatus ? 0 : this.accumulatedDependencies.getRadiusOf(status);
    }

    public CompletableFuture<ChunkAccess> apply(WorldGenContext worldGenContext, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk) {
        if (chunk.getPersistedStatus().isBefore(this.targetStatus)) {
            ProfiledDuration profiledDuration = JvmProfiler.INSTANCE
                .onChunkGenerate(chunk.getPos(), worldGenContext.level().dimension(), this.targetStatus.getName());
            return this.task.doWork(worldGenContext, this, cache, chunk).thenApply(chunkAccess -> this.completeChunkGeneration(chunkAccess, profiledDuration));
        } else {
            return this.task.doWork(worldGenContext, this, cache, chunk);
        }
    }

    private ChunkAccess completeChunkGeneration(ChunkAccess chunk, @Nullable ProfiledDuration duration) {
        if (chunk instanceof ProtoChunk protoChunk && protoChunk.getPersistedStatus().isBefore(this.targetStatus)) {
            protoChunk.setPersistedStatus(this.targetStatus);
        }

        if (duration != null) {
            duration.finish(true);
        }

        return chunk;
    }

    public static class Builder {
        private final ChunkStatus status;
        private final @Nullable ChunkStep parent;
        private ChunkStatus[] directDependenciesByRadius;
        private int blockStateWriteRadius = -1;
        private ChunkStatusTask task = ChunkStatusTasks::passThrough;

        protected Builder(ChunkStatus status) {
            if (status.getParent() != status) {
                throw new IllegalArgumentException("Not starting with the first status: " + status);
            } else {
                this.status = status;
                this.parent = null;
                this.directDependenciesByRadius = new ChunkStatus[0];
            }
        }

        protected Builder(ChunkStatus status, ChunkStep parent) {
            if (parent.targetStatus.getIndex() != status.getIndex() - 1) {
                throw new IllegalArgumentException("Out of order status: " + status);
            } else {
                this.status = status;
                this.parent = parent;
                this.directDependenciesByRadius = new ChunkStatus[]{parent.targetStatus};
            }
        }

        public ChunkStep.Builder addRequirement(ChunkStatus status, int radius) {
            if (status.isOrAfter(this.status)) {
                throw new IllegalArgumentException("Status " + status + " can not be required by " + this.status);
            } else {
                ChunkStatus[] chunkStatuss = this.directDependenciesByRadius;
                int i = radius + 1;
                if (i > chunkStatuss.length) {
                    this.directDependenciesByRadius = new ChunkStatus[i];
                    Arrays.fill(this.directDependenciesByRadius, status);
                }

                for (int i1 = 0; i1 < Math.min(i, chunkStatuss.length); i1++) {
                    this.directDependenciesByRadius[i1] = ChunkStatus.max(chunkStatuss[i1], status);
                }

                return this;
            }
        }

        public ChunkStep.Builder blockStateWriteRadius(int blockStateWriteRadius) {
            this.blockStateWriteRadius = blockStateWriteRadius;
            return this;
        }

        public ChunkStep.Builder setTask(ChunkStatusTask task) {
            this.task = task;
            return this;
        }

        public ChunkStep build() {
            return new ChunkStep(
                this.status,
                new ChunkDependencies(ImmutableList.copyOf(this.directDependenciesByRadius)),
                new ChunkDependencies(ImmutableList.copyOf(this.buildAccumulatedDependencies())),
                this.blockStateWriteRadius,
                this.task
            );
        }

        private ChunkStatus[] buildAccumulatedDependencies() {
            if (this.parent == null) {
                return this.directDependenciesByRadius;
            } else {
                int radiusOfParent = this.getRadiusOfParent(this.parent.targetStatus);
                ChunkDependencies chunkDependencies = this.parent.accumulatedDependencies;
                ChunkStatus[] chunkStatuss = new ChunkStatus[Math.max(radiusOfParent + chunkDependencies.size(), this.directDependenciesByRadius.length)];

                for (int i = 0; i < chunkStatuss.length; i++) {
                    int i1 = i - radiusOfParent;
                    if (i1 < 0 || i1 >= chunkDependencies.size()) {
                        chunkStatuss[i] = this.directDependenciesByRadius[i];
                    } else if (i >= this.directDependenciesByRadius.length) {
                        chunkStatuss[i] = chunkDependencies.get(i1);
                    } else {
                        chunkStatuss[i] = ChunkStatus.max(this.directDependenciesByRadius[i], chunkDependencies.get(i1));
                    }
                }

                return chunkStatuss;
            }
        }

        private int getRadiusOfParent(ChunkStatus status) {
            for (int i = this.directDependenciesByRadius.length - 1; i >= 0; i--) {
                if (this.directDependenciesByRadius[i].isOrAfter(status)) {
                    return i;
                }
            }

            return 0;
        }
    }
}
