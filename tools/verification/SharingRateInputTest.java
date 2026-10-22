package org.uzel.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class SharingRateInputTest {
    @Test public void acceptsDotCommaAndExactDecimalMegabits(){
        assertEquals(1_250_000,SharingRateInput.parse("10",0));
        assertEquals(1_562_500,SharingRateInput.parse("12,5",0));
        assertEquals(1_562_500,SharingRateInput.parse("12.5",0));
        assertEquals(1,SharingRateInput.parse("0.000001",0));
        assertEquals("0.000008",SharingRateInput.format(1));
        assertEquals(1_000_001,SharingRateInput.parse(SharingRateInput.format(1_000_001),1_000_001));
    }
    @Test public void rejectsInvalidBelowMinimumAndOverflowWithoutUnlimitedFallback(){
        for(String value:new String[]{"","0","-1","NaN","Infinity","1e3","1,2.3","1 000","9999999999999999999999999999999","1000000.000008"}){
            try{SharingRateInput.parse(value,0);fail(value);}catch(IllegalArgumentException expected){}
        }
        try{SharingRateInput.parse("7.99",1_000_000);fail("below minimum");}catch(IllegalArgumentException expected){}
        assertEquals(125_000_000_000L,SharingRateInput.parse("1000000",0));
    }
}
