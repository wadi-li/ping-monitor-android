package ru.limar.pingmonitor;

import java.util.*;

/** Status policy is independent of Android and covered by JVM regression tests. */
public final class Health {
    public long attempts, failures, min=-1, max=-1, updated;
    public int streak, kind=Probe.WARN;
    public String detail="Ожидание первой проверки";
    public final List<Long> history=new ArrayList<>();
    public final List<Integer> outcomes=new ArrayList<>();
    public void add(Probe.Answer a,int slow,int threshold,long now) {
        attempts++;
        if(a.kind==Probe.DOWN) { failures++; streak++; } else streak=0;
        updated=now; detail=a.detail;
        history.add(a.ms); outcomes.add(a.kind);
        if(history.size()>100) { history.remove(0); outcomes.remove(0); }
        if(a.ms>=0) { min=min<0?a.ms:Math.min(min,a.ms); max=Math.max(max,a.ms); }
        long recentFails=outcomes.subList(Math.max(0,outcomes.size()-20),outcomes.size()).stream().filter(x->x==Probe.DOWN).count();
        int recent=Math.min(20,outcomes.size());
        kind=streak>=threshold?Probe.DOWN:
            (a.kind!=Probe.OK || a.ms>=slow || (recent>0 && recentFails*100>=recent*20)?Probe.WARN:Probe.OK);
    }
    public double average10() {
        return history.subList(Math.max(0,history.size()-10),history.size()).stream()
            .filter(x->x>=0).mapToLong(Long::longValue).average().orElse(-1);
    }
    public double loss() { return attempts==0?0:100.0*failures/attempts; }
    public static String label(int k) { return k==Probe.OK?"В порядке":k==Probe.DOWN?"Не работает":"Есть проблемы"; }
}
