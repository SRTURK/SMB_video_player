package cn.lanvideo.app;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.ui.*;
import java.util.Locale;

/** Owns only playback UI and touch state; never changes the user's saved playback speed. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class PlaybackControls extends FrameLayout {
    private final Activity activity;
    private final Player player;
    private final PlayerView video;
    private final AudioManager audio;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final LinearLayout top,bottom;
    private final TextView feedback,elapsed,total;
    private final Button speedButton;
    private final ImageButton playButton,previousButton,nextButton;
    private final SeekBar seek;
    final View touchSurface;
    private final int slop;
    private boolean visible=true,dragging=false,boosting=false,closed=false;
    private int axis=0,startVolume;
    private float downX,downY,startBrightness,normalSpeed=1f;
    private long startPosition,targetPosition;
    private long lastTap;
    private float tapX,tapY;
    private boolean secondTap;
    private final Runnable singleTap=()->{lastTap=0;showControls(!visible);};
    private final float originalBrightness;
    private final Runnable hide=()->showControls(false);
    private final Runnable clearFeedback=this::clearHint;
    private final Runnable longPress=this::startBoost;
    private final Runnable tick=new Runnable(){public void run(){if(closed)return;update();handler.postDelayed(this,400);}};
    private final Player.Listener listener=new Player.Listener(){@Override public void onIsPlayingChanged(boolean playing){update();if(!playing&&!boosting)showControls(true);else scheduleHide();}};

    PlaybackControls(Activity activity,Player player,PlayerView video,String title,Runnable back,Runnable speedDialog){
        this(activity,player,video,title,back,speedDialog,null,null);
    }
    PlaybackControls(Activity activity,Player player,PlayerView video,String title,Runnable back,Runnable speedDialog,Runnable previous,Runnable next){
        super(activity);this.activity=activity;this.player=player;this.video=video;
        audio=(AudioManager)activity.getSystemService(Context.AUDIO_SERVICE);slop=ViewConfiguration.get(activity).getScaledTouchSlop();originalBrightness=activity.getWindow().getAttributes().screenBrightness;
        touchSurface=new View(activity);touchSurface.setContentDescription("播放手势区域");touchSurface.setOnTouchListener((v,e)->touch(e));addView(touchSurface,new LayoutParams(-1,-1));
        top=new LinearLayout(activity);top.setGravity(Gravity.CENTER_VERTICAL);top.setPadding(dp(16),0,dp(16),0);top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0xAA000000,0x00000000}));
        ImageButton returnButton=icon(R.drawable.ic_back,"返回",back);top.addView(returnButton,new LinearLayout.LayoutParams(dp(48),dp(56)));
        TextView name=label(title,16);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);top.addView(name,new LinearLayout.LayoutParams(0,-2,1));addView(top,new LayoutParams(-1,dp(64),Gravity.TOP));
        bottom=new LinearLayout(activity);bottom.setOrientation(LinearLayout.VERTICAL);bottom.setPadding(dp(16),dp(12),dp(16),dp(8));bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x00000000,0xBB000000}));
        LinearLayout timeline=new LinearLayout(activity);timeline.setGravity(Gravity.CENTER_VERTICAL);
        elapsed=label("00:00",12);total=label("00:00",12);timeline.addView(elapsed,new LinearLayout.LayoutParams(dp(55),dp(40)));
        seek=new SeekBar(activity);seek.setMax(1000);seek.setProgressTintList(ColorStateList.valueOf(0xFF63B5FF));seek.setThumbTintList(ColorStateList.valueOf(0xFF63B5FF));timeline.addView(seek,new LinearLayout.LayoutParams(0,dp(44),1));timeline.addView(total,new LinearLayout.LayoutParams(dp(55),dp(40)));bottom.addView(timeline);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean user){if(user&&duration()>0)elapsed.setText(time(duration()*p/1000));}public void onStartTrackingTouch(SeekBar s){dragging=true;handler.removeCallbacks(hide);}public void onStopTrackingTouch(SeekBar s){if(duration()>0)player.seekTo(duration()*s.getProgress()/1000);dragging=false;scheduleHide();}});
        LinearLayout actions=new LinearLayout(activity);actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout center=new LinearLayout(activity);center.setGravity(Gravity.CENTER);
        actions.addView(button("旋转",()->{activity.setRequestedOrientation(getResources().getConfiguration().orientation==2?ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);scheduleHide();}),new LinearLayout.LayoutParams(0,dp(52),1));
        previousButton=icon(R.drawable.ic_previous,"上一个视频",()->{if(previous!=null)previous.run();});previousButton.setEnabled(previous!=null);previousButton.setAlpha(previous!=null?1f:.3f);center.addView(previousButton,new LinearLayout.LayoutParams(0,dp(52),1));
        playButton=icon(R.drawable.ic_play,"播放",()->{if(player.getPlayWhenReady())player.pause();else{if(player.getPlaybackState()==Player.STATE_ENDED)player.seekTo(0);player.play();}showControls(true);});center.addView(playButton,new LinearLayout.LayoutParams(0,dp(56),1));
        nextButton=icon(R.drawable.ic_next,"下一个视频",()->{if(next!=null)next.run();});nextButton.setEnabled(next!=null);nextButton.setAlpha(next!=null?1f:.3f);center.addView(nextButton,new LinearLayout.LayoutParams(0,dp(52),1));actions.addView(center,new LinearLayout.LayoutParams(0,dp(56),2));
        speedButton=button("1×",speedDialog);speedButton.setTag("speed");actions.addView(speedButton,new LinearLayout.LayoutParams(0,dp(52),1));bottom.addView(actions);addView(bottom,new LayoutParams(-1,-2,Gravity.BOTTOM));
        feedback=label("",13);feedback.setTextColor(0xD9FFFFFF);feedback.setPadding(dp(12),dp(7),dp(12),dp(7));GradientDrawable bubble=new GradientDrawable();bubble.setColor(0x6615191F);bubble.setCornerRadius(dp(9));feedback.setBackground(bubble);feedback.setVisibility(GONE);feedback.setTranslationY(-dp(64));addView(feedback,new LayoutParams(-2,-2,Gravity.CENTER));
        player.addListener(listener);handler.post(tick);scheduleHide();
    }
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private TextView label(String text,int size){TextView v=new TextView(getContext());v.setText(text);v.setTextSize(size);v.setTextColor(Color.WHITE);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private Button button(String text,Runnable action){Button b=new Button(getContext());b.setText(text);b.setTextColor(Color.WHITE);b.setTextSize(13);b.setAllCaps(false);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(0,0,0,0);b.setBackgroundColor(Color.TRANSPARENT);b.setOnClickListener(v->{handler.removeCallbacks(hide);action.run();scheduleHide();});return b;}
    private ImageButton icon(int res,String description,Runnable action){ImageButton b=new ImageButton(getContext());b.setImageResource(res);b.setColorFilter(Color.WHITE);b.setContentDescription(description);b.setPadding(dp(10),dp(10),dp(10),dp(10));b.setBackgroundColor(Color.TRANSPARENT);b.setOnClickListener(v->action.run());return b;}
    private long duration(){return Math.max(0,player.getDuration());}
    private long bounded(long position){return Math.max(0,Math.min(Math.max(0,duration()-250),position));}
    static String time(long millis){long s=Math.max(0,millis)/1000;return s>=3600?String.format(Locale.ROOT,"%d:%02d:%02d",s/3600,s/60%60,s%60):String.format(Locale.ROOT,"%02d:%02d",s/60,s%60);}
    private void update(){if(!dragging){elapsed.setText(time(player.getCurrentPosition()));total.setText(time(duration()));seek.setProgress(duration()>0?(int)(player.getCurrentPosition()*1000/duration()):0);}seek.setEnabled(duration()>0);playButton.setImageResource(player.isPlaying()?R.drawable.ic_pause:R.drawable.ic_play);playButton.setContentDescription(player.isPlaying()?"暂停":"播放");if(!boosting)speedButton.setText(String.format(Locale.ROOT,"%s×",player.getPlaybackParameters().speed));}
    private void hint(String value){handler.removeCallbacks(clearFeedback);feedback.setText(value);feedback.setVisibility(VISIBLE);}
    private void clearHint(){feedback.setVisibility(GONE);}
    private void startBoost(){if(!closed&&axis==0){handler.removeCallbacks(singleTap);lastTap=0;boosting=true;normalSpeed=player.getPlaybackParameters().speed;player.setPlaybackSpeed(3f);showControls(false);clearHint();}}
    private void scheduleHide(){handler.removeCallbacks(hide);if(visible&&player.isPlaying()&&!dragging)handler.postDelayed(hide,3500);}
    void showControls(boolean show){visible=show;top.setVisibility(show?VISIBLE:GONE);bottom.setVisibility(show?VISIBLE:GONE);scheduleHide();}
    private boolean touch(MotionEvent e){
        if(closed)return false;
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                long now=android.os.SystemClock.uptimeMillis();secondTap=lastTap!=0&&now-lastTap<=ViewConfiguration.getDoubleTapTimeout()&&Math.hypot(e.getX()-tapX,e.getY()-tapY)<dp(48);
                if(secondTap)handler.removeCallbacks(singleTap);
                downX=e.getX();downY=e.getY();axis=0;startPosition=player.getCurrentPosition();targetPosition=startPosition;startVolume=audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                startBrightness=activity.getWindow().getAttributes().screenBrightness;if(startBrightness<0)startBrightness=Settings.System.getInt(activity.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS,128)/255f;
                handler.removeCallbacks(hide);handler.postDelayed(longPress,ViewConfiguration.getLongPressTimeout());return true;
            case MotionEvent.ACTION_POINTER_DOWN:cancelGesture();axis=4;return true;
            case MotionEvent.ACTION_MOVE:
                if(boosting||axis==4)return true;
                float dx=e.getX()-downX,dy=e.getY()-downY;
                if(axis==0&&Math.max(Math.abs(dx),Math.abs(dy))>slop){axis=Math.abs(dx)>Math.abs(dy)?1:downX<getWidth()/2f?2:3;handler.removeCallbacks(longPress);handler.removeCallbacks(singleTap);lastTap=0;secondTap=false;showControls(false);}
                if(axis==1){long span=Math.min(120000,Math.max(10000,duration()));targetPosition=bounded(startPosition+(long)(dx/Math.max(1,getWidth())*span));long delta=(targetPosition-startPosition)/1000;hint(time(targetPosition)+"  /  "+time(duration())+"\n"+(delta>=0?"+":"")+delta+" 秒");}
                else if(axis==2){float brightness=Math.max(.02f,Math.min(1,startBrightness-dy/Math.max(1,getHeight())*1.5f));WindowManager.LayoutParams lp=activity.getWindow().getAttributes();lp.screenBrightness=brightness;activity.getWindow().setAttributes(lp);hint("亮度  "+Math.round(brightness*100)+"%");}
                else if(axis==3){int max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);int volume=Math.max(0,Math.min(max,startVolume+Math.round(-dy/Math.max(1,getHeight())*max*1.5f)));audio.setStreamVolume(AudioManager.STREAM_MUSIC,volume,0);hint("音量  "+Math.round(volume*100f/Math.max(1,max))+"%");}return true;
            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(longPress);
                if(boosting)restoreSpeed();else if(axis==1&&duration()>0)player.seekTo(targetPosition);else if(axis==0){performClick();if(secondTap){lastTap=0;secondTap=false;if(player.getPlayWhenReady())player.pause();else{if(player.getPlaybackState()==Player.STATE_ENDED)player.seekTo(0);player.play();}showControls(true);}else{lastTap=android.os.SystemClock.uptimeMillis();tapX=e.getX();tapY=e.getY();handler.removeCallbacks(singleTap);handler.postDelayed(singleTap,ViewConfiguration.getDoubleTapTimeout());}}
                axis=0;handler.postDelayed(clearFeedback,700);scheduleHide();return true;
            case MotionEvent.ACTION_CANCEL:cancelGesture();return true;
        }return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
    private void restoreSpeed(){if(boosting){boosting=false;player.setPlaybackSpeed(normalSpeed);}}
    void cancelGesture(){handler.removeCallbacks(longPress);handler.removeCallbacks(singleTap);lastTap=0;secondTap=false;restoreSpeed();axis=0;feedback.setVisibility(GONE);scheduleHide();}
    void dispose(){if(closed)return;cancelGesture();closed=true;handler.removeCallbacksAndMessages(null);player.removeListener(listener);WindowManager.LayoutParams lp=activity.getWindow().getAttributes();lp.screenBrightness=originalBrightness;activity.getWindow().setAttributes(lp);}
}
