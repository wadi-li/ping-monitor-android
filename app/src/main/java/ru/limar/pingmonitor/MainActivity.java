package ru.limar.pingmonitor;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

public final class MainActivity extends Activity {
    private Store store;
    private Store.Config config;
    private LinearLayout root,cards;
    private TextView summary;
    private Button start,stop;
    private boolean dark;
    private int bg,fg,muted,cardBg;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Map<String,Card> cardViews=new LinkedHashMap<>();
    private String lastResults="",lastReason="";
    private boolean lastAlive;
    private Bitmap pendingBitmap;
    private final Runnable refresh=new Runnable(){@Override public void run(){update();handler.postDelayed(this,1000);}};
    private static final int IMPORT=21,EXPORT=22,PNG=23,NOTIFY=24;
    private static final class Card {
        TextView title,metrics,detail;
        LinearLayout box;
        Sparkline graph;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store=new Store(this);config=store.config();dark=store.prefs().getBoolean("dark",false);
        build();
    }
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private LinearLayout vertical(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private TextView text(String s,int size,int color) {
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);return t;
    }
    private GradientDrawable background(int color,int stroke) {
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(12));
        if(stroke!=0)d.setStroke(dp(1),stroke);return d;
    }
    private Button button(String label,Runnable action) {
        Button b=new Button(this);b.setText(label);b.setTextSize(12);b.setAllCaps(false);
        b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(7),dp(3),dp(7),dp(3));
        b.setOnClickListener(v->action.run());return b;
    }
    private void addButton(LinearLayout row,Button b) {
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(48),1);lp.setMargins(dp(2),0,dp(2),0);row.addView(b,lp);
    }
    private void build() {
        bg=dark?0xff111a20:0xfff3f6f7;fg=dark?0xffe6edf0:0xff1e3039;
        muted=dark?0xffa9b7bf:0xff536871;cardBg=dark?0xff1c2931:Color.WHITE;
        getWindow().setStatusBarColor(bg);getWindow().setNavigationBarColor(bg);
        int flags=View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if(!dark)flags|=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
        if(Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);
        root=vertical();root.setBackgroundColor(bg);root.setPadding(dp(14),dp(10),dp(14),0);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            if(Build.VERSION.SDK_INT>=30) {
                Insets i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());
                v.setPadding(dp(14)+i.left,dp(10)+i.top,dp(14)+i.right,i.bottom);
                return WindowInsets.CONSUMED;
            }
            v.setPadding(dp(14)+insets.getSystemWindowInsetLeft(),dp(10)+insets.getSystemWindowInsetTop(),
                dp(14)+insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text("Пинг-монитор",24,fg);title.setTypeface(null,Typeface.BOLD);
        header.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));
        header.addView(button(dark?"Светлая":"Тёмная",()->{
            dark=!dark;store.prefs().edit().putBoolean("dark",dark).apply();build();update();
        }));
        root.addView(header);
        summary=text("",14,muted);summary.setPadding(0,dp(5),0,dp(9));root.addView(summary);
        LinearLayout controls=new LinearLayout(this);
        start=button("Запуск",this::startMonitoring);stop=button("Стоп",this::stopMonitoring);
        addButton(controls,start);addButton(controls,stop);addButton(controls,button("Настройки",this::settings));root.addView(controls);
        LinearLayout tools=new LinearLayout(this);
        addButton(tools,button("Добавить",()->editAddress(null)));
        addButton(tools,button("Импорт",this::importFile));addButton(tools,button("CSV-отчёт",this::exportFile));root.addView(tools);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        cards=vertical();cards.setPadding(0,dp(8),0,dp(20));
        scroll.addView(cards);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
        rebuildCards();
    }
    private void rebuildCards() {
        cardViews.clear();cards.removeAllViews();
        for(int idx=0;idx<config.items.size();idx++) {
            final int index=idx;final Store.Item item=config.items.get(idx);
            Card c=new Card();c.box=vertical();c.box.setPadding(dp(12),dp(9),dp(12),dp(7));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(10));
            cards.addView(c.box,lp);
            c.title=text("",15,fg);c.title.setTypeface(null,Typeface.BOLD);
            c.title.setPadding(0,0,0,dp(5));c.title.setOnClickListener(v->editAddress(item));
            c.box.addView(c.title);
            c.detail=text("",13,muted);c.box.addView(c.detail);
            c.metrics=text("",12,muted);c.metrics.setPadding(0,dp(5),0,0);c.box.addView(c.metrics);
            c.graph=new Sparkline(this);c.box.addView(c.graph,new LinearLayout.LayoutParams(-1,dp(58)));
            c.graph.setOnClickListener(v->showGraph(item,c.graph));
            LinearLayout row=new LinearLayout(this);
            Button up=button("Выше",()->move(index,-1));up.setEnabled(index>0);
            up.setContentDescription("Переместить выше "+item.target.value);
            Button down=button("Ниже",()->move(index,1));down.setEnabled(index<config.items.size()-1);
            down.setContentDescription("Переместить ниже "+item.target.value);
            Button toggle=button(item.enabled?"Не опрашивать":"Опрашивать",()->{
                item.enabled=!item.enabled;saveAndReload();rebuildCards();update();
            });
            toggle.setContentDescription((item.enabled?"Не опрашивать ":"Опрашивать ")+item.target.value);
            addButton(row,up);addButton(row,down);
            LinearLayout.LayoutParams toggleLp=new LinearLayout.LayoutParams(0,dp(48),1.8f);
            row.addView(toggle,toggleLp);c.box.addView(row);
            cardViews.put(item.target.value,c);
        }
        if(config.items.isEmpty())cards.addView(text("Добавьте адреса для мониторинга.",16,muted));
        lastResults="\u0000";update();
    }
    private void move(int index,int direction) {
        int other=index+direction;
        if(other<0 || other>=config.items.size())return;
        Collections.swap(config.items,index,other);
        saveAndReload();rebuildCards();
    }
    private void saveAndReload() {
        store.save(config);
        if(MonitorService.alive) startService(new Intent(this,MonitorService.class).setAction(MonitorService.RELOAD));
    }
    private void update() {
        if(summary==null)return;
        String raw=store.prefs().getString("results","{}");
        String reason=store.prefs().getString("runtimeReason","");
        boolean alive=MonitorService.alive;
        Map<String,Health> results=store.loadResults();
        int ok=0,warn=0,down=0,disabled=0;
        long now=System.currentTimeMillis(),newest=0;
        for(Store.Item item:config.items) {
            Health h=results.getOrDefault(item.target.value,new Health());
            boolean stale=h.updated==0 || now-h.updated>staleMillis();
            int kind=stale || (alive && reason.equals("Нет активной сети телефона"))?Probe.WARN:h.kind;
            if(!item.enabled)disabled++;else if(kind==Probe.OK)ok++;else if(kind==Probe.DOWN)down++;else warn++;
            newest=Math.max(newest,h.updated);
            Card c=cardViews.get(item.target.value);if(c==null)continue;
            int color=!item.enabled?MonitorService.GRAY:MonitorService.color(kind);
            c.title.setText((config.items.indexOf(item)+1)+".  ●  "+item.target.value);
            c.title.setTextColor(color);
            c.box.setBackground(background(cardBg,color));
            c.detail.setText(!item.enabled?"Не опрашивается. Исключён из общего статуса.":
                h.updated==0?"Ожидание первой проверки":(stale?"Данные устарели • ":"")+Health.label(kind)+" • "+h.detail);
            c.metrics.setText(h.updated==0?"Измерений пока нет":
                "Последнее: "+(h.history.isEmpty() || h.history.get(h.history.size()-1)<0?"нет ответа":h.history.get(h.history.size()-1)+" мс")+
                " • Сред.10: "+number(h.average10())+" мс\n"+
                "Мин/макс: "+(h.min<0?"–":h.min)+"/"+(h.max<0?"–":h.max)+
                " мс • Ошибки: "+String.format(Locale.US,"%.1f",h.loss())+"% ("+h.failures+"/"+h.attempts+")"+
                "\nПроверено: "+time(h.updated));
            if(!raw.equals(lastResults) || alive!=lastAlive || !reason.equals(lastReason)) c.graph.set(h,color);
        }
        PowerManager power=getSystemService(PowerManager.class);
        boolean exempt=power.isIgnoringBatteryOptimizations(getPackageName());
        String runtime=alive?"Работает в фоне":"Остановлен";
        summary.setText(runtime+" • В порядке "+ok+" / Проблемы "+warn+" / Не работает "+down+
            (disabled>0?" • Отключено "+disabled:"")+
            (alive && !reason.isEmpty()?"\n"+reason:"")+
            (!exempt?"\nДля выключенного экрана: Настройки → Батарея.":"")+
            (alive && !getSystemService(NotificationManager.class).areNotificationsEnabled()?"\nУведомления запрещены системой.":""));
        start.setEnabled(!alive);stop.setEnabled(alive);
        lastResults=raw;lastReason=reason;lastAlive=alive;
    }
    private long staleMillis(){return (config.interval+((config.items.size()+9)/10)*(config.timeout/1000+1)+15)*2000L;}
    private static String number(double n){return n<0?"–":String.format(Locale.US,"%.1f",n);}
    private static String time(long ms){return new SimpleDateFormat("dd.MM HH:mm:ss",Locale.getDefault()).format(new Date(ms));}
    private void startMonitoring() {
        if(config.items.stream().noneMatch(i->i.enabled)){alert("Включите хотя бы один адрес кнопкой «Опрашивать».");return;}
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFY);return;
        }
        if(!getSystemService(NotificationManager.class).areNotificationsEnabled()) {
            new AlertDialog.Builder(this).setMessage("Для индикатора в строке состояния разрешите уведомления приложения.")
                .setPositiveButton("Разрешить",(d,w)->openNotificationSettings()).setNegativeButton("Отмена",null).show();return;
        }
        store.save(config);
        startForegroundService(new Intent(this,MonitorService.class).setAction(MonitorService.START));
        handler.postDelayed(this::update,300);
    }
    private void stopMonitoring(){stopService(new Intent(this,MonitorService.class));store.prefs().edit().putBoolean("requested",false).apply();handler.postDelayed(this::update,200);}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants) {
        super.onRequestPermissionsResult(code,permissions,grants);
        if(code==NOTIFY && grants.length>0 && grants[0]==PackageManager.PERMISSION_GRANTED)startMonitoring();
        else if(code==NOTIFY)alert("Без разрешения уведомлений индикатор не появится. Разрешите их в настройках приложения.");
    }
    private void openNotificationSettings(){startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()));}
    private void editAddress(Store.Item existing) {
        EditText input=new EditText(this);input.setTextSize(15);
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setMinLines(existing==null?3:1);
        input.setHint("example.com\nhttp://192.168.1.1/\ntcp://192.168.1.1:443\nicmp://1.1.1.1");
        if(existing!=null)input.setText(existing.target.value);
        LinearLayout box=vertical();box.setPadding(dp(18),dp(8),dp(18),dp(8));
        box.addView(text("По одному адресу на строку. Без протокола: HTTPS. Нажмите адрес в списке для изменения.",13,0xff536871));box.addView(input);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(existing==null?"Добавить адреса":"Изменить адрес")
            .setView(box).setPositiveButton("Сохранить",null).setNegativeButton("Отмена",null)
            .setNeutralButton(existing==null?"Справка":"Удалить",null).create();
        dialog.setOnShowListener(v->{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b->{
                try {
                    List<Target> parsed=Target.parseLines(input.getText().toString());
                    if(existing!=null) {
                        if(parsed.size()!=1)throw new IllegalArgumentException("При изменении нужен один адрес");
                        String url=parsed.get(0).value;
                        for(Store.Item item:config.items)if(item!=existing && item.target.value.equals(url))throw new IllegalArgumentException("Этот адрес уже есть");
                        config.items.set(config.items.indexOf(existing),new Store.Item(parsed.get(0),existing.enabled));
                    } else {
                        Set<String> keys=new HashSet<>();
                        for(Store.Item item:config.items)keys.add(item.target.value);
                        List<Store.Item> additions=new ArrayList<>();
                        for(Target t:parsed)if(keys.add(t.value))additions.add(new Store.Item(t,true));
                        if(config.items.size()+additions.size()>50)throw new IllegalArgumentException("Не более 50 адресов");
                        config.items.addAll(additions);
                    }
                    saveAndReload();rebuildCards();dialog.dismiss();
                }catch(IllegalArgumentException e){input.setError(e.getMessage());}
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(b->{
                if(existing==null)help();
                else new AlertDialog.Builder(this).setMessage("Удалить "+existing.target.value+"?")
                    .setPositiveButton("Удалить",(d,w)->{config.items.remove(existing);saveAndReload();rebuildCards();dialog.dismiss();})
                    .setNegativeButton("Отмена",null).show();
            });
        });
        dialog.show();
    }
    private EditText numeric(LinearLayout box,String label,int value) {
        box.addView(text(label,14,0xff304951));
        EditText e=new EditText(this);e.setSingleLine();e.setInputType(InputType.TYPE_CLASS_NUMBER);e.setText(""+value);box.addView(e);return e;
    }
    private int range(EditText e,int min,int max) {
        try {int n=Integer.parseInt(e.getText().toString());if(n>=min && n<=max)return n;}
        catch(NumberFormatException ignored){}
        throw new IllegalArgumentException("Допустимое значение: "+min+"–"+max);
    }
    private void settings() {
        LinearLayout box=vertical();box.setPadding(dp(20),dp(6),dp(20),dp(6));
        EditText interval=numeric(box,"Пауза между циклами, с (2–3600)",config.interval);
        EditText timeout=numeric(box,"Таймаут проверки, мс (500–30000)",config.timeout);
        EditText slow=numeric(box,"Жёлтый при задержке от, мс (50–30000)",config.slow);
        EditText threshold=numeric(box,"Красный после ошибок подряд (1–20)",config.threshold);
        CheckBox awake=new CheckBox(this);awake.setText("Держать CPU активным при мониторинге\nРасходует батарею; для выключенного экрана");awake.setChecked(config.keepAwake);box.addView(awake);
        box.addView(button("Батарея: убрать ограничения",()->{
            try{startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}
            catch(ActivityNotFoundException e){startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}
        }));
        box.addView(button("Уведомления",this::openNotificationSettings));
        box.addView(button("Справка и ограничения",this::help));
        box.addView(button("Сбросить статистику",()->{
            if(MonitorService.alive){alert("Сначала остановите мониторинг.");return;}
            new AlertDialog.Builder(this).setMessage("Удалить всю статистику? Адреса и их порядок сохранятся.")
                .setPositiveButton("Сбросить",(d,w)->{store.prefs().edit().remove("results").apply();update();})
                .setNegativeButton("Отмена",null).show();
        }));
        ScrollView scroll=new ScrollView(this);scroll.addView(box);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Настройки").setView(scroll).setPositiveButton("Сохранить",null).setNegativeButton("Отмена",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b->{
            try{
                int i=range(interval,2,3600),t=range(timeout,500,30000),s=range(slow,50,30000),f=range(threshold,1,20);
                config.interval=i;config.timeout=t;config.slow=s;config.threshold=f;config.keepAwake=awake.isChecked();
                saveAndReload();dialog.dismiss();
            }catch(IllegalArgumentException e){alert(e.getMessage());}
        }));
        dialog.show();
    }
    private void help() {
        alert("Пинг-монитор 1.0.0\n\n"+
            "HTTPS/HTTP: время до заголовков ответа, включая DNS, TCP и TLS. Это не ICMP-пинг. Путь, протокол и порт сохраняются. HEAD → GET при 405/501. TLS проверяется штатно.\n\n"+
            "TCP: доступность порта. ICMP: системный ping, может блокироваться узлом или прошивкой; root не нужен.\n\n"+
            "Зелёный: успешный ответ, задержка ниже порога, менее 20% ошибок в последних 20 проверках. Жёлтый: первый сбой, медленный ответ, 401/403/429, ожидание или устаревшие данные. Красный: заданное число ошибок подряд.\n\n"+
            "«Выше» / «Ниже»: порядок списка, опроса и уведомления. «Не опрашивать»: адрес сохраняется, запросы и его вклад в общий статус отключаются. Изменения действуют без остановки сервиса; текущий пакет отменяется.\n\n"+
            "Сервис работает после сворачивания и удаления окна из последних. Для выключенного экрана отключите оптимизацию батареи и разрешите фоновую работу в настройках производителя. Непрерывность после force-stop, перезагрузки или убийства системой не гарантируется. После перезагрузки нажмите «Запуск».\n\n"+
            "В строке состояния Android значки монохромные: круг с галочкой / ! / крестом. Цветные кружки доступны в приложении и раскрытом уведомлении (до 8 адресов, полный список в приложении).\n\n"+
            "График: последние 50 измерений, ошибки разрывают линию. Нажмите график для PNG. CSV: ;, UTF-8 BOM. Импорт: один адрес на строку, строка адресов через ; или отчёт приложения.\n\n"+
            "Адреса и статистика сохраняются только на устройстве. Нет аналитики, рекламы или внешних библиотек. HTTP без TLS разрешён для локальных устройств. Проверяйте только свои или разрешённые адреса; частый опрос расходует трафик и батарею.");
    }
    private void importFile(){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent,IMPORT);
    }
    private void exportFile(){
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/csv").addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_TITLE,"ping-report-"+new SimpleDateFormat("yyyy-MM-dd-HHmmss",Locale.US).format(new Date())+".csv"),EXPORT);
    }
    private void showGraph(Store.Item item,Sparkline graph) {
        Health h=store.loadResults().getOrDefault(item.target.value,new Health());
        Sparkline view=new Sparkline(this);view.set(h,MonitorService.color(h.kind));
        LinearLayout box=vertical();box.setPadding(dp(12),0,dp(12),0);box.addView(view,new LinearLayout.LayoutParams(-1,dp(220)));
        new AlertDialog.Builder(this).setTitle(item.target.host).setView(box).setNegativeButton("Закрыть",null)
            .setPositiveButton("Сохранить PNG",(d,w)->{
                pendingBitmap=view.bitmap();
                startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("image/png").addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(Intent.EXTRA_TITLE,"ping-chart-"+System.currentTimeMillis()+".png"),PNG);
            }).show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK || data==null || data.getData()==null)return;
        Uri uri=data.getData();
        try {
            if(request==IMPORT) {
                String content;
                try(InputStream in=getContentResolver().openInputStream(uri)) {
                    if(in==null)throw new IOException("Файл не открыт");
                    ByteArrayOutputStream buffer=new ByteArrayOutputStream();
                    byte[] chunk=new byte[4096];int n;
                    while((n=in.read(chunk))!=-1){buffer.write(chunk,0,n);if(buffer.size()>262144)throw new IOException("Файл больше 256 КиБ");}
                    content=buffer.toString("UTF-8");
                }
                List<Target> imported=Target.parseLines(Csv.importTargets(content));
                new AlertDialog.Builder(this).setTitle("Импорт "+imported.size()+" адресов")
                    .setMessage("Заменить список? Порядок берётся из файла. Для совпавших адресов сохранится признак «Не опрашивать». Статистика остаётся для совпавших адресов.")
                    .setPositiveButton("Заменить",(d,w)->{
                        Map<String,Boolean> flags=new HashMap<>();
                        for(Store.Item i:config.items)flags.put(i.target.value,i.enabled);
                        config.items.clear();for(Target t:imported)config.items.add(new Store.Item(t,flags.getOrDefault(t.value,true)));
                        saveAndReload();rebuildCards();
                    }).setNegativeButton("Отмена",null).show();
            } else if(request==EXPORT) {
                Map<String,Health> results=store.loadResults();
                StringBuilder csv=new StringBuilder("\uFEFF");
                csv.append(Csv.row("№","Адрес","Опрашивать","Статус","Последнее, мс","Среднее 10, мс","Мин, мс","Макс, мс","Ошибки, %","Запросов","Ошибок","Подряд","Обновлено","Описание"));
                int i=0;
                for(Store.Item item:config.items) {
                    Health h=results.getOrDefault(item.target.value,new Health());
                    String status=!item.enabled?"Не опрашивается":h.updated==0?"Ожидание":
                        System.currentTimeMillis()-h.updated>staleMillis()?"Данные устарели":Health.label(h.kind);
                    csv.append(Csv.row(++i,item.target.value,item.enabled?"Да":"Нет",status,
                        h.history.isEmpty()?-1:h.history.get(h.history.size()-1),number(h.average10()),h.min,h.max,
                        number(h.loss()),h.attempts,h.failures,h.streak,h.updated==0?"":time(h.updated),h.detail));
                }
                try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")) {
                    if(out==null)throw new IOException("Файл не открыт");
                    out.write(csv.toString().getBytes(StandardCharsets.UTF_8));
                }
                Toast.makeText(this,"Отчёт сохранён",Toast.LENGTH_SHORT).show();
            } else if(request==PNG && pendingBitmap!=null) {
                try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")) {
                    if(out==null)throw new IOException("Файл не открыт");
                    pendingBitmap.compress(Bitmap.CompressFormat.PNG,100,out);
                }
                pendingBitmap.recycle();pendingBitmap=null;
                Toast.makeText(this,"PNG сохранён",Toast.LENGTH_SHORT).show();
            }
        }catch(Exception e){alert("Ошибка: "+e.getMessage());}
    }
    private void alert(String message){new AlertDialog.Builder(this).setMessage(message).setPositiveButton("OK",null).show();}
    @Override protected void onResume(){super.onResume();handler.removeCallbacks(refresh);handler.post(refresh);}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacks(refresh);super.onDestroy();}
}
