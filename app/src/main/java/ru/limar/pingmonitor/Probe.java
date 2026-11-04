package ru.limar.pingmonitor;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native HTTP/TCP/ICMP transport. TLS certificate and hostname validation stay enabled. */
public final class Probe implements Callable<Probe.Answer> {
    public static final int OK=0, WARN=1, DOWN=2;
    private static final ExecutorService DISCONNECTORS=new ThreadPoolExecutor(
        2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(100),
        r->{Thread t=new Thread(r,"transport-cancel");t.setDaemon(true);return t;},
        new ThreadPoolExecutor.DiscardPolicy());
    public static final class Answer {
        public final int kind;
        public final long ms;
        public final String detail;
        public Answer(int kind,long ms,String detail) { this.kind=kind; this.ms=ms; this.detail=detail; }
    }
    private final Target target;
    private final int timeout;
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private volatile HttpURLConnection connection;
    private volatile Socket socket;
    private volatile Process process;
    public Probe(Target target,int timeout) { this.target=target; this.timeout=timeout; }
    public void cancel() {
        cancelled.set(true);
        // Some HTTP implementations can block in disconnect. Never do it on Android's UI thread.
        HttpURLConnection c=connection; if(c!=null) DISCONNECTORS.execute(c::disconnect);
        Socket s=socket; if(s!=null) try{s.close();}catch(IOException ignored){}
        Process p=process; if(p!=null) p.destroy();
    }
    private void check() throws InterruptedIOException {
        if(cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Остановлено");
    }
    @Override public Answer call() {
        long start=System.nanoTime();
        try {
            check();
            if(target.scheme.equals("tcp")) {
                socket=new Socket();
                check();
                socket.connect(new InetSocketAddress(target.host,target.port),timeout);
                check();
                return new Answer(OK,elapsed(start),"TCP "+target.port+" открыт");
            }
            if(target.scheme.equals("icmp")) return icmp(start);
            return http(start);
        } catch(Exception e) {
            String msg;
            if(e instanceof UnknownHostException) msg="DNS: имя не найдено";
            else if(e instanceof javax.net.ssl.SSLException) msg="TLS: "+e.getClass().getSimpleName();
            else if(e instanceof SocketTimeoutException || e instanceof InterruptedIOException) msg="Таймаут";
            else if(e instanceof ConnectException) msg="Соединение отклонено";
            else msg=e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage());
            return new Answer(DOWN,-1,msg.length()>160?msg.substring(0,160):msg);
        } finally {
            HttpURLConnection c=connection; if(c!=null)c.disconnect();
            Socket s=socket; if(s!=null)try{s.close();}catch(IOException ignored){}
            Process p=process; if(p!=null)p.destroy();
        }
    }
    private Answer http(long start) throws Exception {
        URL url=new URL(target.value);
        boolean get=false;
        int redirects=0;
        while(true) {
            check();
            connection=(HttpURLConnection)url.openConnection();
            check();
            int left=(int)Math.max(1,timeout-elapsed(start));
            if(left<=1) throw new SocketTimeoutException();
            connection.setConnectTimeout(left); connection.setReadTimeout(left);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(get?"GET":"HEAD");
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent","LimarPingMonitor/1.0");
            connection.setRequestProperty("Cache-Control","no-cache");
            if(get) connection.setRequestProperty("Range","bytes=0-0");
            int code=connection.getResponseCode();
            check();
            String location=connection.getHeaderField("Location");
            connection.disconnect();
            if(!get && (code==405 || code==501)) { get=true; continue; }
            if(code>=300 && code<=399 && location!=null) {
                if(++redirects>5) return new Answer(DOWN,-1,"Слишком много перенаправлений");
                URL next=new URL(url,location);
                if(!next.getProtocol().equals("http") && !next.getProtocol().equals("https"))
                    return new Answer(DOWN,-1,"Неподдерживаемое перенаправление");
                if(url.getProtocol().equals("https") && next.getProtocol().equals("http"))
                    return new Answer(WARN,elapsed(start),"HTTPS → HTTP: небезопасный редирект");
                url=next; continue;
            }
            if(code>=200 && code<400) return new Answer(OK,elapsed(start),"HTTP "+code);
            if(code==401 || code==403 || code==429)
                return new Answer(WARN,elapsed(start),"HTTP "+code+" (доступ ограничен)");
            return new Answer(DOWN,-1,"HTTP "+code);
        }
    }
    private Answer icmp(long start) throws Exception {
        String binary=new File("/system/bin/ping").exists()?"/system/bin/ping":"ping";
        ProcessBuilder builder=new ProcessBuilder(binary,"-n","-c","1","-W",
            String.valueOf(Math.max(1,(timeout+999)/1000)),target.host);
        builder.redirectErrorStream(true);
        process=builder.start();
        check();
        if(!process.waitFor(timeout,TimeUnit.MILLISECONDS)) {
            process.destroy();
            return new Answer(DOWN,-1,"ICMP: таймаут");
        }
        byte[] bytes=new byte[4096];
        int count=process.getInputStream().read(bytes);
        String output=new String(bytes,0,Math.max(0,count),StandardCharsets.UTF_8);
        if(process.exitValue()!=0) {
            if(output.toLowerCase(Locale.ROOT).contains("permitted") || output.contains("Permission"))
                return new Answer(WARN,-1,"ICMP запрещён системой; используйте TCP/HTTPS");
            return new Answer(DOWN,-1,"ICMP: нет ответа (возможна фильтрация)");
        }
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("time[=<]([0-9.]+)").matcher(output);
        long ms=m.find()?Math.max(1,Math.round(Double.parseDouble(m.group(1)))):elapsed(start);
        return new Answer(OK,ms,"ICMP echo reply");
    }
    private static long elapsed(long start) { return Math.max(1,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)); }
}
