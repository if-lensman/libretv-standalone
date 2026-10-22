package family.libretv.tv;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Camera permission is requested only here. Camera frames and QR photos stay on-device. */
@SuppressWarnings("deprecation")
public final class SourceScannerActivity extends Activity implements SurfaceHolder.Callback {
    private SurfaceView preview;
    private TextView status;
    private Camera camera;
    private boolean surfaceReady, resumed, permissionAsked;
    private final AtomicBoolean decoding=new AtomicBoolean(), delivered=new AtomicBoolean();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        FrameLayout stage=new FrameLayout(this);stage.setBackgroundColor(0xff0c141d);
        preview=new SurfaceView(this);stage.addView(preview,new FrameLayout.LayoutParams(-1,-1));preview.getHolder().addCallback(this);
        LinearLayout overlay=new LinearLayout(this);overlay.setOrientation(LinearLayout.VERTICAL);overlay.setPadding(24,12,24,12);overlay.setBackgroundColor(0xdc0c141d);
        status=new TextView(this);status.setTextColor(0xffffffff);status.setTextSize(18);status.setText("对准来源订阅或配置二维码，识别后会先预览。");overlay.addView(status);
        LinearLayout actions=new LinearLayout(this);overlay.addView(actions);
        Button back=new Button(this);back.setText("返回");actions.addView(back,new LinearLayout.LayoutParams(0,-2,1));back.setOnClickListener(v->finish());
        Button photo=new Button(this);photo.setText("从二维码图片识别");actions.addView(photo,new LinearLayout.LayoutParams(0,-2,2));photo.setOnClickListener(v->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE),71));
        stage.addView(overlay,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        ViewCompat.setOnApplyWindowInsetsListener(stage,(v,insets)->{androidx.core.graphics.Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        setContentView(stage);ViewCompat.requestApplyInsets(stage);
    }
    @Override protected void onResume(){super.onResume();resumed=true;tryStart();}
    @Override protected void onPause(){resumed=false;stopCamera();super.onPause();}
    @Override protected void onDestroy(){worker.shutdownNow();main.removeCallbacksAndMessages(null);super.onDestroy();}
    @Override public void surfaceCreated(SurfaceHolder holder){surfaceReady=true;tryStart();}
    @Override public void surfaceChanged(SurfaceHolder holder,int format,int width,int height){}
    @Override public void surfaceDestroyed(SurfaceHolder holder){surfaceReady=false;stopCamera();}
    private void tryStart(){
        if(!resumed || !surfaceReady || camera!=null || delivered.get())return;
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
            if(!permissionAsked){permissionAsked=true;requestPermissions(new String[]{Manifest.permission.CAMERA},72);}return;
        }
        try{
            int id=-1;Camera.CameraInfo info=new Camera.CameraInfo();
            for(int i=0;i<Camera.getNumberOfCameras();i++){Camera.getCameraInfo(i,info);if(info.facing==Camera.CameraInfo.CAMERA_FACING_BACK){id=i;break;}}
            if(id<0)throw new Exception("设备没有后置相机，可选择二维码图片");
            camera=Camera.open(id);Camera.Parameters parameters=camera.getParameters();
            Camera.Size chosen=null;
            for(Camera.Size size:parameters.getSupportedPreviewSizes())if(size.width>=640 && size.width<=1280 && (chosen==null || size.width*size.height<chosen.width*chosen.height))chosen=size;
            if(chosen!=null)parameters.setPreviewSize(chosen.width,chosen.height);
            if(parameters.getSupportedFocusModes()!=null && parameters.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            parameters.setPreviewFormat(android.graphics.ImageFormat.NV21);camera.setParameters(parameters);
            int rotation=getWindowManager().getDefaultDisplay().getRotation();int degrees=rotation==Surface.ROTATION_90?90:rotation==Surface.ROTATION_180?180:rotation==Surface.ROTATION_270?270:0;
            camera.setDisplayOrientation((info.orientation-degrees+360)%360);camera.setPreviewDisplay(preview.getHolder());
            Camera.Size frameSize=camera.getParameters().getPreviewSize();
            final int frameWidth=frameSize.width,frameHeight=frameSize.height;
            camera.setPreviewCallback((bytes,active)->{
                if(!resumed || camera!=active || worker.isShutdown() || delivered.get() || !decoding.compareAndSet(false,true))return;
                byte[] copy=bytes.clone();
                worker.execute(()->{try{String found=SourceQrDecoder.preview(copy,frameWidth,frameHeight);main.post(()->deliver(found));}catch(Exception ignored){}finally{decoding.set(false);}});
            });camera.startPreview();status.setText("对准来源订阅或配置二维码，识别后会先预览。");
        }catch(Exception e){stopCamera();status.setText("相机暂不可用："+e.getMessage()+"。也可从二维码图片识别。");}
    }
    private void stopCamera(){if(camera!=null){try{camera.setPreviewCallback(null);camera.stopPreview();}catch(Exception ignored){}camera.release();camera=null;}}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==72){if(grants.length>0 && grants[0]==PackageManager.PERMISSION_GRANTED)tryStart();else status.setText("未允许相机，可从二维码图片识别，或返回粘贴订阅地址。");}}
    private void deliver(String text){
        if(isFinishing() || isDestroyed() || delivered.get())return;
        try{String value=SourceQrDecoder.payload(text);if(!delivered.compareAndSet(false,true))return;stopCamera();setResult(RESULT_OK,new Intent().putExtra("sourceConfig",value));finish();}
        catch(Exception e){status.setText("未识别为来源配置："+e.getMessage());}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
        if(request!=71 || result!=RESULT_OK || data==null || data.getData()==null)return;
        status.setText("正在识别图片…");
        worker.execute(()->{
            Bitmap image=null;
            try{
                byte[] bytes;
                try(InputStream in=getContentResolver().openInputStream(data.getData())){
                    if(in==null)throw new Exception("无法读取图片");
                    java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
                    while((n=in.read(buffer))!=-1){if(out.size()+n>12*1024*1024)throw new Exception("图片过大，请裁剪二维码后重试");out.write(buffer,0,n);}bytes=out.toByteArray();
                }
                BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
                BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;
                while(bounds.outWidth/options.inSampleSize>1800 || bounds.outHeight/options.inSampleSize>1800)options.inSampleSize*=2;
                image=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(image==null)throw new Exception("图片无效");
                String found=SourceQrDecoder.bitmap(image);main.post(()->deliver(found));
            }catch(Exception e){main.post(()->{if(!isDestroyed())status.setText("未找到可识别的二维码，请裁剪后重试或粘贴地址。");});}
            finally{if(image!=null)image.recycle();}
        });
    }
}
