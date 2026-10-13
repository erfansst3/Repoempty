package com.erfansst3.visiontap;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.opencv.android.Utils;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.Core;
import org.opencv.core.DMatch;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDMatch;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.features2d.BFMatcher;
import org.opencv.features2d.ORB;
import org.opencv.core.KeyPoint;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Scale-pyramid ORB matcher. Keeping several versions of the reference image
 * allows matching when the same image is displayed much smaller or larger
 * than the selected source. ORB descriptors handle rotation, and RANSAC
 * homography rejects most unrelated feature matches.
 */
final class ImageDetector {
    private static final double[] TEMPLATE_SCALES = {
        1.0, 0.72, 0.52, 0.38, 0.27, 0.19, 0.135, 0.095, 0.067, 0.047, 0.033
    };

    static final class Detection {
        final float x, y;
        final int inliers;
        final double ratio;

        Detection(float x, float y, int inliers, double ratio) {
            this.x = x;
            this.y = y;
            this.inliers = inliers;
            this.ratio = ratio;
        }
    }

    private static final class TemplateLevel {
        final double scaleX, scaleY, scale;
        final KeyPoint[] points;
        final Mat descriptors;

        TemplateLevel(double scaleX, double scaleY, KeyPoint[] points, Mat descriptors) {
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.scale = Math.sqrt(scaleX * scaleY);
            this.points = points;
            this.descriptors = descriptors;
        }

        void close() {
            descriptors.release();
        }
    }

    // More pyramid levels and a lower FAST threshold help with size changes
    // and with small/low-contrast details near the edge of a reference image.
    private final ORB templateOrb = ORB.create(
        1400, 1.15f, 12, 8, 0, 2, ORB.HARRIS_SCORE, 21, 5
    );
    private final ORB sceneOrb = ORB.create(
        5200, 1.15f, 12, 8, 0, 2, ORB.HARRIS_SCORE, 21, 5
    );
    private final BFMatcher matcher = BFMatcher.create(Core.NORM_HAMMING, false);
    private final Mat targetGray = new Mat();
    private final List<TemplateLevel> templates = new ArrayList<>();
    private boolean ready;

    ImageDetector(String path) {
        Bitmap bitmap = BitmapFactory.decodeFile(new File(path).getAbsolutePath());
        if (bitmap == null) return;

        Mat rgba = new Mat();
        Mat mask = new Mat();
        try {
            Utils.bitmapToMat(bitmap, rgba);
            Imgproc.cvtColor(rgba, targetGray, Imgproc.COLOR_RGBA2GRAY);

            for (double requestedScale : TEMPLATE_SCALES) {
                int w = Math.max(1, (int) Math.round(targetGray.cols() * requestedScale));
                int h = Math.max(1, (int) Math.round(targetGray.rows() * requestedScale));

                // ORB cannot describe an image reliably once either dimension
                // is too small. Continue to the next (smaller) scale in that case.
                if (Math.min(w, h) < 20 || Math.max(w, h) < 30) continue;
                if (!templates.isEmpty()) {
                    TemplateLevel previous = templates.get(templates.size() - 1);
                    if (w == Math.round(targetGray.cols() * previous.scaleX)
                        && h == Math.round(targetGray.rows() * previous.scaleY)) {
                        continue;
                    }
                }

                Mat scaled = new Mat();
                MatOfKeyPoint keypoints = new MatOfKeyPoint();
                Mat descriptors = new Mat();
                try {
                    if (w == targetGray.cols() && h == targetGray.rows()) {
                        targetGray.copyTo(scaled);
                    } else {
                        Imgproc.resize(targetGray, scaled, new Size(w, h), 0, 0, Imgproc.INTER_AREA);
                    }

                    templateOrb.detectAndCompute(scaled, mask, keypoints, descriptors);
                    KeyPoint[] points = keypoints.toArray();
                    if (!descriptors.empty() && points.length >= 5 && descriptors.rows() >= 5) {
                        templates.add(new TemplateLevel(
                            (double) w / targetGray.cols(),
                            (double) h / targetGray.rows(),
                            points,
                            descriptors
                        ));
                        descriptors = new Mat(); // ownership transferred to TemplateLevel
                    }
                } finally {
                    scaled.release();
                    keypoints.release();
                    descriptors.release();
                }
            }

            ready = !templates.isEmpty();
        } catch (Exception e) {
            ready = false;
            clearTemplates();
        } finally {
            rgba.release();
            mask.release();
            bitmap.recycle();
        }
    }

