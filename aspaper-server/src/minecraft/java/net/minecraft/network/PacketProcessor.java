package net.minecraft.network;

import com.google.common.collect.Queues;
import com.mojang.logging.LogUtils;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;
import net.minecraft.ReportedException;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketUtils;
import org.slf4j.Logger;

public class PacketProcessor implements AutoCloseable {
    static final Logger LOGGER = LogUtils.getLogger();
    private final Queue<PacketProcessor.ListenerAndPacket<?>> packetsToBeHandled = Queues.newConcurrentLinkedQueue();
    private final Thread runningThread;
    private boolean closed;

    public PacketProcessor(Thread runningThread) {
        this.runningThread = runningThread;
    }

    // Paper start - improve tick loop
    public final boolean executeSinglePacket() {
        if (this.closed) {
            return false;
        }

        final PacketProcessor.ListenerAndPacket<?> task = this.packetsToBeHandled.poll();
        if (task == null) {
            return false;
        }

        task.handle();
        return true;
    }
    // Paper end - improve tick loop

    public boolean isSameThread() {
        return Thread.currentThread() == this.runningThread;
    }

    public <T extends PacketListener> void scheduleIfPossible(T listener, Packet<T> packet) {
        if (this.closed) {
            throw new RejectedExecutionException("Server already shutting down");
        } else {
            // Paper start - improve tick loop
            // wake up main thread inbetween ticks to process packets
            final boolean isEmpty = this.packetsToBeHandled.isEmpty();
            final ListenerAndPacket<T> toAdd = new PacketProcessor.ListenerAndPacket<>(listener, packet);
            this.packetsToBeHandled.add(toAdd);
            if (isEmpty || this.packetsToBeHandled.peek() == toAdd) {
                // only unpark if we are the first packet OR are at the head of the queue
                // we unpark if we are at the head in case the main thread emptied the queue
                // immediately before we added but after checking isEmpty
                java.util.concurrent.locks.LockSupport.unpark(this.runningThread);
            }
            // Paper end - improve tick loop
        }
    }

    public void processQueuedPackets() {
        if (!this.closed) {
            while (!this.packetsToBeHandled.isEmpty()) {
                this.packetsToBeHandled.poll().handle();
            }
        }
    }

    @Override
    public void close() {
        this.closed = true;
    }

    // Paper start - detailed watchdog information
    public static final java.util.concurrent.ConcurrentLinkedDeque<PacketListener> packetProcessing = new java.util.concurrent.ConcurrentLinkedDeque<>();
    static final java.util.concurrent.atomic.AtomicLong totalMainThreadPacketsProcessed = new java.util.concurrent.atomic.AtomicLong();

    public static long getTotalProcessedPackets() {
        return totalMainThreadPacketsProcessed.get();
    }

    public static java.util.List<PacketListener> getCurrentPacketProcessors() {
        java.util.List<PacketListener> listeners = new java.util.ArrayList<>(4);
        for (PacketListener listener : packetProcessing) {
            listeners.add(listener);
        }

        return listeners;
    }
    // Paper end - detailed watchdog information

    record ListenerAndPacket<T extends PacketListener>(T listener, Packet<T> packet) {
        public void handle() {
            packetProcessing.push(this.listener); // Paper - detailed watchdog information
            try { // Paper - detailed watchdog information
            if (this.listener instanceof net.minecraft.server.network.ServerCommonPacketListenerImpl serverCommonPacketListener && serverCommonPacketListener.processedDisconnect) return; // Paper - Don't handle sync packets for kicked players
            if (this.listener.shouldHandleMessage(this.packet)) {
                try {
                    this.packet.handle(this.listener);
                } catch (Exception var3) {
                    if (var3 instanceof ReportedException reportedException && reportedException.getCause() instanceof OutOfMemoryError) {
                        throw PacketUtils.makeReportedException(var3, this.packet, this.listener);
                    }

                    this.listener.onPacketError(this.packet, var3);
                }
            } else {
                PacketProcessor.LOGGER.debug("Ignoring packet due to disconnection: {}", this.packet);
            }
            // Paper start - detailed watchdog information
            } finally {
                totalMainThreadPacketsProcessed.getAndIncrement();
                packetProcessing.pop();
            }
            // Paper end - detailed watchdog information
        }
    }
}
