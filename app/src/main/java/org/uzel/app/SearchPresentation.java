package org.uzel.app;

/** Presentation only: never infers successful discovery from an elapsed timer. */
final class SearchPresentation {
    static boolean canConnect(boolean reachable,boolean paired,boolean announced,int freeSlots) {
        return (reachable&&paired)||(freeSlots>0&&(reachable||announced));
    }
    enum State { SHARE_REQUIRED, PREPARING, SEARCHING, SLOW, FOUND, EMPTY, OFFLINE, SETUP }
    static State resolve(boolean sharing,boolean preparing,String network,int count,long elapsed,boolean retry) {
        if(preparing)return elapsed>=30_000?State.SLOW:State.PREPARING;
        if(!sharing)return State.SHARE_REQUIRED;
        if(count>0&&!"OFFLINE".equals(network))return State.FOUND;
        if(retry||"STARTING".equals(network))return elapsed>=30_000?State.SLOW:State.SEARCHING;
        if("OFFLINE".equals(network))return State.OFFLINE;
        if("NOT_CONFIGURED".equals(network))return State.SETUP;
        return count>0?State.FOUND:State.EMPTY;
    }
}