    boolean isReady() {
        return ready;
    }

    Detection detect(Bitmap bitmap) {
        if (!ready || bitmap == null) return null;

        Mat rgba = new Mat();
        Mat gray = new Mat();
        Mat sceneDescriptors = new Mat();
        Mat mask = new Mat();
        MatOfKeyPoint sceneKeypoints = new MatOfKeyPoint();
        try {
            Utils.bitmapToMat(bitmap, rgba);
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
            sceneOrb.detectAndCompute(gray, mask, sceneKeypoints, sceneDescriptors);
            if (sceneDescriptors.empty()) return null;

            KeyPoint[] scenePoints = sceneKeypoints.toArray();
            Detection best = null;

            // Large versions run first; the common case can exit early. Tiny
            // versions are still tried when the reference is shown as a small icon.
            for (TemplateLevel template : templates) {
                Detection candidate = matchTemplate(template, sceneDescriptors, scenePoints, gray.cols(), gray.rows());
                if (candidate == null) continue;

                if (best == null
                    || candidate.inliers > best.inliers
                    || (candidate.inliers == best.inliers && candidate.ratio > best.ratio)) {
                    best = candidate;
                }

                if (candidate.inliers >= 12 && candidate.ratio >= 0.55) {
                    return candidate;
                }
            }
            return best;
        } catch (Exception e) {
            return null;
        } finally {
            rgba.release();
            gray.release();
            sceneDescriptors.release();
            mask.release();
            sceneKeypoints.release();
        }
    }

