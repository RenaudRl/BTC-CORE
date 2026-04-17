package net.minecraft.world.entity.raid;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap.Entry;
import java.util.Iterator;
import java.util.List;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.util.VisibleForDebug;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public class Raids extends SavedData {
    private static final String RAID_FILE_ID = "raids";
    public static final Codec<Raids> CODEC = RecordCodecBuilder.create(
        instance -> instance.group(
                Raids.RaidWithId.CODEC
                    .listOf()
                    .optionalFieldOf("raids", List.of())
                    .forGetter(raids -> raids.raidMap.int2ObjectEntrySet().stream().map(Raids.RaidWithId::from).toList()),
                Codec.INT.fieldOf("next_id").forGetter(raids -> raids.nextId),
                Codec.INT.fieldOf("tick").forGetter(raids -> raids.tick)
            )
            .apply(instance, Raids::new)
    );
    public static final SavedDataType<Raids> TYPE = new SavedDataType<>("raids", Raids::new, CODEC, DataFixTypes.SAVED_DATA_RAIDS);
    public static final SavedDataType<Raids> TYPE_END = new SavedDataType<>("raids_end", Raids::new, CODEC, DataFixTypes.SAVED_DATA_RAIDS);
    public final Int2ObjectMap<Raid> raidMap = new Int2ObjectOpenHashMap<>();
    private int nextId = 1;
    private int tick;

    public static SavedDataType<Raids> getType(Holder<DimensionType> dimension) {
        return dimension.is(BuiltinDimensionTypes.END) ? TYPE_END : TYPE;
    }

    public Raids() {
        this.setDirty();
    }

    private Raids(List<Raids.RaidWithId> raids, int nextId, int tick) {
        for (Raids.RaidWithId raidWithId : raids) {
            this.raidMap.put(raidWithId.id, raidWithId.raid);
        }

        this.nextId = nextId;
        this.tick = tick;
    }

    public @Nullable Raid get(int id) {
        return this.raidMap.get(id);
    }

    public OptionalInt getId(Raid raid) {
        for (Entry<Raid> entry : this.raidMap.int2ObjectEntrySet()) {
            if (entry.getValue() == raid) {
                return OptionalInt.of(entry.getIntKey());
            }
        }

        return OptionalInt.empty();
    }

    public void tick(ServerLevel level) {
        this.tick++;
        Iterator<Raid> iterator = this.raidMap.values().iterator();

        while (iterator.hasNext()) {
            Raid raid = iterator.next();
            if (!level.getGameRules().get(GameRules.RAIDS)) {
                raid.stop();
            }

            if (raid.isStopped()) {
                iterator.remove();
                this.setDirty();
            } else {
                raid.tick(level);
            }
        }

        if (this.tick % 200 == 0) {
            this.setDirty();
        }
    }

    public static boolean canJoinRaid(Raider raider) {
        return raider.isAlive() && raider.canJoinRaid() && raider.getNoActionTime() <= 2400;
    }

    public @Nullable Raid createOrExtendRaid(ServerPlayer player, BlockPos pos) {
        if (player.isSpectator()) {
            return null;
        } else {
            ServerLevel serverLevel = player.level();
            if (!serverLevel.getGameRules().get(GameRules.RAIDS)) {
                return null;
            } else if (!serverLevel.environmentAttributes().getValue(EnvironmentAttributes.CAN_START_RAID, pos)) {
                return null;
            } else {
                List<PoiRecord> list = serverLevel.getPoiManager()
                    .getInRange(holder -> holder.is(PoiTypeTags.VILLAGE), pos, 64, PoiManager.Occupancy.IS_OCCUPIED)
                    .toList();
                int i = 0;
                Vec3 vec3 = Vec3.ZERO;

                for (PoiRecord poiRecord : list) {
                    BlockPos pos1 = poiRecord.getPos();
                    vec3 = vec3.add(pos1.getX(), pos1.getY(), pos1.getZ());
                    i++;
                }

                BlockPos blockPos;
                if (i > 0) {
                    vec3 = vec3.scale(1.0 / i);
                    blockPos = BlockPos.containing(vec3);
                } else {
                    blockPos = pos;
                }

                Raid raid = this.getOrCreateRaid(serverLevel, blockPos);
                if (!raid.isStarted() && !this.raidMap.containsValue(raid)) {
                    this.raidMap.put(this.getUniqueId(), raid);
                }

                if (!raid.isStarted() || raid.getRaidOmenLevel() < raid.getMaxRaidOmenLevel()) {
                    raid.absorbRaidOmen(player);
                }

                this.setDirty();
                return raid;
            }
        }
    }

    private Raid getOrCreateRaid(ServerLevel level, BlockPos pos) {
        Raid raidAt = level.getRaidAt(pos);
        return raidAt != null ? raidAt : new Raid(pos, level.getDifficulty());
    }

    public static Raids load(CompoundTag tag) {
        return CODEC.parse(NbtOps.INSTANCE, tag).resultOrPartial().orElseGet(Raids::new);
    }

    private int getUniqueId() {
        return ++this.nextId;
    }

    public @Nullable Raid getNearbyRaid(BlockPos pos, int distance) {
        Raid raid = null;
        double d = distance;

        for (Raid raid1 : this.raidMap.values()) {
            double d1 = raid1.getCenter().distSqr(pos);
            if (raid1.isActive() && d1 < d) {
                raid = raid1;
                d = d1;
            }
        }

        return raid;
    }

    @VisibleForDebug
    public List<BlockPos> getRaidCentersInChunk(ChunkPos chunkPos) {
        return this.raidMap.values().stream().map(Raid::getCenter).filter(chunkPos::contains).toList();
    }

    record RaidWithId(int id, Raid raid) {
        public static final Codec<Raids.RaidWithId> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(Codec.INT.fieldOf("id").forGetter(Raids.RaidWithId::id), Raid.MAP_CODEC.forGetter(Raids.RaidWithId::raid))
                .apply(instance, Raids.RaidWithId::new)
        );

        public static Raids.RaidWithId from(Entry<Raid> entry) {
            return new Raids.RaidWithId(entry.getIntKey(), entry.getValue());
        }
    }
}
