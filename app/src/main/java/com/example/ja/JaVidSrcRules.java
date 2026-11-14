package com.example.ja;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.Consumer;

/** Shared parsing and freshness policy. ID membership never proves stream playback. */
public final class JaVidSrcRules {
    public static final long DAY_MS = 86_400_000L;
    public static final long RETRY_MS = 900_000L;
    public static final long MAX_BYTES = 64L * 1024 * 1024;
    public static final int MOVIE = 1, TV = 2, EPISODE = 3;
    private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("[1-9][0-9]*");
    private static final java.util.regex.Pattern EP = java.util.regex.Pattern.compile("[1-9][0-9]*_[0-9]+x[1-9][0-9]*");
    private JaVidSrcRules() {}
    public static final class Key {
        public final int id, season, episode;
        Key(int id, int season, int episode) { this.id=id; this.season=season; this.episode=episode; }
    }
    public static boolean due(long checked, long retryAfter, long now) {
        if (retryAfter > now && retryAfter - now <= RETRY_MS) return false;
        return checked <= 0 || now < checked || now - checked >= DAY_MS;
    }
    public static boolean safeCount(long previous, long next) {
        return next > 0 && (previous <= 0 || next * 2 >= previous);
    }
    public static String quality(String value) {
        String q = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (q.equals("CAM") || q.equals("DVD") || q.equals("SD") || q.equals("4K") ||
            q.matches("[1-9][0-9]{2,3}P")) return q;
        return "";
    }
    public static Key key(int kind, String raw) throws IOException {
        String s=raw.trim();
        if (s.length()>64) throw new IOException("Overlong ID entry");
        try {
            if (kind==MOVIE || kind==TV) {
                if (!ID.matcher(s).matches()) throw new NumberFormatException();
                return new Key(Integer.parseInt(s),0,0);
            }
            if (kind!=EPISODE || !EP.matcher(s).matches()) throw new NumberFormatException();
            int split=s.indexOf('_'), x=s.indexOf('x',split);
            return new Key(Integer.parseInt(s.substring(0,split)),Integer.parseInt(s.substring(split+1,x)),Integer.parseInt(s.substring(x+1)));
        } catch (NumberFormatException e) { throw new IOException("Invalid VidSrc ID entry"); }
    }
    private static String line(BufferedReader reader) throws IOException {
        StringBuilder value=new StringBuilder(32);int c;
        while((c=reader.read())!=-1) {
            if(c=='\n')break;
            if(value.length()>=65)throw new IOException("Overlong ID entry");
            value.append((char)c);
        }
        return c==-1 && value.length()==0 ? null : value.toString();
    }
    public static long read(InputStream input, int kind, Consumer<Key> sink) throws IOException {
        InputStream limited=new FilterInputStream(input) {
            private long count;
            private void add(int n) throws IOException { if(n>0 && (count+=n)>MAX_BYTES) throw new IOException("ID feed too large"); }
            @Override public int read() throws IOException {int n=in.read(); if(n>=0)add(1);return n;}
            @Override public int read(byte[] b,int off,int len)throws IOException{int n=in.read(b,off,len);add(n);return n;}
        };
        BufferedReader reader=new BufferedReader(new InputStreamReader(limited,StandardCharsets.UTF_8));
        String line; long count=0; boolean first=true;
        while((line=line(reader))!=null) {
            if(first){line=line.replace("\uFEFF","");first=false;}
            if(line.trim().isEmpty()) continue;
            if(++count>4_000_000L)throw new IOException("Too many ID entries");
            sink.accept(key(kind,line));
        }
        if(count==0)throw new IOException("Empty ID feed");
        return count;
    }
}
