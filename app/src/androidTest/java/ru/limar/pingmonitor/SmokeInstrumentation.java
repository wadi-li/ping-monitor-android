package ru.limar.pingmonitor;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.content.res.Configuration;
import android.net.Uri;
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
@android.annotation.TargetApi(35)
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
    private MainActivity main(){return (MainActivity)activity;}
    private ActivityMonitor documentMonitor(String action,File file) {
        IntentFilter filter=new IntentFilter(action);filter.addCategory(Intent.CATEGORY_OPENABLE);
        try{filter.addDataType("*/*");}catch(IntentFilter.MalformedMimeTypeException e){throw new AssertionError(e);}
        check(filter.match(getTargetContext().getContentResolver(),
            new Intent(action).setType(action.equals(Intent.ACTION_OPEN_DOCUMENT)?"*/*":"image/png")
                .addCategory(Intent.CATEGORY_OPENABLE),true,"document-test")>=0,"document monitor matches MIME and OPENABLE");
        return addMonitor(filter,new ActivityResult(Activity.RESULT_OK,new Intent().setData(Uri.fromFile(file))),true);
    }
    private void clickDialog(AlertDialog dialog,String text) {
        runOnMainSync(()->{
            check(dialog!=null && dialog.isShowing(),"dialog showing for "+text);
            View view=find(dialog.getWindow().getDecorView(),text,false);
            check(view!=null && view.isEnabled(),"dialog button "+text);view.performClick();
        });waitForIdleSync();
    }
    private void selectTheme(int mode) {
        click("Выбрать тему оформления",true);
        runOnMainSync(()->{
            ListView list=main().themeDialog.getListView();
            check(list.getCount()==3,"three theme choices");
            list.performItemClick(list.getChildAt(mode),mode,list.getAdapter().getItemId(mode));
        });waitForIdleSync();
    }
    private void checkButtonPalette(boolean dark) {
        Button button=(Button)find(activity.getWindow().getDecorView(),"Настройки",false);
        check(button.getCurrentTextColor()==(dark?0xffe6edf0:0xff1e3039),"button foreground follows current theme");
        check(button.getBackgroundTintList().getColorForState(new int[]{android.R.attr.state_enabled},0)==
            (dark?0xff334751:0xffdce7ec),"button background follows current theme");
    }
    private void openTransfer() {
        click("Настройки",false);clickDialog(main().settingsDialog,"Import / Export");
    }
    private void closeTransfer() {
        runOnMainSync(()->{main().transferDialog.dismiss();main().settingsDialog.dismiss();});
        waitForIdleSync();
    }
    private View compactRow(String url) {
        TextView title=(TextView)find(activity.getWindow().getDecorView(),"1.  ●  "+url,false);
        return title==null?null:(View)title.getParent();
    }
    private void layoutScreenshot(String name) throws IOException {
        Bitmap[] rendered=new Bitmap[1];
        runOnMainSync(()->{
            View root=activity.getWindow().getDecorView();
            rendered[0]=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(rendered[0]));
        });
        try(OutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),name+".png"))) {
            rendered[0].compress(Bitmap.CompressFormat.PNG,100,out);
        }finally{rendered[0].recycle();}
    }
    private void await(BooleanSupplier test,long timeout,String message) {
        long end=SystemClock.elapsedRealtime()+timeout;
        while(SystemClock.elapsedRealtime()<end){if(test.getAsBoolean()){check(true,message);return;}SystemClock.sleep(100);}
        check(false,message);
    }
    private void screenshot(String name) throws IOException {
        waitForIdleSync();
        // SystemUI applies theme changes in a different process; target-process idle is insufficient.
        SystemClock.sleep(500);
        Bitmap bitmap=getUiAutomation().takeScreenshot();
        check(bitmap!=null,"screenshot "+name);
        try(OutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),name+".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG,100,out);
        }
        bitmap.recycle();
    }
    private Notification currentNotification(Context context) {
        for(android.service.notification.StatusBarNotification n:context.getSystemService(NotificationManager.class).getActiveNotifications())
            if(n.getId()==101)return n.getNotification();
        return null;
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
            selectTheme(2);
            checkButtonPalette(true);
            check(store.prefs().getInt("themeMode",-1)==2,"dark theme persisted");
            screenshot("dark");
            layoutScreenshot("layout-dark");
            selectTheme(1);
            checkButtonPalette(false);
            check(store.prefs().getInt("themeMode",-1)==1,"light theme persisted");
            screenshot("light");
            layoutScreenshot("layout-light");
            await(()->{
                Notification n=currentNotification(context);
                return n!=null && n.getSmallIcon()!=null && n.getSmallIcon().getResId()==R.drawable.ic_down;
            },5000,"foreground down notification registered with Android");
            Notification notification=currentNotification(context);
            check((notification.flags&Notification.FLAG_FOREGROUND_SERVICE)!=0,"notification is foreground service");
            check(notification.getSmallIcon().getResId()==R.drawable.ic_down,"aggregate down status icon");
            check(notification.getLargeIcon()!=null,"colored aggregate icon present");
            check(notification.bigContentView!=null,"expanded status layout present");
            AlertDialog[] preview=new AlertDialog[1];
            runOnMainSync(()->{
                View view=notification.bigContentView.apply(activity,null);
                preview[0]=new AlertDialog.Builder(activity).setTitle("Уведомление: проверка макета")
                    .setView(view).setPositiveButton("Закрыть",null).show();
            });
            screenshot("notification-layout");
            runOnMainSync(()->preview[0].dismiss());
            click("Свернуть список серверов",false);
            checkButtonPalette(false);
            check(store.prefs().getBoolean("compactList",false),"compact mode persisted");
            View row=compactRow(base+"/bad");
            check(row instanceof LinearLayout && ((LinearLayout)row).getOrientation()==LinearLayout.HORIZONTAL,"compact server occupies a horizontal row");
            check(find(row,"Удалить",false)==null,"compact row has no full-card controls");
            check(((LinearLayout)row).getChildCount()==3,"compact row has address, average and miniature");
            check(((TextView)((LinearLayout)row).getChildAt(1)).getText().toString().startsWith("Ср.7:"),"compact average7 visible");
            runOnMainSync(row::performClick);waitForIdleSync();
            check(main().graphDialog!=null && main().graphDialog.isShowing(),"compact row opens PNG dialog");
            ImageView chart=(ImageView)find(main().graphDialog.getWindow().getDecorView(),"PNG-график "+base+"/bad",true);
            check(chart!=null && chart.getDrawable() instanceof BitmapDrawable,"graph preview is a bitmap image");
            check(((BitmapDrawable)chart.getDrawable()).getBitmap().getWidth()==1200,"PNG preview full resolution");
            File pngFile=new File(context.getCacheDir(),"test-chart.png");
            ActivityMonitor pngMonitor=documentMonitor(Intent.ACTION_CREATE_DOCUMENT,pngFile);
            clickDialog(main().graphDialog,"Сохранить PNG");
            check(pngMonitor.getHits()==1,"PNG uses system create-document flow");
            await(()->pngFile.length()>0,5000,"PNG saved by document result handler");
            removeMonitor(pngMonitor);
            Bitmap decoded=BitmapFactory.decodeFile(pngFile.getAbsolutePath());
            check(decoded!=null && decoded.getWidth()==1200 && decoded.getHeight()==400,"saved PNG decodes with expected dimensions");
            if(decoded!=null)decoded.recycle();
            layoutScreenshot("compact");
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
            check(store.prefs().getBoolean("compactList",false),"compact mode survives activity recreation");
            row=compactRow(base+"/bad");
            check(row!=null && ((LinearLayout)row).getChildCount()==3,"recreated compact row remains compact");
            click("Развернуть список серверов",false);
            check(!store.prefs().getBoolean("compactList",true),"expand mode persisted");
            check(find(activity.getWindow().getDecorView(),"Удалить "+base+"/bad",true)!=null,"expanded card has delete control");
            check(find(activity.getWindow().getDecorView(),"Импорт",false)==null,"import removed from main screen");
            check(find(activity.getWindow().getDecorView(),"Сохранить список серверов",false)==null,"list export lives in settings only");
            openTransfer();
            check(find(main().transferDialog.getWindow().getDecorView(),"Загрузить список серверов",false)!=null,"import entry in separate menu");
            File listFile=new File(context.getCacheDir(),"test-servers.csv");
            ActivityMonitor listMonitor=documentMonitor(Intent.ACTION_CREATE_DOCUMENT,listFile);
            clickDialog(main().transferDialog,"Сохранить список серверов");
            check(listMonitor.getHits()==1,"list export uses create-document flow");
            await(()->listFile.length()>0,5000,"server list export file written");
            removeMonitor(listMonitor);
            String exported=new String(java.nio.file.Files.readAllBytes(listFile.toPath()),StandardCharsets.UTF_8);
            check(exported.startsWith("\uFEFF"),"export file UTF-8 BOM");
            check(Csv.importTargets(exported).split("\\n")[0].equals(base+"/bad"),"export keeps custom order");
            check(!Csv.importEnabled(exported).get(base+"/muted"),"export keeps paused flag");
            closeTransfer();
            click("Не опрашивать "+base+"/bad",true);
            click("Переместить выше "+base+"/ok",true);
            openTransfer();
            ActivityMonitor importMonitor=documentMonitor(Intent.ACTION_OPEN_DOCUMENT,listFile);
            clickDialog(main().transferDialog,"Загрузить список серверов");
            check(importMonitor.getHits()==1,"import uses open-document flow");removeMonitor(importMonitor);
            check(main().importDialog!=null && main().importDialog.isShowing(),"import asks before replacing list");
            clickDialog(main().importDialog,"Заменить");
            check(store.config().items.get(0).target.value.equals(base+"/bad"),"import restores saved order");
            check(store.config().items.get(0).enabled,"import restores saved enabled flag");
            check(!store.config().items.get(2).enabled,"import restores disabled flag");
            closeTransfer();
            click("Удалить "+base+"/muted",true);clickDialog(main().deleteDialog,"Отмена");
            check(store.config().items.size()==3,"cancel deletion keeps list intact");
            click("Удалить "+base+"/muted",true);clickDialog(main().deleteDialog,"Удалить");
            check(store.config().items.size()==2,"delete button removes selected server");
            check(store.config().items.stream().noneMatch(i->i.target.value.equals(base+"/muted")),"deleted address removed from persisted list");
            selectTheme(0);
            check(store.prefs().getInt("themeMode",-1)==0,"system theme mode persisted");
            check(find(activity.getWindow().getDecorView(),"Тема: система",false)!=null,"system theme label");
            boolean initialNight=(activity.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
            ActivityMonitor themeMonitor=addMonitor(MainActivity.class.getName(),null,false);
            try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(
                getUiAutomation().executeShellCommand("cmd uimode night "+(initialNight?"no":"yes")))) {while(in.read()!=-1){}}
            Activity changed=themeMonitor.waitForActivityWithTimeout(10000);removeMonitor(themeMonitor);
            check(changed!=null,"system night change recreates activity");
            activity=changed;waitForIdleSync();
            boolean night=(activity.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
            check(night!=initialNight,"system night configuration actually changed");
            checkButtonPalette(night);
            View root=(View)find(activity.getWindow().getDecorView(),"Пинг-монитор",false).getParent().getParent();
            check(((ColorDrawable)root.getBackground()).getColor()==(night?0xff111a20:0xfff3f6f7),"system theme follows Android appearance");
            check(store.prefs().getInt("themeMode",-1)==0,"system mode survives configuration change");
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
