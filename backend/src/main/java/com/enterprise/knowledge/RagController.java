package com.enterprise.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api")
public class RagController {
    private final JdbcTemplate db;private final AccessService access;private final ModelSettings settings;private final ObjectMapper json;private final boolean postgres;
    private final ExecutorService executor=Executors.newFixedThreadPool(8);
    private final ConversationAccess conversations;
    @jakarta.annotation.PreDestroy void stop(){executor.shutdownNow();}
    public RagController(JdbcTemplate db,AccessService a,ModelSettings m,ObjectMapper j,Environment env,ConversationAccess conversations){this.conversations=conversations;this.db=db;access=a;settings=m;json=j;postgres=Arrays.asList(env.getActiveProfiles()).contains("postgres");}
    record Query(@Size(max=36) String kbId,@NotBlank @Size(max=2000) String question,@Min(1) @Max(10) int topK,@Size(max=36) String conversationId,@Size(max=200) String title) {}
    record Citation(int index,String kbId,String kbName,String documentId,String documentName,int ordinal,String content,double score,Integer page) {}
    record Answer(String answer,String mode,List<Citation> citations,String conversationId) {}
    @GetMapping("/system") Map<String,Object> system(){var c=settings.current();return Map.of("modelEnabled",c.enabled(),"embeddingEnabled",c.embeddingEnabled(),"embeddingModel",c.embedding().model(),"storage",postgres?"PostgreSQL + pgvector":"H2 本地开发");}
    @PostMapping("/search") List<Citation> search(@Valid @RequestBody Query q,Authentication a) throws Exception {checkScope(q,a);access.audit(a,"SEARCH",scopeName(q));return retrieve(q,settings.client(),a);}
    @PostMapping("/chat") Answer chat(@Valid @RequestBody Query q,Authentication a) throws Exception {
        checkScope(q,a);checkConversation(q,a);access.audit(a,"CHAT",scopeName(q));var model=settings.client();var citations=retrieve(q,model,a);var history=loadHistory(q.conversationId(),a);
        String answer;String mode;
        if(citations.isEmpty()){answer="没有找到足够相关的知识。请上传资料，或换一种提问方式。";mode=model.enabled?"RAG":"KEYWORD";}
        else if(!model.enabled){answer="当前未启用 AI 回答。以下是检索到的原文片段，管理员可在模型与部署页面配置并启用聊天模型。";mode=model.embeddingEnabled?"VECTOR":"KEYWORD";}
        else{
            StringBuilder context=new StringBuilder();for(var c:citations)context.append("[").append(c.index()).append("] ").append(c.kbName()).append(" / ").append(c.documentName()).append(" / 分块 ").append(c.ordinal()).append("\n").append(c.content()).append("\n\n");
            try{answer=model.answer(q.question(),context.toString(),history);mode="RAG";}
            catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"生成模型不可用，请检查配置或使用检索测试");}
        }
        String conversationId=persist(q,a,answer,mode,citations);
        return new Answer(answer,mode,citations,conversationId);
    }
    @PostMapping("/chat/stream")
    SseEmitter chatStream(@Valid @RequestBody Query q,Authentication a) {
        checkScope(q,a);checkConversation(q,a);
        SseEmitter emitter=new SseEmitter(120000L);
        executor.execute(() -> {
            try {
                checkScope(q,a);access.audit(a,"CHAT",scopeName(q));
                var model=settings.client();var citations=retrieve(q,model,a);var history=loadHistory(q.conversationId(),a);
                String convId=resolveConversation(q,a);
                String answer;String mode;
                if(citations.isEmpty()){answer="没有找到足够相关的知识。请上传资料，或换一种提问方式。";mode=model.enabled?"RAG":"KEYWORD";sendMeta(emitter,convId,mode,citations);sendDelta(emitter,answer);}
                else if(!model.enabled){answer="当前未启用 AI 回答。以下是检索到的原文片段，管理员可在模型与部署页面配置并启用聊天模型。";mode=model.embeddingEnabled?"VECTOR":"KEYWORD";sendMeta(emitter,convId,mode,citations);sendDelta(emitter,answer);}
                else{
                    StringBuilder context=new StringBuilder();for(var c:citations)context.append("[").append(c.index()).append("] ").append(c.kbName()).append(" / ").append(c.documentName()).append(" / 分块 ").append(c.ordinal()).append("\n").append(c.content()).append("\n\n");
                    mode="RAG";sendMeta(emitter,convId,mode,citations);
                    StringBuilder full=new StringBuilder();
                    model.streamAnswer(q.question(),context.toString(),history,chunk->{full.append(chunk);try{sendDelta(emitter,chunk);}catch(IOException e){throw new RuntimeException(e);}});
                    answer=full.toString();
                    if(answer.isBlank())throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"模型返回了空回答");
                }
                saveExchange(convId,q,answer,mode,citations);
                emitter.send(SseEmitter.event().name("done").data("{}"));
                emitter.complete();
            } catch(Exception e) {
                try {
                    String msg=e instanceof ResponseStatusException?((ResponseStatusException)e).getReason():"处理失败，请检查模型服务或稍后重试";
                    emitter.send(SseEmitter.event().name("error").data(json.writeValueAsString(Map.of("message",msg))));
                } catch(Exception ignored) {}
                emitter.complete();
            }
        });
        return emitter;
    }
    private void sendMeta(SseEmitter emitter,String convId,String mode,List<Citation> citations) throws IOException {
        emitter.send(SseEmitter.event().name("meta").data(json.writeValueAsString(Map.of("conversationId",convId,"mode",mode,"citations",citations))));
    }
    private void sendDelta(SseEmitter emitter,String text) throws IOException {
        emitter.send(SseEmitter.event().name("delta").data(json.writeValueAsString(Map.of("text",text))));
    }
    private List<Map<String,String>> loadHistory(String convId,Authentication a) {
        if(convId==null||convId.isBlank())return List.of();
        conversations.requireReadable(convId,a);
        var msgs=db.queryForList("SELECT role,content FROM conversation_message WHERE conversation_id = ? AND role IN ('user','assistant') ORDER BY seq DESC LIMIT 12",convId);
        List<Map<String,String>> history=new ArrayList<>();
        for(int i=msgs.size()-1;i>=0;i--) {
            var m=msgs.get(i);String role=(String)m.get("role");String content=(String)m.get("content");
            if(content!=null&&!content.isBlank())history.add(Map.of("role",role,"content",content));
        }
        return history;
    }
    private String resolveConversation(Query q,Authentication a) {
        String uid=access.userId(a);
        String convId=q.conversationId();
        if(convId==null||convId.isBlank()){
            convId=UUID.randomUUID().toString();
            String title=(q.title()!=null&&!q.title().isBlank())?q.title().strip():defaultTitle(q.question());
            db.update("INSERT INTO conversation(id,user_id,title) VALUES(?,?,?)",convId,uid,title);
        }else{
            var rows=db.queryForList("SELECT user_id FROM conversation WHERE id = ?",convId);
            if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"会话不存在");
            if(!rows.get(0).get("user_id").equals(uid))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"无权访问此会话");
        }
        return convId;
    }
    private void saveExchange(String convId,Query q,String answer,String mode,List<Citation> citations) throws Exception {
        db.update("INSERT INTO conversation_message(id,conversation_id,role,content) VALUES(?,?,?,?)",UUID.randomUUID().toString(),convId,"user",q.question());
        db.update("INSERT INTO conversation_message(id,conversation_id,role,content,mode,citations) VALUES(?,?,?,?,?,?)",UUID.randomUUID().toString(),convId,"assistant",answer,mode,json.writeValueAsString(citations));
        db.update("UPDATE conversation SET updated_at = CURRENT_TIMESTAMP WHERE id = ?",convId);
    }
    private String persist(Query q,Authentication a,String answer,String mode,List<Citation> citations) throws Exception {
        String convId=resolveConversation(q,a);
        saveExchange(convId,q,answer,mode,citations);
        return convId;
    }
    private String defaultTitle(String question) {
        String t=question.replaceAll("\\s+"," ").strip();
        return t.length()<=30?t:t.substring(0,30);
    }
    private void checkConversation(Query q,Authentication a){if(q.conversationId()!=null&&!q.conversationId().isBlank())conversations.requireReadable(q.conversationId(),a);}
    private boolean scoped(Query q){return q.kbId()!=null && !q.kbId().isBlank();}
    private String scopeName(Query q){return scoped(q)?q.kbId():"ALL_ACCESSIBLE";}
    private void checkScope(Query q,Authentication a){if(scoped(q))access.kb(q.kbId(),a,false);}
    private List<Citation> retrieve(Query q,ModelClient model,Authentication a) throws Exception {
        String from=" FROM chunk c JOIN document d ON d.id=c.document_id JOIN knowledge_base k ON k.id=d.kb_id WHERE d.status='READY'";
        List<Object> args=new ArrayList<>();
        if(scoped(q)){from+=" AND k.id=?";args.add(q.kbId());}
        if(!access.admin(a)){
            String user=access.userId(a);
            from+=" AND (k.owner_id=? OR EXISTS (SELECT 1 FROM kb_member m WHERE m.kb_id=k.id AND m.user_id=?))";
            args.add(user);args.add(user);
        }
        String select="SELECT c.*,d.name AS document_name,k.id AS kb_id,k.name AS kb_name"+from;
        Map<String,Integer> queryTerms=TextChunks.termFreqs(q.question());double[] vector;
        try{vector=model.embeddingEnabled?model.embed(q.question()):null;}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"向量模型不可用，请检查模型服务");}
        List<Map<String,Object>> candidates;
        if(postgres && vector!=null) {
            args.add(json.writeValueAsString(vector));
            String predicate=VectorIndexes.predicate(model.signature(),vector.length,"c.");
            String expression=VectorIndexes.expression(model.signature(),vector.length,"c.");
            candidates=db.queryForList(select+" AND "+predicate+" ORDER BY "+expression+" <=> CAST(? AS vector("+vector.length+")) LIMIT 80",args.toArray());
        } else {
            Long count=db.queryForObject("SELECT COUNT(*)"+from,Long.class,args.toArray());
            if(count>20000)throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"本地/关键词检索最多支持 20000 分块，请启用 PostgreSQL 向量检索");
            candidates=db.queryForList(select+" ORDER BY d.created_at,d.id,c.ordinal",args.toArray());
        }
        // BM25 打分：预计算词频、文档长度与文档频率。
        List<Map<String,Integer>> docs=new ArrayList<>();List<Integer> docLens=new ArrayList<>();Map<String,Integer> df=new HashMap<>();
        for(var row:candidates){Map<String,Integer> tf=TextChunks.termFreqs((String)row.get("content"));docs.add(tf);int len=tf.values().stream().mapToInt(Integer::intValue).sum();docLens.add(len);for(String t:tf.keySet())df.merge(t,1,Integer::sum);}
        int total=docs.size();double avgLen=total==0?0:docLens.stream().mapToInt(Integer::intValue).sum()/(double)total;
        List<Double> bm=new ArrayList<>();double maxBm=0;
        for(int i=0;i<total;i++){double s=TextChunks.bm25(queryTerms,docs.get(i),docLens.get(i),avgLen,df,total);bm.add(s);if(s>maxBm)maxBm=s;}
        record Scored(Map<String,Object> row,double score) {}
        List<Scored> ranked=new ArrayList<>();
        for(int i=0;i<candidates.size();i++) {
            var row=candidates.get(i);double bmNorm=maxBm>0?bm.get(i)/maxBm:0;double score=bmNorm;
            if(vector!=null) {
                if(!model.signature().equals(row.get("embedding_model")) || row.get("embedding")==null)continue;
                double semantic=TextChunks.cosine(vector,json.readValue((String)row.get("embedding"),double[].class));
                score=0.8*Math.max(0,semantic)+0.2*bmNorm;
                if(semantic<0.25 && bm.get(i)==0)continue;
            } else if(bm.get(i)<=0)continue;
            ranked.add(new Scored(row,score));
        }
        ranked.sort(Comparator.comparingDouble(Scored::score).reversed());List<Citation> citations=new ArrayList<>();
        for(var hit:ranked.subList(0,Math.min(q.topK(),ranked.size()))) {
            var r=hit.row();Integer page=r.get("page")==null?null:((Number)r.get("page")).intValue();
            citations.add(new Citation(citations.size()+1,(String)r.get("kb_id"),(String)r.get("kb_name"),(String)r.get("document_id"),(String)r.get("document_name"),((Number)r.get("ordinal")).intValue(),(String)r.get("content"),Math.round(hit.score()*1000)/1000.0,page));
        }
        return citations;
    }
}
