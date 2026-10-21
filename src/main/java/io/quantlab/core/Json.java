package io.quantlab.core;

import java.nio.charset.StandardCharsets;

/** Minimal JSON writer: no full object mapper or reflection on the hot path. */
public final class Json {
    private Json() { }
    public static String number(double value) { return Double.isFinite(value) ? Double.toString(value) : "null"; }
    public static String quote(String value) {
        if(value == null) return "null";
        StringBuilder b=new StringBuilder("\"");
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            switch(c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default: if(c<32) b.append(String.format("\\u%04x",(int)c)); else b.append(c);
            }
        }
        return b.append('"').toString();
    }
    public static String hex(String text) {
        if(text==null) return "";
        byte[] b=text.getBytes(StandardCharsets.UTF_8);
        char[] out=new char[b.length*2]; String dig="0123456789abcdef";
        for(int i=0;i<b.length;i++){out[2*i]=dig.charAt((b[i]>>>4)&15);out[2*i+1]=dig.charAt(b[i]&15);}
        return new String(out);
    }
    public static String unhex(String s) {
        if(s.length()%2!=0) throw new IllegalArgumentException("Invalid hex");
        byte[] b=new byte[s.length()/2];
        for(int i=0;i<b.length;i++) {
            int a=Character.digit(s.charAt(2*i),16),c=Character.digit(s.charAt(2*i+1),16);
            if(a<0||c<0) throw new IllegalArgumentException("Invalid hex");
            b[i]=(byte)((a<<4)|c);
        }
        return new String(b,StandardCharsets.UTF_8);
    }
}
