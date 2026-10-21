package org.uzel.app.traversal;

import android.content.Context;
import android.net.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.webrtc.NetworkChangeDetector;

/** Exposes exactly one physical Network to native WebRTC; never the VPN/default route. */
final class PhysicalNetworkDetector implements NetworkChangeDetector {
    private final ConnectivityManager manager;
    private final Network selected;
    private final Observer observer;
    private volatile boolean destroyed;
    private final ConnectivityManager.NetworkCallback callback=new ConnectivityManager.NetworkCallback(){
        @Override public void onLost(Network network){if(selected.equals(network)&&!destroyed){observer.onNetworkDisconnect(selected.getNetworkHandle());observer.onConnectionTypeChanged(ConnectionType.CONNECTION_NONE);}}
        @Override public void onLinkPropertiesChanged(Network network,LinkProperties properties){if(selected.equals(network)&&!destroyed)notifySelected();}
        @Override public void onCapabilitiesChanged(Network network,NetworkCapabilities caps){if(selected.equals(network)&&!destroyed)notifySelected();}
    };
    PhysicalNetworkDetector(Observer observer,Context context,Network network){
        this.observer=observer;selected=network;manager=context.getSystemService(ConnectivityManager.class);
        manager.registerNetworkCallback(new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),callback);
    }
    private ConnectionType type(){
        NetworkCapabilities caps=manager.getNetworkCapabilities(selected);
        if(caps==null||caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))return ConnectionType.CONNECTION_NONE;
        if(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))return ConnectionType.CONNECTION_WIFI;
        if(caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))return ConnectionType.CONNECTION_ETHERNET;
        if(caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))return ConnectionType.CONNECTION_UNKNOWN_CELLULAR;
        return ConnectionType.CONNECTION_UNKNOWN;
    }
    @Override public ConnectionType getCurrentConnectionType(){return type();}
    @Override public boolean supportNetworkCallback(){return true;}
    @Override public List<NetworkInformation> getActiveNetworkList(){
        if(destroyed||type()==ConnectionType.CONNECTION_NONE)return Collections.emptyList();
        LinkProperties links=manager.getLinkProperties(selected);
        if(links==null||links.getInterfaceName()==null)return Collections.emptyList();
        List<IPAddress> addresses=new ArrayList<>();
        for(LinkAddress address:links.getLinkAddresses())if(!address.getAddress().isLoopbackAddress())addresses.add(new IPAddress(address.getAddress().getAddress()));
        return Collections.singletonList(new NetworkInformation(links.getInterfaceName(),type(),ConnectionType.CONNECTION_NONE,selected.getNetworkHandle(),addresses.toArray(new IPAddress[0])));
    }
    private void notifySelected(){
        List<NetworkInformation> active=getActiveNetworkList();
        if(active.isEmpty())observer.onNetworkDisconnect(selected.getNetworkHandle());else observer.onNetworkConnect(active.get(0));
        observer.onConnectionTypeChanged(type());
    }
    @Override public void destroy(){if(!destroyed){destroyed=true;manager.unregisterNetworkCallback(callback);}}
}
