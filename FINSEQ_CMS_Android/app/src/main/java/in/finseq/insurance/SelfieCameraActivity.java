package in.finseq.insurance;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.concurrent.Executor;

/** FINSEQ internal live-selfie camera. CameraX only; no gallery/file picker. */
public class SelfieCameraActivity extends ComponentActivity {
    private static final int REQ_CAMERA_PERMISSION=9201;
    private PreviewView previewView;
    private ImageView reviewImage;
    private TextView status;
    private Button captureButton, retakeButton, useButton, cancelButton;
    private ProcessCameraProvider cameraProvider;
    private ImageCapture imageCapture;
    private File capturedFile;
    private Executor mainExecutor;
    private String lastError="";

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.BLACK);
        mainExecutor=ContextCompat.getMainExecutor(this);
        buildUi();
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
            status.setText("Camera permission required");
            requestPermissions(new String[]{Manifest.permission.CAMERA},REQ_CAMERA_PERMISSION);
        }else startCamera();
    }

    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request!=REQ_CAMERA_PERMISSION)return;
        if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)startCamera();
        else finishWithError("Camera permission denied");
    }

    private void buildUi(){
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);
        previewView=new PreviewView(this);previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);root.addView(previewView,new FrameLayout.LayoutParams(-1,-1));
        reviewImage=new ImageView(this);reviewImage.setScaleType(ImageView.ScaleType.FIT_CENTER);reviewImage.setBackgroundColor(Color.BLACK);reviewImage.setVisibility(View.GONE);root.addView(reviewImage,new FrameLayout.LayoutParams(-1,-1));

        LinearLayout bottom=new LinearLayout(this);bottom.setOrientation(LinearLayout.VERTICAL);bottom.setGravity(Gravity.CENTER);bottom.setPadding(24,20,24,28);bottom.setBackgroundColor(0xAA000000);
        status=new TextView(this);status.setText("Opening front camera…");status.setTextColor(Color.WHITE);status.setTextSize(15);status.setGravity(Gravity.CENTER);bottom.addView(status,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER);row.setPadding(0,16,0,0);
        cancelButton=new Button(this);cancelButton.setText("Cancel");cancelButton.setOnClickListener(v->{cleanupCaptured();Intent i=new Intent();if(!lastError.isEmpty())i.putExtra("camera_error",lastError);setResult(RESULT_CANCELED,i);finish();});row.addView(cancelButton,new LinearLayout.LayoutParams(0,-2,1));
        captureButton=new Button(this);captureButton.setText("CAPTURE SELFIE");captureButton.setEnabled(false);captureButton.setOnClickListener(v->capturePhoto());row.addView(captureButton,new LinearLayout.LayoutParams(0,-2,2));
        bottom.addView(row,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout reviewRow=new LinearLayout(this);reviewRow.setGravity(Gravity.CENTER);reviewRow.setPadding(0,12,0,0);
        retakeButton=new Button(this);retakeButton.setText("Retake");retakeButton.setVisibility(View.GONE);retakeButton.setOnClickListener(v->retake());reviewRow.addView(retakeButton,new LinearLayout.LayoutParams(0,-2,1));
        useButton=new Button(this);useButton.setText("Use Photo");useButton.setVisibility(View.GONE);useButton.setOnClickListener(v->usePhoto());reviewRow.addView(useButton,new LinearLayout.LayoutParams(0,-2,1));
        bottom.addView(reviewRow,new LinearLayout.LayoutParams(-1,-2));
        root.addView(bottom,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        setContentView(root);
    }

    private void startCamera(){
        lastError="";status.setText("Opening front camera…");captureButton.setEnabled(false);
        final ListenableFuture<ProcessCameraProvider> future=ProcessCameraProvider.getInstance(this);
        future.addListener(()->{
            try{
                cameraProvider=future.get();
                Preview preview=new Preview.Builder().build();preview.setSurfaceProvider(previewView.getSurfaceProvider());
                ResolutionSelector resolutionSelector=new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(new ResolutionStrategy(new Size(1280,960),ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .build();
                imageCapture=new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setJpegQuality(82)
                        .setResolutionSelector(resolutionSelector)
                        .build();
                CameraSelector selector;
                if(cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA))selector=CameraSelector.DEFAULT_FRONT_CAMERA;
                else if(cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA))selector=CameraSelector.DEFAULT_BACK_CAMERA;
                else throw new IllegalStateException("No usable camera found on this device");

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this,selector,preview,imageCapture);
                status.setText(selector==CameraSelector.DEFAULT_FRONT_CAMERA?"Front camera ready":"Front camera unavailable · using available camera");
                captureButton.setEnabled(true);
            }catch(Exception e){showCameraError("Camera start failed: "+safe(e.getMessage()));}
        },mainExecutor);
    }

    private void capturePhoto(){
        if(imageCapture==null){showCameraError("Camera is not ready");return;}
        try{
            File dir=new File(getCacheDir(),"attendance_camera");if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("Could not create selfie cache");
            capturedFile=new File(dir,"selfie_"+System.currentTimeMillis()+".jpg");
            ImageCapture.OutputFileOptions options=new ImageCapture.OutputFileOptions.Builder(capturedFile).build();
            captureButton.setEnabled(false);status.setText("Capturing fresh selfie…");
            imageCapture.takePicture(options,mainExecutor,new ImageCapture.OnImageSavedCallback(){
                @Override public void onImageSaved(ImageCapture.OutputFileResults output){
                    if(capturedFile==null||!capturedFile.isFile()||capturedFile.length()<100){showCameraError("Captured selfie file is empty");return;}
                    if(capturedFile.length()>2*1024*1024){showCameraError("Captured selfie is too large. Please retake.");cleanupCaptured();return;}
                    showReview();
                }
                @Override public void onError(ImageCaptureException exc){cleanupCaptured();showCameraError("Camera capture failed: "+safe(exc.getMessage()));}
            });
        }catch(Exception e){cleanupCaptured();showCameraError("Camera capture failed: "+safe(e.getMessage()));}
    }

    private void showReview(){
        lastError="";try{if(cameraProvider!=null)cameraProvider.unbindAll();}catch(Exception ignored){}
        previewView.setVisibility(View.GONE);reviewImage.setVisibility(View.VISIBLE);reviewImage.setImageURI(Uri.fromFile(capturedFile));
        captureButton.setVisibility(View.GONE);retakeButton.setVisibility(View.VISIBLE);useButton.setVisibility(View.VISIBLE);status.setText("Review fresh selfie");
    }

    private void retake(){
        cleanupCaptured();reviewImage.setImageDrawable(null);reviewImage.setVisibility(View.GONE);previewView.setVisibility(View.VISIBLE);
        retakeButton.setVisibility(View.GONE);useButton.setVisibility(View.GONE);captureButton.setVisibility(View.VISIBLE);startCamera();
    }

    private void usePhoto(){
        if(capturedFile==null||!capturedFile.isFile()||capturedFile.length()<100){showCameraError("Selfie file missing. Please retake.");return;}
        Intent result=new Intent().putExtra("selfie_path",capturedFile.getAbsolutePath()).putExtra("selfie_bytes",capturedFile.length());setResult(RESULT_OK,result);finish();
    }

    private void showCameraError(String msg){lastError=safe(msg);status.setText(lastError);captureButton.setEnabled(false);}
    private void finishWithError(String msg){lastError=safe(msg);setResult(RESULT_CANCELED,new Intent().putExtra("camera_error",lastError));finish();}
    private void cleanupCaptured(){try{if(capturedFile!=null&&capturedFile.exists())capturedFile.delete();}catch(Exception ignored){}capturedFile=null;}
    private static String safe(String s){return s==null||s.trim().isEmpty()?"Unknown camera error":s.trim();}

    @Override protected void onDestroy(){try{if(cameraProvider!=null)cameraProvider.unbindAll();}catch(Exception ignored){}super.onDestroy();}
}
