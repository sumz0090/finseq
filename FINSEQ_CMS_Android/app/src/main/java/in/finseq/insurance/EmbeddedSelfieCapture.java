package in.finseq.insurance;

import android.app.Dialog;
import android.graphics.Color;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

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

/** Embedded attendance selfie camera. No gallery. CameraX first, system-camera fallback on failure. */
public final class EmbeddedSelfieCapture {
    public interface Callback { void onUse(File file); void onCancel(String reason); void onFallback(String reason); }
    private EmbeddedSelfieCapture(){}

    public static void open(FinseqActivity activity, Callback callback){
        final Dialog dialog=new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final FrameLayout root=new FrameLayout(activity);root.setBackgroundColor(Color.BLACK);
        final PreviewView preview=new PreviewView(activity);preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);root.addView(preview,new FrameLayout.LayoutParams(-1,-1));
        final ImageView review=new ImageView(activity);review.setScaleType(ImageView.ScaleType.FIT_CENTER);review.setBackgroundColor(Color.BLACK);review.setVisibility(View.GONE);root.addView(review,new FrameLayout.LayoutParams(-1,-1));
        final LinearLayout bottom=new LinearLayout(activity);bottom.setOrientation(LinearLayout.VERTICAL);bottom.setGravity(Gravity.CENTER);bottom.setPadding(24,20,24,28);bottom.setBackgroundColor(0xBB000000);
        final TextView status=new TextView(activity);status.setTextColor(Color.WHITE);status.setTextSize(15);status.setGravity(Gravity.CENTER);status.setText("Opening front camera…");bottom.addView(status,new LinearLayout.LayoutParams(-1,-2));
        final LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER);row.setPadding(0,14,0,0);
        final Button cancel=new Button(activity);cancel.setText("Cancel");row.addView(cancel,new LinearLayout.LayoutParams(0,-2,1));
        final Button capture=new Button(activity);capture.setText("CAPTURE SELFIE");capture.setEnabled(false);row.addView(capture,new LinearLayout.LayoutParams(0,-2,2));bottom.addView(row,new LinearLayout.LayoutParams(-1,-2));
        final LinearLayout reviewRow=new LinearLayout(activity);reviewRow.setGravity(Gravity.CENTER);reviewRow.setPadding(0,12,0,0);
        final Button retake=new Button(activity);retake.setText("Retake");retake.setVisibility(View.GONE);reviewRow.addView(retake,new LinearLayout.LayoutParams(0,-2,1));
        final Button use=new Button(activity);use.setText("Use Photo");use.setVisibility(View.GONE);reviewRow.addView(use,new LinearLayout.LayoutParams(0,-2,1));bottom.addView(reviewRow,new LinearLayout.LayoutParams(-1,-2));
        root.addView(bottom,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        dialog.setContentView(root);
        if(dialog.getWindow()!=null){dialog.getWindow().setLayout(-1,-1);dialog.getWindow().setBackgroundDrawableResource(android.R.color.black);}

        final Holder holder=new Holder();
        final Runnable[] fallback={null};
        fallback[0]=()->{
            if(holder.fallbackSent)return;holder.fallbackSent=true;
            String reason=(holder.error==null||holder.error.isEmpty())?"CameraX unavailable":holder.error;
            cleanup(holder);
            try{dialog.dismiss();}catch(Exception ignored){}
            callback.onFallback(reason);
        };
        Runnable startCamera=()->{
            status.setText("Opening front camera…");capture.setEnabled(false);
            ListenableFuture<ProcessCameraProvider> future=ProcessCameraProvider.getInstance(activity);
            future.addListener(()->{
                try{
                    holder.provider=future.get();
                    Preview p=new Preview.Builder().build();p.setSurfaceProvider(preview.getSurfaceProvider());
                    ResolutionSelector rs=new ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(new ResolutionStrategy(new android.util.Size(1280,960),ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build();
                    holder.capture=new ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).setJpegQuality(80).setResolutionSelector(rs).build();
                    CameraSelector selector=holder.provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)?CameraSelector.DEFAULT_FRONT_CAMERA:CameraSelector.DEFAULT_BACK_CAMERA;
                    holder.provider.unbindAll();holder.provider.bindToLifecycle(activity,selector,p,holder.capture);
                    status.setText(selector==CameraSelector.DEFAULT_FRONT_CAMERA?"Front camera ready":"Using available camera");capture.setEnabled(true);
                }catch(Exception e){holder.error="CameraX start failed: "+safe(e.getMessage());status.setText(holder.error+"\nOpening system camera…");activity.getWindow().getDecorView().postDelayed(fallback[0],350);}
            },ContextCompat.getMainExecutor(activity));
        };

        cancel.setOnClickListener(v->{cleanup(holder);dialog.dismiss();callback.onCancel("Cancelled");});
        capture.setOnClickListener(v->{
            if(holder.capture==null){holder.error="CameraX is not ready";fallback[0].run();return;}
            try{
                File dir=new File(activity.getCacheDir(),"attendance_camera");if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("Could not create selfie cache");holder.file=new File(dir,"selfie_"+System.currentTimeMillis()+".jpg");
                ImageCapture.OutputFileOptions options=new ImageCapture.OutputFileOptions.Builder(holder.file).build();capture.setEnabled(false);status.setText("Capturing fresh selfie…");
                holder.capture.takePicture(options,ContextCompat.getMainExecutor(activity),new ImageCapture.OnImageSavedCallback(){
                    @Override public void onImageSaved(ImageCapture.OutputFileResults r){
                        if(holder.file==null||!holder.file.isFile()||holder.file.length()<100){holder.error="CameraX returned an empty selfie";fallback[0].run();return;}
                        if(holder.file.length()>2*1024*1024){holder.error="CameraX selfie exceeded upload size";fallback[0].run();return;}
                        try{if(holder.provider!=null)holder.provider.unbindAll();}catch(Exception ignored){}
                        preview.setVisibility(View.GONE);review.setVisibility(View.VISIBLE);review.setImageURI(Uri.fromFile(holder.file));capture.setVisibility(View.GONE);retake.setVisibility(View.VISIBLE);use.setVisibility(View.VISIBLE);status.setText("Review fresh selfie");
                    }
                    @Override public void onError(ImageCaptureException e){cleanupFile(holder);holder.error="CameraX capture failed: "+safe(e.getMessage());fallback[0].run();}
                });
            }catch(Exception e){cleanupFile(holder);holder.error="CameraX capture failed: "+safe(e.getMessage());fallback[0].run();}
        });
        retake.setOnClickListener(v->{cleanupFile(holder);review.setImageDrawable(null);review.setVisibility(View.GONE);preview.setVisibility(View.VISIBLE);retake.setVisibility(View.GONE);use.setVisibility(View.GONE);capture.setVisibility(View.VISIBLE);holder.error="";holder.fallbackSent=false;startCamera.run();});
        use.setOnClickListener(v->{if(holder.file==null||!holder.file.isFile()||holder.file.length()<100){holder.error="Selfie file missing";fallback[0].run();return;}File selected=holder.file;holder.file=null;try{if(holder.provider!=null)holder.provider.unbindAll();}catch(Exception ignored){}dialog.dismiss();callback.onUse(selected);});
        dialog.setOnDismissListener(d->{try{if(holder.provider!=null)holder.provider.unbindAll();}catch(Exception ignored){}});
        dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().setLayout(-1,-1);startCamera.run();
    }

    static class Holder { ProcessCameraProvider provider; ImageCapture capture; File file; String error=""; boolean fallbackSent=false; }
    private static void cleanup(Holder h){try{if(h.provider!=null)h.provider.unbindAll();}catch(Exception ignored){}cleanupFile(h);}
    private static void cleanupFile(Holder h){try{if(h.file!=null&&h.file.exists())h.file.delete();}catch(Exception ignored){}h.file=null;}
    private static String safe(String s){return s==null||s.trim().isEmpty()?"Unknown camera error":s.trim();}
}
