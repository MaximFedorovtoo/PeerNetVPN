package org.uzel.core;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class DirectoryFailoverTest {
    @Test public void backupBecomesPreferredAndDeadPrimaryDoesNotDelayIt(){
        DirectoryFailover policy=new DirectoryFailover();
        policy.failed("primary",100);
        assertEquals(Collections.singletonList("backup"),policy.order(Arrays.asList("primary","backup"),101));
        policy.succeeded("backup");
        assertEquals(Arrays.asList("backup","primary"),policy.order(Arrays.asList("primary","backup"),2000));
    }
    @Test public void failedBackupLetsRecoveredPrimaryBeRetried(){
        DirectoryFailover policy=new DirectoryFailover();
        policy.succeeded("backup");policy.failed("backup",2000);
        assertEquals(Collections.singletonList("primary"),policy.order(Arrays.asList("primary","backup"),2001));
        policy.succeeded("primary");
        assertEquals(Arrays.asList("primary","backup"),policy.order(Arrays.asList("primary","backup"),4000));
    }
    @Test public void backoffIsBoundedAndForgetRemovedEndpoints(){
        DirectoryFailover policy=new DirectoryFailover();
        for(int i=0;i<25;i++)policy.failed("a",0);
        assertTrue(policy.order(Collections.singletonList("a"),29999).isEmpty());
        assertEquals(Collections.singletonList("a"),policy.order(Collections.singletonList("a"),30000));
        assertTrue(policy.order(Collections.emptyList(),31000).isEmpty());
        assertEquals(Collections.singletonList("a"),policy.order(Collections.singletonList("a"),0));
    }
}