    private Detection matchTemplate(
        TemplateLevel template,
        Mat sceneDescriptors,
        KeyPoint[] scenePoints,
        int sceneWidth,
        int sceneHeight
    ) {
        List<MatOfDMatch> pairs = new ArrayList<>();
        List<Point> sourcePoints = new ArrayList<>();
        List<Point> matchedScenePoints = new ArrayList<>();
        MatOfPoint2f source = new MatOfPoint2f();
        MatOfPoint2f destination = new MatOfPoint2f();
        Mat inlierMask = new Mat();
        Mat homography = null;

        try {
            if (template.descriptors.empty() || template.descriptors.rows() < 4) return null;
            matcher.knnMatch(template.descriptors, sceneDescriptors, pairs, 2);

            float ratioThreshold = template.scale < 0.15 ? 0.80f : 0.76f;
            for (MatOfDMatch pair : pairs) {
                DMatch[] matches = pair.toArray();
                if (matches.length < 2) continue;

                DMatch first = matches[0];
                DMatch second = matches[1];
                if (first.distance >= ratioThreshold * second.distance
                    || first.queryIdx < 0 || first.queryIdx >= template.points.length
                    || first.trainIdx < 0 || first.trainIdx >= scenePoints.length) {
                    continue;
                }

                Point p = template.points[first.queryIdx].pt;
                // Express every scaled-template feature in the original
                // reference-image coordinate system before estimating the homography.
                sourcePoints.add(new Point(p.x / template.scaleX, p.y / template.scaleY));
                matchedScenePoints.add(scenePoints[first.trainIdx].pt);
            }

            int minInliers = template.scale < 0.08 ? 4 : (template.scale < 0.15 ? 5 : 6);
            if (sourcePoints.size() < minInliers) return null;

            source.fromList(sourcePoints);
            destination.fromList(matchedScenePoints);
            homography = Calib3d.findHomography(source, destination, Calib3d.RANSAC, 5.0, inlierMask);
            if (homography == null || homography.empty() || inlierMask.empty()) return null;

            byte[] maskBytes = new byte[(int) inlierMask.total()];
            inlierMask.get(0, 0, maskBytes);
            int inliers = 0;
            double sumX = 0, sumY = 0;
            int count = Math.min(maskBytes.length, matchedScenePoints.size());
            for (int i = 0; i < count; i++) {
                if ((maskBytes[i] & 255) != 0) {
                    inliers++;
                    Point p = matchedScenePoints.get(i);
                    sumX += p.x;
                    sumY += p.y;
                }
            }

            double inlierRatio = (double) inliers / sourcePoints.size();
            double minRatio = template.scale < 0.08 ? 0.70 : (template.scale < 0.15 ? 0.60 : 0.50);
            if (inliers < minInliers || inlierRatio < minRatio) return null;

            MatOfPoint2f corners = new MatOfPoint2f(
                new Point(0, 0),
                new Point(targetGray.cols() - 1, 0),
                new Point(targetGray.cols() - 1, targetGray.rows() - 1),
                new Point(0, targetGray.rows() - 1)
            );
            MatOfPoint2f projected = new MatOfPoint2f();
            Point[] cornersOnScreen;
            try {
                Core.perspectiveTransform(corners, projected, homography);
                cornersOnScreen = projected.toArray();
            } finally {
                corners.release();
                projected.release();
            }

            if (cornersOnScreen.length != 4 || !isValidQuad(cornersOnScreen)) return null;

            double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
            for (Point p : cornersOnScreen) {
                if (!Double.isFinite(p.x) || !Double.isFinite(p.y)) return null;
                minX = Math.min(minX, p.x);
                minY = Math.min(minY, p.y);
                maxX = Math.max(maxX, p.x);
                maxY = Math.max(maxY, p.y);
            }

            if (maxX < 0 || maxY < 0 || minX >= sceneWidth || minY >= sceneHeight
                || maxX - minX < 5 || maxY - minY < 5
                || maxX - minX > sceneWidth * 12.0
                || maxY - minY > sceneHeight * 12.0) {
                return null;
            }

            float x = (float) (sumX / inliers);
            float y = (float) (sumY / inliers);
            if (!Float.isFinite(x) || !Float.isFinite(y)
                || x < 0 || y < 0 || x >= sceneWidth || y >= sceneHeight) {
                return null;
            }
            return new Detection(x, y, inliers, inlierRatio);
        } catch (Exception e) {
            return null;
        } finally {
            for (MatOfDMatch pair : pairs) pair.release();
            source.release();
            destination.release();
            inlierMask.release();
            if (homography != null) homography.release();
        }
    }

    private static boolean isValidQuad(Point[] points) {
        if (points == null || points.length != 4) return false;
        double signedAreaTwice = 0;
        double crossSign = 0;
        for (int i = 0; i < 4; i++) {
            Point a = points[i];
            Point b = points[(i + 1) % 4];
            Point c = points[(i + 2) % 4];

            double dx = b.x - a.x;
            double dy = b.y - a.y;
            if (dx * dx + dy * dy < 9.0) return false;

            signedAreaTwice += a.x * b.y - b.x * a.y;
            double cross = dx * (c.y - b.y) - dy * (c.x - b.x);
            if (Math.abs(cross) < 1e-3) return false;
            if (crossSign == 0) crossSign = Math.signum(cross);
            else if (Math.signum(cross) != crossSign) return false;
        }
        return Math.abs(signedAreaTwice) * 0.5 >= 16.0;
    }

    private void clearTemplates() {
        for (TemplateLevel template : templates) template.close();
        templates.clear();
    }

    void close() {
        clearTemplates();
        templateOrb.clear();
        sceneOrb.clear();
        targetGray.release();
    }
}
