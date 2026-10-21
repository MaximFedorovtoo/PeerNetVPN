package org.uzel.core;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public final class SocksPacket {
    public final String host;
    public final int port;
    public final byte[] payload;
    private SocksPacket(String host,int port,byte[] payload) {this.host=host;this.port=port;this.payload=payload;}
    public static String readHost(DataInputStream in,int type) throws IOException {
        if(type==1 || type==4) {
            byte[] bytes=new byte[type==1?4:16];in.readFully(bytes);
            return InetAddress.getByAddress(bytes).getHostAddress();
        }
        if(type==3) {
            int length=in.readUnsignedByte();
            if(length==0) throw new IOException("Empty destination");
            byte[] b=new byte[length];in.readFully(b);
            String host=new String(b,StandardCharsets.US_ASCII);
            if(!host.matches("[A-Za-z0-9.-]+")) throw new IOException("Invalid destination");
            return host;
        }
        throw new IOException("Address type");
    }
    public static SocksPacket parse(byte[] packet) throws IOException {
        if(packet.length>65507) throw new IOException("Datagram too large");
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(packet));
        if(in.readUnsignedShort()!=0 || in.readUnsignedByte()!=0) throw new IOException("UDP fragments unsupported");
        String host=readHost(in,in.readUnsignedByte());
        int port=in.readUnsignedShort();
        if(port==0) throw new IOException("Port zero");
        byte[] payload=new byte[in.available()];in.readFully(payload);
        return new SocksPacket(host,port,payload);
    }
    public static byte[] encode(InetAddress address,int port,byte[] payload) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        DataOutputStream out=new DataOutputStream(bytes);
        out.writeShort(0);out.writeByte(0);
        byte[] ip=address.getAddress();out.writeByte(ip.length==4?1:4);
        out.write(ip);out.writeShort(port);out.write(payload);
        return bytes.toByteArray();
    }
}
