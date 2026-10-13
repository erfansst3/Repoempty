package com.erfansst3.visiontap;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import android.graphics.PixelFormat;
import android.view.WindowManager;
import java.io.File;
import java.nio.ByteBuffer;

public class ScreenCaptureService extends Service {
    public static final String ACTION_START="com.erfansst3.visiontap.START";
    public static final String ACTION_STATUS="com.erfansst3.visiontap.STATUS";
    public static final String EXTRA_RESULT_CODE="resultCode";
    public static final String EXTRA_RESULT_DATA="resultData";
    public static final String EXTRA_TARGET_PATH="targetPath";
    public static final String EXTRA_AUTO_CLICK="autoClick";
    private static final String CHANNEL="visiontap_capture";
    private static final int NOTIFICATION_ID=74;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread thread;
    private Handler handler;
    private ImageDetector detector;
    private boolean autoClick,locked;
    private int hits,misses,width,height,density;
    private long lastFrame;

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null||!ACTION_START.equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        try{
            autoClick=intent.getBooleanExtra(EXTRA_AUTO_CLICK,true);
            startForegroundNow("در حال آماده‌سازی...");
            Intent data;
            if(Build.VERSION.SDK_INT>=33)data=intent.getParcelableExtra(EXTRA_RESULT_DATA,Intent.class);
            else data=(Intent)intent.getParcelableExtra(EXTRA_RESULT_DATA);
            int result=intent.getIntExtra(EXTRA_RESULT_CODE,0);
            String path=intent.getStringExtra(EXTRA_TARGET_PATH);
            if(data==null||path==null||!new File(path).isFile())throw new IllegalStateException("مجوز ضبط صفحه یا تصویر مرجع موجود نیست");
            detector=new ImageDetector(path);
            if(!detector.isReady())throw new IllegalStateException("در تصویر مرجع جزئیات کافی برای تطبیق پیدا نشد");
            MediaProjectionManager manager=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
            projection=manager.getMediaProjection(result,data);
            if(projection==null)throw new IllegalStateException("شروع ضبط صفحه ناموفق بود");
            thread=new HandlerThread("VisionTapCapture");
            thread.start();
            handler=new Handler(thread.getLooper());
            projection.registerCallback(new MediaProjection.Callback(){
                @Override public void onStop(){stopSelf();}
            },handler);
            Point size=new Point();
            WindowManager wm=(WindowManager)getSystemService(WINDOW_SERVICE);
            Display display=wm.getDefaultDisplay();
            display.getRealSize(size);
            width=size.x;height=size.y;
            DisplayMetrics metrics=getResources().getDisplayMetrics();
            density=metrics.densityDpi;
            if(width<1||height<1)throw new IllegalStateException("ابعاد صفحه قابل‌خواندن نیست");
            imageReader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2);
            imageReader.setOnImageAvailableListener(this::onImageAvailable,handler);
            virtualDisplay=projection.createVirtualDisplay("VisionTap",width,height,density,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,imageReader.getSurface(),null,handler);
            if(virtualDisplay==null)throw new IllegalStateException("ساخت جریان تصویر صفحه ناموفق بود");
            publish("در حال جست‌وجوی تصویر روی صفحه...");
        }catch(Exception e){publish("خطا: "+e.getMessage());stopSelf();}
        return START_NOT_STICKY;
    }

    private void onImageAvailable(ImageReader source){
        Image image=null;
        try{
            image=source.acquireLatestImage();
            if(image==null)return;
            long now=SystemClock.elapsedRealtime();
            if(now-lastFrame<350)return;
            lastFrame=now;
            Bitmap bitmap=imageToBitmap(image);
            image.close();image=null;
            if(bitmap==null)return;
            ImageDetector.Detection d=detector.detect(bitmap);
            bitmap.recycle();
            if(d!=null){
                misses=0;hits++;
                if(hits>=2&&!locked){
                    locked=true;
                    if(autoClick){
                        if(ClickAccessibilityService.tap(d.x,d.y))publish("تصویر پیدا شد؛ کلیک روی ("+(int)d.x+", "+(int)d.y+") انجام شد. تطبیق: "+d.inliers+" نقطه.");
                        else publish("تصویر پیدا شد، اما سرویس کلیک متصل نیست؛ دسترس‌پذیری را فعال کن.");
                    }else publish("تصویر پیدا شد در ("+(int)d.x+", "+(int)d.y+"). تطبیق: "+d.inliers+" نقطه.");
                }
            }else{
                hits=0;misses++;
                if(misses>=3&&locked){locked=false;publish("تصویر دیگر دیده نمی‌شود؛ جست‌وجو ادامه دارد.");}
            }
        }catch(Exception e){publish("خطا در پردازش فریم: "+e.getMessage());}
        finally{if(image!=null)image.close();}
    }

    private Bitmap imageToBitmap(Image image){
        Image.Plane[] planes=image.getPlanes();
        if(planes.length==0)return null;
        Image.Plane plane=planes[0];
        int pixelStride=plane.getPixelStride(),rowStride=plane.getRowStride();
        if(pixelStride<=0)return null;
        int paddedWidth=rowStride/pixelStride;
        ByteBuffer buffer=plane.getBuffer();
        Bitmap padded=Bitmap.createBitmap(paddedWidth,image.getHeight(),Bitmap.Config.ARGB_8888);
        buffer.rewind();
        padded.copyPixelsFromBuffer(buffer);
        if(paddedWidth==image.getWidth())return padded;
        Bitmap cropped=Bitmap.createBitmap(padded,0,0,image.getWidth(),image.getHeight());
        padded.recycle();
        return cropped;
    }

    private void startForegroundNow(String text){
        createChannel();
        Notification notification=buildNotification(text);
        if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION_ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(NOTIFICATION_ID,notification);
    }

    private Notification buildNotification(String text){
        Intent open=new Intent(this,MainActivity.class);
        int flags=PendingIntent.FLAG_UPDATE_CURRENT;
        if(Build.VERSION.SDK_INT>=23)flags|=PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pending=PendingIntent.getActivity(this,0,open,flags);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_menu_search).setContentTitle("VisionTap").setContentText(text).setContentIntent(pending).setOngoing(true).setOnlyAlertOnce(true).build();
    }

    private void createChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel channel=new NotificationChannel(CHANNEL,"VisionTap screen detection",NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Screen capture and image matching status");
            ((NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    private void publish(String message){
        Intent i=new Intent(ACTION_STATUS);
        i.setPackage(getPackageName());
        i.putExtra("message",message);
        sendBroadcast(i);
        try{((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID,buildNotification(message));}catch(Exception ignored){}
    }

    @Override public void onDestroy(){
        if(imageReader!=null){imageReader.setOnImageAvailableListener(null,null);try{imageReader.close();}catch(Exception ignored){}imageReader=null;}
        if(virtualDisplay!=null){virtualDisplay.release();virtualDisplay=null;}
        MediaProjection p=projection;projection=null;
        if(p!=null){try{p.stop();}catch(Exception ignored){}}
        if(detector!=null){detector.close();detector=null;}
        if(thread!=null){thread.quitSafely();thread=null;handler=null;}
        try{((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID);}catch(Exception ignored){}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent){return null;}
}
