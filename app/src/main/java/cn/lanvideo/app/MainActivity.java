package cn.lanvideo.app;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.*;
import java.util.*;
import java.util.concurrent.*;
import java.text.SimpleDateFormat;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class MainActivity extends Activity {
    private final int BG=Color.rgb(255,255,255), CARD=Color.rgb(243,245,248), INK=Color.rgb(27,31,37), MUTED=Color.rgb(129,136,146), ACCENT=Color.rgb(40,107,239);
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private android.content.SharedPreferences prefs;
    private CIFSContext smb;
    private FolderRepository<Entry> folders;
    private ListView fileList;
    private String displayedPath="";
    private final Map<String,ScrollPosition> scrollPositions=new HashMap<>();
    private final android.os.Handler browserHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable preload;
    private volatile boolean idleCoverAllowed;
    private boolean resumed,browserScrolling;
    private int idleCoverCursor;
    private boolean idleCoverPending;
    private final Runnable idleCovers=new Runnable(){public void run(){
        idleCoverAllowed=resumed&&!dead&&!busy&&smb!=null&&((browserVisible&&!browserScrolling)||(player!=null&&!player.isLoading()&&( !player.getPlayWhenReady()||player.getBufferedPosition()-player.getCurrentPosition()>15000*player.getPlaybackParameters().speed||player.getPlaybackState()==Player.STATE_ENDED)));
        if(idleCoverAllowed&&!idleCoverPending){
            while(idleCoverCursor<entries.size()){
                Entry e=entries.get(idleCoverCursor++);if(e.folder||thumbnails.isCached(e.url,e.modified,e.bytes))continue;
                idleCoverPending=true;int token=generation;CIFSContext context=smb;
                thumbnails.prefetch(e.url,e.modified,e.bytes,context,()->idleCoverAllowed&&!dead&&token==generation).whenComplete((bitmap,error)->browserHandler.post(()->{if(token==generation){idleCoverPending=false;if(bitmap==null&&!idleCoverAllowed&&idleCoverCursor>0)idleCoverCursor--;}}));break;
            }
        }
        if(!dead)browserHandler.postDelayed(this,2000);
    }};
    private String root="", current="", playingUrl="";
    private ArrayList<Entry> entries=new ArrayList<>();
    private LinearLayout page;
    private TextView status, breadcrumb;
    private EditText host, share, user, pass, domain;
    private Button connect;
    private CheckBox remember;
    private ThumbnailLoader thumbnails;
    private ExoPlayer player;
    private PlayerView playerView;
    private PlaybackControls playbackControls;
    private float speed=1f;
    private int sort=0;
    private volatile int generation=0;
    private boolean descending=false, busy=false;
    private volatile boolean dead=false,browserVisible=false;
    private final String[] sortLabels={"名称","修改时间","文件大小"};

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        prefs=getSharedPreferences("lanvideo",MODE_PRIVATE);
        sort=prefs.getInt("sort",0); descending=prefs.getBoolean("descending",false);
        speed=prefs.getFloat("speed",1f);
        thumbnails=new ThumbnailLoader(this);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        showConnect();
        if(prefs.getBoolean("autoConnect",false))page.post(()->{if(!dead&&!busy&&smb==null)connect();});
    }
    private int dp(int n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout v=new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private TextView text(String s,int size,int color) { TextView t=new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); t.setPadding(0,dp(6),0,dp(6)); return t; }
    private Button button(String s,Runnable action) { Button b=new Button(this); b.setText(s); b.setTextSize(14); b.setTextColor(ACCENT); b.setAllCaps(false); b.setMinWidth(0); b.setMinimumWidth(0); b.setPadding(dp(12),0,dp(12),0); b.setBackgroundColor(Color.TRANSPARENT); b.setOnClickListener(v->action.run()); return b; }
    private GradientDrawable rounded(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private ImageButton backButton(Runnable action,boolean dark){ImageButton b=new ImageButton(this);b.setImageResource(cn.lanvideo.app.R.drawable.ic_back);b.setColorFilter(dark?Color.WHITE:INK);b.setContentDescription("返回上一级");b.setPadding(dp(10),dp(10),dp(10),dp(10));b.setBackgroundColor(Color.TRANSPARENT);b.setOnClickListener(v->action.run());return b;}
    private void base(String title,String subtitle) {
        page=column(); page.setBackgroundColor(BG); page.setPadding(dp(20),dp(16),dp(20),dp(12));
        restoreBars();
        TextView heading=text(title,28,INK); heading.setTypeface(null,Typeface.BOLD); page.addView(heading);
        if(!subtitle.isEmpty()) page.addView(text(subtitle,14,MUTED));
        setContentView(page);
    }
    private EditText field(LinearLayout parent,String label,String value,boolean secret) {
        parent.addView(text(label,13,MUTED));
        EditText e=new EditText(this); e.setSingleLine(true); e.setTextColor(INK); e.setTextSize(16);
        e.setInputType(secret?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setText(value);e.setContentDescription(label); e.setPadding(dp(14),0,dp(14),0);e.setBackground(rounded(CARD,12));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(50));lp.bottomMargin=dp(10);parent.addView(e,lp); return e;
    }
    private void showConnect() {
        browserVisible=false;
        stopPreload();if(folders!=null){folders.close();folders=null;}fileList=null;scrollPositions.clear();
        base("连接电脑","简映 SMB · 你的远程视频文件夹");
        ScrollView scroll=new ScrollView(this); LinearLayout form=column(); scroll.addView(form); page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        host=field(form,"电脑 IP 或主机名",prefs.getString("host",""),false);
        share=field(form,"共享名称（可留空，先列出共享）",prefs.getString("share",""),false);
        user=field(form,"用户名（匿名共享可留空）",prefs.getString("user",""),false);
        String savedPassword="";boolean keyFailed=false;
        try{savedPassword=CredentialStore.read(prefs);}catch(Exception e){CredentialStore.clear(prefs);keyFailed=true;}
        pass=field(form,"密码",savedPassword,true);
        domain=field(form,"域（通常留空）",prefs.getString("domain",""),false);
        remember=new CheckBox(this);remember.setText("记住密码，下次打开自动连接");remember.setTextColor(INK);remember.setTextSize(14);remember.setChecked(prefs.getBoolean("remember",true));form.addView(remember);
        connect=button("连接",this::connect);connect.setTextColor(Color.WHITE);connect.setBackground(rounded(ACCENT,14));form.addView(connect,new LinearLayout.LayoutParams(-1,dp(52)));
        status=text(keyFailed?"已保存的凭据无法解密，请重新输入密码。":"密码由手机系统密钥加密保存。\n使用 Tailscale 时，请先连接同一网络。",13,MUTED); form.addView(status);
        form.addView(button("清除记住的密码",()->{CredentialStore.clear(prefs);pass.setText("");remember.setChecked(false);prefs.edit().putBoolean("remember",false).apply();status.setText("已清除密码并关闭自动连接。");}));
    }
    private void connect() {
        if(busy)return;
        String h=host.getText().toString().trim(), s=share.getText().toString().trim();
        if(h.startsWith("smb://")) h=h.substring(6);
        h=h.replace('\\','/'); while(h.startsWith("/")) h=h.substring(1);
        int slash=h.indexOf('/'); if(slash>=0) { if(s.isEmpty()) s=h.substring(slash+1); h=h.substring(0,slash); }
        if(!h.matches("[a-zA-Z0-9._-]+(?::[0-9]{1,5})?")) { status.setText("请输入有效 IP 或主机名，例如 fileserver"); return; }
        if(h.contains(":")){int port=Integer.parseInt(h.substring(h.indexOf(':')+1));if(port<1||port>65535){status.setText("端口必须在 1–65535 之间");return;}}
        s=s.replace('\\','/'); while(s.startsWith("/")) s=s.substring(1); while(s.endsWith("/")) s=s.substring(0,s.length()-1);
        if(s.contains("..") || s.contains("@")) { status.setText("共享名称无效"); return; }
        String u=user.getText().toString().trim(), p=pass.getText().toString(), d=domain.getText().toString().trim();
        final String finalHost=h,finalShare=s;final boolean save=remember.isChecked();
        String url="smb://"+h+"/"+(s.isEmpty()?"":encodePath(s)+"/");
        connect.setEnabled(false); status.setText("正在连接电脑…"); busy=true;
        io.execute(()->{
            CIFSContext next=null;
            try {
                Properties props=new Properties();
                props.setProperty("jcifs.smb.client.minVersion","SMB202");
                props.setProperty("jcifs.smb.client.maxVersion","SMB210");
                props.setProperty("jcifs.smb.client.connTimeout","10000");
                props.setProperty("jcifs.smb.client.responseTimeout","15000");
                props.setProperty("jcifs.smb.client.soTimeout","20000");
                BaseContext base=new BaseContext(new PropertyConfiguration(props));
                next=u.isEmpty()?base.withAnonymousCredentials():base.withCredentials(new NtlmPasswordAuthenticator(d,u,p));
                ArrayList<Entry> found=readFolder(url,next);
                CIFSContext ready=next;
                String storageError="";
                try{if(save)CredentialStore.save(prefs,p);else CredentialStore.clear(prefs);}catch(Exception e){storageError="已连接，但密码保存失败，下次需要重新输入。";CredentialStore.clear(prefs);}
                prefs.edit().putString("host",finalHost).putString("share",finalShare).putString("user",u).putString("domain",d).putBoolean("remember",save).putBoolean("autoConnect",save&&storageError.isEmpty()).apply();
                String notice=storageError;
                runOnUiThread(()->{ if(dead) {new Thread(()->closeContext(ready)).start();return;} smb=ready;folders=new FolderRepository<>(path->readFolder(path,ready));folders.seed(url,found);scrollPositions.clear(); root=url; current=url; entries=found; busy=false; pass.setText(""); showBrowser();if(!notice.isEmpty())Toast.makeText(this,notice,Toast.LENGTH_LONG).show(); });
            } catch(Exception e) {
                closeContext(next); String message=explain(e);
                runOnUiThread(()->{ if(dead)return; busy=false; connect.setEnabled(true); status.setText(message); });
            }
        });
    }
    private static String encodePath(String path) {
        StringBuilder out=new StringBuilder(); for(String part:path.split("/")) { if(out.length()>0)out.append('/'); out.append(Uri.encode(part)); } return out.toString();
    }
    private ArrayList<Entry> readFolder(String url,CIFSContext ctx) throws Exception {
        ArrayList<Entry> list=new ArrayList<>();
        try(SmbFile dir=new SmbFile(Uri.decode(url),ctx)) {
            SmbFile[] files=dir.listFiles();
            try {
                for(SmbFile f:files) {
                    String name=f.getName().replaceAll("/$","");
                    if(name.equals(".") || name.equals("..") || name.endsWith("$"))continue;
                    boolean folder=f.isDirectory();
                    if(!folder&&!isVideo(name))continue;
                    list.add(new Entry(name,url+Uri.encode(name)+(folder?"/":""),folder,folder?0:f.length(),f.lastModified()));
                }
            } finally { for(SmbFile f:files)f.close(); }
        }
        return list;
    }
    private void showBrowser() {
        rememberScroll();fileList=null;stopPreload();
        browserVisible=true;browserScrolling=false;int token=++generation;idleCoverCursor=0;idleCoverPending=false;restoreBars();
        page=column();page.setBackgroundColor(BG);page.setPadding(dp(16),dp(8),dp(16),0);setContentView(page);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(backButton(this::up,false),new LinearLayout.LayoutParams(dp(48),dp(56)));
        TextView heading=text(current.equals(root)?"远程文件":folderName(current),24,INK);heading.setTypeface(null,Typeface.BOLD);heading.setSingleLine(true);heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(heading,new LinearLayout.LayoutParams(0,dp(56),1));header.addView(button("•••",this::browserMenu),new LinearLayout.LayoutParams(dp(48),dp(56)));page.addView(header);
        HorizontalScrollView trail=new HorizontalScrollView(this);trail.setHorizontalScrollBarEnabled(false);LinearLayout crumbs=new LinearLayout(this);trail.addView(crumbs);
        crumbs.addView(button("电脑",()->load(root)));
        String relative=current.startsWith(root)?current.substring(root.length()):"";String path=root;
        for(String part:relative.split("/")){if(part.isEmpty())continue;crumbs.addView(text("›",14,MUTED));path+=part+"/";String destination=path;crumbs.addView(button(Uri.decode(part),()->load(destination)));}
        page.addView(trail,new LinearLayout.LayoutParams(-1,dp(44)));trail.post(()->trail.fullScroll(View.FOCUS_RIGHT));
        LinearLayout tools=new LinearLayout(this);tools.setGravity(Gravity.CENTER_VERTICAL);
        int videos=0;for(Entry e:entries)if(!e.folder)videos++;
        status=text(videos+" 个视频 · "+(entries.size()-videos)+" 个文件夹",12,MUTED);tools.addView(status,new LinearLayout.LayoutParams(0,-2,1));
        tools.addView(button(sortLabels[sort]+(descending?" ↓":" ↑"),this::sortDialog));page.addView(tools,new LinearLayout.LayoutParams(-1,dp(46)));
        View divider=new View(this);divider.setBackgroundColor(CARD);page.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));
        if(entries.isEmpty()){TextView empty=text("暂无视频\n这里只显示视频文件和文件夹",16,MUTED);empty.setGravity(Gravity.CENTER);page.addView(empty,new LinearLayout.LayoutParams(-1,0,1));return;}
        ListView list=new ListView(this);list.setDivider(null);list.setVerticalScrollBarEnabled(false);list.setClipToPadding(false);list.setPadding(0,dp(8),0,dp(12));
        fileList=list;displayedPath=current;
        page.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        applySort();
        list.setAdapter(new BaseAdapter() {
            public int getCount(){return entries.size();}
            public Object getItem(int i){return entries.get(i);}
            public long getItemId(int i){return i;}
            public View getView(int position,View convert,android.view.ViewGroup parent) {
                Entry e=entries.get(position);LinearLayout row=new LinearLayout(MainActivity.this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(2),dp(10),dp(2),dp(10));
                ImageView cover=new ImageView(MainActivity.this);cover.setScaleType(ImageView.ScaleType.CENTER_CROP);cover.setBackground(rounded(CARD,10));cover.setClipToOutline(true);
                int rowWidth=getResources().getDisplayMetrics().widthPixels-dp(36);
                int width=e.folder?52:Math.round(rowWidth*.4f/getResources().getDisplayMetrics().density);
                int height=e.folder?52:Math.round(width*9f/16f);
                if(e.folder){cover.setImageResource(cn.lanvideo.app.R.drawable.ic_folder);cover.setPadding(dp(8),dp(8),dp(8),dp(8));}
                else{cover.setImageResource(cn.lanvideo.app.R.drawable.ic_video_placeholder);CIFSContext ctx=smb;cover.post(()->thumbnails.load(cover,e.url,e.modified,e.bytes,ctx,()->!dead&&browserVisible&&generation==token&&cover.isAttachedToWindow()));}
                row.addView(cover,new LinearLayout.LayoutParams(dp(width),dp(height)));
                LinearLayout detail=column();detail.setPadding(dp(14),0,0,0);detail.setGravity(Gravity.CENTER_VERTICAL);
                TextView title=text(e.name,15,INK);title.setPadding(0,0,0,dp(7));title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);title.setLineSpacing(dp(2),1);detail.addView(title);
                TextView meta=text(e.folder?"文件夹":size(e.bytes)+"  ·  "+new SimpleDateFormat("MM-dd",Locale.CHINA).format(new Date(e.modified)),11,MUTED);meta.setPadding(0,0,0,0);detail.addView(meta);
                row.addView(detail,new LinearLayout.LayoutParams(0,-2,1));
                if(e.folder)row.addView(text("›",24,MUTED));
                return row;
            }
        });
        list.setOnItemClickListener((p,v,pos,id)->{if(busy)return;Entry e=entries.get(pos);if(e.folder)load(e.url);else play(e);});
        ScrollPosition saved=scrollPositions.get(current);
        if(saved!=null){int index=Math.min(saved.index,entries.size()-1);for(int i=0;i<entries.size();i++)if(entries.get(i).url.equals(saved.anchor)){index=i;break;}list.setSelectionFromTop(Math.max(0,index),saved.top);}
        list.setOnScrollListener(new AbsListView.OnScrollListener(){public void onScrollStateChanged(AbsListView v,int state){browserScrolling=state!=SCROLL_STATE_IDLE;if(browserScrolling)idleCoverAllowed=false;if(state==SCROLL_STATE_IDLE)schedulePreload(token);else if(preload!=null)browserHandler.removeCallbacks(preload);}public void onScroll(AbsListView v,int first,int count,int total){schedulePreload(token);}});
        list.post(()->schedulePreload(token));
    }
    private void rememberScroll(){if(fileList==null||fileList.getChildCount()==0||displayedPath.isEmpty())return;int index=fileList.getFirstVisiblePosition();String anchor="";if(displayedPath.equals(current)&&index<entries.size())anchor=entries.get(index).url;else{ScrollPosition previous=scrollPositions.get(displayedPath);if(previous!=null)anchor=previous.anchor;}scrollPositions.put(displayedPath,new ScrollPosition(index,fileList.getChildAt(0).getTop(),anchor));}
    private void stopPreload(){if(preload!=null)browserHandler.removeCallbacks(preload);if(folders!=null)folders.cancelQueuedPrefetch();}
    private void schedulePreload(int token){if(preload!=null)browserHandler.removeCallbacks(preload);preload=()->{if(dead||!browserVisible||busy||token!=generation||fileList==null||folders==null)return;int first=fileList.getFirstVisiblePosition(),last=fileList.getLastVisiblePosition();CIFSContext context=smb;
        for(int i=Math.max(0,last+1),n=0;i<entries.size()&&n<8;i++){Entry e=entries.get(i);if(!e.folder){n++;thumbnails.prefetch(e.url,e.modified,e.bytes,context,()->!dead&&browserVisible&&token==generation);}}
        if(!"/".equals(Uri.parse(current).getPath()))for(int i=first,n=0;i<entries.size()&&i<=last+4&&n<2;i++){Entry e=entries.get(i);if(e.folder){folders.prefetch(e.url);n++;}}
    };browserHandler.postDelayed(preload,350);}
    private static final class ScrollPosition{final int index,top;final String anchor;ScrollPosition(int i,int t,String a){index=i;top=t;anchor=a;}}
    private static String folderName(String url){String trim=url.endsWith("/")?url.substring(0,url.length()-1):url;return Uri.decode(trim.substring(trim.lastIndexOf('/')+1));}
    private void browserMenu(){if(busy)return;new AlertDialog.Builder(this).setItems(new String[]{"刷新文件夹","排序方式","连接设置"},(d,which)->{if(which==0)load(current,true);else if(which==1)sortDialog();else{generation++;browserVisible=false;CIFSContext old=smb;smb=null;io.execute(()->closeContext(old));showConnect();}}).show();}
    private void load(String url) {
        load(url,false);
    }
    private void load(String url,boolean refresh) {
        if(busy||smb==null||folders==null)return;
        rememberScroll();stopPreload();
        List<Entry> cached=refresh?null:folders.peek(url);
        if(cached!=null){fileList=null;current=url;entries=new ArrayList<>(cached);showBrowser();return;}
        busy=true;status.setText("正在读取文件夹…");int request=++generation;
        folders.load(url,refresh).whenComplete((found,error)->runOnUiThread(()->{if(dead||request!=generation)return;busy=false;if(error==null){fileList=null;current=url;entries=new ArrayList<>(found);showBrowser();}else{status.setText(explain(error instanceof Exception?(Exception)error:new Exception(error))+"\n可点击刷新重试。");}}));
    }
    private void up() {
        if(busy)return;
        if(current.equals(root)) { Toast.makeText(this,"已到连接根目录",Toast.LENGTH_SHORT).show(); return; }
        String trim=current.endsWith("/")?current.substring(0,current.length()-1):current;
        String parent=trim.substring(0,trim.lastIndexOf('/')+1); load(parent.length()<root.length()?root:parent);
    }
    private void sortDialog() {
        if(busy)return;
        String[] labels={"名称 · 升序","名称 · 降序","修改时间 · 升序","修改时间 · 降序","文件大小 · 升序","文件大小 · 降序"};
        new AlertDialog.Builder(this).setTitle("排序方式（文件夹始终在前）").setSingleChoiceItems(labels,sort*2+(descending?1:0),(d,w)->{sort=w/2;descending=w%2==1;prefs.edit().putInt("sort",sort).putBoolean("descending",descending).apply();d.dismiss();showBrowser();}).show();
    }
    private void applySort() {
        entries.sort((a,b)->{
            if(a.folder!=b.folder)return a.folder?-1:1;
            int c=sort==1?Long.compare(a.modified,b.modified):sort==2?Long.compare(a.bytes,b.bytes):NaturalOrder.compare(a.name,b.name);
            if(c==0)c=NaturalOrder.compare(a.name,b.name);
            return descending?-Integer.signum(c):c;
        });
    }
    private void play(Entry entry) {
        if(player!=null)releasePlayer();
        rememberScroll();fileList=null;stopPreload();
        browserVisible=false;generation++;idleCoverAllowed=false;idleCoverPending=false;idleCoverCursor=0;
        playingUrl=entry.url;
        FrameLayout screen=new FrameLayout(this);screen.setBackgroundColor(Color.BLACK);
        playerView=new PlayerView(this);playerView.setUseController(false);playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER);
        screen.addView(playerView,new FrameLayout.LayoutParams(-1,-1));
        setContentView(screen);
        CIFSContext context=smb;
        player=new ExoPlayer.Builder(this).setMediaSourceFactory(new DefaultMediaSourceFactory(()->new SmbSource(context))).build();
        playerView.setPlayer(player);
        Entry previous=adjacentVideo(entry.url,-1),next=adjacentVideo(entry.url,1);
        playbackControls=new PlaybackControls(this,player,playerView,entry.name,this::stopPlayback,this::speedDialog,previous==null?null:()->play(previous),next==null?null:()->play(next));screen.addView(playbackControls,new FrameLayout.LayoutParams(-1,-1));
        player.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true);
        player.setHandleAudioBecomingNoisy(true);
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException error) {
                new AlertDialog.Builder(MainActivity.this).setTitle("暂时无法播放").setMessage("请检查网络和共享权限。若只有这个视频失败，可能是手机不支持其音视频编码。\n\n"+error.getErrorCodeName()).setPositiveButton("重试",(d,w)->{if(player!=null){player.prepare();player.play();}}).setNegativeButton("返回目录",(d,w)->stopPlayback()).show();
            }
        });
        player.setMediaItem(MediaItem.fromUri(entry.url));
        long position=prefs.getLong("position:"+playingUrl,0); player.seekTo(position);
        player.setPlaybackSpeed(speed); player.prepare(); player.play();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideBars();
    }
    private Entry adjacentVideo(String url,int direction){int index=-1;for(int i=0;i<entries.size();i++)if(entries.get(i).url.equals(url)){index=i;break;}if(index<0)return null;for(int i=index+direction;i>=0&&i<entries.size();i+=direction)if(!entries.get(i).folder)return entries.get(i);return null;}
    private void hideBars(){
        getWindow().setStatusBarColor(Color.BLACK);getWindow().setNavigationBarColor(Color.BLACK);
        if(Build.VERSION.SDK_INT>=28){WindowManager.LayoutParams lp=getWindow().getAttributes();lp.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;getWindow().setAttributes(lp);}
        if(Build.VERSION.SDK_INT>=30){getWindow().setDecorFitsSystemWindows(false);WindowInsetsController c=getWindow().getInsetsController();if(c!=null){c.hide(WindowInsets.Type.systemBars());c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);}}
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
    private void restoreBars(){
        getWindow().getDecorView();
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        if(Build.VERSION.SDK_INT>=28){WindowManager.LayoutParams lp=getWindow().getAttributes();lp.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;getWindow().setAttributes(lp);}
        if(Build.VERSION.SDK_INT>=30){getWindow().setDecorFitsSystemWindows(true);WindowInsetsController c=getWindow().getInsetsController();if(c!=null){c.show(WindowInsets.Type.systemBars());c.setSystemBarsAppearance(WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);}}
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused){if(player!=null)hideBars();else restoreBars();}}
    @Override public void onConfigurationChanged(android.content.res.Configuration config){super.onConfigurationChanged(config);if(player!=null)hideBars();else if(browserVisible)showBrowser();}
    private void speedDialog() {
        float[] rates={.25f,.5f,.75f,1f,1.25f,1.5f,1.75f,2f,2.5f,3f};
        String[] labels=new String[rates.length]; int selected=3;
        for(int i=0;i<rates.length;i++){labels[i]=rates[i]+"×";if(rates[i]==speed)selected=i;}
        new AlertDialog.Builder(this).setTitle("播放速度").setSingleChoiceItems(labels,selected,(d,w)->{speed=rates[w];prefs.edit().putFloat("speed",speed).apply();if(player!=null)player.setPlaybackSpeed(speed);Button b=getWindow().getDecorView().findViewWithTag("speed");if(b!=null)b.setText(speed+"×");d.dismiss();}).show();
    }
    private void savePosition() {
        if(player!=null && !playingUrl.isEmpty()) {
            long position=player.getCurrentPosition(); if(player.getPlaybackState()==Player.STATE_ENDED)position=0;
            prefs.edit().putLong("position:"+playingUrl,position).apply();
        }
    }
    private void releasePlayer() {if(playbackControls!=null){playbackControls.dispose();playbackControls=null;} if(player!=null){savePosition();playerView.setPlayer(null);player.release();player=null;} getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); }
    private void stopPlayback() {releasePlayer();setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);showBrowser();}
    @Override public void onBackPressed() {if(player!=null)stopPlayback();else if(smb!=null && !current.equals(root))up();else if(smb!=null){CIFSContext old=smb;smb=null;generation++;busy=false;io.execute(()->closeContext(old));showConnect();}else super.onBackPressed();}
    @Override protected void onResume(){super.onResume();resumed=true;browserHandler.removeCallbacks(idleCovers);browserHandler.postDelayed(idleCovers,2000);}
    @Override protected void onPause(){super.onPause();resumed=false;idleCoverAllowed=false;browserHandler.removeCallbacks(idleCovers);if(playbackControls!=null)playbackControls.cancelGesture();if(player!=null){savePosition();player.pause();}}
    @Override protected void onDestroy(){dead=true;generation++;stopPreload();if(folders!=null)folders.close();browserHandler.removeCallbacksAndMessages(null);thumbnails.close();releasePlayer();CIFSContext old=smb;io.execute(()->closeContext(old));io.shutdown();super.onDestroy();}
    private static void closeContext(CIFSContext c) {if(c!=null)try{c.close();}catch(Exception ignored){}}
    static boolean isVideo(String name){return name.toLowerCase(Locale.ROOT).matches(".*\\.(mp4|mkv|avi|mov|webm|m4v|ts|m2ts|mts|mpeg|mpg|3gp|3g2|flv|wmv|asf|vob|ogv|rm|rmvb|divx|f4v)");}
    private static String size(long b){return b>=1073741824?String.format(Locale.CHINA,"%.2f GB",b/1073741824d):String.format(Locale.CHINA,"%.1f MB",b/1048576d);}
    private static String explain(Exception e) {
        String message=String.valueOf(e.getMessage());
        if(e instanceof SmbAuthException || message.contains("ACCESS_DENIED") || message.contains("LOGON"))return "共享需要登录，或当前账号没有权限。请使用 Windows 共享账号和密码（不是 PIN）。";
        return "连接或读取失败。请检查 Tailscale、共享名称和权限。\n"+message;
    }
    private static class Entry {
        final String name,url;final boolean folder;final long bytes,modified;
        Entry(String n,String u,boolean f,long b,long m){name=n;url=u;folder=f;bytes=b;modified=m;}
    }
}
