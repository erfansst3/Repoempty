package com.erfansst3.visiontap;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

public class ClickAccessibilityService extends AccessibilityService {
    private static volatile ClickAccessibilityService instance;
    private final Handler mainHandler=new Handler(Looper.getMainLooper());

    @Override protected void onServiceConnected(){super.onServiceConnected();instance=this;}
    @Override public void onAccessibilityEvent(AccessibilityEvent event){}
    @Override public void onInterrupt(){instance=null;}
    @Override public boolean onUnbind(android.content.Intent intent){instance=null;return super.onUnbind(intent);}
    @Override public void onDestroy(){if(instance==this)instance=null;super.onDestroy();}
    public static boolean isConnected(){return instance!=null&&Build.VERSION.SDK_INT>=24;}
    public static boolean tap(float x,float y){
        ClickAccessibilityService s=instance;
        if(s==null||Build.VERSION.SDK_INT<24)return false;
        s.mainHandler.post(()->{
            Path p=new Path();
            p.moveTo(x,y);
            GestureDescription.StrokeDescription stroke=new GestureDescription.StrokeDescription(p,0,70);
            s.dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(),null,s.mainHandler);
        });
        return true;
    }
}
