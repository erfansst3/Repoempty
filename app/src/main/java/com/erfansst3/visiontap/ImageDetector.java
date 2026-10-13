package com.erfansst3.visiontap;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.opencv.android.Utils;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.Core;
import org.opencv.core.DMatch;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDMatch;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.features2d.BFMatcher;
import org.opencv.features2d.ORB;
import org.opencv.imgproc.Imgproc;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

final class ImageDetector {
    static final class Detection {
        final float x,y;
        final int inliers;
        final double ratio;
        Detection(float x,float y,int inliers,double ratio){this.x=x;this.y=y;this.inliers=inliers;this.ratio=ratio;}
    }

    private final ORB orb=ORB.create(1800);
    private final BFMatcher matcher=BFMatcher.create(Core.NORM_HAMMING,false);
    private final Mat targetGray=new Mat();
    private final MatOfKeyPoint targetKeys=new MatOfKeyPoint();
    private final Mat targetDescriptors=new Mat();
    private boolean ready;

    ImageDetector(String path){
        Bitmap bitmap=BitmapFactory.decodeFile(new File(path).getAbsolutePath());
        if(bitmap==null)return;
        Mat rgba=new Mat();
        Mat mask=new Mat();
        try{
            Utils.bitmapToMat(bitmap,rgba);
            Imgproc.cvtColor(rgba,targetGray,Imgproc.COLOR_RGBA2GRAY);
            orb.detectAndCompute(targetGray,mask,targetKeys,targetDescriptors);
            ready=!targetDescriptors.empty()&&targetKeys.toArray().length>=7;
        }catch(Exception e){ready=false;}
        finally{rgba.release();mask.release();bitmap.recycle();}
    }

    boolean isReady(){return ready;}

    Detection detect(Bitmap bitmap){
        if(!ready||bitmap==null)return null;
        Mat rgba=new Mat(),gray=new Mat(),sceneDesc=new Mat(),mask=new Mat();
        MatOfKeyPoint sceneKeys=new MatOfKeyPoint();
        MatOfPoint2f src=new MatOfPoint2f(),dst=new MatOfPoint2f();
        Mat homography=new Mat();
        List<MatOfDMatch> pairs=new ArrayList<>();
        try{
            Utils.bitmapToMat(bitmap,rgba);
            Imgproc.cvtColor(rgba,gray,Imgproc.COLOR_RGBA2GRAY);
            orb.detectAndCompute(gray,mask,sceneKeys,sceneDesc);
            if(sceneDesc.empty())return null;
            matcher.knnMatch(targetDescriptors,sceneDesc,pairs,2);
            List<DMatch> good=new ArrayList<>();
            KeyPointHolder holder=new KeyPointHolder(targetKeys.toArray(),sceneKeys.toArray());
            List<Point> sourcePoints=new ArrayList<>(),scenePoints=new ArrayList<>();
            for(MatOfDMatch pair:pairs){
                DMatch[] a=pair.toArray();
                if(a.length>=2&&a[0].distance<0.74f*a[1].distance){
                    good.add(a[0]);
                    sourcePoints.add(holder.target[a[0].queryIdx].pt);
                    scenePoints.add(holder.scene[a[0].trainIdx].pt);
                }
            }
            if(good.size()<7)return null;
            src.fromList(sourcePoints);
            dst.fromList(scenePoints);
            homography=Calib3d.findHomography(src,dst,Calib3d.RANSAC,4.0,mask);
            if(homography.empty())return null;
            byte[] inlierMask=new byte[(int)mask.total()];
            mask.get(0,0,inlierMask);
            int inliers=0;
            double sx=0,sy=0;
            for(int i=0;i<inlierMask.length&&i<scenePoints.size();i++){
                if((inlierMask[i]&255)!=0){inliers++;sx+=scenePoints.get(i).x;sy+=scenePoints.get(i).y;}
            }
            double ratio=(double)inliers/good.size();
            if(inliers<7||ratio<0.50)return null;
            MatOfPoint2f corners=new MatOfPoint2f(new Point(0,0),new Point(targetGray.cols(),0),new Point(targetGray.cols(),targetGray.rows()),new Point(0,targetGray.rows()));
            MatOfPoint2f projected=new MatOfPoint2f();
            Core.perspectiveTransform(corners,projected,homography);
            Point[] cp=projected.toArray();
            corners.release();projected.release();
            if(cp.length!=4)return null;
            double minX=Double.POSITIVE_INFINITY,minY=Double.POSITIVE_INFINITY,maxX=Double.NEGATIVE_INFINITY,maxY=Double.NEGATIVE_INFINITY;
            for(Point p:cp){
                if(!Double.isFinite(p.x)||!Double.isFinite(p.y))return null;
                minX=Math.min(minX,p.x);minY=Math.min(minY,p.y);maxX=Math.max(maxX,p.x);maxY=Math.max(maxY,p.y);
            }
            if(maxX<0||maxY<0||minX>=gray.cols()||minY>=gray.rows()||maxX-minX<5||maxY-minY<5||maxX-minX>gray.cols()*12.0||maxY-minY>gray.rows()*12.0)return null;
            float x=(float)(sx/inliers),y=(float)(sy/inliers);
            if(x<0||y<0||x>=gray.cols()||y>=gray.rows())return null;
            return new Detection(x,y,inliers,ratio);
        }catch(Exception e){return null;}
        finally{
            for(MatOfDMatch p:pairs)p.release();
            rgba.release();gray.release();sceneDesc.release();mask.release();sceneKeys.release();src.release();dst.release();homography.release();
        }
    }

    void close(){orb.clear();targetGray.release();targetKeys.release();targetDescriptors.release();}

    private static final class KeyPointHolder{
        final org.opencv.core.KeyPoint[] target,scene;
        KeyPointHolder(org.opencv.core.KeyPoint[] target,org.opencv.core.KeyPoint[] scene){this.target=target;this.scene=scene;}
    }
}
