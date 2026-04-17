package net.minecraft.network;

import com.google.common.collect.Queues;
import com.mojang.logging.LogUtils;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.flow.FlowControlHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.TimeoutException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import javax.crypto.Cipher;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.BundlerInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.handshake.ClientIntent;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.handshake.HandshakeProtocols;
import net.minecraft.network.protocol.handshake.ServerHandshakePacketListener;
import net.minecraft.network.protocol.login.ClientLoginPacketListener;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.network.protocol.status.ClientStatusPacketListener;
import net.minecraft.network.protocol.status.StatusProtocols;
import net.minecraft.server.RunningOnDifferentThreadException;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.util.debugchart.LocalSampleLogger;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

public class Connection extends SimpleChannelInboundHandler<Packet<?>> {
    private static final float AVERAGE_PACKETS_SMOOTHING = 0.75F;
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final Marker ROOT_MARKER = MarkerFactory.getMarker("NETWORK");
    public static final Marker PACKET_MARKER = Util.make(MarkerFactory.getMarker("NETWORK_PACKETS"), marker -> marker.add(ROOT_MARKER));
    public static final Marker PACKET_RECEIVED_MARKER = Util.make(MarkerFactory.getMarker("PACKET_RECEIVED"), marker -> marker.add(PACKET_MARKER));
    public static final Marker PACKET_SENT_MARKER = Util.make(MarkerFactory.getMarker("PACKET_SENT"), marker -> marker.add(PACKET_MARKER));
    private static final ProtocolInfo<ServerHandshakePacketListener> INITIAL_PROTOCOL = HandshakeProtocols.SERVERBOUND;
    private final PacketFlow receiving;
    private volatile boolean sendLoginDisconnect = true;
    private final Queue<Consumer<Connection>> pendingActions = Queues.newConcurrentLinkedQueue();
    public Channel channel;
    public SocketAddress address;
    private volatile @Nullable PacketListener disconnectListener;
    private volatile @Nullable PacketListener packetListener;
    private @Nullable DisconnectionDetails disconnectionDetails;
    private boolean encrypted;
    private boolean disconnectionHandled;
    private int receivedPackets;
    private int sentPackets;
    private float averageReceivedPackets;
    private float averageSentPackets;
    private int tickCount;
    private boolean handlingFault;
    private volatile @Nullable DisconnectionDetails delayedDisconnect;
    @Nullable BandwidthDebugMonitor bandwidthDebugMonitor;

