package ru.limar.pingmonitor;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.util.*;

final class Sparkline extends View {
    List<Long> points=Collections.emptyList();
    int lineColor=MonitorService.GREEN;
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    Sparkline(Context c) {super(c);setContentDescription("График последних 50 измерений; ошибки показаны разрывами");}
    void set(Health h,int color) {points=new ArrayList<>(h.history);lineColor=color;invalidate();}
    @Override protected void onDraw(Canvas canvas) {super.onDraw(canvas);drawGraph(canvas,getWidth(),getHeight());}
    void drawGraph(Canvas c,int w,int h) {
        float density=getResources().getDisplayMetrics().density;
        float pad=5*density, bottom=h-17*density, top=5*density;
        long max=1;int first=Math.max(0,points.size()-50);
        for(int i=first;i<points.size();i++)max=Math.max(max,points.get(i));
        p.setColor(0xffc4cdd1);p.setStrokeWidth(density);c.drawLine(pad,bottom,w-pad,bottom,p);
        p.setTextSize(10*density);p.setColor(0xff7d8c93);
        c.drawText("0",pad,h-2*density,p);
        String label=max+" мс • последние "+Math.min(50,points.size());
        c.drawText(label,Math.max(pad,w-pad-p.measureText(label)),h-2*density,p);
        p.setColor(lineColor);p.setStrokeWidth(2*density);p.setStyle(Paint.Style.STROKE);
        Path path=new Path();boolean previous=false;
        int count=points.size()-first;
        for(int i=0;i<count;i++) {
            long v=points.get(first+i);
            float x=pad+(w-2*pad)*i/Math.max(1,count-1);
            if(v<0){previous=false;continue;}
            float y=bottom-(bottom-top)*v/max;
            if(previous)path.lineTo(x,y);else {path.moveTo(x,y);c.drawCircle(x,y,2*density,p);}
            previous=true;
        }
        c.drawPath(path,p);p.setStyle(Paint.Style.FILL);
    }
    Bitmap bitmap() {
        Bitmap b=Bitmap.createBitmap(1200,400,Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(b);c.drawColor(Color.WHITE);drawGraph(c,1200,400);return b;
    }
}
