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
        Mat rgba=new Mat(),featureMask=new Mat();
        try{
            Utils.bitmapToMat(bitmap,rgba);
            Imgproc.cvtColor(rgba,targetGray,Imgproc.COLOR_RGBA2GRAY);
            orb.detectAndCompute(targetGray,featureMask,targetKeys,targetDescriptors);
            ready=!targetDescriptors.empty()&&targetKeys.toArray().length>=7;
        }catch(Exception e){ready=false;}
        finally{rgba.release();featureMask.release();bitmap.recycle();}
    }

    boolean isReady(){return ready;}

    Detection detect(Bitmap bitmap){
        if(!ready||bitmap==null)return null;
        Mat rgba=new Mat(),gray=new Mat(),sceneDesc=new Mat(),featureMask=new Mat(),inlierMask=new Mat();
        MatOfKeyPoint sceneKeys=new MatOfKeyPoint();
        MatOfPoint2f src=new MatOfPoint2f(),dst=new MatOfPoint2f();
        Mat homography=null;
        List<MatOfDMatch> pairs=new ArrayList<>();
        try{
            Utils.bitmapToMat(bitmap,rgba);
            Imgproc.cvtColor(rgba,gray,Imgproc.COLOR_RGBA2GRAY);
            orb.detectAndCompute(gray,featureMask,sceneKeys,sceneDesc);
            if(sceneDesc.empty())return null;
            matcher.knnMatch(targetDescriptors,sceneDesc,pairs,2);
            org.opencv.core.KeyPoint[] target=targetKeys.toArray(),scene=sceneKeys.toArray();
            List<Point> sourcePoints=new ArrayList<>(),scenePoints=new ArrayList<>();
            for(MatOfDMatch pair:pairs){
                DMatch[] matches=pair.toArray();
                if(matches.length<2)continue;
                DMatch best=matches[0],second=matches[1];
                if(best.distance<0.74f*second.distance&&best.queryIdx<target.length&&best.trainIdx<scene.length){
                    sourcePoints.add(target[best.queryIdx].pt);
                    scenePoints.add(scene[best.trainIdx].pt);
                }
            }
            if(sourcePoints.size()<7)return null;
            src.fromList(sourcePoints);
            dst.fromList(scenePoints);
            homography=Calib3d.findHomography(src,dst,Calib3d.RANSAC,4.0,inlierMask);
            if(homography==null||homography.empty()||inlierMask.empty())return null;
            byte[] inliersMask=new byte[(int)inlierMask.total()];
            inlierMask.get(0,0,inliersMask);
            int inliers=0;
            double sx=0,sy=0;
            int count=Math.min(inliersMask.length,scenePoints.size());
            for(int i=0;i<count;i++){
                if((inliersMask[i]&255)!=0){
                    inliers++;
                    sx+=scenePoints.get(i).x;
                    sy+=scenePoints.get(i).y;
                }
            }
            double ratio=(double)inliers/sourcePoints.size();
            if(inliers<7||ratio<0.50)return null;

            MatOfPoint2f corners=new MatOfPoint2f(
                new Point(0,0),new Point(targetGray.cols()-1,0),
                new Point(targetGray.cols()-1,targetGray.rows()-1),new Point(0,targetGray.rows()-1));
            MatOfPoint2f projected=new MatOfPoint2f();
            Point[] cp;
            try{
                Core.perspectiveTransform(corners,projected,homography);
                cp=projected.toArray();
            }finally{corners.release();projected.release();}
            if(cp.length!=4)return null;
            double minX=Double.POSITIVE_INFINITY,minY=Double.POSITIVE_INFINITY;
            double maxX=Double.NEGATIVE_INFINITY,maxY=Double.NEGATIVE_INFINITY;
            for(Point p:cp){
                if(!Double.isFinite(p.x)||!Double.isFinite(p.y))return null;
                minX=Math.min(minX,p.x);minY=Math.min(minY,p.y);
                maxX=Math.max(maxX,p.x);maxY=Math.max(maxY,p.y);
            }
            if(maxX<0||maxY<0||minX>=gray.cols()||minY>=gray.rows()
                ||maxX-minX<5||maxY-minY<5
                ||maxX-minX>gray.cols()*12.0||maxY-minY>gray.rows()*12.0)return null;
            float x=(float)(sx/inliers),y=(float)(sy/inliers);
            if(!Float.isFinite(x)||!Float.isFinite(y)||x<0||y<0||x>=gray.cols()||y>=gray.rows())return null;
            return new Detection(x,y,inliers,ratio);
        }catch(Exception e){return null;}
        finally{
            for(MatOfDMatch p:pairs)p.release();
            rgba.release();gray.release();sceneDesc.release();featureMask.release();inlierMask.release();
            sceneKeys.release();src.release();dst.release();
            if(homography!=null)homography.release();
        }
    }

    void close(){orb.clear();targetGray.release();targetKeys.release();targetDescriptors.release();}
}
