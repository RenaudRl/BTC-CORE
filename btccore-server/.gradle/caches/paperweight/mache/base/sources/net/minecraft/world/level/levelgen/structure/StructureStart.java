package net.minecraft.world.level.levelgen.structure;

import com.mojang.logging.LogUtils;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentStructure;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public final class StructureStart {
    public static final String INVALID_START_ID = "INVALID";
    public static final StructureStart INVALID_START = new StructureStart(null, new ChunkPos(0, 0), 0, new PiecesContainer(List.of()));
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Structure structure;
    private final PiecesContainer pieceContainer;
    private final ChunkPos chunkPos;
    private int references;
    private volatile @Nullable BoundingBox cachedBoundingBox;

    public StructureStart(Structure structure, ChunkPos chunkPos, int references, PiecesContainer pieceContainer) {
        this.structure = structure;
        this.chunkPos = chunkPos;
        this.references = references;
        this.pieceContainer = pieceContainer;
    }

    public static @Nullable StructureStart loadStaticStart(StructurePieceSerializationContext context, CompoundTag tag, long seed) {
        String stringOr = tag.getStringOr("id", "");
        if ("INVALID".equals(stringOr)) {
            return INVALID_START;
        } else {
            Registry<Structure> registry = context.registryAccess().lookupOrThrow(Registries.STRUCTURE);
            Structure structure = registry.getValue(Identifier.parse(stringOr));
            if (structure == null) {
                LOGGER.error("Unknown stucture id: {}", stringOr);
                return null;
            } else {
                ChunkPos chunkPos = new ChunkPos(tag.getIntOr("ChunkX", 0), tag.getIntOr("ChunkZ", 0));
                int intOr = tag.getIntOr("references", 0);
                ListTag listOrEmpty = tag.getListOrEmpty("Children");

                try {
                    PiecesContainer piecesContainer = PiecesContainer.load(listOrEmpty, context);
                    if (structure instanceof OceanMonumentStructure) {
                        piecesContainer = OceanMonumentStructure.regeneratePiecesAfterLoad(chunkPos, seed, piecesContainer);
                    }

                    return new StructureStart(structure, chunkPos, intOr, piecesContainer);
                } catch (Exception var11) {
                    LOGGER.error("Failed Start with id {}", stringOr, var11);
                    return null;
                }
            }
        }
    }

    public BoundingBox getBoundingBox() {
        BoundingBox boundingBox = this.cachedBoundingBox;
        if (boundingBox == null) {
            boundingBox = this.structure.adjustBoundingBox(this.pieceContainer.calculateBoundingBox());
            this.cachedBoundingBox = boundingBox;
        }

        return boundingBox;
    }

    public void placeInChunk(
        WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator, RandomSource random, BoundingBox box, ChunkPos chunkPos
    ) {
        List<StructurePiece> list = this.pieceContainer.pieces();
        if (!list.isEmpty()) {
            BoundingBox boundingBox = list.get(0).boundingBox;
            BlockPos center = boundingBox.getCenter();
            BlockPos blockPos = new BlockPos(center.getX(), boundingBox.minY(), center.getZ());

            for (StructurePiece structurePiece : list) {
                if (structurePiece.getBoundingBox().intersects(box)) {
                    structurePiece.postProcess(level, structureManager, generator, random, box, chunkPos, blockPos);
                }
            }

            this.structure.afterPlace(level, structureManager, generator, random, box, chunkPos, this.pieceContainer);
        }
    }

    public CompoundTag createTag(StructurePieceSerializationContext context, ChunkPos chunkPos) {
        CompoundTag compoundTag = new CompoundTag();
        if (this.isValid()) {
            compoundTag.putString("id", context.registryAccess().lookupOrThrow(Registries.STRUCTURE).getKey(this.structure).toString());
            compoundTag.putInt("ChunkX", chunkPos.x);
            compoundTag.putInt("ChunkZ", chunkPos.z);
            compoundTag.putInt("references", this.references);
            compoundTag.put("Children", this.pieceContainer.save(context));
            return compoundTag;
        } else {
            compoundTag.putString("id", "INVALID");
            return compoundTag;
        }
    }

    public boolean isValid() {
        return !this.pieceContainer.isEmpty();
    }

    public ChunkPos getChunkPos() {
        return this.chunkPos;
    }

    public boolean canBeReferenced() {
        return this.references < this.getMaxReferences();
    }

    public void addReference() {
        this.references++;
    }

    public int getReferences() {
        return this.references;
    }

    protected int getMaxReferences() {
        return 1;
    }

    public Structure getStructure() {
        return this.structure;
    }

    public List<StructurePiece> getPieces() {
        return this.pieceContainer.pieces();
    }
}
