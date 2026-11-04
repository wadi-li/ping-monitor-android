package ru.limar.pingmonitor;

import java.util.*;

public final class Csv {
    public static String field(Object value) {
        String s=String.valueOf(value);
        // Keep imported target names from becoming spreadsheet formulas.
        if(s.matches("^[=+@\\-].*")) s="'"+s;
        return "\""+s.replace("\"","\"\"")+"\"";
    }
    public static String row(Object... values) {
        StringJoiner j=new StringJoiner(";");
        for(Object value:values) j.add(field(value));
        return j+"\r\n";
    }
    /** RFC-style quoting, semicolon delimiter, multiline fields and UTF-8 BOM. */
    public static List<List<String>> parse(String input) {
        String s=input.startsWith("\uFEFF")?input.substring(1):input;
        List<List<String>> rows=new ArrayList<>(); List<String> row=new ArrayList<>();
        StringBuilder cell=new StringBuilder(); boolean quoted=false;
        for(int i=0;i<s.length();i++) {
            char c=s.charAt(i);
            if(c=='"') {
                if(quoted && i+1<s.length() && s.charAt(i+1)=='"') {cell.append('"');i++;}
                else quoted=!quoted;
            } else if(!quoted && (c==';' || c=='\r' || c=='\n')) {
                row.add(cell.toString()); cell.setLength(0);
                if(c!=';') {
                    rows.add(row); row=new ArrayList<>();
                    if(c=='\r' && i+1<s.length() && s.charAt(i+1)=='\n') i++;
                }
            } else cell.append(c);
        }
        if(quoted) throw new IllegalArgumentException("CSV: незакрытая кавычка");
        if(cell.length()>0 || !row.isEmpty()) {row.add(cell.toString());rows.add(row);}
        return rows;
    }
    public static String importTargets(String input) {
        List<List<String>> rows=parse(input);
        LinkedHashSet<String> result=new LinkedHashSet<>();
        boolean report=!rows.isEmpty() && rows.get(0).size()>1 &&
            (rows.get(0).get(0).equals("№") || rows.get(0).get(0).equals("Адрес"));
        for(int i=report?1:0;i<rows.size();i++) {
            List<String> row=rows.get(i);
            if(report) {
                if(row.stream().allMatch(v->v.trim().isEmpty()))continue;
                int column=rows.get(0).get(0).equals("№")?1:0;
                if(row.size()<=column)throw new IllegalArgumentException("CSV: нет адреса в строке "+(i+1));
                String v=row.get(column);
                if(!v.trim().isEmpty()) result.add(Target.parse(v).value);
            } else for(String v:row) if(!v.trim().isEmpty()) result.add(Target.parse(v).value);
        }
        return String.join("\n",result);
    }
    /** Portable address list: order and enabled flags, without accumulated statistics. */
    public static String serverList(Map<String,Boolean> servers) {
        StringBuilder out=new StringBuilder("\uFEFF").append(row("№","Адрес","Опрашивать"));
        int index=0;
        for(Map.Entry<String,Boolean> entry:servers.entrySet())
            out.append(row(++index,entry.getKey(),entry.getValue()?"Да":"Нет"));
        return out.toString();
    }
    /** Plain TXT/legacy lists have no flags; preserve existing flags when importing them. */
    public static Map<String,Boolean> importEnabled(String input) {
        List<List<String>> rows=parse(input);
        Map<String,Boolean> flags=new LinkedHashMap<>();
        if(rows.isEmpty())return flags;
        List<String> header=rows.get(0);
        int url=header.indexOf("Адрес"),enabled=header.indexOf("Опрашивать");
        if(url<0 || enabled<0)return flags;
        for(int i=1;i<rows.size();i++) {
            List<String> row=rows.get(i);
            if(row.stream().allMatch(v->v.trim().isEmpty()))continue;
            if(row.size()<=Math.max(url,enabled))throw new IllegalArgumentException("CSV: неполная строка "+(i+1));
            String flag=row.get(enabled).trim().toLowerCase(Locale.ROOT);
            boolean on=Arrays.asList("да","true","1").contains(flag);
            if(!on && !Arrays.asList("нет","false","0").contains(flag))
                throw new IllegalArgumentException("CSV: в поле «Опрашивать» ожидается Да или Нет, строка "+(i+1));
            flags.put(Target.parse(row.get(url)).value,on);
        }
        return flags;
    }
}
