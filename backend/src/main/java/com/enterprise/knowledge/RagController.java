package com.enterprise.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api")
public class RagController {
    private final JdbcTemplate db;private final AccessService access;private final ModelSettings settings;private final ObjectMapper json;private final boolean postgres;
    public RagController(JdbcTemplate db,AccessService a,ModelSettings m,ObjectMapper j,Environment env){this.db=db;access=a;settings=m;json=j;postgres=Arrays.asList(env.getActiveProfiles()).contains("postgres");}
    record Query(@Size(max=36) String kbId,@NotBlank @Size(max=2000) String question,@Min(1) @Max(10) int topK,@Size(max=36) String conversationId,@Size(max=200) String title) {}
    record Citation(int index,String kbId,String kbName,String documentId,String documentName,int ordinal,String content,double score) {}
    record Answer(String answer,String mode,List<Citation> citations,String conversationId) {}
    @GetMapping("/system") Map<String,Object> system(){var c=settings.current();return Map.of("modelEnabled",c.enabled(),"embeddingEnabled",c.embeddingEnabled(),"embeddingModel",c.embedding().model(),"storage",postgres?"PostgreSQL + pgvector":"H2 本地开发");}
    @PostMapping("/search") List<Citation> search(@Valid @RequestBody Query q,Authentication a) throws Exception {checkScope(q,a);access.audit(a,"SEARCH",scopeName(q));return retrieve(q,settings.client(),a);}
    @PostMapping("/chat") Answer chat(@Valid @RequestBody Query q,Authentication a) throws Exception {
        checkScope(q,a);access.audit(a,"CHAT",scopeName(q));var model=settings.client();var citations=retrieve(q,model,a);
        String answer;String mode;
        if(citations.isEmpty()){answer="没有找到足够相关的知识。请上传资料，或换一种提问方式。";mode=model.enabled?"RAG":"KEYWORD";}
        else if(!model.enabled){answer="当前未启用 AI 回答。以下是检索到的原文片段，管理员可在模型与部署页面配置并启用聊天模型。";mode=model.embeddingEnabled?"VECTOR":"KEYWORD";}
        else{
            StringBuilder context=new StringBuilder();for(var c:citations)context.append("[").append(c.index()).append("] ").append(c.kbName()).append(" / ").append(c.documentName()).append(" / 分块 ").append(c.ordinal()).append("\n").append(c.content()).append("\n\n");
            try{answer=model.answer(q.question(),context.toString());mode="RAG";}
            catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"生成模型不可用，请检查配置或使用检索测试");}
        }
        String conversationId=persist(q,a,answer,mode,citations);
        return new Answer(answer,mode,citations,conversationId);
    }
    private String persist(Query q,Authentication a,String answer,String mode,List<Citation> citations) throws Exception {
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
        db.update("INSERT INTO conversation_message(id,conversation_id,role,content) VALUES(?,?,?,?)",UUID.randomUUID().toString(),convId,"user",q.question());
        db.update("INSERT INTO conversation_message(id,conversation_id,role,content,mode,citations) VALUES(?,?,?,?,?,?)",UUID.randomUUID().toString(),convId,"assistant",answer,mode,json.writeValueAsString(citations));
        db.update("UPDATE conversation SET updated_at = CURRENT_TIMESTAMP WHERE id = ?",convId);
        return convId;
    }
    private String defaultTitle(String question) {
        String t=question.replaceAll("\\s+"," ").strip();
        return t.length()<=30?t:t.substring(0,30);
    }
    private boolean scoped(Query q){return q.kbId()!=null && !q.kbId().isBlank();}
    private String scopeName(Query q){return scoped(q)?q.kbId():"ALL_ACCESSIBLE";}
    private void checkScope(Query q,Authentication a){if(scoped(q))access.kb(q.kbId(),a,false);}
    private List<Citation> retrieve(Query q,ModelClient model,Authentication a) throws Exception {
        String from=" FROM chunk c JOIN document d ON d.id=c.document_id JOIN knowledge_base k ON k.id=d.kb_id WHERE d.status='READY'";
        List<Object> args=new ArrayList<>();
        if(scoped(q)){from+=" AND k.id=?";args.add(q.kbId());}
        // Apply ACL before counting or ranking candidates, for both storage engines.
        if(!access.admin(a)){
            String user=access.userId(a);
            from+=" AND (k.owner_id=? OR EXISTS (SELECT 1 FROM kb_member m WHERE m.kb_id=k.id AND m.user_id=?))";
            args.add(user);args.add(user);
        }
        String select="SELECT c.*,d.name AS document_name,k.id AS kb_id,k.name AS kb_name"+from;
        Set<String> terms=TextChunks.terms(q.question());double[] vector;
        try{vector=model.embeddingEnabled?model.embed(q.question()):null;}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"向量模型不可用，请检查模型服务");}
        List<Map<String,Object>> candidates;
        if(postgres && vector!=null) {
            args.add(model.signature());args.add(json.writeValueAsString(vector));
            candidates=db.queryForList(select+" AND c.embedding_model=? AND c.vector_embedding IS NOT NULL ORDER BY c.vector_embedding <=> CAST(? AS vector) LIMIT 80",args.toArray());
        } else {
            Long count=db.queryForObject("SELECT COUNT(*)"+from,Long.class,args.toArray());
            if(count>20000)throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"本地/关键词检索最多支持 20000 分块，请启用 PostgreSQL 向量检索");
            candidates=db.queryForList(select+" ORDER BY d.created_at,d.id,c.ordinal",args.toArray());
        }
        record Scored(Map<String,Object> row,double score) {}
        List<Scored> ranked=new ArrayList<>();
        for(var row:candidates) {
            String content=(String)row.get("content");Set<String> contentTerms=TextChunks.terms(content);
            double lexical=terms.isEmpty()?0:terms.stream().filter(contentTerms::contains).count()/(double)terms.size();
            double score=lexical;
            if(vector!=null) {
                if(!model.signature().equals(row.get("embedding_model")) || row.get("embedding")==null)continue;
                double semantic=TextChunks.cosine(vector,json.readValue((String)row.get("embedding"),double[].class));
                score=0.8*Math.max(0,semantic)+0.2*lexical;
                if(semantic<0.25 && lexical==0)continue;
            } else if(lexical==0)continue;
            ranked.add(new Scored(row,score));
        }
        ranked.sort(Comparator.comparingDouble(Scored::score).reversed());List<Citation> citations=new ArrayList<>();
        for(var hit:ranked.subList(0,Math.min(q.topK(),ranked.size()))) {
            var r=hit.row();citations.add(new Citation(citations.size()+1,(String)r.get("kb_id"),(String)r.get("kb_name"),(String)r.get("document_id"),(String)r.get("document_name"),((Number)r.get("ordinal")).intValue(),(String)r.get("content"),Math.round(hit.score()*1000)/1000.0));
        }
        return citations;
    }
}
