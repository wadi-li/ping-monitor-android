package ru.limar.pingmonitor;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** No third-party test runtime: emulator regression test using Android Instrumentation. */
public final class SmokeInstrumentation extends Instrumentation {
    private int checks;
    private Activity activity;
    private ServerSocket server;
    private final Map<String,Integer> counts=new ConcurrentHashMap<>();
    private final AtomicBoolean serving=new AtomicBoolean(true);
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private View find(View root,String text,boolean description) {
        CharSequence s=description?root.getContentDescription():root instanceof TextView?((TextView)root).getText():null;
        if(s!=null && s.toString().equals(text))return root;
        if(root instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++){View v=find(g.getChildAt(i),text,description);if(v!=null)return v;}
        return null;
    }
    private void click(String text,boolean description) {
        runOnMainSync(()->{
            View v=find(activity.getWindow().getDecorView(),text,description);
            check(v!=null,"button exists: "+text);check(v.isEnabled(),"button enabled: "+text);v.performClick();
        });
        waitForIdleSync();
    }
    private void await(BooleanSupplier test,long timeout,String message) {
        long end=SystemClock.elapsedRealtime()+timeout;
        while(SystemClock.elapsedRealtime()<end){if(test.getAsBoolean()){check(true,message);return;}SystemClock.sleep(100);}
        check(false,message);
    }
    private void screenshot(String name) throws IOException {
        waitForIdleSync();
        Bitmap bitmap=getUiAutomation().takeScreenshot();
        check(bitmap!=null,"screenshot "+name);
        try(OutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),name+".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG,100,out);
        }
        bitmap.recycle();
    }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            Context context=getTargetContext();
            context.stopService(new Intent(context,MonitorService.class));
            Store store=new Store(context);store.prefs().edit().clear().commit();
            server=new ServerSocket(0,20,InetAddress.getByName("127.0.0.1"));
            Thread serverThread=new Thread(()->{
                while(serving.get())try(Socket client=server.accept()) {
                    client.setSoTimeout(2000);
                    BufferedReader r=new BufferedReader(new InputStreamReader(client.getInputStream(),StandardCharsets.US_ASCII));
                    String request=r.readLine();
                    if(request==null)continue;
                    String path=request.split(" ")[1];counts.merge(path,1,Integer::sum);
                    while(true){String line=r.readLine();if(line==null || line.isEmpty())break;}
                    String code=path.equals("/bad")?"500 Error":"204 No Content";
                    client.getOutputStream().write(("HTTP/1.1 "+code+"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                }catch(IOException e){if(serving.get())throw new RuntimeException(e);}
            },"test-http");
            serverThread.setDaemon(true);serverThread.start();
            String base="http://127.0.0.1:"+server.getLocalPort();
            Store.Config cfg=new Store.Config();cfg.interval=2;cfg.timeout=3000;cfg.slow=10000;cfg.threshold=1;
            cfg.items.add(new Store.Item(Target.parse(base+"/ok"),true));
            cfg.items.add(new Store.Item(Target.parse(base+"/bad"),true));
            cfg.items.add(new Store.Item(Target.parse(base+"/muted"),false));store.save(cfg);
            activity=startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            click("Переместить выше "+base+"/bad",true);
            check(store.config().items.get(0).target.value.equals(base+"/bad"),"reorder persisted");
            click("Не опрашивать "+base+"/bad",true);
            check(!store.config().items.get(0).enabled,"per-target pause persisted");
            click("Запуск",false);
            await(()->MonitorService.alive,5000,"service running");
            await(()->store.loadResults().containsKey(base+"/ok"),10000,"native HTTP result stored");
            check(store.loadResults().get(base+"/ok").kind==Probe.OK,"green response");
            check(counts.getOrDefault("/bad",0)==0,"paused target has no requests");
            check(counts.getOrDefault("/muted",0)==0,"initially paused target has no requests");
            click("Опрашивать "+base+"/bad",true);
            await(()->store.loadResults().containsKey(base+"/bad"),10000,"resume starts requests");
            check(store.loadResults().get(base+"/bad").kind==Probe.DOWN,"HTTP 500 red at threshold 1");
            click("Тёмная",false);
            check(store.prefs().getBoolean("dark",false),"dark theme persisted");
            screenshot("dark");
            click("Светлая",false);
            screenshot("light");
            int before=counts.getOrDefault("/ok",0);
            runOnMainSync(()->activity.moveTaskToBack(true));
            await(()->counts.getOrDefault("/ok",0)>before,10000,"requests continue with activity in background");
            context.stopService(new Intent(context,MonitorService.class));
            await(()->!MonitorService.alive,5000,"stop service");
            runOnMainSync(()->activity.finish());
            activity=startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            check(store.config().items.get(0).target.value.equals(base+"/bad"),"order survives activity recreation");
            check(!store.config().items.get(2).enabled,"pause survives recreation");
            check(store.loadResults().get(base+"/ok").attempts>0,"statistics survive recreation");
            result.putString("stream","PASS: "+checks+" Android instrumentation checks\n");
            finish(Activity.RESULT_OK,result);
        } catch(Throwable e) {
            StringWriter sw=new StringWriter();e.printStackTrace(new PrintWriter(sw));
            result.putString("stream","FAIL: "+sw+"\n");finish(Activity.RESULT_CANCELED,result);
        } finally {
            serving.set(false);if(server!=null)try{server.close();}catch(IOException ignored){}
            getTargetContext().stopService(new Intent(getTargetContext(),MonitorService.class));
            if(activity!=null)runOnMainSync(()->activity.finish());
        }
    }
}
