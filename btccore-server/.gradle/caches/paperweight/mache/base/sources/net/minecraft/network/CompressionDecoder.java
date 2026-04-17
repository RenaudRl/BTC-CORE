package net.minecraft.network;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.DecoderException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

public class CompressionDecoder extends ByteToMessageDecoder {
    public static final int MAXIMUM_COMPRESSED_LENGTH = 2097152;
    public static final int MAXIMUM_UNCOMPRESSED_LENGTH = 8388608;
    private Inflater inflater;
    private int threshold;
    private boolean validateDecompressed;

    public CompressionDecoder(int threshold, boolean validateDecompressed) {
        this.threshold = threshold;
        this.validateDecompressed = validateDecompressed;
        this.inflater = new Inflater();
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        int i = VarInt.read(in);
        if (i == 0) {
            out.add(in.readBytes(in.readableBytes()));
        } else {
            if (this.validateDecompressed) {
                if (i < this.threshold) {
                    throw new DecoderException("Badly compressed packet - size of " + i + " is below server threshold of " + this.threshold);
                }

                if (i > 8388608) {
                    throw new DecoderException("Badly compressed packet - size of " + i + " is larger than protocol maximum of 8388608");
                }
            }

            this.setupInflaterInput(in);
            ByteBuf byteBuf = this.inflate(ctx, i);
            this.inflater.reset();
            out.add(byteBuf);
        }
    }

    private void setupInflaterInput(ByteBuf buffer) {
        ByteBuffer byteBuffer;
        if (buffer.nioBufferCount() > 0) {
            byteBuffer = buffer.nioBuffer();
            buffer.skipBytes(buffer.readableBytes());
        } else {
            byteBuffer = ByteBuffer.allocateDirect(buffer.readableBytes());
            buffer.readBytes(byteBuffer);
            byteBuffer.flip();
        }

        this.inflater.setInput(byteBuffer);
    }

    private ByteBuf inflate(ChannelHandlerContext ctx, int size) throws DataFormatException {
        ByteBuf byteBuf = ctx.alloc().directBuffer(size);

        try {
            ByteBuffer byteBuffer = byteBuf.internalNioBuffer(0, size);
            int position = byteBuffer.position();
            this.inflater.inflate(byteBuffer);
            int i = byteBuffer.position() - position;
            if (i != size) {
                throw new DecoderException("Badly compressed packet - actual length of uncompressed payload " + i + " is does not match declared size " + size);
            } else {
                byteBuf.writerIndex(byteBuf.writerIndex() + i);
                return byteBuf;
            }
        } catch (Exception var7) {
            byteBuf.release();
            throw var7;
        }
    }

    public void setThreshold(int threshold, boolean validateDecompressed) {
        this.threshold = threshold;
        this.validateDecompressed = validateDecompressed;
    }
}
