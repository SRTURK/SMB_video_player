package cn.lanvideo.app;

import java.util.*;
import java.util.concurrent.*;

/** Session-scoped directory snapshots. Foreground requests have a dedicated worker. */
final class FolderRepository<T> implements AutoCloseable {
    interface Reader<T>{List<T> read(String path)throws Exception;}
    private final Reader<T> reader;
    private final ThreadPoolExecutor foreground=pool(),background=pool();
    private final LinkedHashMap<String,List<T>> cache=new LinkedHashMap<>(64,.75f,true);
    private final Map<String,Job> pending=new HashMap<>();
    private boolean closed;
    private static ThreadPoolExecutor pool(){return new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new LinkedBlockingQueue<>());}
    FolderRepository(Reader<T> reader){this.reader=reader;}
    synchronized void seed(String path,List<T> entries){cache.put(path,Collections.unmodifiableList(new ArrayList<>(entries)));trim();}
    synchronized List<T> peek(String path){return cache.get(path);}
    synchronized CompletableFuture<List<T>> load(String path,boolean refresh){return request(path,refresh,false);}
    synchronized void prefetch(String path){request(path,false,true);}
    private CompletableFuture<List<T>> request(String path,boolean refresh,boolean speculative){
        if(closed)return failed();
        if(refresh)cache.remove(path);
        List<T> hit=cache.get(path);if(hit!=null)return CompletableFuture.completedFuture(hit);
        Job existing=pending.get(path);
        if(existing!=null){if(!speculative&&background.remove(existing))foreground.execute(existing);return existing.result;}
        Job job=new Job(path);pending.put(path,job);(speculative?background:foreground).execute(job);return job.result;
    }
    private CompletableFuture<List<T>> failed(){CompletableFuture<List<T>> f=new CompletableFuture<>();f.completeExceptionally(new CancellationException("连接已关闭"));return f;}
    private void trim(){while(cache.size()>64)cache.remove(cache.keySet().iterator().next());}
    synchronized void cancelQueuedPrefetch(){for(Runnable task:new ArrayList<>(background.getQueue()))if(background.remove(task)){Job job=(Job)task;pending.remove(job.path,job);job.result.completeExceptionally(new CancellationException());}}
    private final class Job implements Runnable {
        final String path;final CompletableFuture<List<T>> result=new CompletableFuture<>();
        Job(String path){this.path=path;}
        public void run(){
            try{List<T> entries=Collections.unmodifiableList(new ArrayList<>(reader.read(path)));
                synchronized(FolderRepository.this){if(closed)throw new CancellationException();cache.put(path,entries);trim();pending.remove(path,this);}result.complete(entries);
            }catch(Exception e){synchronized(FolderRepository.this){pending.remove(path,this);}result.completeExceptionally(e);}
        }
    }
    @Override public synchronized void close(){closed=true;for(Job job:pending.values())job.result.completeExceptionally(new CancellationException());pending.clear();cache.clear();foreground.shutdownNow();background.shutdownNow();}
}
