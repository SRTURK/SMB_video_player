package cn.lanvideo.app;

import java.text.Collator;
import java.util.Locale;

/** Numeric runs are compared without parsing, so arbitrarily long numbers are safe. */
public final class NaturalOrder {
    private NaturalOrder() {}
    private static final ThreadLocal<Collator> COLLATOR=ThreadLocal.withInitial(()->{Collator c=Collator.getInstance(Locale.SIMPLIFIED_CHINESE);c.setStrength(Collator.SECONDARY);return c;});
    public static int compare(String a, String b) {
        Collator collator = COLLATOR.get();
        int i=0, j=0;
        while(i<a.length() && j<b.length()) {
            boolean ad = digit(a.charAt(i)), bd = digit(b.charAt(j));
            int ae=i+1, be=j+1;
            while(ae<a.length() && digit(a.charAt(ae))==ad) ae++;
            while(be<b.length() && digit(b.charAt(be))==bd) be++;
            int c;
            if(ad && bd) {
                int az=i, bz=j;
                while(az<ae-1 && a.charAt(az)=='0') az++;
                while(bz<be-1 && b.charAt(bz)=='0') bz++;
                c=Integer.compare(ae-az,be-bz);
                if(c==0) c=a.substring(az,ae).compareTo(b.substring(bz,be));
                if(c==0) c=Integer.compare(ae-i,be-j);
            } else c=collator.compare(a.substring(i,ae),b.substring(j,be));
            if(c!=0) return c;
            i=ae; j=be;
        }
        int c=Integer.compare(a.length()-i,b.length()-j);
        return c==0 ? a.compareTo(b) : c;
    }
    private static boolean digit(char c) { return c>='0' && c<='9'; }
}
