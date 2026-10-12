package in.finseq.insurance;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Internal camera-only selfie capture. No gallery/file picker and no external camera app dependency. */
public class SelfieCameraActivity extends Activity {
    private TextureView preview;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private CaptureRequest.Builder previewBuilder;
    private ImageReader reader;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private String cameraId;
    private int sensorOrientation=90;
    private int lensFacing=CameraCharacteristics.LENS_FACING_FRONT;
    private TextView status;
    private Button capture;

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.BLACK);
        buildUi();
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
            setResult(RESULT_CANCELED,new Intent().putExtra("camera_error","Camera permission not granted"));
            finish();
            return;
        }
        cameraThread=new HandlerThread("finseq-selfie-camera");cameraThread.start();cameraHandler=new Handler(cameraThread.getLooper());
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){
            public void onSurfaceTextureAvailable(SurfaceTexture s,int w,int h){openCamera();}
            public void onSurfaceTextureSizeChanged(SurfaceTexture s,int w,int h){}
            public boolean onSurfaceTextureDestroyed(SurfaceTexture s){return true;}
            public void onSurfaceTextureUpdated(SurfaceTexture s){}
        });
    }

    private void buildUi(){
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);
        preview=new TextureView(this);root.addView(preview,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout bottom=new LinearLayout(this);bottom.setOrientation(LinearLayout.VERTICAL);bottom.setGravity(Gravity.CENTER);bottom.setPadding(24,20,24,28);bottom.setBackgroundColor(0x99000000);
        status=new TextView(this);status.setText("Opening front camera…");status.setTextColor(Color.WHITE);status.setTextSize(15);status.setGravity(Gravity.CENTER);bottom.addView(status,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER);row.setPadding(0,16,0,0);
        Button cancel=new Button(this);cancel.setText("Cancel");cancel.setOnClickListener(v->{setResult(RESULT_CANCELED);finish();});row.addView(cancel,new LinearLayout.LayoutParams(0,-2,1));
        capture=new Button(this);capture.setText("CAPTURE SELFIE");capture.setEnabled(false);capture.setOnClickListener(v->capturePhoto());row.addView(capture,new LinearLayout.LayoutParams(0,-2,2));
        bottom.addView(row,new LinearLayout.LayoutParams(-1,-2));
        FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);root.addView(bottom,bp);setContentView(root);
    }

    private void openCamera(){
        try{
            CameraManager cm=(CameraManager)getSystemService(CAMERA_SERVICE);
            if(cm==null)throw new IllegalStateException("Camera service unavailable");
            String fallback=null;
            for(String id:cm.getCameraIdList()){
                CameraCharacteristics c=cm.getCameraCharacteristics(id);
                Integer facing=c.get(CameraCharacteristics.LENS_FACING);
                if(fallback==null)fallback=id;
                if(facing!=null&&facing==CameraCharacteristics.LENS_FACING_FRONT){cameraId=id;break;}
            }
            if(cameraId==null)cameraId=fallback;
            if(cameraId==null)throw new IllegalStateException("No camera found");
            CameraCharacteristics cc=cm.getCameraCharacteristics(cameraId);
            Integer so=cc.get(CameraCharacteristics.SENSOR_ORIENTATION);if(so!=null)sensorOrientation=so;
            Integer lf=cc.get(CameraCharacteristics.LENS_FACING);if(lf!=null)lensFacing=lf;
            android.util.Size[] sizes=cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(android.graphics.ImageFormat.JPEG);
            android.util.Size chosen=sizes[0];
            for(android.util.Size s:sizes){if(s.getWidth()<=1920&&s.getHeight()<=1920&&s.getWidth()*s.getHeight()>chosen.getWidth()*chosen.getHeight())chosen=s;}
            reader=ImageReader.newInstance(chosen.getWidth(),chosen.getHeight(),android.graphics.ImageFormat.JPEG,2);
            reader.setOnImageAvailableListener(r->saveImage(r.acquireLatestImage()),cameraHandler);
            if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return;
            cm.openCamera(cameraId,new CameraDevice.StateCallback(){
                public void onOpened(CameraDevice c){camera=c;startPreview();}
                public void onDisconnected(CameraDevice c){c.close();camera=null;fail("Camera disconnected");}
                public void onError(CameraDevice c,int error){c.close();camera=null;fail("Camera error "+error);}
            },cameraHandler);
        }catch(Exception e){fail(e.getMessage());}
    }

    private void startPreview(){
        try{
            SurfaceTexture st=preview.getSurfaceTexture();if(st==null)throw new IllegalStateException("Preview unavailable");
            st.setDefaultBufferSize(Math.max(preview.getWidth(),720),Math.max(preview.getHeight(),1280));
            Surface surface=new Surface(st);
            previewBuilder=camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);previewBuilder.addTarget(surface);
            previewBuilder.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            camera.createCaptureSession(Arrays.asList(surface,reader.getSurface()),new CameraCaptureSession.StateCallback(){
                public void onConfigured(CameraCaptureSession s){session=s;try{s.setRepeatingRequest(previewBuilder.build(),null,cameraHandler);runOnUiThread(()->{status.setText(lensFacing==CameraCharacteristics.LENS_FACING_FRONT?"Front camera ready":"Camera ready");capture.setEnabled(true);});}catch(Exception e){fail(e.getMessage());}}
                public void onConfigureFailed(CameraCaptureSession s){fail("Camera preview configuration failed");}
            },cameraHandler);
        }catch(Exception e){fail(e.getMessage());}
    }

    private int jpegOrientation(){
        int rotation=getWindowManager().getDefaultDisplay().getRotation();
        int device=rotation==Surface.ROTATION_90?90:rotation==Surface.ROTATION_180?180:rotation==Surface.ROTATION_270?270:0;
        if(lensFacing==CameraCharacteristics.LENS_FACING_FRONT)return (sensorOrientation+device)%360;
        return (sensorOrientation-device+360)%360;
    }

    private void capturePhoto(){
        if(camera==null||session==null||reader==null)return;
        capture.setEnabled(false);status.setText("Capturing…");
        try{
            CaptureRequest.Builder b=camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);b.addTarget(reader.getSurface());
            b.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);b.set(CaptureRequest.JPEG_ORIENTATION,jpegOrientation());
            session.capture(b.build(),new CameraCaptureSession.CaptureCallback(){},cameraHandler);
        }catch(Exception e){capture.setEnabled(true);fail(e.getMessage());}
    }

    private void saveImage(Image image){
        if(image==null){fail("No selfie image received");return;}
        try{
            ByteBuffer buffer=image.getPlanes()[0].getBuffer();byte[] bytes=new byte[buffer.remaining()];buffer.get(bytes);
            File dir=new File(getCacheDir(),"attendance_camera");dir.mkdirs();File f=new File(dir,"selfie_"+System.currentTimeMillis()+".jpg");
            try(FileOutputStream out=new FileOutputStream(f)){out.write(bytes);out.flush();}
            if(f.length()<100)throw new IllegalStateException("Captured selfie is empty");
            Intent result=new Intent().putExtra("selfie_path",f.getAbsolutePath());setResult(RESULT_OK,result);runOnUiThread(this::finish);
        }catch(Exception e){fail(e.getMessage());}finally{image.close();}
    }

    private void fail(String msg){runOnUiThread(()->{status.setText(msg==null?"Camera unavailable":msg);capture.setEnabled(false);});}

    @Override protected void onPause(){super.onPause();closeCamera();}
    @Override protected void onDestroy(){closeCamera();if(cameraThread!=null){cameraThread.quitSafely();cameraThread=null;}super.onDestroy();}
    private void closeCamera(){try{if(session!=null){session.close();session=null;}}catch(Exception ignored){}try{if(camera!=null){camera.close();camera=null;}}catch(Exception ignored){}try{if(reader!=null){reader.close();reader=null;}}catch(Exception ignored){}}
}
