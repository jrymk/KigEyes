package com.genymobile.scrcpy;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
public final class ControlRelayTest {
    static void check(boolean ok,String message) { if(!ok)throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        ByteArrayOutputStream packet=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(packet);
        d.writeByte(0);d.writeByte(0);d.writeInt(24);d.writeInt(0);d.writeInt(0);
        byte[] key=packet.toByteArray();ByteArrayOutputStream output=new ByteArrayOutputStream();ControlRelay relay=new ControlRelay(output);
        CountDownLatch partial=new CountDownLatch(1),tail=new CountDownLatch(1);
        InputStream fragmented=new ByteArrayInputStream(key) {
            public synchronized int read(byte[] bytes,int offset,int length) {
                if(pos==0) { int n=super.read(bytes,offset,2);partial.countDown();return n; }
                try { tail.await(2,TimeUnit.SECONDS); } catch(InterruptedException e) { throw new RuntimeException(e); }
                return super.read(bytes,offset,length);
            }
        };
        Thread t=new Thread(() -> { try { relay.forward(fragmented); } catch(EOFException expected) { } catch(IOException e) { throw new RuntimeException(e); } });t.start();
        check(partial.await(2,TimeUnit.SECONDS),"partial packet received");relay.send(new byte[] {(byte)128});tail.countDown();t.join(2000);
        check(!t.isAlive(),"relay stops on EOF");byte[] expected=new byte[key.length+1];expected[0]=(byte)128;System.arraycopy(key,0,expected,1,key.length);
        check(Arrays.equals(expected,output.toByteArray()),"local control cannot split a remote packet");
        packet.reset();d.writeByte(1);d.writeLong(ControlRelay.GUARD_ACK_BASE+1);
        d.writeByte(1);d.writeLong(ControlRelay.GUARD_ACK_BASE+5);
        d.writeByte(1);d.writeLong(ControlRelay.GUARD_ACK_BASE+4);
        d.writeByte(1);d.writeLong(42);
        List<Integer> states=new ArrayList<>();output.reset();
        try { ControlRelay.replies(new ByteArrayInputStream(packet.toByteArray()),output,states::add); } catch(EOFException expectedEof) { }
        check(states.equals(Arrays.asList(1,5,4)) && output.size()==9,"only private guard ack consumed");
        System.out.println("Control relay framing and private acknowledgement tests passed");
    }
}
