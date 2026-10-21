package org.uzel.app;

import android.net.*;
import java.io.*;
import java.net.*;
import org.uzel.core.NetworkAccess;

/** Holds a physical Network, so neither probes nor an exit recurse through the VPN. */
final class PhysicalNetwork extends NetworkAccess {
    final Network network;
    PhysicalNetwork(Network network){this.network=network;}
    @Override public InetAddress[] resolve(String host)throws IOException{return network.getAllByName(host);}
    @Override public Socket open(InetAddress address,int port)throws IOException {
        Socket socket=network.getSocketFactory().createSocket();
        try {
            socket.connect(new InetSocketAddress(address,port),5000);
            socket.setSoTimeout(15000);socket.setTcpNoDelay(true);return socket;
        }catch(IOException e){socket.close();throw e;}
    }
    @Override public DatagramSocket datagram()throws IOException {
        DatagramSocket socket=new DatagramSocket(null);
        try {network.bindSocket(socket);socket.bind(new InetSocketAddress(0));socket.setSoTimeout(30000);return socket;}
        catch(IOException e){socket.close();throw e;}
    }
    HttpURLConnection http(String url)throws IOException {
        HttpURLConnection connection=(HttpURLConnection)network.openConnection(new URL(url));
        connection.setConnectTimeout(8000);connection.setReadTimeout(10000);
        connection.setInstanceFollowRedirects(false);return connection;
    }
}
