package com.enterprise.knowledge;
import java.util.*;

public final class TextChunks {
    private TextChunks() {}
    public static List<String> split(String text,int size,int overlap) {
        if(size<=0 || overlap<0 || overlap>=size) throw new IllegalArgumentException("分块配置错误");
        String normalized=text.replace("\u0000","").replace("\r\n","\n").strip();
        List<String> result=new ArrayList<>();
        for(int start=0;start<normalized.length();) {
            int end=Math.min(start+size,normalized.length());
            if(end<normalized.length() && Character.isHighSurrogate(normalized.charAt(end-1))) end--;
            String part=normalized.substring(start,end).strip();if(!part.isEmpty())result.add(part);
            if(end==normalized.length())break;
            start=end-overlap;
            if(start>0 && Character.isLowSurrogate(normalized.charAt(start)))start++;
        }
        return result;
    }
    public static double cosine(double[] a,double[] b) {
        if(a.length!=b.length) return 0;
        double dot=0,aa=0,bb=0;for(int i=0;i<a.length;i++){dot+=a[i]*b[i];aa+=a[i]*a[i];bb+=b[i]*b[i];}
        return aa==0 || bb==0?0:dot/Math.sqrt(aa*bb);
    }
    public static Set<String> terms(String input) {
        Set<String> terms=new HashSet<>();
        for(String word:input.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if(word.isBlank())continue;
            if(word.codePoints().anyMatch(c -> Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN)) {
                int[] cp=word.codePoints().toArray();
                if(cp.length==1)terms.add(word);
                for(int i=0;i<cp.length-1;i++)terms.add(new String(cp,i,2));
            } else terms.add(word);
        }
        return terms;
    }
}
