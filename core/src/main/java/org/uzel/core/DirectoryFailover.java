package org.uzel.core;

import java.util.*;

/** Local contact preference only: availability never elects a global network leader. */
public final class DirectoryFailover {
    private final Map<String,Integer> failures=new HashMap<>();
    private final Map<String,Long> retryAt=new HashMap<>();
    private String preferred="";
    public synchronized List<String> order(List<String> configured,long now){
        Set<String> active=new LinkedHashSet<>(configured);
        failures.keySet().retainAll(active);retryAt.keySet().retainAll(active);
        List<String> result=new ArrayList<>();
        if(active.contains(preferred)&&retryAt.getOrDefault(preferred,0L)<=now)result.add(preferred);
        for(String key:active)if(!key.equals(preferred)&&retryAt.getOrDefault(key,0L)<=now)result.add(key);
        return result;
    }
    public synchronized void succeeded(String key){preferred=key;failures.remove(key);retryAt.remove(key);}
    public synchronized void failed(String key,long now){
        int count=Math.min(6,failures.getOrDefault(key,0)+1);failures.put(key,count);
        retryAt.put(key,now+Math.min(30_000L,1000L<<(count-1)));
    }
}
