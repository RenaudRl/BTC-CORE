package net.minecraft.network;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import java.util.zip.Deflater;

public class CompressionEncoder extends MessageToByteEncoder<ByteBuf> {
    private final byte[] encodeBuf = new byte[8192];
    private final Deflater deflater;
    private int threshold;

    public CompressionEncoder(int threshold) {
        this.threshold = threshold;
        this.deflater = new Deflater();
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf encodingByteBuf, ByteBuf byteBuf) {
        int i = encodingByteBuf.readableBytes();
        if (i > 8388608) {
            throw new IllegalArgumentException("Packet too big (is " + i + ", should be less than 8388608)");
        } else {
            if (i < this.threshold) {
                VarInt.write(byteBuf, 0);
                byteBuf.writeBytes(encodingByteBuf);
            } else {
                byte[] bytes = new byte[i];
                encodingByteBuf.readBytes(bytes);
                VarInt.write(byteBuf, bytes.length);
                this.deflater.setInput(bytes, 0, i);
                this.deflater.finish();

                while (!this.deflater.finished()) {
                    int i1 = this.deflater.deflate(this.encodeBuf);
                    byteBuf.writeBytes(this.encodeBuf, 0, i1);
                }

                this.deflater.reset();
            }
        }
    }

    public int getThreshold() {
        return this.threshold;
    }

    public void setThreshold(int threshold) {
        this.threshold = threshold;
    }
}
