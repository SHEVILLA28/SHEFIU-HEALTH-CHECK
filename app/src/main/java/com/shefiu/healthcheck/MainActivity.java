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
 private static final int CAMERA_REQUEST=7; private static final long SCAN_TARGET_MS=15000L,MIN_PEAK_GAP_MS=350L; private static final int MIN_SAMPLES=50;
 private final Handler mainHandler=new Handler(Looper.getMainLooper()); private final ArrayList<Sample> samples=new ArrayList<>(); private final AtomicBoolean processingFrame=new AtomicBoolean(false);
 private LinearLayout home,scan; private ScrollView questions,output,medscreen; private SurfaceView camera; private TextView bpm,scanStatus,timer,signal,text,medtext; private ProgressBar progress;
 private CameraDevice device; private CameraCaptureSession session; private ImageReader reader; private SurfaceHolder.Callback surfaceCallback; private long started,lastUiUpdate; private Integer pulse; private boolean scanning;

 @Override public void onCreate(Bundle state){
  super.onCreate(state);
  try{
   setContentView(R.layout.activity_main);
   home=findViewById(R.id.home);
   scan=findViewById(R.id.scan);
   questions=findViewById(R.id.questions);
   output=findViewById(R.id.output);
   camera=findViewById(R.id.camera);
   bpm=findViewById(R.id.bpm);
   scanStatus=findViewById(R.id.scan_status);
   timer=findViewById(R.id.timer);
   signal=findViewById(R.id.signal);
   progress=findViewById(R.id.scan_progress);
   text=findViewById(R.id.text);
   medscreen=findViewById(R.id.medscreen);
   medtext=findViewById(R.id.medtext);

   View start=findViewById(R.id.start);
   View medicine=findViewById(R.id.medicine);
   View medback=findViewById(R.id.medback);
   View finish=findViewById(R.id.finish);
   View result=findViewById(R.id.result);
   View again=findViewById(R.id.again);
   View cancel=findViewById(R.id.cancel_scan);

   if(home==null||scan==null||questions==null||output==null||camera==null||
      bpm==null||scanStatus==null||timer==null||signal==null||progress==null||
      text==null||medscreen==null||medtext==null||start==null||medicine==null||
      medback==null||finish==null||result==null||again==null||cancel==null){
    throw new IllegalStateException("Required app view is missing");
   }

   start.setOnClickListener(v->askCamera());
   medicine.setOnClickListener(v->showMedicine());
   medback.setOnClickListener(v->show(home));
   finish.setOnClickListener(v->finishScan());
   result.setOnClickListener(v->makeResult());
   again.setOnClickListener(v->show(home));
   cancel.setOnClickListener(v->{stopCam();show(home);});
  }catch(Throwable e){
   showStartupError(e);
  }
 }

 private void showStartupError(Throwable e){
  TextView t=new TextView(this);
  t.setText("SHEFIU Health Check\n\nThe app could not start safely.\n\nError: "
    +e.getClass().getSimpleName()+"\n"
    +(e.getMessage()==null?"":e.getMessage())
    +"\n\nPlease send this screen to support.");
  t.setTextSize(16);
  t.setPadding(32,48,32,32);
  setContentView(t);
  Toast.makeText(this,"Startup error captured",Toast.LENGTH_LONG).show();
 }
 private void show(View target){home.setVisibility(View.GONE);scan.setVisibility(View.GONE);questions.setVisibility(View.GONE);output.setVisibility(View.GONE);medscreen.setVisibility(View.GONE);target.setVisibility(View.VISIBLE);}
 private void showMedicine(){String m="Medicine guide — information, not a prescription\n\nThis app will not diagnose disease or automatically prescribe medicine from symptoms. Different causes can produce the same symptom, and medicines can interact with conditions or other medicines.\n\nFor common minor muscle or back pain, an OTC pain reliever such as ibuprofen may sometimes help, but it is not suitable for everyone. Ask a pharmacist before using it if you have stomach ulcers or bleeding, kidney or heart problems, are pregnant, take blood thinners, or use other medicines. Follow the original package label exactly.\n\nFor fever or general aches, paracetamol (acetaminophen) is a common OTC option for some people. Follow the package label and never combine it with another product containing paracetamol or acetaminophen. Ask a pharmacist if you have liver disease or take other medicines.\n\nThe app provides medicine INFORMATION and safety warnings — not a personal prescription or dose. Always check the active ingredient, strength, warnings, interactions and expiry date on the original package."; medtext.setText(m); show(medscreen);}
 private void askCamera(){if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA_REQUEST);else startScan();}
 @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==CAMERA_REQUEST&&g.length>0&&g[0]==PackageManager.PERMISSION_GRANTED)startScan();else if(r==CAMERA_REQUEST){show(home);Toast.makeText(this,"Camera permission is required.",Toast.LENGTH_LONG).show();}}
 private void startScan(){stopCam();show(scan);samples.clear();pulse=null;scanning=true;started=System.currentTimeMillis();lastUiUpdate=0;bpm.setText("Preparing scan…");scanStatus.setText("Rest your fingertip gently over the rear camera and flash. Do NOT press. Stop if uncomfortable.");signal.setText("● Waiting for gentle fingertip signal");timer.setText("0:15");progress.setProgress(0);SurfaceHolder h=camera.getHolder();if(h.getSurface().isValid())openCamera(h.getSurface());else{surfaceCallback=new SurfaceHolder.Callback(){public void surfaceCreated(SurfaceHolder x){openCamera(x.getSurface());}public void surfaceChanged(SurfaceHolder x,int f,int w,int h){}public void surfaceDestroyed(SurfaceHolder x){}};h.addCallback(surfaceCallback);}updateScanUi();}
 private void openCamera(Surface s){try{CameraManager m=(CameraManager)getSystemService(Context.CAMERA_SERVICE);String id=findRearCamera(m);if(id==null){failScan("Rear camera not available.");return;}CameraCharacteristics c=m.getCameraCharacteristics(id);android.hardware.camera2.params.StreamConfigurationMap map=c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);if(map==null){failScan("Camera configuration unavailable.");return;}android.util.Size z=chooseReaderSize(map.getOutputSizes(ImageFormat.YUV_420_888));reader=ImageReader.newInstance(z.getWidth(),z.getHeight(),ImageFormat.YUV_420_888,3);reader.setOnImageAvailableListener(r->{Image im=null;try{im=r.acquireLatestImage();if(im!=null&&scanning&&processingFrame.compareAndSet(false,true))processFrame(im);}finally{if(im!=null)im.close();processingFrame.set(false);}},mainHandler);if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return;m.openCamera(id,new CameraDevice.StateCallback(){public void onOpened(CameraDevice d){device=d;createSession(s,c);}public void onDisconnected(CameraDevice d){d.close();if(device==d)device=null;if(scanning)failScan("Camera disconnected. Please try again.");}public void onError(CameraDevice d,int e){d.close();if(device==d)device=null;if(scanning)failScan("Camera could not be started. Please try again.");}},mainHandler);}catch(Exception e){failScan("Camera setup failed. Please try again.");}}
 private String findRearCamera(CameraManager m)throws Exception{for(String id:m.getCameraIdList()){Integer f=m.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);if(f!=null&&f==CameraCharacteristics.LENS_FACING_BACK)return id;}return null;}
 private android.util.Size chooseReaderSize(android.util.Size[] a){if(a==null||a.length==0)return new android.util.Size(640,480);ArrayList<android.util.Size> c=new ArrayList<>(Arrays.asList(a));Collections.sort(c,Comparator.comparingLong(x->(long)x.getWidth()*x.getHeight()));for(android.util.Size z:c)if(z.getWidth()>=320&&z.getWidth()<=1280&&z.getHeight()>=240&&z.getHeight()<=960)return z;return c.get(0);}
 private void createSession(Surface p,CameraCharacteristics c){if(device==null||reader==null)return;try{device.createCaptureSession(Arrays.asList(p,reader.getSurface()),new CameraCaptureSession.StateCallback(){public void onConfigured(CameraCaptureSession cs){if(device==null||!scanning)return;session=cs;try{CaptureRequest.Builder q=device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);q.addTarget(p);q.addTarget(reader.getSurface());Boolean f=c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);if(Boolean.TRUE.equals(f))q.set(CaptureRequest.FLASH_MODE,CaptureRequest.FLASH_MODE_TORCH);cs.setRepeatingRequest(q.build(),null,mainHandler);}catch(Exception e){failScan("Could not start the scan camera.");}}public void onConfigureFailed(CameraCaptureSession cs){failScan("Camera preview could not be configured.");}},mainHandler);}catch(Exception e){failScan("Could not start camera session.");}}
 private void processFrame(Image im){
  long now=System.currentTimeMillis();
  if(!scanning||now-started<1200)return;
  Image.Plane[] planes=im.getPlanes();
  if(planes.length<3)return;
  Image.Plane yp=planes[0],up=planes[1],vp=planes[2];
  ByteBuffer yb=yp.getBuffer().duplicate(),ub=up.getBuffer().duplicate(),vb=vp.getBuffer().duplicate();
  int w=im.getWidth(),h=im.getHeight();
  int yrs=yp.getRowStride(),yps=yp.getPixelStride(),urs=up.getRowStride(),ups=up.getPixelStride(),vrs=vp.getRowStride(),vps=vp.getPixelStride();
  double redSum=0;int skin=0,total=0;
  for(int y=h/4;y<3*h/4;y+=4)for(int x=w/4;x<3*w/4;x+=4){
    int yi=y*yrs+x*yps, ui=(y/2)*urs+(x/2)*ups, vi=(y/2)*vrs+(x/2)*vps;
    if(yi<0||ui<0||vi<0||yi>=yb.limit()||ui>=ub.limit()||vi>=vb.limit())continue;
    double Y=yb.get(yi)&255,U=ub.get(ui)&255,V=vb.get(vi)&255;
    double R=Y+1.402*(V-128),G=Y-0.344*(U-128)-0.714*(V-128),B=Y+1.772*(U-128);
    total++;
    if(R>65&&G>35&&B>20&&R>=G*0.92&&G>=B*0.85&&R<=255){
      skin++;redSum+=R;
    }
  }
  if(total<10)return;
  double skinRatio=(double)skin/total;
  if(skinRatio<0.28)return;
  samples.add(new Sample(now,redSum/Math.max(1,skin)));
  while(!samples.isEmpty()&&now-samples.get(0).time>SCAN_TARGET_MS)samples.remove(0);
  if(now-lastUiUpdate>=500){
    lastUiUpdate=now;Integer e=estimatePulse();if(e!=null)pulse=e;
    long elapsed=now-started;Integer shown=pulse;
    runOnUiThread(()->{
      updateScanUi();
      if(elapsed<5000){bpm.setText("Calibrating…");signal.setText("● Checking fingertip signal");}
      else if(shown==null){bpm.setText("Reading…");signal.setText("● No reliable fingertip signal — keep fingertip gently over camera");}
      else{bpm.setText("Estimated pulse\n"+shown+" BPM");signal.setText("● Fingertip signal detected");}
    });
  }
  if(now-started>=SCAN_TARGET_MS)runOnUiThread(this::finishScan);
 }
 private Integer estimatePulse(){if(samples.size()<MIN_SAMPLES)return null;double mean=0;for(Sample s:samples)mean+=s.value;mean/=samples.size();double v=0;for(Sample s:samples){double d=s.value-mean;v+=d*d;}double sd=Math.sqrt(v/samples.size());if(sd<1.0)return null;int peaks=0;long last=-Long.MAX_VALUE/4;for(int i=1;i<samples.size()-1;i++){double a=samples.get(i-1).value,c=samples.get(i).value,d=samples.get(i+1).value;if(c>a&&c>=d&&c-mean>0.45*sd&&samples.get(i).time-last>=MIN_PEAK_GAP_MS){peaks++;last=samples.get(i).time;}}double sec=(samples.get(samples.size()-1).time-samples.get(0).time)/1000.0;if(sec<8||peaks<5)return null;int e=(int)Math.round(peaks/sec*60);return e>=45&&e<=170?e:null;}
 private void updateScanUi(){if(!scanning)return;long e=Math.max(0,System.currentTimeMillis()-started);int rem=(int)Math.max(0,(SCAN_TARGET_MS-e+999)/1000);timer.setText("0:"+(rem<10?"0":"")+rem);progress.setProgress((int)Math.min(100,e*100/SCAN_TARGET_MS));if(e>=SCAN_TARGET_MS)finishScan();}
 private void finishScan(){if(!scanning)return;Integer e=estimatePulse();if(e!=null)pulse=e;scanning=false;stopCam();show(questions);}
 private void failScan(String m){scanning=false;stopCam();show(scan);bpm.setText(m);signal.setText("● Scan stopped");}
 private void stopCam(){try{if(session!=null)session.close();if(device!=null)device.close();if(reader!=null)reader.close();}catch(Exception ignored){}session=null;device=null;reader=null;scanning=false;if(surfaceCallback!=null){try{camera.getHolder().removeCallback(surfaceCallback);}catch(Exception ignored){}surfaceCallback=null;}}
 private boolean checked(int id){return ((CheckBox)findViewById(id)).isChecked();}
 private void makeResult(){
  int[] ids={R.id.headache,R.id.vision,R.id.eyePain,R.id.ear,R.id.throat,R.id.chest,R.id.breath,R.id.palpitations,R.id.abdominal,R.id.nausea,R.id.bowel,R.id.urine,R.id.back,R.id.neck,R.id.joint,R.id.arm,R.id.hand,R.id.hip,R.id.leg,R.id.foot,R.id.weakness,R.id.skin,R.id.fever,R.id.fatigue,R.id.dizzy,R.id.faint,R.id.confusion};
  String[] names={"Headache/head pressure","Vision change","Eye pain/irritation","Ear/hearing symptom","Throat/swallowing symptom","Chest pain/pressure","Serious breathing trouble","Heartbeat change","Abdominal pain","Nausea/vomiting","Bowel change/blood in stool","Urinary symptom","Back/waist pain","Neck symptom","Joint pain/swelling","Arm/shoulder pain","Hand/finger symptom","Hip pain","Leg/knee pain","Foot/ankle symptom","New weakness/numbness/trouble walking","Skin change/wound/swelling","Fever/chills","Unusual fatigue","Dizziness/feeling faint","Fainting/nearly fainting","Severe confusion/difficulty staying awake"};
  StringBuilder selected=new StringBuilder();int count=0;for(int i=0;i<ids.length;i++)if(checked(ids[i])){if(count++>0)selected.append("\n• ");selected.append(names[i]);}
  boolean urgent=checked(R.id.chest)||checked(R.id.breath)||checked(R.id.faint)||checked(R.id.confusion)||checked(R.id.weakness);
  String sys=get(R.id.sys),dia=get(R.id.dia),temp=get(R.id.temp),spo2=get(R.id.spo2),glucose=get(R.id.glucose);
  StringBuilder r=new StringBuilder();
  r.append("SCREENING SUMMARY\n\nPULSE\n");
  r.append(pulse==null?"• Experimental camera estimate: Not available\n":"• Experimental camera estimate: "+pulse+" BPM\n");
  r.append("Camera pulse is experimental and not clinically validated. Do not use it for diagnosis or treatment decisions.\n\n");
  r.append("SYMPTOMS REPORTED\n");
  r.append(count==0?"• None reported\n":"• "+selected+"\n");
  r.append("\n");
  if(urgent)r.append("URGENT SAFETY NOTICE\nA serious symptom was selected. Seek urgent medical care, especially if the symptom is severe, sudden, or worsening.\n\n");
  else r.append("SAFETY CHECK\nNo urgent symptom was selected in this screening. This does not rule out illness.\n\n");
  r.append("MEASUREMENTS FROM VALIDATED DEVICES\n");
  r.append("Blood pressure: ").append(sys.isEmpty()&&dia.isEmpty()?"Not entered":sys.isEmpty()||dia.isEmpty()?"Incomplete":sys+"/"+dia+" mmHg").append("\n");
  r.append("Temperature: ").append(temp.isEmpty()?"Not entered":temp+" °C").append("\n");
  r.append("SpO₂: ").append(spo2.isEmpty()?"Not entered":spo2+" %").append("\n");
  r.append("Glucose: ").append(glucose.isEmpty()?"Not entered":glucose).append("\n\n");
  r.append("IMPORTANT\nThis is a symptom and wellness screening, not a diagnosis. Measurement values must come from appropriate validated devices. If symptoms are severe, sudden, or worsening, seek medical care.");
  text.setText(r.toString());show(output);
 }
private String get(int id){return ((EditText)findViewById(id)).getText().toString().trim();}
 @Override protected void onDestroy(){stopCam();super.onDestroy();}
 private static final class Sample{final long time;final double value;Sample(long t,double v){time=t;value=v;}}
}