package com.erfansst3.visiontap;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;
import org.opencv.android.OpenCVLoader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_IMAGE=10,REQ_CAPTURE=11,REQ_NOTIFICATIONS=12;
    private TextView tvTarget,tvStatus;
    private CheckBox checkAutoClick;
    private File targetFile;
    private boolean receiverRegistered;
    private final BroadcastReceiver statusReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){
            String message=i.getStringExtra("message");
            if(message!=null)tvStatus.setText(message);
        }
    };

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        tvTarget=findViewById(R.id.tvTarget);
        tvStatus=findViewById(R.id.tvStatus);
        checkAutoClick=findViewById(R.id.checkAutoClick);
        targetFile=new File(getFilesDir(),"target.png");
        if(targetFile.isFile())tvTarget.setText("تصویر مرجع آماده است: target.png");
        findViewById(R.id.btnChoose).setOnClickListener(v->chooseImage());
        findViewById(R.id.btnAccessibility).setOnClickListener(v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.btnStart).setOnClickListener(v->startCapture());
        findViewById(R.id.btnStop).setOnClickListener(v->{stopService(new Intent(this,ScreenCaptureService.class));tvStatus.setText("درخواست توقف ارسال شد.");});
        if(!OpenCVLoader.initLocal())tvStatus.setText("بارگذاری OpenCV ناموفق بود.");
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},REQ_NOTIFICATIONS);
    }

    @Override protected void onStart(){
        super.onStart();
        IntentFilter f=new IntentFilter(ScreenCaptureService.ACTION_STATUS);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(statusReceiver,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(statusReceiver,f);
        receiverRegistered=true;
    }

    @Override protected void onStop(){
        if(receiverRegistered){unregisterReceiver(statusReceiver);receiverRegistered=false;}
        super.onStop();
    }

    private void chooseImage(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(i,REQ_IMAGE);
    }

    private void startCapture(){
        if(!targetFile.isFile()){toast("ابتدا تصویر مرجع را انتخاب کن.");return;}
        if(!OpenCVLoader.initLocal()){toast("OpenCV بارگذاری نشده است.");return;}
        boolean auto=checkAutoClick.isChecked();
        if(auto&&!isAccessibilityEnabled()){
            tvStatus.setText("ابتدا سرویس کلیک VisionTap را در تنظیمات دسترس‌پذیری فعال کن.");
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        MediaProjectionManager m=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(m.createScreenCaptureIntent(),REQ_CAPTURE);
    }

    private boolean isAccessibilityEnabled(){
        String enabled=Settings.Secure.getString(getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String ours=new ComponentName(this,ClickAccessibilityService.class).flattenToString();
        return !TextUtils.isEmpty(enabled)&&enabled.toLowerCase(Locale.ROOT).contains(ours.toLowerCase(Locale.ROOT));
    }

    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK||data==null)return;
        if(request==REQ_IMAGE){
            try{saveTarget(data.getData());tvTarget.setText("تصویر مرجع آماده است: target.png");tvStatus.setText("تصویر ذخیره شد؛ می‌توانی تشخیص را شروع کنی.");}
            catch(Exception e){toast("خواندن تصویر ناموفق بود: "+e.getMessage());}
        }else if(request==REQ_CAPTURE){
            Intent s=new Intent(this,ScreenCaptureService.class);
            s.setAction(ScreenCaptureService.ACTION_START);
            s.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE,result);
            s.putExtra(ScreenCaptureService.EXTRA_RESULT_DATA,data);
            s.putExtra(ScreenCaptureService.EXTRA_TARGET_PATH,targetFile.getAbsolutePath());
            s.putExtra(ScreenCaptureService.EXTRA_AUTO_CLICK,checkAutoClick.isChecked());
            if(Build.VERSION.SDK_INT>=26)startForegroundService(s);else startService(s);
            tvStatus.setText("در حال آماده‌سازی تشخیص تصویر...");
        }
    }

    private void saveTarget(Uri uri)throws Exception{
        BitmapFactory.Options bounds=new BitmapFactory.Options();
        bounds.inJustDecodeBounds=true;
        try(InputStream in=getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,bounds);}
        if(bounds.outWidth<=0||bounds.outHeight<=0)throw new IllegalArgumentException("فایل تصویر معتبر نیست");
        int sample=1;
        while(bounds.outWidth/sample>1200||bounds.outHeight/sample>1200)sample*=2;
        BitmapFactory.Options opts=new BitmapFactory.Options();
        opts.inSampleSize=sample;
        Bitmap bitmap;
        try(InputStream in=getContentResolver().openInputStream(uri)){bitmap=BitmapFactory.decodeStream(in,null,opts);}
        if(bitmap==null)throw new IllegalArgumentException("تصویر قابل‌خواندن نیست");
        try(FileOutputStream out=new FileOutputStream(targetFile)){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}
        bitmap.recycle();
    }

    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
}
