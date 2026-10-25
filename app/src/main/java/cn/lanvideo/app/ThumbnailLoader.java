package cn.lanvideo.app;

import android.content.Context;
import android.graphics.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.os.Handler;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import jcifs.CIFSContext;
import jcifs.smb.*;

/** Two workers with visible-first priority; in-flight reads are shared. */
final class ThumbnailLoader {
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new PriorityBlockingQueue<>());
    private long sequence;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final File directory;
    private volatile boolean closed;
    private final Map<String,Job> pending=new HashMap<>();
    private final Map<String,Long> failed=new ConcurrentHashMap<>();
    private final Set<String> retried=ConcurrentHashMap.newKeySet();
    private final LruCache<String,Bitmap> memory=new LruCache<>(12*1024*1024){@Override protected int sizeOf(String k,Bitmap b){return b.getAllocationByteCount();}};
    ThumbnailLoader(Context context){directory=new File(context.getCacheDir(),"covers");directory.mkdirs();}
    void load(ImageView view,String url,long modified,long length,CIFSContext context,BooleanSupplier valid){
        String key=key(url+"|"+modified+"|"+length);view.setTag(key);
        Bitmap hit=memory.get(key);if(hit!=null){view.setImageBitmap(hit);return;}
        request(key,url,context,valid,false).thenAccept(bitmap->main.post(()->{
            if(closed||!valid.getAsBoolean()||!key.equals(view.getTag()))return;
            if(bitmap!=null)view.setImageBitmap(bitmap);
            else if(retried.add(key))main.postDelayed(()->{if(!closed&&valid.getAsBoolean()&&key.equals(view.getTag()))load(view,url,modified,length,context,valid);},6000);
        }));
    }
    boolean isCached(String url,long modified,long length){String k=key(url+"|"+modified+"|"+length);return memory.get(k)!=null||new File(directory,k+".jpg").isFile();}
    CompletableFuture<Bitmap> prefetch(String url,long modified,long length,CIFSContext context,BooleanSupplier valid){return request(key(url+"|"+modified+"|"+length),url,context,valid,true);}
    private synchronized CompletableFuture<Bitmap> request(String key,String url,CIFSContext context,BooleanSupplier valid,boolean speculative){
        if(closed)return CompletableFuture.completedFuture(null);
        Bitmap hit=memory.get(key);if(hit!=null)return CompletableFuture.completedFuture(hit);
        Long failure=failed.get(key);if(failure!=null&&SystemClock.elapsedRealtime()-failure<5000)return CompletableFuture.completedFuture(null);
        Job existing=pending.get(key);
        if(existing!=null){existing.interests.add(valid);if(!speculative&&existing.priority>0&&worker.remove(existing)){existing.priority=0;worker.execute(existing);}return existing.result;}
        // Drop obsolete queued work, and bound speculative work even during a rapid scroll.
        for(Job job:new ArrayList<>(pending.values()))if(!job.wanted()&&worker.remove(job)){pending.remove(job.key);job.result.complete(null);}
        if(speculative&&worker.getQueue().size()>=16)return CompletableFuture.completedFuture(null);
        Job job=new Job(key,url,context,valid,speculative);pending.put(key,job);worker.execute(job);return job.result;
    }
    private final class Job implements Runnable,Comparable<Job> {
        int priority;final long order=sequence++;
        public int compareTo(Job other){int p=Integer.compare(priority,other.priority);return p!=0?p:Long.compare(order,other.order);}
        final String key,url;final CIFSContext context;
        final List<BooleanSupplier> interests=new CopyOnWriteArrayList<>();final CompletableFuture<Bitmap> result=new CompletableFuture<>();
        Job(String key,String url,CIFSContext context,BooleanSupplier valid,boolean speculative){priority=speculative?1:0;this.key=key;this.url=url;this.context=context;interests.add(valid);}
        boolean wanted(){if(closed)return false;for(BooleanSupplier valid:interests)if(valid.getAsBoolean())return true;return false;}
        public void run(){Bitmap bitmap=null;try{if(wanted())bitmap=decode();}finally{if(bitmap!=null)memory.put(key,bitmap);synchronized(ThumbnailLoader.this){pending.remove(key,this);}result.complete(bitmap);}}
        private Bitmap decode(){
            File target=new File(directory,key+".jpg"); Bitmap bitmap=BitmapFactory.decodeFile(target.getPath());
            if(bitmap==null){
                MediaMetadataRetriever retriever=new MediaMetadataRetriever();
                try(RemoteData source=new RemoteData(url,context,this::wanted)) {
                    retriever.setDataSource(source);
                    byte[] artwork=retriever.getEmbeddedPicture();
                    if(artwork!=null)bitmap=BitmapFactory.decodeByteArray(artwork,0,artwork.length);
                    if(bitmap==null)bitmap=Build.VERSION.SDK_INT>=27?retriever.getScaledFrameAtTime(1_000_000,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,480,270):retriever.getFrameAtTime(1_000_000,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if(bitmap==null)bitmap=retriever.getFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if(bitmap==null)bitmap=retriever.getFrameAtTime(-1);
                    if(bitmap!=null){
                        if(bitmap.getWidth()>480 || bitmap.getHeight()>480){float scale=480f/Math.max(bitmap.getWidth(),bitmap.getHeight());bitmap=Bitmap.createScaledBitmap(bitmap,Math.max(1,(int)(bitmap.getWidth()*scale)),Math.max(1,(int)(bitmap.getHeight()*scale)),true);}
                        try(FileOutputStream out=new FileOutputStream(target)){bitmap.compress(Bitmap.CompressFormat.JPEG,82,out);}
                        trim();
                    }
                }catch(Exception e){if(wanted()){failed.put(key,SystemClock.elapsedRealtime());android.util.Log.w("LanVideoCover","Cover read failed: "+e.getClass().getSimpleName());}}
                finally{try{retriever.release();}catch(Exception ignored){}}
            }
            return bitmap;
        }
    }
    private void trim(){File[] files=directory.listFiles();if(files==null)return;Arrays.sort(files,Comparator.comparingLong(File::lastModified));long total=0;for(File f:files)total+=f.length();for(File f:files){if(total<32*1024*1024)break;long size=f.length();if(f.delete())total-=size;}}
    private static String key(String value){try{byte[] hash=MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(byte n:hash)b.append(String.format(java.util.Locale.ROOT,"%02x",n));return b.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    synchronized void close(){closed=true;for(Job job:pending.values())job.result.complete(null);pending.clear();worker.shutdownNow();main.removeCallbacksAndMessages(null);}
    static final class RemoteData extends MediaDataSource {
        private final SmbFile file;private SmbFileInputStream input;private final long size;private long position=0,read=0;
        private static final int BLOCK=256*1024;
        private final LinkedHashMap<Long,byte[]> blocks=new LinkedHashMap<>(32,.75f,true);
        private final long started=SystemClock.elapsedRealtime();
        private final BooleanSupplier valid;
        RemoteData(String url,CIFSContext context,BooleanSupplier valid)throws Exception {
            this.valid=valid;file=new SmbFile(Uri.decode(url),context);
            try{size=file.length();input=new SmbFileInputStream(file);}catch(Exception e){file.close();throw e;}
        }
        @Override public synchronized int readAt(long pos,byte[] buffer,int offset,int count)throws IOException {
            if(!valid.getAsBoolean()||Thread.currentThread().isInterrupted()||read>64*1024*1024||SystemClock.elapsedRealtime()-started>45000)throw new IOException("封面任务已停止");
            if(count==0)return 0;if(pos>=size)return -1;
            long blockStart=(pos/BLOCK)*BLOCK;byte[] block=blocks.get(blockStart);
            if(block==null){
                if(blockStart<position){input.close();input=new SmbFileInputStream(file);position=0;}
                if(blockStart>position){input.skip(blockStart-position);position=blockStart;}
                block=new byte[(int)Math.min(BLOCK,size-blockStart)];int filled=0;
                while(filled<block.length){int n=input.read(block,filled,block.length-filled);if(n<=0)throw new EOFException("封面读取中断");filled+=n;position+=n;read+=n;}
                blocks.put(blockStart,block);if(blocks.size()>32)blocks.remove(blocks.keySet().iterator().next());
            }
            int within=(int)(pos-blockStart),n=Math.min(count,block.length-within);System.arraycopy(block,within,buffer,offset,n);return n;
        }
        @Override public long getSize(){return size;}
        @Override public synchronized void close()throws IOException {try{if(input!=null)input.close();}finally{input=null;file.close();}}
    }
}
