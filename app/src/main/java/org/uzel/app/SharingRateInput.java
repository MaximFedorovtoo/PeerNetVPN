package org.uzel.app;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Decimal Mbit/s; zero is reserved for the separate unlimited choice. */
final class SharingRateInput {
    private static final BigDecimal BYTES_PER_MEGABIT=BigDecimal.valueOf(125_000);
    private static final BigDecimal MAX_BYTES_PER_SECOND=BigDecimal.valueOf(125_000_000_000L);
    static long parse(String text,long minimum){
        if(text==null)throw new IllegalArgumentException("speed");
        String value=text.trim().replace(',','.');
        if(value.length()>80 || !value.matches("[0-9]+(?:\\.[0-9]+)?"))throw new IllegalArgumentException("speed");
        try {
            BigDecimal bytes=new BigDecimal(value).multiply(BYTES_PER_MEGABIT);
            if(bytes.signum()<=0 || bytes.compareTo(MAX_BYTES_PER_SECOND)>0)throw new IllegalArgumentException("speed");
            long result=bytes.setScale(0,RoundingMode.CEILING).longValueExact();
            if(result<minimum)throw new IllegalArgumentException("minimum speed");
            return result;
        } catch(ArithmeticException e){throw new IllegalArgumentException("speed",e);}
    }
    static String format(long bytesPerSecond){
        return BigDecimal.valueOf(bytesPerSecond).divide(BYTES_PER_MEGABIT).stripTrailingZeros().toPlainString();
    }
}
