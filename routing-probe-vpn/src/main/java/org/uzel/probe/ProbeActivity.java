package org.uzel.probe;

import android.app.Activity;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Test-only probe; normal URL.openConnection intentionally follows this UID's default route. */
public final class ProbeActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private TextView result;
    private Button run;
    private String endpoint,runId;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);column.setPadding(24,24,24,24);
        result=new TextView(this);result.setTextSize(16);result.setTextIsSelectable(true);
        run=new Button(this);run.setText("Run HTTPS routing probe");run.setOnClickListener(view->probe());
        column.addView(run);column.addView(result);
        ScrollView scroll=new ScrollView(this);scroll.addView(column);setContentView(scroll);
        readIntent(getIntent());probe();
    }
    @Override protected void onNewIntent(Intent intent) {super.onNewIntent(intent);setIntent(intent);readIntent(intent);probe();}
    private void readIntent(Intent intent) {
        endpoint=intent.getStringExtra("endpoint");if(endpoint==null)endpoint="https://api.country.is/";
        runId=intent.getStringExtra("runId");if(runId==null)runId="manual";
        runId=runId.replaceAll("[^a-zA-Z0-9_.-]","_");if(runId.length()>48)runId=runId.substring(0,48);
    }
    private void probe() {
        final String address=endpoint,id=runId;
        run.setEnabled(false);result.setText("Running bounded HTTPS probe…\n"+getPackageName()+"\n"+address);
        worker.execute(()->{
            JSONObject report=new JSONObject();HttpURLConnection connection=null;
            long started=System.nanoTime();
            try {
                report.put("package",getPackageName());report.put("uid",Process.myUid());report.put("runId",id);
                URL url=new URL(address);
                String host=url.getHost();
                if(!"https".equals(url.getProtocol())||url.getUserInfo()!=null||url.getPort()!=-1
                        ||!("api.country.is".equals(host)||"api.ipify.org".equals(host)||"api64.ipify.org".equals(host)))
                    throw new IllegalArgumentException("Only fixed public HTTPS IP-check endpoints are allowed");
                report.put("endpoint",address);
                ConnectivityManager manager=getSystemService(ConnectivityManager.class);
                Network active=manager.getActiveNetwork();NetworkCapabilities caps=manager.getNetworkCapabilities(active);
                report.put("vpnTransport",caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
                // No network binding, VPN bypass, custom socket factory or other-app data.
                connection=(HttpURLConnection)url.openConnection();
                connection.setConnectTimeout(12000);connection.setReadTimeout(12000);
                connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
                connection.setRequestProperty("Cache-Control","no-cache");
                report.put("httpStatus",connection.getResponseCode());
                try(InputStream in=connection.getResponseCode()<400?connection.getInputStream():connection.getErrorStream()) {
                    ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[128];
                    if(in!=null)while(bytes.size()<512){int n=in.read(buffer,0,Math.min(buffer.length,512-bytes.size()));if(n<0)break;bytes.write(buffer,0,n);}
                    report.put("body",bytes.toString(StandardCharsets.UTF_8.name()));
                }
            }catch(Exception failure) {
                try{report.put("error",failure.getClass().getSimpleName()+": "+String.valueOf(failure.getMessage()));}catch(Exception ignored){}
            }finally{if(connection!=null)connection.disconnect();}
            try{report.put("elapsedMs",(System.nanoTime()-started)/1_000_000);}catch(Exception ignored){}
            String output=report.toString();Log.i("UzelioProbe",output);
            runOnUiThread(()->{result.setText(output);run.setEnabled(true);});
        });
    }
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
