package org.uzel.core;

import java.io.IOException;

public final class Pacer {
    private volatile long rate;
    public void setMeasuredUpload(long bytesPerSecond) {
        if (bytesPerSecond<0 || bytesPerSecond>125_000_000_000L) throw new IllegalArgumentException("speed");
        rate=bytesPerSecond/3;
    }
    public long limit() { return rate; }
    /** Serial pacing, no idle credit/bursts. Shared by every outgoing data stream. */
    public synchronized void acquire(int bytes) throws IOException {
        if (bytes<0 || bytes>65536) throw new IOException("Invalid pacing chunk");
        long current=rate;
        if (current<=0) throw new IOException("NEED_MEASUREMENT");
        long nanos=(bytes*1_000_000_000L+current-1)/current;
        long end=System.nanoTime()+nanos;
        try {
            while ((nanos=end-System.nanoTime())>0) {
                java.util.concurrent.TimeUnit.NANOSECONDS.sleep(nanos);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("Stopped");
        }
        if(rate<=0) throw new IOException("NEED_MEASUREMENT");
    }
}
