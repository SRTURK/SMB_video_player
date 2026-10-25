package cn.lanvideo.app;

import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class FolderRepositoryTest {
    @Test public void parentSnapshotIsReusedUntilExplicitRefresh()throws Exception{
        AtomicInteger reads=new AtomicInteger();
        try(FolderRepository<String> folders=new FolderRepository<>(path->{reads.incrementAndGet();return Arrays.asList(path+"/video");})){
            List<String> parent=folders.load("parent",false).get(2,TimeUnit.SECONDS);
            folders.load("child",false).get(2,TimeUnit.SECONDS);
            assertSame(parent,folders.load("parent",false).get());assertEquals(2,reads.get());
            folders.load("parent",true).get(2,TimeUnit.SECONDS);assertEquals(3,reads.get());
        }
    }
    @Test public void navigationSharesAnInFlightPrefetch()throws Exception{
        AtomicInteger reads=new AtomicInteger();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try(FolderRepository<String> folders=new FolderRepository<>(path->{reads.incrementAndGet();entered.countDown();assertTrue(release.await(2,TimeUnit.SECONDS));return Arrays.asList("video");})){
            folders.prefetch("child");assertTrue(entered.await(2,TimeUnit.SECONDS));
            CompletableFuture<List<String>> navigation=folders.load("child",false);release.countDown();
            assertEquals(Arrays.asList("video"),navigation.get(2,TimeUnit.SECONDS));assertEquals(1,reads.get());
        }finally{release.countDown();}
    }
    @Test public void prefetchDoesNotBlockUnrelatedNavigation()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try(FolderRepository<String> folders=new FolderRepository<>(path->{if(path.equals("slow")){entered.countDown();release.await(2,TimeUnit.SECONDS);}return Arrays.asList(path);})){
            folders.prefetch("slow");assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertEquals(Arrays.asList("next"),folders.load("next",false).get(1,TimeUnit.SECONDS));
        }finally{release.countDown();}
    }
    @Test public void seedIsCopiedAndClosedRepositoryRejectsRequests()throws Exception{
        FolderRepository<String> folders=new FolderRepository<>(path->{throw new AssertionError("Must not read seeded directory");});
        ArrayList<String> entries=new ArrayList<>(Arrays.asList("a"));folders.seed("root",entries);entries.clear();
        assertEquals(Arrays.asList("a"),folders.load("root",false).get());folders.close();
        assertTrue(folders.load("root",false).isCompletedExceptionally());assertNull(folders.peek("root"));
    }
}
