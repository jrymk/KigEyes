package com.genymobile.scrcpy;

import com.genymobile.scrcpy.control.ControlMessageReader;
import java.io.*;
import java.util.function.IntConsumer;

/** Frame-aware merge of authenticated Pi controls and the paired app's fixed local action. */
final class ControlRelay {
    static final long GUARD_ACK_BASE=0x4b47475000000000L;
    private final OutputStream child;
    ControlRelay(OutputStream child) { this.child=child; }
    synchronized void send(byte[] packet) throws IOException { child.write(packet);child.flush(); }
    void forward(InputStream network) throws IOException {
        ControlMessageReader reader=new ControlMessageReader(network,true);
        while(true)send(reader.readPacket());
    }
    static void replies(InputStream child,OutputStream network,IntConsumer guardState) throws IOException {
        DataInputStream in=new DataInputStream(new BufferedInputStream(child));
        while(true) {
            int type=in.readUnsignedByte();
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(buffer);out.writeByte(type);
            if(type==0) {
                int length=in.readInt();if(length<0 || length>262144)throw new IOException("Invalid device reply");
                out.writeInt(length);byte[] bytes=new byte[length];in.readFully(bytes);out.write(bytes);
            } else if(type==1) {
                long sequence=in.readLong();
                if(sequence>=GUARD_ACK_BASE && sequence<=GUARD_ACK_BASE+5) { guardState.accept((int)(sequence-GUARD_ACK_BASE));continue; }
                out.writeLong(sequence);
            } else if(type==2) {
                out.writeShort(in.readUnsignedShort());int length=in.readUnsignedShort();out.writeShort(length);
                byte[] bytes=new byte[length];in.readFully(bytes);out.write(bytes);
            } else throw new IOException("Invalid device reply type");
            network.write(buffer.toByteArray());network.flush();
        }
    }
}
