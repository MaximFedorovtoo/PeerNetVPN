package org.uzel.core;

import org.junit.*;
import static org.junit.Assert.*;
import java.io.*;

public class PairingCodeTest extends NetworkTest {
    @Test public void signedOfferAnswerBindInvitationsIdentityAndExactOffer()throws Exception{
        Node a=node(0),b=node(1);long now=System.currentTimeMillis();
        PairingCode offer=PairingCode.offer(keys.get(0),"v=0\r\na=offer",a.invitation("127.0.0.1"),now);
        PairingCode received=PairingCode.parseAndVerify(offer.encode(),now);
        assertEquals(keys.get(0).fingerprint,received.fingerprint);assertEquals(a.invitation("127.0.0.1").secret,received.invitation.secret);
        PairingCode answer=PairingCode.answer(keys.get(1),"v=0\r\na=answer",b.invitation("127.0.0.1"),received,now);
        PairingCode verified=PairingCode.parseAndVerify(answer.encode(),now);PairingCode.verifyAnswer(verified,offer,now);
        PairingCode another=PairingCode.offer(keys.get(0),"v=0\r\na=offer",a.invitation("127.0.0.1"),now);
        try{PairingCode.verifyAnswer(verified,another,now);fail("wrong offer");}catch(IOException expected){}
        PairingCode.ReplayWindow replay=new PairingCode.ReplayWindow();assertTrue(replay.claim(received,now));assertFalse(replay.claim(received,now));
    }
    @Test public void expiredTamperedOversizedAndMismatchedIdentityAreRejected()throws Exception{
        Node a=node(0),b=node(1);long now=System.currentTimeMillis();
        PairingCode offer=PairingCode.offer(keys.get(0),"v=0",a.invitation("127.0.0.1"),now);
        try{PairingCode.parseAndVerify(offer.encode(),offer.expiresAt);fail("expired");}catch(IOException expected){}
        try{PairingCode.parseAndVerify(offer.encode(),now-31_000);fail("future");}catch(IOException expected){}
        String encoded=offer.encode();int at=encoded.length()-10;char c=encoded.charAt(at)=='A'?'B':'A';
        try{PairingCode.parseAndVerify(encoded.substring(0,at)+c+encoded.substring(at+1),now);fail("tampered");}catch(IOException expected){}
        try{PairingCode.parseAndVerify("X".repeat(65_537),now);fail("oversized");}catch(IOException expected){}
        try{PairingCode.offer(keys.get(0),"v=0",b.invitation("127.0.0.1"),now);fail("wrong invitation identity");}catch(IOException expected){}
    }
}
