package org.uzel.core;

/** Admission policy, exercised before the transport is integrated. */
public final class Admission {
    private boolean sharing;
    private final java.util.Set<String> devices = new java.util.HashSet<>();
    public synchronized void setSharing(boolean value) {
        sharing=value;
        if (!value) devices.clear();
    }
    public synchronized boolean join(String id) {
        if (!sharing || id==null || id.isEmpty()) return false;
        if (devices.contains(id)) return true;
        return devices.size()<3 && devices.add(id);
    }
    public synchronized boolean contains(String id) { return sharing && devices.contains(id); }
    public synchronized void leave(String id) { devices.remove(id); }
    public synchronized int size() { return devices.size(); }
}
