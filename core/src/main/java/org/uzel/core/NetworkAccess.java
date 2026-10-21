package org.uzel.core;

import java.net.*;
import java.io.IOException;

/** Android overrides these methods to bind all traffic to the physical network. */
public class NetworkAccess {
    public InetAddress[] resolve(String host) throws IOException { return InetAddress.getAllByName(host); }
    public Socket open(String host,int port) throws IOException {return open(resolve(host)[0],port);}
    public Socket open(InetAddress address,int port) throws IOException {
        Socket socket=new Socket();
        try {
            socket.connect(new InetSocketAddress(address,port),5000);
            socket.setSoTimeout(15000);
            socket.setTcpNoDelay(true);
            return socket;
        } catch(IOException e){socket.close();throw e;}
    }
    public DatagramSocket datagram() throws IOException {
        DatagramSocket socket=new DatagramSocket();
        socket.setSoTimeout(30000);
        return socket;
    }
}