    public Connection(PacketFlow receiving) {
        this.receiving = receiving;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        this.channel = ctx.channel();
        this.address = this.channel.remoteAddress();
        if (this.delayedDisconnect != null) {
            this.disconnect(this.delayedDisconnect);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        this.disconnect(Component.translatable("disconnect.endOfStream"));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable exception) {
        if (exception instanceof SkipPacketException) {
            LOGGER.debug("Skipping packet due to errors", exception.getCause());
        } else {
            boolean flag = !this.handlingFault;
            this.handlingFault = true;
            if (this.channel.isOpen()) {
                if (exception instanceof TimeoutException) {
                    LOGGER.debug("Timeout", exception);
                    this.disconnect(Component.translatable("disconnect.timeout"));
                } else {
                    Component component = Component.translatable("disconnect.genericReason", "Internal Exception: " + exception);
                    PacketListener packetListener = this.packetListener;
                    DisconnectionDetails disconnectionDetails;
                    if (packetListener != null) {
                        disconnectionDetails = packetListener.createDisconnectionInfo(component, exception);
                    } else {
                        disconnectionDetails = new DisconnectionDetails(component);
                    }

                    if (flag) {
                        LOGGER.debug("Failed to sent packet", exception);
                        if (this.getSending() == PacketFlow.CLIENTBOUND) {
                            Packet<?> packet = (Packet<?>)(this.sendLoginDisconnect
                                ? new ClientboundLoginDisconnectPacket(component)
                                : new ClientboundDisconnectPacket(component));
                            this.send(packet, PacketSendListener.thenRun(() -> this.disconnect(disconnectionDetails)));
                        } else {
                            this.disconnect(disconnectionDetails);
                        }

                        this.setReadOnly();
                    } else {
                        LOGGER.debug("Double fault", exception);
                        this.disconnect(disconnectionDetails);
                    }
                }
            }
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Packet<?> packet) {
        if (this.channel.isOpen()) {
            PacketListener packetListener = this.packetListener;
            if (packetListener == null) {
                throw new IllegalStateException("Received a packet before the packet listener was initialized");
            } else {
                if (packetListener.shouldHandleMessage(packet)) {
                    try {
                        genericsFtw(packet, packetListener);
                    } catch (RunningOnDifferentThreadException var5) {
                    } catch (RejectedExecutionException var6) {
                        this.disconnect(Component.translatable("multiplayer.disconnect.server_shutdown"));
                    } catch (ClassCastException var7) {
                        LOGGER.error("Received {} that couldn't be processed", packet.getClass(), var7);
                        this.disconnect(Component.translatable("multiplayer.disconnect.invalid_packet"));
                    }

                    this.receivedPackets++;
                }
            }
        }
    }

    private static <T extends PacketListener> void genericsFtw(Packet<T> packet, PacketListener listener) {
        packet.handle((T)listener);
    }

    private void validateListener(ProtocolInfo<?> protocolInfo, PacketListener packetListener) {
        Objects.requireNonNull(packetListener, "packetListener");
        PacketFlow packetFlow = packetListener.flow();
        if (packetFlow != this.receiving) {
            throw new IllegalStateException("Trying to set listener for wrong side: connection is " + this.receiving + ", but listener is " + packetFlow);
        } else {
            ConnectionProtocol connectionProtocol = packetListener.protocol();
            if (protocolInfo.id() != connectionProtocol) {
                throw new IllegalStateException("Listener protocol (" + connectionProtocol + ") does not match requested one " + protocolInfo);
            }
        }
    }

    private static void syncAfterConfigurationChange(ChannelFuture future) {
        try {
            future.syncUninterruptibly();
        } catch (Exception var2) {
            if (var2 instanceof ClosedChannelException) {
                LOGGER.info("Connection closed during protocol change");
            } else {
                throw var2;
            }
        }
    }

    public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocolInfo, T packetInfo) {
        this.validateListener(protocolInfo, packetInfo);
        if (protocolInfo.flow() != this.getReceiving()) {
            throw new IllegalStateException("Invalid inbound protocol: " + protocolInfo.id());
        } else {
            this.packetListener = packetInfo;
            this.disconnectListener = null;
            UnconfiguredPipelineHandler.InboundConfigurationTask inboundConfigurationTask = UnconfiguredPipelineHandler.setupInboundProtocol(protocolInfo);
            BundlerInfo bundlerInfo = protocolInfo.bundlerInfo();
            if (bundlerInfo != null) {
                PacketBundlePacker packetBundlePacker = new PacketBundlePacker(bundlerInfo);
                inboundConfigurationTask = inboundConfigurationTask.andThen(ctx -> ctx.pipeline().addAfter("decoder", "bundler", packetBundlePacker));
            }

            syncAfterConfigurationChange(this.channel.writeAndFlush(inboundConfigurationTask));
        }
    }

    public void setupOutboundProtocol(ProtocolInfo<?> protocolInfo) {
        if (protocolInfo.flow() != this.getSending()) {
            throw new IllegalStateException("Invalid outbound protocol: " + protocolInfo.id());
        } else {
            UnconfiguredPipelineHandler.OutboundConfigurationTask outboundConfigurationTask = UnconfiguredPipelineHandler.setupOutboundProtocol(protocolInfo);
            BundlerInfo bundlerInfo = protocolInfo.bundlerInfo();
            if (bundlerInfo != null) {
                PacketBundleUnpacker packetBundleUnpacker = new PacketBundleUnpacker(bundlerInfo);
                outboundConfigurationTask = outboundConfigurationTask.andThen(ctx -> ctx.pipeline().addAfter("encoder", "unbundler", packetBundleUnpacker));
            }

            boolean flag = protocolInfo.id() == ConnectionProtocol.LOGIN;
            syncAfterConfigurationChange(this.channel.writeAndFlush(outboundConfigurationTask.andThen(ctx -> this.sendLoginDisconnect = flag)));
        }
    }

    public void setListenerForServerboundHandshake(PacketListener packetListener) {
        if (this.packetListener != null) {
            throw new IllegalStateException("Listener already set");
        } else if (this.receiving == PacketFlow.SERVERBOUND
            && packetListener.flow() == PacketFlow.SERVERBOUND
            && packetListener.protocol() == INITIAL_PROTOCOL.id()) {
            this.packetListener = packetListener;
        } else {
            throw new IllegalStateException("Invalid initial listener");
        }
    }

    public void initiateServerboundStatusConnection(String hostName, int port, ClientStatusPacketListener packetListener) {
        this.initiateServerboundConnection(hostName, port, StatusProtocols.SERVERBOUND, StatusProtocols.CLIENTBOUND, packetListener, ClientIntent.STATUS);
    }

    public void initiateServerboundPlayConnection(String hostName, int port, ClientLoginPacketListener packetListener) {
        this.initiateServerboundConnection(hostName, port, LoginProtocols.SERVERBOUND, LoginProtocols.CLIENTBOUND, packetListener, ClientIntent.LOGIN);
    }

    public <S extends ServerboundPacketListener, C extends ClientboundPacketListener> void initiateServerboundPlayConnection(
        String hostName, int port, ProtocolInfo<S> serverboundProtocol, ProtocolInfo<C> clientbountProtocol, C packetListener, boolean isTransfer
    ) {
        this.initiateServerboundConnection(
            hostName, port, serverboundProtocol, clientbountProtocol, packetListener, isTransfer ? ClientIntent.TRANSFER : ClientIntent.LOGIN
        );
    }

    private <S extends ServerboundPacketListener, C extends ClientboundPacketListener> void initiateServerboundConnection(
        String hostName, int port, ProtocolInfo<S> serverboundProtocol, ProtocolInfo<C> clientboundProtocol, C packetListener, ClientIntent intention
    ) {
        if (serverboundProtocol.id() != clientboundProtocol.id()) {
            throw new IllegalStateException("Mismatched initial protocols");
        } else {
            this.disconnectListener = packetListener;
            this.runOnceConnected(connection -> {
                this.setupInboundProtocol(clientboundProtocol, packetListener);
                connection.sendPacket(new ClientIntentionPacket(SharedConstants.getCurrentVersion().protocolVersion(), hostName, port, intention), null, true);
                this.setupOutboundProtocol(serverboundProtocol);
            });
        }
    }

    public void send(Packet<?> packet) {
        this.send(packet, null);
    }

    public void send(Packet<?> packet, @Nullable ChannelFutureListener sendListener) {
        this.send(packet, sendListener, true);
    }

    public void send(Packet<?> packet, @Nullable ChannelFutureListener sendListener, boolean flush) {
        if (this.isConnected()) {
            this.flushQueue();
            this.sendPacket(packet, sendListener, flush);
        } else {
            this.pendingActions.add(connection -> connection.sendPacket(packet, sendListener, flush));
        }
    }

    public void runOnceConnected(Consumer<Connection> action) {
        if (this.isConnected()) {
            this.flushQueue();
            action.accept(this);
        } else {
            this.pendingActions.add(action);
        }
    }

    private void sendPacket(Packet<?> packet, @Nullable ChannelFutureListener sendListener, boolean flush) {
        this.sentPackets++;
        if (this.channel.eventLoop().inEventLoop()) {
            this.doSendPacket(packet, sendListener, flush);
        } else {
            this.channel.eventLoop().execute(() -> this.doSendPacket(packet, sendListener, flush));
        }
    }

    private void doSendPacket(Packet<?> packet, @Nullable ChannelFutureListener sendListener, boolean flush) {
        if (sendListener != null) {
            ChannelFuture channelFuture = flush ? this.channel.writeAndFlush(packet) : this.channel.write(packet);
            channelFuture.addListener(sendListener);
        } else if (flush) {
            this.channel.writeAndFlush(packet, this.channel.voidPromise());
        } else {
            this.channel.write(packet, this.channel.voidPromise());
        }
    }

    public void flushChannel() {
        if (this.isConnected()) {
            this.flush();
        } else {
            this.pendingActions.add(Connection::flush);
        }
    }

    private void flush() {
        if (this.channel.eventLoop().inEventLoop()) {
            this.channel.flush();
        } else {
            this.channel.eventLoop().execute(() -> this.channel.flush());
        }
    }

    private void flushQueue() {
        if (this.channel != null && this.channel.isOpen()) {
            synchronized (this.pendingActions) {
                Consumer<Connection> consumer;
                while ((consumer = this.pendingActions.poll()) != null) {
                    consumer.accept(this);
                }
            }
        }
    }

    public void tick() {
        this.flushQueue();
        if (this.packetListener instanceof TickablePacketListener tickablePacketListener) {
            tickablePacketListener.tick();
        }

        if (!this.isConnected() && !this.disconnectionHandled) {
            this.handleDisconnection();
        }

        if (this.channel != null) {
            this.channel.flush();
        }

        if (this.tickCount++ % 20 == 0) {
            this.tickSecond();
        }

        if (this.bandwidthDebugMonitor != null) {
            this.bandwidthDebugMonitor.tick();
        }
    }

    protected void tickSecond() {
        this.averageSentPackets = Mth.lerp(0.75F, (float)this.sentPackets, this.averageSentPackets);
        this.averageReceivedPackets = Mth.lerp(0.75F, (float)this.receivedPackets, this.averageReceivedPackets);
        this.sentPackets = 0;
        this.receivedPackets = 0;
    }

    public SocketAddress getRemoteAddress() {
        return this.address;
    }

    public String getLoggableAddress(boolean logIps) {
        if (this.address == null) {
            return "local";
        } else {
            return logIps ? this.address.toString() : "IP hidden";
        }
    }

    public void disconnect(Component reason) {
        this.disconnect(new DisconnectionDetails(reason));
    }

    public void disconnect(DisconnectionDetails disconnectionDetails) {
        if (this.channel == null) {
            this.delayedDisconnect = disconnectionDetails;
        }

        if (this.isConnected()) {
            this.channel.close().awaitUninterruptibly();
            this.disconnectionDetails = disconnectionDetails;
        }
    }

    public boolean isMemoryConnection() {
        return this.channel instanceof LocalChannel || this.channel instanceof LocalServerChannel;
    }

    public PacketFlow getReceiving() {
        return this.receiving;
    }

    public PacketFlow getSending() {
        return this.receiving.getOpposite();
    }

    public static Connection connectToServer(InetSocketAddress address, EventLoopGroupHolder eventLoopGroupHolder, @Nullable LocalSampleLogger sampleLogger) {
        Connection connection = new Connection(PacketFlow.CLIENTBOUND);
        if (sampleLogger != null) {
            connection.setBandwidthLogger(sampleLogger);
        }

        ChannelFuture channelFuture = connect(address, eventLoopGroupHolder, connection);
        channelFuture.syncUninterruptibly();
        return connection;
    }

    public static ChannelFuture connect(InetSocketAddress address, EventLoopGroupHolder eventLoopGroupHolder, final Connection connection) {
        return new Bootstrap().group(eventLoopGroupHolder.eventLoopGroup()).handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel channel) {
                try {
                    channel.config().setOption(ChannelOption.TCP_NODELAY, true);
                } catch (ChannelException var3) {
                }

                ChannelPipeline channelPipeline = channel.pipeline().addLast("timeout", new ReadTimeoutHandler(30));
                Connection.configureSerialization(channelPipeline, PacketFlow.CLIENTBOUND, false, connection.bandwidthDebugMonitor);
                connection.configurePacketHandler(channelPipeline);
            }
        }).channel(eventLoopGroupHolder.channelCls()).connect(address.getAddress(), address.getPort());
    }

    private static String outboundHandlerName(boolean clientbound) {
        return clientbound ? "encoder" : "outbound_config";
    }

    private static String inboundHandlerName(boolean serverbound) {
        return serverbound ? "decoder" : "inbound_config";
    }

    public void configurePacketHandler(ChannelPipeline pipeline) {
        pipeline.addLast("hackfix", new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext channelHandlerContext, Object object, ChannelPromise channelPromise) throws Exception {
                super.write(channelHandlerContext, object, channelPromise);
            }
        }).addLast("packet_handler", this);
    }

    public static void configureSerialization(
        ChannelPipeline pipeline, PacketFlow flow, boolean memoryOnly, @Nullable BandwidthDebugMonitor bandwidthDebugMonitor
    ) {
        PacketFlow opposite = flow.getOpposite();
        boolean flag = flow == PacketFlow.SERVERBOUND;
        boolean flag1 = opposite == PacketFlow.SERVERBOUND;
        pipeline.addLast("splitter", createFrameDecoder(bandwidthDebugMonitor, memoryOnly))
            .addLast(new FlowControlHandler())
            .addLast(inboundHandlerName(flag), (ChannelHandler)(flag ? new PacketDecoder<>(INITIAL_PROTOCOL) : new UnconfiguredPipelineHandler.Inbound()))
            .addLast("prepender", createFrameEncoder(memoryOnly))
            .addLast(outboundHandlerName(flag1), (ChannelHandler)(flag1 ? new PacketEncoder<>(INITIAL_PROTOCOL) : new UnconfiguredPipelineHandler.Outbound()));
    }

    private static ChannelOutboundHandler createFrameEncoder(boolean memoryOnly) {
        return (ChannelOutboundHandler)(memoryOnly ? new LocalFrameEncoder() : new Varint21LengthFieldPrepender());
    }

    private static ChannelInboundHandler createFrameDecoder(@Nullable BandwidthDebugMonitor bandwidthDebugMonitor, boolean memoryOnly) {
        if (!memoryOnly) {
            return new Varint21FrameDecoder(bandwidthDebugMonitor);
        } else {
            return (ChannelInboundHandler)(bandwidthDebugMonitor != null ? new MonitoredLocalFrameDecoder(bandwidthDebugMonitor) : new LocalFrameDecoder());
        }
    }

    public static void configureInMemoryPipeline(ChannelPipeline pipeline, PacketFlow flow) {
        configureSerialization(pipeline, flow, true, null);
    }

    public static Connection connectToLocalServer(SocketAddress address) {
        final Connection connection = new Connection(PacketFlow.CLIENTBOUND);
        new Bootstrap().group(EventLoopGroupHolder.local().eventLoopGroup()).handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel channel) {
                ChannelPipeline channelPipeline = channel.pipeline();
                Connection.configureInMemoryPipeline(channelPipeline, PacketFlow.CLIENTBOUND);
                connection.configurePacketHandler(channelPipeline);
            }
        }).channel(EventLoopGroupHolder.local().channelCls()).connect(address).syncUninterruptibly();
        return connection;
    }

    public void setEncryptionKey(Cipher decryptingCipher, Cipher encryptingCipher) {
        this.encrypted = true;
        this.channel.pipeline().addBefore("splitter", "decrypt", new CipherDecoder(decryptingCipher));
        this.channel.pipeline().addBefore("prepender", "encrypt", new CipherEncoder(encryptingCipher));
    }

    public boolean isEncrypted() {
        return this.encrypted;
    }

    public boolean isConnected() {
        return this.channel != null && this.channel.isOpen();
    }

    public boolean isConnecting() {
        return this.channel == null;
    }

    public @Nullable PacketListener getPacketListener() {
        return this.packetListener;
    }

    public @Nullable DisconnectionDetails getDisconnectionDetails() {
        return this.disconnectionDetails;
    }

    public void setReadOnly() {
        if (this.channel != null) {
            this.channel.config().setAutoRead(false);
        }
    }

    public void setupCompression(int threshold, boolean validateDecompressed) {
        if (threshold >= 0) {
            if (this.channel.pipeline().get("decompress") instanceof CompressionDecoder compressionDecoder) {
                compressionDecoder.setThreshold(threshold, validateDecompressed);
            } else {
                this.channel.pipeline().addAfter("splitter", "decompress", new CompressionDecoder(threshold, validateDecompressed));
            }

            if (this.channel.pipeline().get("compress") instanceof CompressionEncoder compressionEncoder) {
                compressionEncoder.setThreshold(threshold);
            } else {
                this.channel.pipeline().addAfter("prepender", "compress", new CompressionEncoder(threshold));
            }
        } else {
            if (this.channel.pipeline().get("decompress") instanceof CompressionDecoder) {
                this.channel.pipeline().remove("decompress");
            }

            if (this.channel.pipeline().get("compress") instanceof CompressionEncoder) {
                this.channel.pipeline().remove("compress");
            }
        }
    }

    public void handleDisconnection() {
        if (this.channel != null && !this.channel.isOpen()) {
            if (this.disconnectionHandled) {
                LOGGER.warn("handleDisconnection() called twice");
            } else {
                this.disconnectionHandled = true;
                PacketListener packetListener = this.getPacketListener();
                PacketListener packetListener1 = packetListener != null ? packetListener : this.disconnectListener;
                if (packetListener1 != null) {
                    DisconnectionDetails disconnectionDetails = Objects.requireNonNullElseGet(
                        this.getDisconnectionDetails(), () -> new DisconnectionDetails(Component.translatable("multiplayer.disconnect.generic"))
                    );
                    packetListener1.onDisconnect(disconnectionDetails);
                }
            }
        }
    }

    public float getAverageReceivedPackets() {
        return this.averageReceivedPackets;
    }

    public float getAverageSentPackets() {
        return this.averageSentPackets;
    }

    public void setBandwidthLogger(LocalSampleLogger bandwidthLogger) {
        this.bandwidthDebugMonitor = new BandwidthDebugMonitor(bandwidthLogger);
    }
}
