package cn.lanvideo.app;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
public class NaturalOrderTest {
    @Test public void episodeNumbers() {
        List<String> files=new ArrayList<>(Arrays.asList("第10集.mkv","第2集.mkv","第1集.mkv"));
        files.sort(NaturalOrder::compare);
        assertEquals(Arrays.asList("第1集.mkv","第2集.mkv","第10集.mkv"),files);
    }
    @Test public void largeNumbersAndZeros() {
        assertTrue(NaturalOrder.compare("a99999999999999999999","a100000000000000000000")<0);
        assertTrue(NaturalOrder.compare("2","02")<0);
        assertEquals(0,NaturalOrder.compare("电影","电影"));
    }
    @Test public void comparatorContract() {
        String[] values={"a","A","a2","a02","a10","第2集","第10集","","001","1","a-2"};
        for(String a:values) for(String b:values) {
            assertEquals(Integer.signum(NaturalOrder.compare(a,b)),-Integer.signum(NaturalOrder.compare(b,a)));
            for(String c:values) if(NaturalOrder.compare(a,b)<0 && NaturalOrder.compare(b,c)<0) assertTrue(NaturalOrder.compare(a,c)<0);
        }
    }
}
