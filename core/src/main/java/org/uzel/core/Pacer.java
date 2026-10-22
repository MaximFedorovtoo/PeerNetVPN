package org.uzel.core;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.TimeUnit;

/** One aggregate outgoing budget; zero means unlimited. No idle credit or bursts. */
public final class Pacer {
    private volatile long rate;
    private boolean active,stopped;
    private long generation;

    public synchronized void setShareRateLimit(long bytesPerSecond) {
        if(bytesPerSecond<0)throw new IllegalArgumentException("speed");
        rate=bytesPerSecond;notifyAll();
    }
    public long limit(){return rate;}
    /** Wake revoked traffic without discarding the configured rate. */
    synchronized void cancelPending(){generation++;notifyAll();}
    public synchronized void stop(){stopped=true;cancelPending();}

    public synchronized void acquire(int bytes)throws IOException {
        if(bytes<0 || bytes>65536)throw new IOException("Invalid pacing chunk");
        long epoch=generation;
        try {
            while(active && !stopped && epoch==generation)wait();
            requireLive(epoch);
            if(rate==0 || bytes==0)return;
            active=true;
            try {
                double remaining=bytes;
                while(remaining>0){
                    requireLive(epoch);
                    long current=rate;
                    if(current==0)return;
                    long nanos=Math.max(1,(long)Math.ceil(remaining*1_000_000_000d/current));
                    long started=System.nanoTime();
                    TimeUnit.NANOSECONDS.timedWait(this,nanos);
                    remaining-=(System.nanoTime()-started)*(current/1_000_000_000d);
                }
                requireLive(epoch);
            } finally {active=false;notifyAll();}
        } catch(InterruptedException e){
            Thread.currentThread().interrupt();throw new InterruptedIOException("Stopped");
        }
    }
    private void requireLive(long epoch)throws IOException {
        if(stopped || epoch!=generation)throw new IOException("Forwarding revoked");
    }
}
