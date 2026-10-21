package org.uzel.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class SearchPresentationTest {
    @Test public void pairedPeerRetainsItsReservedSlot(){assertTrue(SearchPresentation.canConnect(true,true,true,0));}
    @Test public void fullUnpairedPeerIsDisabled(){assertFalse(SearchPresentation.canConnect(false,false,true,0));}
    @Test public void disabledSharingIsNotAnEmptySearch(){assertEquals(SearchPresentation.State.SHARE_REQUIRED,state(false,false,"NOT_CONFIGURED",0,0,false));}
    @Test public void completedEmptySearchIsNotLoading(){assertEquals(SearchPresentation.State.EMPTY,state(true,false,"ONLINE",0,1,false));}
    @Test public void offlineIsNotEmpty(){assertEquals(SearchPresentation.State.OFFLINE,state(true,false,"OFFLINE",0,1,false));}
    @Test public void initialSearchHasProgress(){assertEquals(SearchPresentation.State.SEARCHING,state(true,false,"STARTING",0,1000,false));}
    @Test public void slowSearchStopsSpinner(){assertEquals(SearchPresentation.State.SLOW,state(true,false,"STARTING",0,31000,false));}
    @Test public void retryShowsProgressUntilBound(){assertEquals(SearchPresentation.State.SEARCHING,state(true,false,"OFFLINE",0,1000,true));assertEquals(SearchPresentation.State.SLOW,state(true,false,"OFFLINE",0,31000,true));}
    @Test public void noContactsIsActionable(){assertEquals(SearchPresentation.State.SETUP,state(true,false,"NOT_CONFIGURED",0,0,false));}
    @Test public void foundNodesRemainVisibleDuringRefresh(){assertEquals(SearchPresentation.State.FOUND,state(true,false,"ONLINE",2,0,true));}
    @Test public void startPreparationIsNotMissingPeers(){assertEquals(SearchPresentation.State.PREPARING,state(false,true,"NOT_CONFIGURED",0,0,false));}
    private SearchPresentation.State state(boolean sharing,boolean busy,String network,int count,long elapsed,boolean retry){return SearchPresentation.resolve(sharing,busy,network,count,elapsed,retry);}
}
