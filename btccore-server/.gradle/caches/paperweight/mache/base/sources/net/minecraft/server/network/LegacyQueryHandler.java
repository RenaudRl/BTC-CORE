package net.minecraft.server.network;

import com.mojang.logging.LogUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.net.SocketAddress;
import java.util.Locale;
import net.minecraft.server.ServerInfo;
import org.slf4j.Logger;

public class LegacyQueryHandler extends ChannelInboundHandlerAdapter {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final ServerInfo server;

    public LegacyQueryHandler(ServerInfo server) {
        this.server = server;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message) {
        ByteBuf byteBuf = (ByteBuf)message;
        byteBuf.markReaderIndex();
        boolean flag = true;

        try {
            try {
                if (byteBuf.readUnsignedByte() != 254) {
                    return;
                }

                SocketAddress socketAddress = ctx.channel().remoteAddress();
                int i = byteBuf.readableBytes();
                if (i == 0) {
                    LOGGER.debug("Ping: (<1.3.x) from {}", socketAddress);
                    String string = createVersion0Response(this.server);
                    sendFlushAndClose(ctx, createLegacyDisconnectPacket(ctx.alloc(), string));
                } else {
                    if (byteBuf.readUnsignedByte() != 1) {
                        return;
                    }

                    if (byteBuf.isReadable()) {
                        if (!readCustomPayloadPacket(byteBuf)) {
                            return;
                        }

                        LOGGER.debug("Ping: (1.6) from {}", socketAddress);
                    } else {
                        LOGGER.debug("Ping: (1.4-1.5.x) from {}", socketAddress);
                    }

                    String string = createVersion1Response(this.server);
                    sendFlushAndClose(ctx, createLegacyDisconnectPacket(ctx.alloc(), string));
                }

                byteBuf.release();
                flag = false;
            } catch (RuntimeException var11) {
            }
        } finally {
            if (flag) {
                byteBuf.resetReaderIndex();
                ctx.channel().pipeline().remove(this);
                ctx.fireChannelRead(message);
            }
        }
    }

    private static boolean readCustomPayloadPacket(ByteBuf buffer) {
        short unsignedByte = buffer.readUnsignedByte();
        if (unsignedByte != 250) {
            return false;
        } else {
            String legacyString = LegacyProtocolUtils.readLegacyString(buffer);
            if (!"MC|PingHost".equals(legacyString)) {
                return false;
            } else {
                int unsignedShort = buffer.readUnsignedShort();
                if (buffer.readableBytes() != unsignedShort) {
                    return false;
                } else {
                    short unsignedByte1 = buffer.readUnsignedByte();
                    if (unsignedByte1 < 73) {
                        return false;
                    } else {
                        String legacyString1 = LegacyProtocolUtils.readLegacyString(buffer);
                        int _int = buffer.readInt();
                        return _int <= 65535;
                    }
                }
            }
        }
    }

    private static String createVersion0Response(ServerInfo server) {
        return String.format(Locale.ROOT, "%s§%d§%d", server.getMotd(), server.getPlayerCount(), server.getMaxPlayers());
    }

    private static String createVersion1Response(ServerInfo server) {
        return String.format(
            Locale.ROOT,
            "§1\u0000%d\u0000%s\u0000%s\u0000%d\u0000%d",
            127,
            server.getServerVersion(),
            server.getMotd(),
            server.getPlayerCount(),
            server.getMaxPlayers()
        );
    }

    private static void sendFlushAndClose(ChannelHandlerContext ctx, ByteBuf buffer) {
        ctx.pipeline().firstContext().writeAndFlush(buffer).addListener(ChannelFutureListener.CLOSE);
    }

    private static ByteBuf createLegacyDisconnectPacket(ByteBufAllocator bufferAllocator, String reason) {
        ByteBuf byteBuf = bufferAllocator.buffer();
        byteBuf.writeByte(255);
        LegacyProtocolUtils.writeLegacyString(byteBuf, reason);
        return byteBuf;
    }
}
