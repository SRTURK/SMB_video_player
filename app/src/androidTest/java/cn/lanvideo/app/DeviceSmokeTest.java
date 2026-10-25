package cn.lanvideo.app;

import android.content.Intent;
import android.app.Activity;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.util.Properties;
import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.SmbFile;
import androidx.media3.datasource.DataSpec;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class DeviceSmokeTest {
    @androidx.test.filters.SdkSuppress(minSdkVersion=27)
    @Test public void actualUserVideoCover()throws Exception{
        String url=InstrumentationRegistry.getArguments().getString("actualSmbFile");Assume.assumeTrue(url!=null);
        Properties p=new Properties();p.setProperty("jcifs.smb.client.minVersion","SMB202");p.setProperty("jcifs.smb.client.maxVersion","SMB210");CIFSContext ctx=new BaseContext(new PropertyConfiguration(p)).withAnonymousCredentials();android.media.MediaMetadataRetriever retriever=new android.media.MediaMetadataRetriever();
        try(ThumbnailLoader.RemoteData data=new ThumbnailLoader.RemoteData(url,ctx,()->true)){retriever.setDataSource(data);android.graphics.Bitmap frame=retriever.getScaledFrameAtTime(1000000,android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,480,270);assertNotNull("Actual video cover must decode",frame);try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null),"actual-cover.jpg"))){frame.compress(android.graphics.Bitmap.CompressFormat.JPEG,85,out);}frame.recycle();}finally{retriever.release();ctx.close();}
    }
    private static void touch(android.view.View view,int action,float x,float y){long t=android.os.SystemClock.uptimeMillis();android.view.MotionEvent event=android.view.MotionEvent.obtain(t,t,action,x,y,0);view.dispatchTouchEvent(event);event.recycle();}
    @Test public void gestureControlsRestoreSpeedSeekAndAdjustLevels()throws Exception {
        String url=InstrumentationRegistry.getArguments().getString("smbTestFile");Assume.assumeTrue(url!=null);
        var inst=InstrumentationRegistry.getInstrumentation();Activity a=inst.startActivitySync(new Intent(inst.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Properties props=new Properties();props.setProperty("jcifs.smb.client.minVersion","SMB202");props.setProperty("jcifs.smb.client.maxVersion","SMB210");CIFSContext ctx=new BaseContext(new PropertyConfiguration(props)).withAnonymousCredentials();
        AtomicReference<ExoPlayer> player=new AtomicReference<>();AtomicReference<PlaybackControls> controls=new AtomicReference<>();AtomicReference<PlaybackException> error=new AtomicReference<>();
        android.media.AudioManager audio=(android.media.AudioManager)a.getSystemService(android.content.Context.AUDIO_SERVICE);int originalVolume=audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);float originalBrightness=a.getWindow().getAttributes().screenBrightness;
        try{
            inst.runOnMainSync(()->{android.widget.FrameLayout frame=new android.widget.FrameLayout(a);PlayerView view=new PlayerView(a);view.setUseController(false);frame.addView(view,new android.widget.FrameLayout.LayoutParams(-1,-1));ExoPlayer p=new ExoPlayer.Builder(a).setMediaSourceFactory(new DefaultMediaSourceFactory(()->new SmbSource(ctx))).build();player.set(p);view.setPlayer(p);PlaybackControls c=new PlaybackControls(a,p,view,"手势验证",()->{},()->{});controls.set(c);frame.addView(c,new android.widget.FrameLayout.LayoutParams(-1,-1));a.setContentView(frame);p.addListener(new Player.Listener(){@Override public void onPlayerError(PlaybackException e){error.set(e);}});p.setMediaItem(MediaItem.fromUri(url));p.prepare();p.play();});
            await(inst,()->player.get().getCurrentPosition()>500,error);
            inst.runOnMainSync(()->{player.get().pause();player.get().setPlaybackSpeed(1.25f);android.view.View v=controls.get().touchSurface;touch(v,0,v.getWidth()/2f,v.getHeight()/2f);});Thread.sleep(700);
            inst.runOnMainSync(()->{assertEquals(3f,player.get().getPlaybackParameters().speed,.001f);try{assertEquals(android.view.View.GONE,((android.widget.TextView)field(controls.get(),"feedback")).getVisibility());}catch(Exception e){throw new RuntimeException(e);}android.view.View v=controls.get().touchSurface;touch(v,1,v.getWidth()/2f,v.getHeight()/2f);assertEquals(1.25f,player.get().getPlaybackParameters().speed,.001f);touch(v,0,100,200);});Thread.sleep(700);
            inst.runOnMainSync(()->{assertEquals(3f,player.get().getPlaybackParameters().speed,.001f);controls.get().cancelGesture();assertEquals(1.25f,player.get().getPlaybackParameters().speed,.001f);player.get().seekTo(4000);});
            inst.runOnMainSync(()->{android.view.View v=controls.get().touchSurface;float x=v.getWidth()/2f,y=v.getHeight()/2f;touch(v,0,x,y);touch(v,1,x,y);touch(v,0,x,y);touch(v,1,x,y);assertTrue("Double tap resumes",player.get().getPlayWhenReady());touch(v,0,x,y);touch(v,1,x,y);touch(v,0,x,y);touch(v,1,x,y);assertFalse("Double tap pauses",player.get().getPlayWhenReady());});
            inst.runOnMainSync(()->{android.view.View v=controls.get().touchSurface;float w=v.getWidth(),h=v.getHeight();touch(v,0,w*.3f,h*.5f);touch(v,2,w*.6f,h*.5f);touch(v,1,w*.6f,h*.5f);assertTrue(player.get().getCurrentPosition()>8000);android.view.WindowManager.LayoutParams lp=a.getWindow().getAttributes();lp.screenBrightness=.4f;a.getWindow().setAttributes(lp);touch(v,0,w*.2f,h*.5f);touch(v,2,w*.2f,h*.3f);touch(v,1,w*.2f,h*.3f);assertTrue(a.getWindow().getAttributes().screenBrightness>.5f);int max=audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,max/2,0);int before=audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);touch(v,0,w*.8f,h*.5f);touch(v,2,w*.8f,h*.7f);touch(v,1,w*.8f,h*.7f);assertTrue(audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)<before);controls.get().dispose();assertEquals(originalBrightness,a.getWindow().getAttributes().screenBrightness,.001f);});
        }finally{inst.runOnMainSync(()->{if(controls.get()!=null)controls.get().dispose();if(player.get()!=null)player.get().release();audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,originalVolume,0);a.finish();});ctx.close();}
    }
    @Test public void prefetchedCoverIsReadyBeforeRowAppears()throws Exception{
        String url=InstrumentationRegistry.getArguments().getString("smbTestFile");Assume.assumeTrue(url!=null);
        var inst=InstrumentationRegistry.getInstrumentation();Properties props=new Properties();props.setProperty("jcifs.smb.client.minVersion","SMB202");props.setProperty("jcifs.smb.client.maxVersion","SMB210");CIFSContext ctx=new BaseContext(new PropertyConfiguration(props)).withAnonymousCredentials();ThumbnailLoader loader=new ThumbnailLoader(inst.getTargetContext());
        try{loader.prefetch(url,12345,0,ctx,()->true);await(inst,()->{try{return ((android.util.LruCache<?,?>)field(loader,"memory")).size()>0;}catch(Exception e){throw new RuntimeException(e);}},new AtomicReference<>());
            inst.runOnMainSync(()->{android.widget.ImageView row=new android.widget.ImageView(inst.getTargetContext());loader.load(row,url,12345,0,ctx,()->true);assertTrue("Cover must bind immediately from preload",row.getDrawable() instanceof android.graphics.drawable.BitmapDrawable);});
        }finally{loader.close();ctx.close();}
    }
    @Before public void resetLabConnection(){if(InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName().endsWith(".lab"))InstrumentationRegistry.getInstrumentation().getTargetContext().getSharedPreferences("lanvideo",0).edit().clear().commit();}
    private static Object field(Object object,String name)throws Exception{var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private static void call(Object object,String name)throws Exception{var m=object.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(object);}
    @Test public void browserCoversFullscreenAndAutoReconnect()throws Exception {
        String url=InstrumentationRegistry.getArguments().getString("smbTestFile");Assume.assumeTrue(url!=null);
        var inst=InstrumentationRegistry.getInstrumentation();
        Activity a=inst.startActivitySync(new Intent(inst.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            inst.runOnMainSync(()->{try{
                ((android.widget.EditText)field(a,"host")).setText("127.0.0.1:1445");((android.widget.EditText)field(a,"share")).setText("fixture/Season1");
                ((android.widget.EditText)field(a,"user")).setText("");((android.widget.EditText)field(a,"pass")).setText("");((android.widget.CheckBox)field(a,"remember")).setChecked(true);call(a,"connect");
            }catch(Exception e){throw new RuntimeException(e);}});
            await(inst,()->{try{return (boolean)field(a,"browserVisible");}catch(Exception e){throw new RuntimeException(e);}},new AtomicReference<>());
            Thread.sleep(3500);
            screenshot(inst,"browser-preview.png");
            inst.runOnMainSync(()->{try{
                java.util.List<?> entries=(java.util.List<?>)field(a,"entries");assertTrue(entries.size()>0);Object video=null;
                for(Object e:entries){var f=e.getClass().getDeclaredField("folder");f.setAccessible(true);if(!f.getBoolean(e)){video=e;break;}}
                assertNotNull(video);var play=a.getClass().getDeclaredMethod("play",video.getClass());play.setAccessible(true);play.invoke(a,video);a.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            }catch(Exception e){throw new RuntimeException(e);}});
            await(inst,()->{try{return ((ExoPlayer)field(a,"player")).getCurrentPosition()>1000;}catch(Exception e){throw new RuntimeException(e);}},new AtomicReference<>());
            Thread.sleep(4000);
            inst.runOnMainSync(()->{if(android.os.Build.VERSION.SDK_INT>=30)assertFalse(a.getWindow().getDecorView().getRootWindowInsets().isVisible(android.view.WindowInsets.Type.statusBars()));});
            screenshot(inst,"fullscreen-preview.png");
            inst.runOnMainSync(()->{try{((ExoPlayer)field(a,"player")).pause();((PlaybackControls)field(a,"playbackControls")).showControls(true);}catch(Exception e){throw new RuntimeException(e);}});
            screenshot(inst,"bottom-controls-preview.png");
            inst.runOnMainSync(()->{try{PlaybackControls c=(PlaybackControls)field(a,"playbackControls");assertFalse(((android.view.View)field(c,"previousButton")).isEnabled());assertTrue(((android.view.View)field(c,"nextButton")).isEnabled());assertNull(c.findViewWithTag("fit"));assertEquals(android.view.View.GONE,((PlayerView)field(a,"playerView")).findViewById(androidx.media3.ui.R.id.exo_buffering).getVisibility());((android.view.View)field(c,"nextButton")).performClick();}catch(Exception e){throw new RuntimeException(e);}});
            await(inst,()->{try{return ((ExoPlayer)field(a,"player")).getCurrentPosition()>500;}catch(Exception e){throw new RuntimeException(e);}},new AtomicReference<>());
            inst.runOnMainSync(()->{try{PlaybackControls c=(PlaybackControls)field(a,"playbackControls");assertTrue(((android.view.View)field(c,"previousButton")).isEnabled());assertFalse(((android.view.View)field(c,"nextButton")).isEnabled());((android.view.View)field(c,"previousButton")).performClick();}catch(Exception e){throw new RuntimeException(e);}});
            await(inst,()->{try{return ((ExoPlayer)field(a,"player")).getPlaybackState()==Player.STATE_READY;}catch(Exception e){throw new RuntimeException(e);}},new AtomicReference<>());
            // A fresh cover cache during playback must be filled by the idle scheduler, without a browser row.
            inst.runOnMainSync(()->{try{((ExoPlayer)field(a,"player")).pause();}catch(Exception e){throw new RuntimeException(e);}});
            await(inst,()->{try{return !((ExoPlayer)field(a,"player")).isLoading()&&!(boolean)field(a,"idleCoverPending");}catch(Exception e){throw new RuntimeException(e);}},new AtomicReference<>());
            inst.runOnMainSync(()->{try{ThumbnailLoader loader=(ThumbnailLoader)field(a,"thumbnails");((android.util.LruCache<?,?>)field(loader,"memory")).evictAll();java.io.File[] cache=((java.io.File)field(loader,"directory")).listFiles();if(cache!=null)for(java.io.File f:cache)if(f.getName().endsWith(".jpg"))assertTrue(f.delete());var cursor=a.getClass().getDeclaredField("idleCoverCursor");cursor.setAccessible(true);cursor.setInt(a,0);}catch(Exception e){throw new RuntimeException(e);}});
            await(inst,()->{try{return ((android.util.LruCache<?,?>)field(field(a,"thumbnails"),"memory")).size()>0;}catch(Exception e){throw new RuntimeException(e);}},new AtomicReference<>());


        }finally{inst.runOnMainSync(a::finish);}
        inst.waitForIdleSync();
        Activity restarted=inst.startActivitySync(new Intent(inst.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{await(inst,()->{try{return (boolean)field(restarted,"browserVisible");}catch(Exception e){throw new RuntimeException(e);}},new AtomicReference<>());}finally{inst.runOnMainSync(restarted::finish);}
    }
    @Test public void parentDirectoryRestoresScrollAndPrefetchesChildren()throws Exception {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("smbTestFile")!=null);
        var inst=InstrumentationRegistry.getInstrumentation();Activity a=inst.startActivitySync(new Intent(inst.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            inst.runOnMainSync(()->{try{((android.widget.EditText)field(a,"host")).setText("127.0.0.1:1445");((android.widget.EditText)field(a,"share")).setText("fixture/Scroll");((android.widget.EditText)field(a,"user")).setText("");((android.widget.EditText)field(a,"pass")).setText("");call(a,"connect");}catch(Exception e){throw new RuntimeException(e);}});
            await(inst,()->{try{return (boolean)field(a,"browserVisible");}catch(Exception e){throw new RuntimeException(e);}},new AtomicReference<>());
            inst.runOnMainSync(()->{try{((android.widget.ListView)field(a,"fileList")).setSelectionFromTop(15,-20);}catch(Exception e){throw new RuntimeException(e);}});inst.waitForIdleSync();
            String[] parent={null},child={null};int[] position={0},offset={0};
            inst.runOnMainSync(()->{try{android.widget.ListView list=(android.widget.ListView)field(a,"fileList");position[0]=list.getFirstVisiblePosition();offset[0]=list.getChildAt(0).getTop();parent[0]=(String)field(a,"current");Object entry=((java.util.List<?>)field(a,"entries")).get(position[0]);child[0]=(String)field(entry,"url");}catch(Exception e){throw new RuntimeException(e);}});
            await(inst,()->{try{return ((FolderRepository<?>)field(a,"folders")).peek(child[0])!=null;}catch(Exception e){throw new RuntimeException(e);}},new AtomicReference<>());
            inst.runOnMainSync(()->{try{var load=a.getClass().getDeclaredMethod("load",String.class);load.setAccessible(true);load.invoke(a,child[0]);assertFalse((boolean)field(a,"busy"));assertEquals(child[0],field(a,"current"));call(a,"up");assertFalse("Cached parent needs no network",(boolean)field(a,"busy"));assertEquals(parent[0],field(a,"current"));}catch(Exception e){throw new RuntimeException(e);}});inst.waitForIdleSync();
            inst.runOnMainSync(()->{try{android.widget.ListView list=(android.widget.ListView)field(a,"fileList");assertEquals(position[0],list.getFirstVisiblePosition());assertEquals(offset[0],list.getChildAt(0).getTop());}catch(Exception e){throw new RuntimeException(e);}});
        }finally{inst.runOnMainSync(a::finish);}
    }
    private static void screenshot(android.app.Instrumentation inst,String name)throws Exception {
        inst.waitForIdleSync();Thread.sleep(250);
        android.graphics.Bitmap b=inst.getUiAutomation().takeScreenshot();if(b==null)return;
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(inst.getTargetContext().getExternalFilesDir(null),name))){b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{b.recycle();}
    }
    @Test public void passwordIsEncryptedAndCanBeRecovered()throws Exception {
        var prefs=InstrumentationRegistry.getInstrumentation().getTargetContext().getSharedPreferences("credential-test",0);
        try{CredentialStore.save(prefs,"synthetic-test-password");assertEquals("synthetic-test-password",CredentialStore.read(prefs));assertFalse(prefs.getString("password_ciphertext","").contains("synthetic-test-password"));CredentialStore.clear(prefs);assertEquals("",CredentialStore.read(prefs));}finally{prefs.edit().clear().commit();}
    }
    @Test public void onlyVideoExtensionsPass(){assertTrue(MainActivity.isVideo("第2集.MP4"));assertTrue(MainActivity.isVideo("movie.mkv"));assertFalse(MainActivity.isVideo("subtitle.srt"));assertFalse(MainActivity.isVideo("cover.jpg"));assertFalse(MainActivity.isVideo("movie.mp4.txt"));}
    @Test public void extractsCoverFromSmb()throws Exception {
        String url=InstrumentationRegistry.getArguments().getString("smbTestFile");Assume.assumeTrue(url!=null);
        Properties p=new Properties();p.setProperty("jcifs.smb.client.minVersion","SMB202");p.setProperty("jcifs.smb.client.maxVersion","SMB210");
        CIFSContext ctx=new BaseContext(new PropertyConfiguration(p)).withAnonymousCredentials();
        android.media.MediaMetadataRetriever retriever=new android.media.MediaMetadataRetriever();
        try(ThumbnailLoader.RemoteData data=new ThumbnailLoader.RemoteData(url,ctx,()->true)){
            retriever.setDataSource(data);android.graphics.Bitmap frame=retriever.getFrameAtTime(1_000_000);assertNotNull(frame);assertEquals(640,frame.getWidth());frame.recycle();
        }finally{retriever.release();ctx.close();}
    }
    @Test public void playsSeeksAndChangesSpeed() throws Exception {
        String url=InstrumentationRegistry.getArguments().getString("smbTestFile");
        Assume.assumeTrue("SMB test file not supplied",url!=null);
        var inst=InstrumentationRegistry.getInstrumentation();
        Properties props=new Properties();props.setProperty("jcifs.smb.client.minVersion","SMB202");props.setProperty("jcifs.smb.client.maxVersion","SMB210");
        CIFSContext ctx=new BaseContext(new PropertyConfiguration(props)).withAnonymousCredentials();
        Activity activity=inst.startActivitySync(new Intent(inst.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        AtomicReference<ExoPlayer> ref=new AtomicReference<>(); AtomicReference<PlaybackException> error=new AtomicReference<>();
        try {
            inst.runOnMainSync(()->{
                PlayerView view=new PlayerView(activity); activity.setContentView(view);
                ExoPlayer player=new ExoPlayer.Builder(activity).setMediaSourceFactory(new DefaultMediaSourceFactory(()->new SmbSource(ctx))).build();
                ref.set(player);view.setPlayer(player);
                player.addListener(new Player.Listener(){@Override public void onPlayerError(PlaybackException e){error.set(e);}});
                player.setMediaItem(MediaItem.fromUri(url));player.prepare();player.play();
            });
            await(inst,()->ref.get().getCurrentPosition()>1500,error);
            inst.runOnMainSync(()->{assertEquals(640,ref.get().getVideoSize().width);ref.get().seekTo(12000);ref.get().setPlaybackSpeed(2f);});
            await(inst,()->ref.get().getCurrentPosition()>13000,error);
            long[] start={0},end={0};
            inst.runOnMainSync(()->{assertEquals(2f,ref.get().getPlaybackParameters().speed,0.001f);start[0]=ref.get().getCurrentPosition();});
            Thread.sleep(1000);
            inst.runOnMainSync(()->end[0]=ref.get().getCurrentPosition());
            assertTrue("2x playback must advance more than 1.5 seconds per real second",end[0]-start[0]>1500);
            assertNull(error.get());
        } finally {
            inst.runOnMainSync(()->{if(ref.get()!=null)ref.get().release();activity.finish();});ctx.close();
        }
    }
    private interface Condition {boolean check();}
    private void await(android.app.Instrumentation inst,Condition condition,AtomicReference<PlaybackException> error)throws Exception {
        long deadline=android.os.SystemClock.elapsedRealtime()+25000;
        while(android.os.SystemClock.elapsedRealtime()<deadline){
            if(error.get()!=null)throw new AssertionError(error.get());
            boolean[] ok={false};inst.runOnMainSync(()->ok[0]=condition.check());if(ok[0])return;Thread.sleep(100);
        }
        fail("Timed out waiting for SMB playback");
    }
    @Test public void opensConnectionScreen() {
        var inst=InstrumentationRegistry.getInstrumentation();
        Intent intent=new Intent(inst.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity a=inst.startActivitySync(intent);
        inst.waitForIdleSync();
        assertNotNull(a);
        inst.runOnMainSync(a::finish);
    }
    /** Optional real-server read/seek test. Pass -e smbTestFile smb://host/share/file. */
    @Test public void smbRandomReadOnDevice() throws Exception {
        String url=InstrumentationRegistry.getArguments().getString("smbTestFile");
        Assume.assumeTrue("SMB test file not supplied",url!=null);
        Properties p=new Properties(); p.setProperty("jcifs.smb.client.minVersion","SMB202"); p.setProperty("jcifs.smb.client.maxVersion","SMB210");
        CIFSContext ctx=new BaseContext(new PropertyConfiguration(p)).withAnonymousCredentials();
        try {
            byte[] first=new byte[128],again=new byte[128];
            try(SmbFile f=new SmbFile(Uri.decode(url),ctx)){assertTrue(f.length()>256);}
            SmbSource source=new SmbSource(ctx);
            try {
                source.open(new DataSpec(Uri.parse(url))); assertEquals(128,source.read(first,0,128)); source.close();
                source.open(new DataSpec.Builder().setUri(url).setPosition(0).setLength(128).build());
                assertEquals(128,source.read(again,0,128)); assertArrayEquals(first,again);
                assertEquals(-1,source.read(again,0,1)); assertEquals(0,source.read(again,0,0)); source.close();
                source.open(new DataSpec.Builder().setUri(url).setPosition(128).setLength(128).build()); assertEquals(128,source.read(again,0,128));
            } finally {source.close();}
        } finally {ctx.close();}
    }
}
