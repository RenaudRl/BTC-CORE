package net.minecraft.world.level;

import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import net.minecraft.SharedConstants;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class TicketStorage extends SavedData {
    private static final int INITIAL_TICKET_LIST_CAPACITY = 4;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Codec<Pair<ChunkPos, Ticket>> TICKET_ENTRY = Codec.mapPair(ChunkPos.CODEC.fieldOf("chunk_pos"), Ticket.CODEC).codec();
    public static final Codec<TicketStorage> CODEC = RecordCodecBuilder.create(
        instance -> instance.group(TICKET_ENTRY.listOf().optionalFieldOf("tickets", List.of()).forGetter(TicketStorage::packTickets))
            .apply(instance, TicketStorage::fromPacked)
    );
    public static final SavedDataType<TicketStorage> TYPE = new SavedDataType<>("chunks", TicketStorage::new, CODEC, DataFixTypes.SAVED_DATA_FORCED_CHUNKS);
    public final Long2ObjectOpenHashMap<List<Ticket>> tickets;
    private final Long2ObjectOpenHashMap<List<Ticket>> deactivatedTickets;
    private LongSet chunksWithForcedTickets = new LongOpenHashSet();
    private TicketStorage.@Nullable ChunkUpdated loadingChunkUpdatedListener;
    private TicketStorage.@Nullable ChunkUpdated simulationChunkUpdatedListener;

    private TicketStorage(Long2ObjectOpenHashMap<List<Ticket>> tickets, Long2ObjectOpenHashMap<List<Ticket>> deactivatedTickets) {
        this.tickets = tickets;
        this.deactivatedTickets = deactivatedTickets;
        this.updateForcedChunks();
    }

    public TicketStorage() {
        this(new Long2ObjectOpenHashMap<>(4), new Long2ObjectOpenHashMap<>());
    }

    private static TicketStorage fromPacked(List<Pair<ChunkPos, Ticket>> packed) {
        Long2ObjectOpenHashMap<List<Ticket>> map = new Long2ObjectOpenHashMap<>();

        for (Pair<ChunkPos, Ticket> pair : packed) {
            ChunkPos chunkPos = pair.getFirst();
            List<Ticket> list = map.computeIfAbsent(chunkPos.toLong(), l -> new ObjectArrayList<>(4));
            list.add(pair.getSecond());
        }

        return new TicketStorage(new Long2ObjectOpenHashMap<>(4), map);
    }

    private List<Pair<ChunkPos, Ticket>> packTickets() {
        List<Pair<ChunkPos, Ticket>> list = new ArrayList<>();
        this.forEachTicket((chunkPos, ticket) -> {
            if (ticket.getType().persist()) {
                list.add(new Pair<>(chunkPos, ticket));
            }
        });
        return list;
    }

    private void forEachTicket(BiConsumer<ChunkPos, Ticket> action) {
        forEachTicket(action, this.tickets);
        forEachTicket(action, this.deactivatedTickets);
    }

    private static void forEachTicket(BiConsumer<ChunkPos, Ticket> action, Long2ObjectOpenHashMap<List<Ticket>> tickets) {
        for (Entry<List<Ticket>> entry : Long2ObjectMaps.fastIterable(tickets)) {
            ChunkPos chunkPos = new ChunkPos(entry.getLongKey());

            for (Ticket ticket : entry.getValue()) {
                action.accept(chunkPos, ticket);
            }
        }
    }

    public void activateAllDeactivatedTickets() {
        for (Entry<List<Ticket>> entry : Long2ObjectMaps.fastIterable(this.deactivatedTickets)) {
            for (Ticket ticket : entry.getValue()) {
                this.addTicket(entry.getLongKey(), ticket);
            }
        }

        this.deactivatedTickets.clear();
    }

    public void setLoadingChunkUpdatedListener(TicketStorage.@Nullable ChunkUpdated loadingChunkUpdatedListener) {
        this.loadingChunkUpdatedListener = loadingChunkUpdatedListener;
    }

    public void setSimulationChunkUpdatedListener(TicketStorage.@Nullable ChunkUpdated simulationChunkUpdatedListener) {
        this.simulationChunkUpdatedListener = simulationChunkUpdatedListener;
    }

    public boolean hasTickets() {
        return !this.tickets.isEmpty();
    }

    public boolean shouldKeepDimensionActive() {
        for (List<Ticket> list : this.tickets.values()) {
            for (Ticket ticket : list) {
                if (ticket.getType().shouldKeepDimensionActive()) {
                    return true;
                }
            }
        }

        return false;
    }

    public List<Ticket> getTickets(long chunkPos) {
        return this.tickets.getOrDefault(chunkPos, List.of());
    }

    private List<Ticket> getOrCreateTickets(long chunkPos) {
        return this.tickets.computeIfAbsent(chunkPos, l -> new ObjectArrayList<>(4));
    }

    public void addTicketWithRadius(TicketType ticketType, ChunkPos chunkPos, int radius) {
        Ticket ticket = new Ticket(ticketType, ChunkLevel.byStatus(FullChunkStatus.FULL) - radius);
        this.addTicket(chunkPos.toLong(), ticket);
    }

    public void addTicket(Ticket ticket, ChunkPos chunkPos) {
        this.addTicket(chunkPos.toLong(), ticket);
    }

    public boolean addTicket(long chunkPos, Ticket ticket) {
        List<Ticket> tickets = this.getOrCreateTickets(chunkPos);

        for (Ticket ticket1 : tickets) {
            if (isTicketSameTypeAndLevel(ticket, ticket1)) {
                ticket1.resetTicksLeft();
                this.setDirty();
                return false;
            }
        }

        int ticketLevelAt = getTicketLevelAt(tickets, true);
        int ticketLevelAt1 = getTicketLevelAt(tickets, false);
        tickets.add(ticket);
        if (SharedConstants.DEBUG_VERBOSE_SERVER_EVENTS) {
            LOGGER.debug("ATI {} {}", new ChunkPos(chunkPos), ticket);
        }

        if (ticket.getType().doesSimulate() && ticket.getTicketLevel() < ticketLevelAt && this.simulationChunkUpdatedListener != null) {
            this.simulationChunkUpdatedListener.update(chunkPos, ticket.getTicketLevel(), true);
        }

        if (ticket.getType().doesLoad() && ticket.getTicketLevel() < ticketLevelAt1 && this.loadingChunkUpdatedListener != null) {
            this.loadingChunkUpdatedListener.update(chunkPos, ticket.getTicketLevel(), true);
        }

        if (ticket.getType().equals(TicketType.FORCED)) {
            this.chunksWithForcedTickets.add(chunkPos);
        }

        this.setDirty();
        return true;
    }

    private static boolean isTicketSameTypeAndLevel(Ticket first, Ticket second) {
        return second.getType() == first.getType() && second.getTicketLevel() == first.getTicketLevel();
    }

    public int getTicketLevelAt(long chunkPos, boolean requireSimulation) {
        return getTicketLevelAt(this.getTickets(chunkPos), requireSimulation);
    }

    private static int getTicketLevelAt(List<Ticket> tickets, boolean requireSimulation) {
        Ticket lowestTicket = getLowestTicket(tickets, requireSimulation);
        return lowestTicket == null ? ChunkLevel.MAX_LEVEL + 1 : lowestTicket.getTicketLevel();
    }

    private static @Nullable Ticket getLowestTicket(@Nullable List<Ticket> tickets, boolean requireSimulation) {
        if (tickets == null) {
            return null;
        } else {
            Ticket ticket = null;

            for (Ticket ticket1 : tickets) {
                if (ticket == null || ticket1.getTicketLevel() < ticket.getTicketLevel()) {
                    if (requireSimulation && ticket1.getType().doesSimulate()) {
                        ticket = ticket1;
                    } else if (!requireSimulation && ticket1.getType().doesLoad()) {
                        ticket = ticket1;
                    }
                }
            }

            return ticket;
        }
    }

    public void removeTicketWithRadius(TicketType ticketType, ChunkPos chunkPos, int radius) {
        Ticket ticket = new Ticket(ticketType, ChunkLevel.byStatus(FullChunkStatus.FULL) - radius);
        this.removeTicket(chunkPos.toLong(), ticket);
    }

    public void removeTicket(Ticket ticket, ChunkPos chunkPos) {
        this.removeTicket(chunkPos.toLong(), ticket);
    }

    public boolean removeTicket(long chunkPos, Ticket ticket) {
        List<Ticket> list = this.tickets.get(chunkPos);
        if (list == null) {
            return false;
        } else {
            boolean flag = false;
            Iterator<Ticket> iterator = list.iterator();

            while (iterator.hasNext()) {
                Ticket ticket1 = iterator.next();
                if (isTicketSameTypeAndLevel(ticket, ticket1)) {
                    iterator.remove();
                    if (SharedConstants.DEBUG_VERBOSE_SERVER_EVENTS) {
                        LOGGER.debug("RTI {} {}", new ChunkPos(chunkPos), ticket1);
                    }

                    flag = true;
                    break;
                }
            }

            if (!flag) {
                return false;
            } else {
                if (list.isEmpty()) {
                    this.tickets.remove(chunkPos);
                }

                if (ticket.getType().doesSimulate() && this.simulationChunkUpdatedListener != null) {
                    this.simulationChunkUpdatedListener.update(chunkPos, getTicketLevelAt(list, true), false);
                }

                if (ticket.getType().doesLoad() && this.loadingChunkUpdatedListener != null) {
                    this.loadingChunkUpdatedListener.update(chunkPos, getTicketLevelAt(list, false), false);
                }

                if (ticket.getType().equals(TicketType.FORCED)) {
                    this.updateForcedChunks();
                }

                this.setDirty();
                return true;
            }
        }
    }

    private void updateForcedChunks() {
        this.chunksWithForcedTickets = this.getAllChunksWithTicketThat(ticket -> ticket.getType().equals(TicketType.FORCED));
    }

    public String getTicketDebugString(long chunkPos, boolean requireSimulation) {
        List<Ticket> tickets = this.getTickets(chunkPos);
        Ticket lowestTicket = getLowestTicket(tickets, requireSimulation);
        return lowestTicket == null ? "no_ticket" : lowestTicket.toString();
    }

    public void purgeStaleTickets(ChunkMap map) {
        this.removeTicketIf((ticket, chunkPos) -> {
            if (this.canTicketExpire(map, ticket, chunkPos)) {
                ticket.decreaseTicksLeft();
                return ticket.isTimedOut();
            } else {
                return false;
            }
        }, null);
        this.setDirty();
    }

    private boolean canTicketExpire(ChunkMap map, Ticket ticket, long chunkPos) {
        if (!ticket.getType().hasTimeout()) {
            return false;
        } else if (ticket.getType().canExpireIfUnloaded()) {
            return true;
        } else {
            ChunkHolder updatingChunkIfPresent = map.getUpdatingChunkIfPresent(chunkPos);
            return updatingChunkIfPresent == null || updatingChunkIfPresent.isReadyForSaving();
        }
    }

    public void deactivateTicketsOnClosing() {
        this.removeTicketIf((ticket, chunkPos) -> ticket.getType() != TicketType.UNKNOWN, this.deactivatedTickets);
    }

    public void removeTicketIf(TicketStorage.TicketPredicate predicate, @Nullable Long2ObjectOpenHashMap<List<Ticket>> tickets) {
        ObjectIterator<Entry<List<Ticket>>> objectIterator = this.tickets.long2ObjectEntrySet().fastIterator();
        boolean flag = false;

        while (objectIterator.hasNext()) {
            Entry<List<Ticket>> entry = objectIterator.next();
            Iterator<Ticket> iterator = entry.getValue().iterator();
            long longKey = entry.getLongKey();
            boolean flag1 = false;
            boolean flag2 = false;

            while (iterator.hasNext()) {
                Ticket ticket = iterator.next();
                if (predicate.test(ticket, longKey)) {
                    if (tickets != null) {
                        List<Ticket> list = tickets.computeIfAbsent(longKey, chunkPos -> new ObjectArrayList<>(entry.getValue().size()));
                        list.add(ticket);
                    }

                    iterator.remove();
                    if (ticket.getType().doesLoad()) {
                        flag2 = true;
                    }

                    if (ticket.getType().doesSimulate()) {
                        flag1 = true;
                    }

                    if (ticket.getType().equals(TicketType.FORCED)) {
                        flag = true;
                    }
                }
            }

            if (flag2 || flag1) {
                if (flag2 && this.loadingChunkUpdatedListener != null) {
                    this.loadingChunkUpdatedListener.update(longKey, getTicketLevelAt(entry.getValue(), false), false);
                }

                if (flag1 && this.simulationChunkUpdatedListener != null) {
                    this.simulationChunkUpdatedListener.update(longKey, getTicketLevelAt(entry.getValue(), true), false);
                }

                this.setDirty();
                if (entry.getValue().isEmpty()) {
                    objectIterator.remove();
                }
            }
        }

        if (flag) {
            this.updateForcedChunks();
        }
    }

    public void replaceTicketLevelOfType(int level, TicketType ticketType) {
        List<Pair<Ticket, Long>> list = new ArrayList<>();

        for (Entry<List<Ticket>> entry : this.tickets.long2ObjectEntrySet()) {
            for (Ticket ticket : entry.getValue()) {
                if (ticket.getType() == ticketType) {
                    list.add(Pair.of(ticket, entry.getLongKey()));
                }
            }
        }

        for (Pair<Ticket, Long> pair : list) {
            Long _long = pair.getSecond();
            Ticket ticketx = pair.getFirst();
            this.removeTicket(_long, ticketx);
            TicketType type = ticketx.getType();
            this.addTicket(_long, new Ticket(type, level));
        }
    }

    public boolean updateChunkForced(ChunkPos chunkPos, boolean add) {
        Ticket ticket = new Ticket(TicketType.FORCED, ChunkMap.FORCED_TICKET_LEVEL);
        return add ? this.addTicket(chunkPos.toLong(), ticket) : this.removeTicket(chunkPos.toLong(), ticket);
    }

    public LongSet getForceLoadedChunks() {
        return this.chunksWithForcedTickets;
    }

    private LongSet getAllChunksWithTicketThat(Predicate<Ticket> predicate) {
        LongOpenHashSet set = new LongOpenHashSet();

        for (Entry<List<Ticket>> entry : Long2ObjectMaps.fastIterable(this.tickets)) {
            for (Ticket ticket : entry.getValue()) {
                if (predicate.test(ticket)) {
                    set.add(entry.getLongKey());
                    break;
                }
            }
        }

        return set;
    }

    @FunctionalInterface
    public interface ChunkUpdated {
        void update(long chunkPos, int ticketLevel, boolean isDecreasing);
    }

    public interface TicketPredicate {
        boolean test(Ticket ticket, long chunkPos);
    }
}
