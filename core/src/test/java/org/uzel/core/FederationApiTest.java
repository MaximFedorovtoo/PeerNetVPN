package org.uzel.core;
import org.junit.Test;
import static org.junit.Assert.*;
/** The public federation API must exist before callers can integrate it. */
public class FederationApiTest {
 @Test public void federationHasPinnedExchangeAndPersistentNode() {
  for(String name:new String[]{"FederationContact","FederationExchange","FederationNode"}) {
   try {Class.forName("org.uzel.core."+name);} catch(ClassNotFoundException e){fail("Missing federated directory implementation: "+name);}
  }
 }
}
