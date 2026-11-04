package ru.limar.pingmonitor;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.*;
import android.net.*;
import android.os.*;
import android.widget.RemoteViews;
import java.util.*;
import java.util.concurrent.*;

public final class MonitorService extends Service {
    static final String START="start", STOP="stop", RELOAD="reload";
    private static final int ID=101;
    static volatile boolean alive=false;
    static final int GREEN=0xff167445, YELLOW=0xffad7200, RED=0xffc83232, GRAY=0xff76838b;
    private final Object signal=new Object();
    private final List<Probe> active=new ArrayList<>();
    private volatile boolean running=false;
    private volatile int generation;
    private Thread worker;
    private ExecutorService pool;
    private Store store;
    private final Map<String,Health> results=new LinkedHashMap<>();
    private PowerManager.WakeLock wake;
    private NotificationManager notifications;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile boolean networkChanged=false;
    private String transientReason="";
    private boolean firstCycle=true;

    @Override public void onCreate() {
        super.onCreate();
        store=new Store(this); results.putAll(store.loadResults());
        notifications=getSystemService(NotificationManager.class);
        NotificationChannel channel=new NotificationChannel("monitor_status","Текущий мониторинг",NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Постоянный статус выбранных адресов");
        channel.setShowBadge(false); channel.setSound(null,null);
        notifications.createNotificationChannel(channel);
        wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,getPackageName()+":monitor");
        wake.setReferenceCounted(false);
        connectivity=getSystemService(ConnectivityManager.class);
        networkCallback=new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { changed(); }
            @Override public void onLost(Network network) { changed(); }
            private void changed() { networkChanged=true; synchronized(signal){signal.notifyAll();} }
        };
        connectivity.registerDefaultNetworkCallback(networkCallback);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        String action=intent==null?START:intent.getAction();
        if(STOP.equals(action)) { stopMonitor(); return START_NOT_STICKY; }
        if(RELOAD.equals(action) && !running) {stopSelf();return START_NOT_STICKY;}
        if(running) {
            generation++;
            cancelActive();
            synchronized(signal){signal.notifyAll();}
            return START_STICKY;
        }
        running=true; alive=true;
        store.prefs().edit().putBoolean("requested",true).apply();
        transientReason="Ожидание свежей проверки";
        Notification n=notification(store.config(),true);
        if(Build.VERSION.SDK_INT>=34) startForeground(ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(ID,n);
        pool=new ThreadPoolExecutor(10,10,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(50),
            r->{Thread t=new Thread(r,"network-probe");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        worker=new Thread(this::loop,"monitor-coordinator"); worker.start();
        return START_STICKY;
    }
    private void cancelActive() { synchronized(active){for(Probe p:active)p.cancel();} }
    private void loop() {
        try {
            while(running) {
                final int version=generation;
                Store.Config c=store.config();
                updateWake(c.keepAwake);
                List<Store.Item> enabled=new ArrayList<>();
                for(Store.Item item:c.items) if(item.enabled) enabled.add(item);
                Network network=connectivity.getActiveNetwork();
                boolean online=network!=null && connectivity.getNetworkCapabilities(network)!=null;
                boolean fresh=false;
                transientReason=enabled.isEmpty()?"Все адреса отключены":online?"":"Нет активной сети телефона";
                if(online && !enabled.isEmpty()) {
                    transientReason="Проверка адресов";
                    publish(c,firstCycle);
                    // At most ten probes in flight; no overlapping cycles and no unbounded task queue.
                    for(int base=0;base<enabled.size() && running && version==generation;base+=10) {
                        List<Probe> probes=new ArrayList<>();
                        List<Future<Probe.Answer>> futures=new ArrayList<>();
                        int end=Math.min(base+10,enabled.size());
                        for(int i=base;i<end;i++) {
                            Probe p=new Probe(enabled.get(i).target,c.timeout);
                            probes.add(p);
                            synchronized(active){active.add(p);}
                            try {futures.add(pool.submit(p));}
                            catch(RejectedExecutionException ex) {futures.add(null);}
                        }
                        long deadline=SystemClock.elapsedRealtime()+c.timeout+200;
                        for(int i=0;i<probes.size();i++) {
                            Probe.Answer a;
                            Future<Probe.Answer> f=futures.get(i);
                            try {
                                if(f==null) a=new Probe.Answer(Probe.WARN,-1,"Системный DNS/сеть заняты; повтор позже");
                                else a=f.get(Math.max(1,deadline-SystemClock.elapsedRealtime()),TimeUnit.MILLISECONDS);
                            } catch(TimeoutException ex) {
                                probes.get(i).cancel(); if(f!=null)f.cancel(true);
                                a=new Probe.Answer(Probe.DOWN,-1,"Таймаут, включая DNS ("+c.timeout+" мс)");
                            } catch(ExecutionException ex) {a=new Probe.Answer(Probe.DOWN,-1,"Ошибка проверки");}
                            catch(CancellationException ex) {a=new Probe.Answer(Probe.WARN,-1,"Проверка отменена");}
                            finally {synchronized(active){active.remove(probes.get(i));}}
                            if(!running || version!=generation) continue;
                            String key=enabled.get(base+i).target.value;
                            results.computeIfAbsent(key,k->new Health()).add(a,c.slow,c.threshold,System.currentTimeMillis());
                            fresh=true;
                        }
                        if(!running || version!=generation) {
                            for(int i=0;i<futures.size();i++) {probes.get(i).cancel();if(futures.get(i)!=null)futures.get(i).cancel(true);}
                        }
                    }
                    if(version==generation) transientReason="";
                }
                if(!running) break;
                if(version!=generation) continue;
                Set<String> keys=new HashSet<>();
                for(Store.Item item:c.items) keys.add(item.target.value);
                results.keySet().retainAll(keys);
                store.saveResults(results);
                store.prefs().edit().putLong("heartbeat",System.currentTimeMillis())
                    .putString("runtimeReason",transientReason).apply();
                publish(c,!online || !fresh);
                firstCycle=false;
                synchronized(signal) {
                    if(running && version==generation && !networkChanged) signal.wait(c.interval*1000L);
                    networkChanged=false;
                }
            }
        } catch(InterruptedException ignored) { Thread.currentThread().interrupt(); }
        catch(Exception e) {
            store.prefs().edit().putString("runtimeReason","Сервис остановлен: "+e.getClass().getSimpleName()).apply();
            new Handler(getMainLooper()).post(this::stopMonitor);
        } finally { if(wake.isHeld())wake.release(); }
    }
    private void updateWake(boolean enabled) {
        // Renew a bounded lease. Stop/destroy also release it immediately.
        if(enabled)wake.acquire(2*60*60*1000L);
        else if(!enabled && wake.isHeld())wake.release();
    }
    private void publish(Store.Config c,boolean pending) {
        // Batch notifications, not one update for every endpoint.
        notifications.notify(ID,notification(c,pending));
    }
    private Notification notification(Store.Config c,boolean pending) {
        int total=0,ok=0,warn=0,down=0;
        for(Store.Item i:c.items) if(i.enabled) {
            total++; Health h=results.get(i.target.value);
            int k=displayKind(c,h,pending);
            if(k==Probe.OK)ok++;else if(k==Probe.DOWN)down++;else warn++;
        }
        int aggregate=down>0?Probe.DOWN:warn>0 || total==0?Probe.WARN:Probe.OK;
        int color=color(aggregate);
        String title=total==0?"Нет включённых адресов":
            "В порядке: "+ok+" • Проблемы: "+warn+" • Не работает: "+down;
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,MonitorService.class).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        RemoteViews big=new RemoteViews(getPackageName(),R.layout.notification);
        big.setTextViewText(R.id.notification_status,title);
        big.removeAllViews(R.id.notification_rows);
        int shown=0;
        for(Store.Item item:c.items) {
            if(!item.enabled || shown>=8)continue;
            shown++;
            Health h=results.get(item.target.value);
            int k=displayKind(c,h,pending);
            RemoteViews row=new RemoteViews(getPackageName(),R.layout.notification_row);
            row.setTextColor(R.id.row_dot,color(k));
            row.setTextViewText(R.id.row_text,item.target.host+" • "+(pending?"Проверка / ожидание":h==null?"Ожидание":h.detail));
            big.addView(R.id.notification_rows,row);
        }
        if(total>8) {
            RemoteViews row=new RemoteViews(getPackageName(),R.layout.notification_row);
            row.setTextColor(R.id.row_dot,GRAY);
            row.setTextViewText(R.id.row_text,"Ещё "+(total-8)+" адресов. Полный список в приложении.");
            big.addView(R.id.notification_rows,row);
        }
        String summary=transientReason.isEmpty()?"Нажмите для полного списка":transientReason;
        Notification.Builder b=new Notification.Builder(this,"monitor_status")
            .setSmallIcon(aggregate==Probe.OK?R.drawable.ic_ok:aggregate==Probe.DOWN?R.drawable.ic_down:R.drawable.ic_warn)
            .setContentTitle(title).setContentText(summary).setContentIntent(open)
            .setLargeIcon(dot(color)).setColor(color).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE).setShowWhen(true).setWhen(System.currentTimeMillis())
            .setStyle(new Notification.DecoratedCustomViewStyle()).setCustomBigContentView(big)
            .addAction(new Notification.Action.Builder(null,"Стоп",stop).build());
        if(Build.VERSION.SDK_INT>=31)b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }
    static int color(int kind) {return kind==Probe.OK?GREEN:kind==Probe.DOWN?RED:YELLOW;}
    private static int displayKind(Store.Config c,Health h,boolean pending) {
        long stale=(c.interval+((c.items.size()+9)/10)*(c.timeout/1000+1)+15)*2000L;
        return pending || h==null || h.updated==0 || System.currentTimeMillis()-h.updated>stale?Probe.WARN:h.kind;
    }
    private Bitmap dot(int color) {
        Bitmap image=Bitmap.createBitmap(80,80,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(image); Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(color);
        canvas.drawCircle(40,40,28,p);return image;
    }
    private void stopMonitor() {
        running=false;alive=false;generation++;
        store.prefs().edit().putBoolean("requested",false).apply();
        cancelActive();
        if(worker!=null)worker.interrupt();
        synchronized(signal){signal.notifyAll();}
        if(pool!=null)pool.shutdownNow();
        if(wake!=null && wake.isHeld())wake.release();
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
    }
    @Override public void onDestroy() {
        running=false;alive=false;generation++;
        cancelActive();
        if(worker!=null)worker.interrupt();
        if(pool!=null)pool.shutdownNow();
        if(wake!=null && wake.isHeld())wake.release();
        if(connectivity!=null && networkCallback!=null)try{connectivity.unregisterNetworkCallback(networkCallback);}catch(Exception ignored){}
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) {return null;}
}
