package org.uzel.app;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.pm.PackageManager;
import android.net.*;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import hev.htproxy.TProxyService;
import org.uzel.core.LocalSocks;

public final class UzelVpnService extends VpnService {
    private ParcelFileDescriptor tunnel;
    private PowerManager.WakeLock awake;
    @Override public void onCreate(){
        super.onCreate();
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("node",getString(R.string.notification_channel),NotificationManager.IMPORTANCE_LOW));
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent content=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,UzelVpnService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,"node").setSmallIcon(R.drawable.ic_node)
                .setContentTitle(getString(R.string.notification_title)).setContentText(getString(R.string.notification_text))
                .setContentIntent(content).setOngoing(true).addAction(new Notification.Action.Builder(
                        null,getString(R.string.notification_stop),stop).build()).build();
        if(Build.VERSION.SDK_INT>=34)startForeground(1,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1,notification);
        awake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"uzel:sharing");
        awake.acquire();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        NodeController controller=NodeController.get(this);
        if(intent!=null && "STOP".equals(intent.getAction())){controller.stopSharing();return START_NOT_STICKY;}
        controller.startInternal(this);
        return START_NOT_STICKY;
    }
    synchronized void startTunnel(LocalSocks proxy,Network network)throws Exception {
        stopTunnel();
        Builder builder=new Builder().setSession("Uzelio").setMtu(1280)
                .addAddress("198.18.0.1",32).addRoute("0.0.0.0",0)
                .addAddress("fd00:757a:656c::1",128).addRoute("::",0)
                .addDnsServer("1.1.1.1").addDnsServer("2606:4700:4700::1111")
                .setUnderlyingNetworks(new Network[]{network});
        // Calling establish without a single allowed app would route every app.
        // Revalidate here even when a caller bypasses the Activity selection gate.
        int allowed = 0;
        for (String packageName : AppSelection.validPackages(this)) {
            if (getPackageName().equals(packageName)) continue;
            try {
                builder.addAllowedApplication(packageName);
                allowed++;
            } catch (PackageManager.NameNotFoundException ignored) {
                // An application may have been removed since the selection was read.
            }
        }
        if (allowed == 0) throw new IOException("SELECT_APPS");
        tunnel=builder.establish();
        if(tunnel==null)throw new IOException("VPN permission revoked");
        File config=new File(getFilesDir(),"tunnel.yml");
        String yaml="tunnel:\n  mtu: 1280\n  ipv4: 198.18.0.1\n  ipv6: 'fd00:757a:656c::1'\n"
                +"socks5:\n  address: 127.0.0.1\n  port: "+proxy.port()+"\n  udp: 'udp'\n"
                +"  username: '"+proxy.username()+"'\n  password: '"+proxy.password()+"'\n"
                +"misc:\n  max-session-count: 24\n  connect-timeout: 8000\n  read-write-timeout: 60000\n  log-level: error\n";
        try(FileOutputStream out=new FileOutputStream(config)){out.write(yaml.getBytes(StandardCharsets.UTF_8));}
        if(!TProxyService.TProxyStartService(config.getAbsolutePath(),tunnel.getFd())){
            stopTunnel();throw new IOException("Native tunnel failed");
        }
        Thread.sleep(150);
        if(!TProxyService.TProxyIsRunning()){stopTunnel();throw new IOException("Native tunnel stopped");}
    }
    synchronized boolean tunnelRunning(){return tunnel!=null && TProxyService.TProxyIsRunning();}
    synchronized void stopTunnel(){
        if(tunnel==null)return;
        TProxyService.TProxyStopService();
        try{tunnel.close();}catch(IOException ignored){}
        tunnel=null;
    }
    @Override public void onRevoke(){NodeController.get(this).stopSharing();super.onRevoke();}
    @Override public void onDestroy(){
        NodeController.get(this).serviceDestroyed(this);
        stopTunnel();
        if(awake!=null && awake.isHeld())awake.release();
        super.onDestroy();
    }
}
