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

    private static boolean isCjk(int cp) {
        Character.UnicodeScript s=Character.UnicodeScript.of(cp);
        return s==Character.UnicodeScript.HAN||s==Character.UnicodeScript.HIRAGANA||s==Character.UnicodeScript.KATAKANA||s==Character.UnicodeScript.HANGUL;
    }

    /** 估算 token 数：CJK 每个码点≈1 token，拉丁/数字按每 4 字符≈1 token。 */
    public static int estimateTokens(String s) {
        int tokens=0;
        for(int i=0;i<s.length();) {
            int cp=s.codePointAt(i);
            if(isCjk(cp)){tokens++;i+=Character.charCount(cp);}
            else{
                int j=i;
                while(j<s.length() && !isCjk(s.codePointAt(j)) && !Character.isWhitespace(s.charAt(j))) j+=Character.charCount(s.codePointAt(j));
                if(j==i){j+=Character.charCount(s.codePointAt(i));}
                tokens+=Math.max(1,(j-i+3)/4);
                i=j;
            }
        }
        return tokens;
    }

    /** 按 token 预算切块（带重叠）。 */
    public static List<String> splitByTokens(String text,int maxTokens,int overlapTokens) {
        if(maxTokens<=0 || overlapTokens<0 || overlapTokens>=maxTokens) throw new IllegalArgumentException("分块配置错误");
        String normalized=text.replace("\u0000","").replace("\r\n","\n").strip();
        List<String> result=new ArrayList<>();
        int n=normalized.length();
        int start=0;
        while(start<n) {
            int end=start, tokens=0;
            while(end<n && tokens<maxTokens) {
                int cp=normalized.codePointAt(end);
                if(isCjk(cp)){tokens++;end+=Character.charCount(cp);}
                else{
                    int j=end;
                    while(j<n && !isCjk(normalized.codePointAt(j)) && !Character.isWhitespace(normalized.charAt(j))) j+=Character.charCount(normalized.codePointAt(j));
                    if(j==end)j+=Character.charCount(normalized.codePointAt(end));
                    tokens+=Math.max(1,(j-end+3)/4);
                    end=j;
                }
            }
            String part=normalized.substring(start,end).strip();
            if(!part.isEmpty())result.add(part);
            if(end>=n)break;
            // 按重叠 token 数回退起始位置
            start=end;
            int back=0;
            while(start>0 && back<overlapTokens) {
                int cp=normalized.codePointAt(start-1);
                int cc=Character.charCount(cp);
                if(isCjk(cp))back++;else back+=Math.max(1,(cc+3)/4);
                if(back>=overlapTokens){start-=cc;break;}
                start-=cc;
            }
            if(start<=0){start=end;}
            if(start==end)break; // 防死循环
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

    /** 词频统计（与 terms 使用同一分词规则，但保留计数）。 */
    public static Map<String,Integer> termFreqs(String input) {
        Map<String,Integer> freq=new HashMap<>();
        for(String word:input.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if(word.isBlank())continue;
            if(word.codePoints().anyMatch(c -> Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN)) {
                int[] cp=word.codePoints().toArray();
                if(cp.length==1)freq.merge(word,1,Integer::sum);
                for(int i=0;i<cp.length-1;i++)freq.merge(new String(cp,i,2),1,Integer::sum);
            } else freq.merge(word,1,Integer::sum);
        }
        return freq;
    }

    /** BM25 打分。query/doc 为词频表，df 为文档频率，totalDocs 为总文档数，avgLen 为平均文档长度（按词数）。 */
    public static double bm25(Map<String,Integer> query,Map<String,Integer> doc,int docLen,double avgLen,Map<String,Integer> df,int totalDocs) {
        if(totalDocs<=0)return 0;
        double k1=1.2,b=0.75,score=0;
        for(var e:query.entrySet()) {
            Integer f=doc.get(e.getKey());if(f==null)continue;
            int dfTerm=df.getOrDefault(e.getKey(),0);if(dfTerm<=0)continue;
            double idf=Math.log(1.0+(totalDocs-dfTerm+0.5)/(dfTerm+0.5));
            double tf=f.doubleValue();
            score+=idf*(tf*(k1+1))/(tf+k1*(1-b+b*docLen/avgLen));
        }
        return score;
    }
}
