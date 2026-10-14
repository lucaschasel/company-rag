package com.enterprise.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
@RequestMapping("/api/admin/model-settings")
public class ModelSettingsController {
    private final ModelSettings settings;private final ObjectMapper json;private final AccessService access;private final JdbcTemplate db;
    public ModelSettingsController(ModelSettings s,ObjectMapper j,AccessService a,JdbcTemplate db){settings=s;json=j;access=a;this.db=db;}
    public record Update(ModelSettings.Config config,boolean clearChatKey,boolean clearEmbeddingKey) {}
    @GetMapping Map<String,Object> get(){return settings.view();}
    @PutMapping Map<String,Object> save(@RequestBody Update input,Authentication a) throws Exception {
        var config=settings.prepare(input.config(),input.clearChatKey(),input.clearEmbeddingKey());settings.save(config);access.audit(a,"MODEL_SETTINGS_SAVE","global");return settings.view();
    }
    @PostMapping("/test/{kind}") Map<String,Object> test(@PathVariable String kind,@RequestBody Update input,Authentication a) {
        if(!Set.of("chat","embedding").contains(kind))throw new ResponseStatusException(BAD_REQUEST,"测试类型错误");
        var config=settings.prepare(input.config(),input.clearChatKey(),input.clearEmbeddingKey());
        var client=new ModelClient(json,config);long started=System.nanoTime();access.audit(a,"MODEL_TEST",kind);
        try {
            String detail=kind.equals("chat")?client.answer("请回复连接成功","[1] 连接成功"):"向量维数："+client.embed("连接测试").length;
            // Never reflect provider response/error text: it may contain credentials or proxy diagnostics.
            return Map.of("ok",true,"message",kind.equals("chat")?"聊天接口返回有效回答":detail,"elapsedMs",(System.nanoTime()-started)/1_000_000);
        }catch(Exception ex){return Map.of("ok",false,"message","连接失败，请检查服务地址、密钥、模型名称及服务状态（超时上限 120 秒）","elapsedMs",(System.nanoTime()-started)/1_000_000);}
    }
    @PostMapping("/reindex") Map<String,Integer> reindex(Authentication a) {
        int count=db.update("UPDATE document SET status='PENDING',error_message=NULL WHERE status IN ('READY','FAILED')");
        access.audit(a,"REINDEX_ALL",Integer.toString(count));return Map.of("queued",count);
    }
}
