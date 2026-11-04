package ru.limar.pingmonitor;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.util.*;

final class Store {
    static final class Item {
        final Target target;
        boolean enabled;
        Item(Target t,boolean enabled) { target=t; this.enabled=enabled; }
    }
    static final class Config {
        final List<Item> items=new ArrayList<>();
        int interval=15, timeout=3000, slow=1000, threshold=3;
        boolean keepAwake=true;
    }
    private final SharedPreferences prefs;
    Store(Context c) { prefs=c.getSharedPreferences("monitor",Context.MODE_PRIVATE); }
    SharedPreferences prefs() { return prefs; }
    Config config() {
        Config c=new Config();
        try {
            JSONArray a=new JSONArray(prefs.getString("targets","[{\"url\":\"https://google.com/\",\"enabled\":true}]"));
            for(int i=0;i<a.length();i++) {
                JSONObject o=a.getJSONObject(i);
                c.items.add(new Item(Target.parse(o.getString("url")),o.optBoolean("enabled",true)));
            }
        } catch(Exception ignored) { c.items.clear(); }
        c.interval=prefs.getInt("interval",15); c.timeout=prefs.getInt("timeout",3000);
        c.slow=prefs.getInt("slow",1000); c.threshold=prefs.getInt("threshold",3);
        c.keepAwake=prefs.getBoolean("keepAwake",true);
        return c;
    }
    void save(Config c) {
        JSONArray a=new JSONArray();
        for(Item i:c.items) try {
            a.put(new JSONObject().put("url",i.target.value).put("enabled",i.enabled));
        } catch(JSONException e) { throw new IllegalStateException(e); }
        prefs.edit().putString("targets",a.toString()).putInt("interval",c.interval)
            .putInt("timeout",c.timeout).putInt("slow",c.slow).putInt("threshold",c.threshold)
            .putBoolean("keepAwake",c.keepAwake).apply();
    }
    static JSONObject encode(Health h) throws JSONException {
        return new JSONObject().put("attempts",h.attempts).put("failures",h.failures)
            .put("min",h.min).put("max",h.max).put("updated",h.updated)
            .put("streak",h.streak).put("kind",h.kind).put("detail",h.detail)
            .put("history",new JSONArray(h.history)).put("outcomes",new JSONArray(h.outcomes));
    }
    static Health decode(JSONObject o) {
        Health h=new Health();
        if(o==null) return h;
        h.attempts=o.optLong("attempts"); h.failures=o.optLong("failures");
        h.min=o.optLong("min",-1); h.max=o.optLong("max",-1); h.updated=o.optLong("updated");
        h.streak=o.optInt("streak"); h.kind=o.optInt("kind",Probe.WARN);
        h.detail=o.optString("detail","Ожидание первой проверки");
        JSONArray a=o.optJSONArray("history"),b=o.optJSONArray("outcomes");
        if(a!=null && b!=null) for(int i=Math.max(0,a.length()-100);i<Math.min(a.length(),b.length());i++) {
            h.history.add(a.optLong(i,-1)); h.outcomes.add(b.optInt(i,Probe.WARN));
        }
        return h;
    }
    Map<String,Health> loadResults() {
        Map<String,Health> m=new LinkedHashMap<>();
        try {
            JSONObject all=new JSONObject(prefs.getString("results","{}"));
            Iterator<String> it=all.keys();
            while(it.hasNext()) {String k=it.next();m.put(k,decode(all.optJSONObject(k)));}
        } catch(JSONException ignored) {}
        return m;
    }
    void saveResults(Map<String,Health> results) {
        JSONObject all=new JSONObject();
        try { for(Map.Entry<String,Health> e:results.entrySet()) all.put(e.getKey(),encode(e.getValue())); }
        catch(JSONException e) { throw new IllegalStateException(e); }
        prefs.edit().putString("results",all.toString()).apply();
    }
}
