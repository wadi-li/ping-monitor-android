package ru.limar.pingmonitor;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class CoreTests {
    static int checks;
    static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    static void bad(String input){try{Target.parse(input);throw new AssertionError("Accepted: "+input);}catch(IllegalArgumentException expected){checks++;}}
    static void eq(Object expected,Object actual,String label){check(Objects.equals(expected,actual),label+" expected="+expected+" got="+actual);}
    public static void main(String[] args) throws Exception {
        eq("https://example.com/",Target.parse("example.com").value,"default HTTPS");
        eq("http://example.com:8080/health?q=%D0%B0",Target.parse("http://EXAMPLE.com:8080/health?q=%D0%B0").value,"preserve HTTP, path, query");
        eq("tcp://192.168.1.1:22",Target.parse("tcp://192.168.1.1:22").value,"TCP");
        eq("icmp://1.1.1.1",Target.parse("icmp://1.1.1.1").value,"ICMP");
        eq("https://xn--e1afmkfd.xn--p1ai/",Target.parse("пример.рф").value,"IDN");
        eq("::1",Target.parse("tcp://[::1]:80").host,"IPv6");
        eq("https://[::1]:8443/health",Target.parse("https://[::1]:8443/health").value,"IPv6 URL");
        for(String s:List.of("ftp://example.com","http://","http://bad host","http://x:0","http://x:65536",
            "https://user:pass@example.com","https://example.com/#fragment","tcp://host","icmp://host:42",
            "icmp://host/path","tcp://host:22/query","http://example.com:abc","-n"))bad(s);
        eq(2,Target.parseLines("\uFEFFexample.com\nexample.com\nhttp://example.com\n\n").size(),"dedup by full URL");
        try{Target.parseLines(" \n");throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
        StringBuilder many=new StringBuilder();
        for(int i=0;i<51;i++)many.append("https://host").append(i).append(".example\n");
        try{Target.parseLines(many.toString());throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
        List<List<String>> csv=Csv.parse("\uFEFF\"a;b\";\"c\"\"d\"\r\n\"line1\nline2\";z\r\n");
        eq("a;b",csv.get(0).get(0),"CSV semicolon quoted");eq("c\"d",csv.get(0).get(1),"CSV quote");
        eq("line1\nline2",csv.get(1).get(0),"CSV multiline");
        eq("\"'=SUM(1)\"",Csv.field("=SUM(1)"),"CSV formula safety");
        eq("https://a.example/\nhttps://b.example/",Csv.importTargets("a.example;b.example"),"legacy row import");
        eq("https://a.example/\nhttps://b.example/",Csv.importTargets("a.example\nb.example\n"),"line import");
        String report="\uFEFF"+Csv.row("№","Адрес","Опрашивать")+Csv.row(1,"http://a.example/health?x=1;y=2","Да");
        eq("http://a.example/health?x=1;y=2",Csv.importTargets(report),"report roundtrip");
        try{Csv.parse("\"broken");throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
        Map<String,Boolean> servers=new LinkedHashMap<>();
        servers.put("http://b.example/check?a=1;b=2",false);servers.put("https://a.example/",true);
        String savedList=Csv.serverList(servers);
        check(savedList.startsWith("\uFEFF"),"server list UTF-8 BOM");
        eq(new ArrayList<>(servers.keySet()),Arrays.asList(Csv.importTargets(savedList).split("\\n")),"server list order roundtrip");
        eq(servers,Csv.importEnabled(savedList),"server list enabled flags roundtrip");
        check(Csv.importEnabled("a.example\nb.example").isEmpty(),"TXT does not overwrite existing flags");
        check(Csv.importEnabled("№;Домен;Средний\n1;a.example;10").isEmpty(),"legacy report has no enabled flags");
        eq("https://a.example/",Csv.importTargets("№;Адрес;Опрашивать\n\n1;a.example;Да\n"),"skip blank CSV row");
        for(String broken:List.of("№;Адрес;Опрашивать\n1;a.example;maybe\n","№;Адрес;Опрашивать\n1\n")) {
            try{Csv.importEnabled(broken);throw new AssertionError("accepted malformed enabled flag");}
            catch(IllegalArgumentException expected){checks++;}
        }
        try{Csv.importTargets("№;Адрес\n1\n");throw new AssertionError("accepted missing address");}
        catch(IllegalArgumentException expected){checks++;}
        Health h=new Health();
        eq(Probe.WARN,h.kind,"initial not green");
        h.add(new Probe.Answer(Probe.OK,25,"ok"),1000,3,1);
        eq(Probe.OK,h.kind,"success");
        h.add(new Probe.Answer(Probe.DOWN,-1,"error"),1000,3,2);eq(Probe.WARN,h.kind,"first failure");
        h.add(new Probe.Answer(Probe.DOWN,-1,"error"),1000,3,3);eq(Probe.WARN,h.kind,"second failure");
        h.add(new Probe.Answer(Probe.DOWN,-1,"error"),1000,3,4);eq(Probe.DOWN,h.kind,"third down");
        h.add(new Probe.Answer(Probe.OK,15,"ok"),1000,3,5);eq(Probe.WARN,h.kind,"recovered but recent losses");
        eq(0,h.streak,"reset streak");eq(60.0,h.loss(),"loss");eq(20.0,h.average10(),"average excludes failures");
        for(int i=0;i<21;i++)h.add(new Probe.Answer(Probe.OK,10,"ok"),1000,3,6+i);
        eq(Probe.OK,h.kind,"rolling loss recovery");eq(10L,h.min,"min");eq(25L,h.max,"max");
        h.add(new Probe.Answer(Probe.OK,1000,"ok"),1000,3,99);eq(Probe.WARN,h.kind,"slow");
        h.add(new Probe.Answer(Probe.WARN,10,"403"),1000,3,100);eq(Probe.WARN,h.kind,"HTTP limited");eq(0,h.streak,"warn not down");
        for(int i=0;i<110;i++)h.add(new Probe.Answer(Probe.OK,10,"ok"),1000,3,101+i);
        eq(100,h.history.size(),"bounded history");eq(100,h.outcomes.size(),"bounded outcomes");
        Health seven=new Health();eq(-1.0,seven.average7(),"average7 empty");
        for(long value:new long[]{10,20,-1,30,40,50,60,70,80,-1,-1})
            seven.add(new Probe.Answer(value<0?Probe.DOWN:Probe.OK,value,"test"),1000,3,1);
        eq(50.0,seven.average7(),"last seven measured responses, skip timeouts and oldest response");
        Health sparse=new Health();sparse.history.add(-1L);sparse.history.add(0L);sparse.history.add(20L);
        eq(10.0,sparse.average7(),"average7 fewer than seven and zero measurement");
        sparse.history.clear();sparse.history.add(-1L);eq(-1.0,sparse.average7(),"average7 all timeouts");
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        ExecutorService serverPool=Executors.newCachedThreadPool();server.setExecutor(serverPool);
        server.createContext("/ok",e->{e.sendResponseHeaders(204,-1);e.close();});
        server.createContext("/bad",e->{e.sendResponseHeaders(500,-1);e.close();});
        server.createContext("/missing",e->{e.sendResponseHeaders(404,-1);e.close();});
        server.createContext("/forbidden",e->{e.sendResponseHeaders(403,-1);e.close();});
        server.createContext("/limited",e->{e.sendResponseHeaders(429,-1);e.close();});
        server.createContext("/get",e->{e.sendResponseHeaders(e.getRequestMethod().equals("HEAD")?405:200,-1);e.close();});
        server.createContext("/redirect",e->{e.getResponseHeaders().add("Location","/ok");e.sendResponseHeaders(302,-1);e.close();});
        server.createContext("/loop",e->{e.getResponseHeaders().add("Location","/loop");e.sendResponseHeaders(302,-1);e.close();});
        server.createContext("/slow",e->{try{Thread.sleep(1200);e.sendResponseHeaders(200,-1);}catch(Exception ignored){}finally{e.close();}});
        server.start();
        String base="http://127.0.0.1:"+server.getAddress().getPort();
        try {
            for(String path:List.of("/ok","/get","/redirect")) {
                Probe.Answer a=new Probe(Target.parse(base+path),1000).call();
                eq(Probe.OK,a.kind,path);check(a.ms>=1,"latency positive");
            }
            for(String path:List.of("/bad","/missing","/loop"))eq(Probe.DOWN,new Probe(Target.parse(base+path),1000).call().kind,path);
            for(String path:List.of("/forbidden","/limited"))eq(Probe.WARN,new Probe(Target.parse(base+path),1000).call().kind,path);
            long start=System.nanoTime();
            eq(Probe.DOWN,new Probe(Target.parse(base+"/slow"),150).call().kind,"timeout");
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1000,"timeout bounded");
            eq(Probe.OK,new Probe(Target.parse("tcp://127.0.0.1:"+server.getAddress().getPort()),1000).call().kind,"TCP open");
            int unused;
            try(ServerSocket s=new ServerSocket(0)){unused=s.getLocalPort();}
            eq(Probe.DOWN,new Probe(Target.parse("tcp://127.0.0.1:"+unused),500).call().kind,"TCP closed");
            Probe p=new Probe(Target.parse(base+"/ok"),1000);p.cancel();eq(Probe.DOWN,p.call().kind,"cancelled before start");
            ExecutorService cancelPool=Executors.newSingleThreadExecutor();
            try {
                Probe slow=new Probe(Target.parse(base+"/slow"),2500);
                Future<Probe.Answer> f=cancelPool.submit(slow);
                Thread.sleep(80);
                long cancelStart=System.nanoTime();
                slow.cancel();
                check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-cancelStart)<200,"cancel does not block caller");
                eq(Probe.DOWN,f.get(3,TimeUnit.SECONDS).kind,"in-flight cancellation");
            } finally {cancelPool.shutdownNow();}
        } finally {server.stop(0);serverPool.shutdownNow();}
        System.out.println("PASS: "+checks+" checks, including real local HTTP/TCP integration");
    }
}
