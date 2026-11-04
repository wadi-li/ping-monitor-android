package ru.limar.pingmonitor;

import java.net.IDN;
import java.net.URI;
import java.util.*;

/** Pure Java parser; never converts HTTP to HTTPS or discards the URL path. */
public final class Target {
    public final String value, scheme, host;
    public final int port;
    private Target(String value, String scheme, String host, int port) {
        this.value=value; this.scheme=scheme; this.host=host; this.port=port;
    }
    public static Target parse(String input) {
        String s=input.trim();
        if (s.isEmpty()) throw new IllegalArgumentException("Пустой адрес");
        if (!s.contains("://")) s="https://"+s;
        try {
            URI u=new URI(s);
            String scheme=u.getScheme().toLowerCase(Locale.ROOT);
            if (!Arrays.asList("http","https","tcp","icmp").contains(scheme))
                throw new IllegalArgumentException("Допустимы http, https, tcp, icmp");
            if (u.getRawUserInfo()!=null || u.getRawFragment()!=null)
                throw new IllegalArgumentException("Логин, пароль и #фрагмент в URL не поддерживаются");
            String host=u.getHost();
            // URI#getHost does not recognize Unicode DNS names.
            if (host==null) {
                String a=u.getRawAuthority();
                if(a==null || a.contains("@") || a.contains("[") || a.contains("%"))
                    throw new IllegalArgumentException("Некорректное имя узла");
                int colon=a.lastIndexOf(':');
                String h=colon<0?a:a.substring(0,colon);
                host=IDN.toASCII(h, IDN.USE_STD3_ASCII_RULES);
                String authority=host+(colon<0?"":a.substring(colon));
                u=new URI(scheme+"://"+authority+(u.getRawPath()==null?"":u.getRawPath())+
                    (u.getRawQuery()==null?"":"?"+u.getRawQuery()));
            }
            host=u.getHost();
            if(host==null || host.isEmpty()) throw new IllegalArgumentException("Некорректный узел");
            if(host.startsWith("[")) host=host.substring(1,host.length()-1);
            host=host.toLowerCase(Locale.ROOT);
            int port=u.getPort();
            if(port==0 || port>65535 || port < -1) throw new IllegalArgumentException("Порт: 1–65535");
            if(scheme.equals("tcp") && port<1) throw new IllegalArgumentException("TCP: укажите порт");
            if((scheme.equals("tcp") || scheme.equals("icmp")) &&
                ((u.getRawPath()!=null && !u.getRawPath().isEmpty() && !u.getRawPath().equals("/")) || u.getRawQuery()!=null))
                throw new IllegalArgumentException("Для TCP/ICMP нужен узел, без пути и параметров");
            if(scheme.equals("icmp") && port!=-1) throw new IllegalArgumentException("У ICMP нет порта");
            String authority=(host.contains(":")?"["+host+"]":host)+(port==-1?"":":"+port);
            String path=u.getRawPath();
            String value=scheme+"://"+authority;
            if(scheme.equals("http") || scheme.equals("https"))
                value+=(path==null || path.isEmpty()?"/":path)+(u.getRawQuery()==null?"":"?"+u.getRawQuery());
            return new Target(value,scheme,host,port<0?(scheme.equals("http")?80:443):port);
        } catch(java.net.URISyntaxException e) {
            throw new IllegalArgumentException("Некорректный URL: "+input);
        }
    }
    public static List<Target> parseLines(String text) {
        LinkedHashMap<String,Target> out=new LinkedHashMap<>();
        int line=0;
        for(String s:text.replace("\uFEFF","").split("\\r?\\n")) {
            line++;
            if(s.trim().isEmpty()) continue;
            try { Target t=parse(s); out.put(t.value,t); }
            catch(IllegalArgumentException e) { throw new IllegalArgumentException("Строка "+line+": "+e.getMessage()); }
        }
        if(out.isEmpty()) throw new IllegalArgumentException("Введите хотя бы один адрес");
        if(out.size()>50) throw new IllegalArgumentException("Не более 50 уникальных адресов");
        return new ArrayList<>(out.values());
    }
}
