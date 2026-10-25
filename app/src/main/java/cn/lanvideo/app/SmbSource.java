package cn.lanvideo.app;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;
import java.io.EOFException;
import java.io.IOException;
import jcifs.CIFSContext;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbFileInputStream;

/** Direct SMB random reads: seeking never downloads the preceding part of a movie. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class SmbSource extends BaseDataSource {
    private final CIFSContext context;
    private SmbFile file;
    private SmbFileInputStream input;
    private Uri uri;
    private long remaining;
    private boolean opened;
    private final byte[] cache=new byte[256*1024];
    private int cachePosition,cacheLimit;
    public SmbSource(CIFSContext context) { super(true); this.context=context; }
    @Override public long open(DataSpec spec) throws IOException {
        transferInitializing(spec);
        uri=spec.uri;
        try {
            // jcifs expects literal SMB paths, whereas Media3 URIs are percent encoded.
            file=new SmbFile(Uri.decode(uri.toString()),context);
            long length=file.length();
            input=new SmbFileInputStream(file);
            if(spec.position>length) throw new EOFException("视频位置超出文件大小");
            // jcifs skip updates its remote read offset without reading discarded bytes.
            if(input.skip(spec.position)!=spec.position) throw new EOFException("无法定位视频");
            cachePosition=cacheLimit=0;
            remaining=spec.length==C.LENGTH_UNSET ? length-spec.position : Math.min(spec.length,length-spec.position);
            opened=true; transferStarted(spec);
            return remaining;
        } catch(IOException | RuntimeException e) {
            try { close(); } catch(IOException suppressed) { e.addSuppressed(suppressed); }
            throw e;
        }
    }
    @Override public int read(byte[] buffer,int offset,int length) throws IOException {
        if(length==0) return 0;
        if(remaining==0) return C.RESULT_END_OF_INPUT;
        if(cachePosition==cacheLimit) {
            cacheLimit=input.read(cache,0,(int)Math.min(cache.length,remaining));
            cachePosition=0;
            if(cacheLimit<=0) throw new EOFException("远程视频读取中断");
        }
        int n=(int)Math.min(Math.min(length,remaining),cacheLimit-cachePosition);
        System.arraycopy(cache,cachePosition,buffer,offset,n); cachePosition+=n;
        remaining-=n; bytesTransferred(n); return n;
    }
    @Override public Uri getUri() { return uri; }
    @Override public void close() throws IOException {
        try { if(input!=null) input.close(); }
        finally {
            input=null;
            if(file!=null) file.close(); file=null; uri=null;
            if(opened) { opened=false; transferEnded(); }
        }
    }
}
