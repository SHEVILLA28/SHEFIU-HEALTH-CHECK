package com.shefiu.healthcheck;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final int CAMERA_REQUEST=7;
    private static final long SCAN_TARGET_MS=15000L;
    private static final long MIN_PEAK_GAP_MS=350L;
    private static final int MIN_SAMPLES=50;

    private final Handler mainHandler=new Handler(Looper.getMainLooper());
    private final ArrayList<Sample> samples=new ArrayList<>();
    private final AtomicBoolean processingFrame=new AtomicBoolean(false);

    private LinearLayout home,scan;
    private ScrollView questions,output;
    private SurfaceView camera;
    private TextView bpm,scanStatus,timer,signal,text;
    private ProgressBar progress;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private SurfaceHolder.Callback surfaceCallback;
    private long started,lastUiUpdate;
    private Integer pulse;
    private boolean scanning;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        home=findViewById(R.id.home); scan=findViewById(R.id.scan);
        questions=findViewById(R.id.questions); output=findViewById(R.id.output);
        camera=findViewById(R.id.camera); bpm=findViewById(R.id.bpm);
        scanStatus=findViewById(R.id.scan_status); timer=findViewById(R.id.timer);
        signal=findViewById(R.id.signal); progress=findViewById(R.id.scan_progress);
        text=findViewById(R.id.text);
        findViewById(R.id.start).setOnClickListener(v->askCamera());
        findViewById(R.id.finish).setOnClickListener(v->finishScan());
        findViewById(R.id.result).setOnClickListener(v->makeResult());
        findViewById(R.id.again).setOnClickListener(v->show(home));
        findViewById(R.id.cancel_scan).setOnClickListener(v->{stopCam();show(home);});
    }

    private void show(View target){
        home.setVisibility(View.GONE); scan.setVisibility(View.GONE);
        questions.setVisibility(View.GONE); output.setVisibility(View.GONE);
        target.setVisibility(View.VISIBLE);
    }

    private void askCamera(){
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA_REQUEST);
        else startScan();
    }

    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==CAMERA_REQUEST&&grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED) startScan();
        else if(request==CAMERA_REQUEST){ show(home); Toast.makeText(this,"Camera permission is required.",Toast.LENGTH_LONG).show(); }
    }

    private void startScan(){
        stopCam(); show(scan); samples.clear(); pulse=null; scanning=true;
        started=System.currentTimeMillis(); lastUiUpdate=0L;
        bpm.setText("Preparing scan…");
        scanStatus.setText("Place one fingertip gently over the rear camera and flash. Keep still.");
        signal.setText("● Waiting for fingertip signal");
        timer.setText("0:15"); progress.setProgress(0);
        SurfaceHolder holder=camera.getHolder();
        if(holder.getSurface().isValid()) openCamera(holder.getSurface());
        else {
            surfaceCallback=new SurfaceHolder.Callback(){
                @Override public void surfaceCreated(SurfaceHolder h){openCamera(h.getSurface());}
                @Override public void surfaceChanged(SurfaceHolder h,int f,int w,int h2){}
                @Override public void surfaceDestroyed(SurfaceHolder h){}
            };
            holder.addCallback(surfaceCallback);
        }
        updateScanUi();
    }

    private void openCamera(Surface surface){
        try{
            CameraManager manager=(CameraManager)getSystemService(Context.CAMERA_SERVICE);
            String cameraId=findRearCamera(manager);
            if(cameraId==null){failScan("Rear camera not available.");return;}
            CameraCharacteristics characteristics=manager.getCameraCharacteristics(cameraId);
            android.hardware.camera2.params.StreamConfigurationMap map=
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if(map==null){failScan("Camera configuration is unavailable.");return;}
            android.util.Size size=chooseReaderSize(map.getOutputSizes(ImageFormat.YUV_420_888));
            reader=ImageReader.newInstance(size.getWidth(),size.getHeight(),ImageFormat.YUV_420_888,3);
            reader.setOnImageAvailableListener(r->{
                Image image=null;
                try{
                    image=r.acquireLatestImage();
                    if(image!=null&&scanning&&processingFrame.compareAndSet(false,true)) processFrame(image);
                }finally{
                    if(image!=null) image.close();
                    processingFrame.set(false);
                }
            },mainHandler);
            if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return;
            manager.openCamera(cameraId,new CameraDevice.StateCallback(){
                @Override public void onOpened(CameraDevice d){device=d;createSession(surface,characteristics);}
                @Override public void onDisconnected(CameraDevice d){d.close();if(device==d)device=null;if(scanning)failScan("Camera disconnected. Please try again.");}
                @Override public void onError(CameraDevice d,int error){d.close();if(device==d)device=null;if(scanning)failScan("Camera could not be started. Please try again.");}
            },mainHandler);
        }catch(SecurityException e){failScan("Camera permission is required.");}
        catch(Exception e){failScan("Camera setup failed. Please try again.");}
    }

    private String findRearCamera(CameraManager manager)throws Exception{
        for(String id:manager.getCameraIdList()){
            Integer facing=manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if(facing!=null&&facing==CameraCharacteristics.LENS_FACING_BACK)return id;
        }
        return null;
    }

    private android.util.Size chooseReaderSize(android.util.Size[] sizes){
        if(sizes==null||sizes.length==0)return new android.util.Size(640,480);
        ArrayList<android.util.Size> candidates=new ArrayList<>(Arrays.asList(sizes));
        Collections.sort(candidates,Comparator.comparingLong(s->(long)s.getWidth()*s.getHeight()));
        for(android.util.Size s:candidates)
            if(s.getWidth()>=320&&s.getWidth()<=1280&&s.getHeight()>=240&&s.getHeight()<=960)return s;
        return candidates.get(0);
    }

    private void createSession(Surface previewSurface,CameraCharacteristics characteristics){
        if(device==null||reader==null)return;
        try{
            device.createCaptureSession(Arrays.asList(previewSurface,reader.getSurface()),new CameraCaptureSession.StateCallback(){
                @Override public void onConfigured(CameraCaptureSession cs){
                    if(device==null||!scanning)return;
                    session=cs;
                    try{
                        CaptureRequest.Builder request=device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        request.addTarget(previewSurface); request.addTarget(reader.getSurface());
                        Boolean flash=characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                        if(Boolean.TRUE.equals(flash))request.set(CaptureRequest.FLASH_MODE,CaptureRequest.FLASH_MODE_TORCH);
                        cs.setRepeatingRequest(request.build(),null,mainHandler);
                        mainHandler.postDelayed(()->{if(scanning)scanStatus.setText("Hold still. Keep your fingertip covering the camera and flash.");},1200L);
                    }catch(Exception e){failScan("Could not start the scan camera.");}
                }
                @Override public void onConfigureFailed(CameraCaptureSession cs){failScan("Camera preview could not be configured.");}
            },mainHandler);
        }catch(Exception e){failScan("Could not start camera session.");}
    }

    private void processFrame(Image image){
        long now=System.currentTimeMillis();
        if(!scanning||now-started<1200L)return;
        Image.Plane plane=image.getPlanes()[0];
        ByteBuffer buffer=plane.getBuffer().duplicate();
        int width=image.getWidth(),height=image.getHeight();
        int rowStride=plane.getRowStride(),pixelStride=plane.getPixelStride();
        if(width<=0||height<=0||pixelStride<=0)return;
        int left=width/4,right=(width*3)/4,top=height/4,bottom=(height*3)/4;
        long sum=0,count=0;
        for(int y=top;y<bottom;y+=4){
            int row=y*rowStride;
            for(int x=left;x<right;x+=4){
                int index=row+x*pixelStride;
                if(index>=0&&index<buffer.limit()){sum+=buffer.get(index)&0xFF;count++;}
            }
        }
        if(count==0)return;
        samples.add(new Sample(now,(double)sum/count));
        while(!samples.isEmpty()&&now-samples.get(0).time>SCAN_TARGET_MS)samples.remove(0);
        if(now-lastUiUpdate>=500L){
            lastUiUpdate=now; Integer estimate=estimatePulse(); if(estimate!=null)pulse=estimate;
            final long elapsed=now-started; final Integer shown=pulse;
            runOnUiThread(()->{
                updateScanUi();
                if(elapsed<5000L){bpm.setText("Calibrating…");signal.setText("● Detecting fingertip signal");}
                else if(shown==null){bpm.setText("Reading…");signal.setText("● Keep fingertip still");}
                else{bpm.setText("Estimated pulse\n"+shown+" BPM");signal.setText("● Signal detected — keep still");}
            });
        }
        if(now-started>=SCAN_TARGET_MS)runOnUiThread(this::finishScan);
    }

    private Integer estimatePulse(){
        if(samples.size()<MIN_SAMPLES)return null;
        double mean=0; for(Sample s:samples)mean+=s.value; mean/=samples.size();
        double variance=0; for(Sample s:samples){double d=s.value-mean;variance+=d*d;}
        double sd=Math.sqrt(variance/samples.size()); if(sd<0.7)return null;
        int peaks=0; long lastPeak=-Long.MAX_VALUE/4;
        for(int i=1;i<samples.size()-1;i++){
            double prev=samples.get(i-1).value,current=samples.get(i).value,next=samples.get(i+1).value;
            if(current>prev&&current>=next&&current-mean>0.35*sd&&samples.get(i).time-lastPeak>=MIN_PEAK_GAP_MS){peaks++;lastPeak=samples.get(i).time;}
        }
        double seconds=(samples.get(samples.size()-1).time-samples.get(0).time)/1000.0;
        if(seconds<6.0||peaks<4)return null;
        int estimate=(int)Math.round((peaks/seconds)*60.0);
        return estimate>=40&&estimate<=180?estimate:null;
    }

    private void updateScanUi(){
        if(!scanning)return;
        long elapsed=Math.max(0L,System.currentTimeMillis()-started);
        int remaining=(int)Math.max(0L,(SCAN_TARGET_MS-elapsed+999L)/1000L);
        timer.setText("0:"+(remaining<10?"0":"")+remaining);
        progress.setProgress((int)Math.min(100L,(elapsed*100L)/SCAN_TARGET_MS));
        if(elapsed>=SCAN_TARGET_MS)finishScan();
    }

    private void finishScan(){
        if(!scanning)return;
        Integer finalEstimate=estimatePulse(); if(finalEstimate!=null)pulse=finalEstimate;
        scanning=false; stopCam(); show(questions);
    }

    private void failScan(String message){
        scanning=false; stopCam(); show(scan);
        bpm.setText(message); signal.setText("● Scan stopped");
    }

    private void stopCam(){
        try{if(session!=null)session.close();if(device!=null)device.close();if(reader!=null)reader.close();}catch(Exception ignored){}
        session=null;device=null;reader=null;scanning=false;
        if(surfaceCallback!=null){try{camera.getHolder().removeCallback(surfaceCallback);}catch(Exception ignored){}surfaceCallback=null;}
    }

    private void makeResult(){
        boolean chest=((CheckBox)findViewById(R.id.chest)).isChecked();
        boolean breath=((CheckBox)findViewById(R.id.breath)).isChecked();
        boolean faint=((CheckBox)findViewById(R.id.faint)).isChecked();
        boolean dizzy=((CheckBox)findViewById(R.id.dizzy)).isChecked();
        boolean urgent=chest||breath||faint;
        String sys=((EditText)findViewById(R.id.sys)).getText().toString().trim();
        String dia=((EditText)findViewById(R.id.dia)).getText().toString().trim();
        StringBuilder result=new StringBuilder();
        result.append("Pulse estimate: ").append(pulse==null?"not available":pulse+" BPM").append("\n\n");
        if(urgent)result.append("URGENT SYMPTOM FLAG\nA serious symptom was selected. Seek urgent medical attention, especially if it is severe, sudden, or worsening.\n\n");
        else if(dizzy)result.append("SYMPTOM FLAG\nDizziness was selected. Consider medical attention if it is persistent, severe, or worsening.\n\n");
        else result.append("No urgent symptom was selected in this screening.\n\n");
        if(sys.isEmpty()&&dia.isEmpty())result.append("Blood pressure: not entered.\n\n");
        else if(sys.isEmpty()||dia.isEmpty())result.append("Blood pressure: incomplete entry. Enter both systolic and diastolic values from a validated device.\n\n");
        else result.append("Blood pressure entered: ").append(sys).append('/').append(dia).append(" mmHg\n\n");
        result.append("IMPORTANT\nThe camera pulse estimate is experimental and not clinically validated. This app does not diagnose disease and should not replace a medical device or clinician.");
        text.setText(result.toString()); show(output);
    }

    @Override protected void onDestroy(){stopCam();super.onDestroy();}
    private static final class Sample{final long time;final double value;Sample(long t,double v){time=t;value=v;}}
}
