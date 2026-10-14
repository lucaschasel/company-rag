package com.enterprise.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Service
public class ModelSettings {
    public record Endpoint(String provider,String baseUrl,String apiKey,String model) {}
    public record Config(boolean enabled,boolean embeddingEnabled,Endpoint chat,Endpoint embedding,String version) {}
    private final JdbcTemplate db; private final ObjectMapper json; private final byte[] secret;
    private final Config defaults;
    public ModelSettings(JdbcTemplate db,ObjectMapper json,@Value("${app.storage}") String storage,
        @Value("${app.model.base-url}") String base,@Value("${app.model.api-key}") String key,
        @Value("${app.model.chat-model}") String chat,@Value("${app.model.embedding-model}") String embedding,
        @Value("${app.model.embedding-version}") String version,@Value("${app.model.enabled}") boolean enabled) throws Exception {
        this.db=db;this.json=json;
        defaults=new Config(enabled,enabled,new Endpoint("OPENAI",base,key,chat),new Endpoint("OPENAI",base,key,embedding),version);
        Path path=Path.of(storage).toAbsolutePath().normalize().resolveSibling("model-secret.key");
        Files.createDirectories(path.getParent());
        if(!Files.exists(path)) {
            if(db.queryForObject("SELECT COUNT(*) FROM model_settings",Integer.class)>0)throw new IllegalStateException("模型配置解密密钥丢失，请恢复 model-secret.key 备份");
            byte[] generated=new byte[32];new SecureRandom().nextBytes(generated);Files.write(path,generated,StandardOpenOption.CREATE_NEW);
        }
        secret=Files.readAllBytes(path);if(secret.length!=32)throw new IllegalStateException("模型配置密钥格式错误");
    }
    public synchronized Config current() {
        var rows=db.queryForList("SELECT encrypted_config FROM model_settings WHERE id=1",String.class);
        if(rows.isEmpty())return defaults;
        try{return json.readValue(decrypt(rows.get(0)),Config.class);}catch(Exception e){throw new IllegalStateException("无法读取模型配置，请检查加密密钥",e);}
    }
    public ModelClient client(){return new ModelClient(json,current());}
    public synchronized Config prepare(Config input,boolean clearChatKey,boolean clearEmbeddingKey) {
        if(input==null || input.chat()==null || input.embedding()==null)throw bad("聊天和向量配置不能为空");
        var old=current();
        String version=Objects.requireNonNullElse(input.version(),"").strip();
        if(version.isEmpty()||version.length()>60)throw bad("向量版本需要 1–60 个字符");
        return new Config(input.enabled(),input.embeddingEnabled(),validate(input.chat(),old.chat(),clearChatKey),validate(input.embedding(),old.embedding(),clearEmbeddingKey),version);
    }
    private Endpoint validate(Endpoint e,Endpoint old,boolean clear) {
        if(!Set.of("OPENAI","OLLAMA").contains(Objects.requireNonNullElse(e.provider(),"")))throw bad("接口类型必须是 OPENAI 或 OLLAMA");
        String base=Objects.requireNonNullElse(e.baseUrl(),"").strip().replaceAll("/+$","");
        try {
            URI uri=URI.create(base);
            if(!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||base.length()>500)throw new IllegalArgumentException();
        }catch(Exception ex){throw bad("接口地址必须是合法的 HTTP/HTTPS 基础地址，不能包含凭证、查询参数或片段");}
        String model=Objects.requireNonNullElse(e.model(),"").strip();
        if(model.isBlank()||model.length()>150)throw bad("模型名称需要 1–150 个字符");
        String key=Objects.requireNonNullElse(e.apiKey(),"").strip();
        // Never forward a saved credential to a newly selected endpoint/provider.
        boolean same=base.equals(old.baseUrl().replaceAll("/+$",""))&&e.provider().equals(old.provider());
        if(clear)key="";else if(key.isEmpty()&&same)key=old.apiKey();
        if(key.length()>2000||key.contains("\n")||key.contains("\r"))throw bad("API Key 格式错误");
        return new Endpoint(e.provider(),base,key,model);
    }
    public synchronized void save(Config config) throws Exception {
        String encrypted=encrypt(json.writeValueAsBytes(config));
        if(db.update("UPDATE model_settings SET encrypted_config=?,updated_at=CURRENT_TIMESTAMP WHERE id=1",encrypted)==0)
            db.update("INSERT INTO model_settings(id,encrypted_config) VALUES(1,?)",encrypted);
    }
    public Map<String,Object> view() {
        var c=current();return Map.of("enabled",c.enabled(),"embeddingEnabled",c.embeddingEnabled(),"chat",safe(c.chat()),"embedding",safe(c.embedding()),"version",c.version());
    }
    private Map<String,Object> safe(Endpoint e){return Map.of("provider",e.provider(),"baseUrl",e.baseUrl(),"apiKey","","keyConfigured",!e.apiKey().isEmpty(),"model",e.model());}
    private String encrypt(byte[] data) throws Exception {
        byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(secret,"AES"),new GCMParameterSpec(128,iv));
        byte[] encrypted=cipher.doFinal(data),all=new byte[iv.length+encrypted.length];System.arraycopy(iv,0,all,0,iv.length);System.arraycopy(encrypted,0,all,iv.length,encrypted.length);
        return Base64.getEncoder().encodeToString(all);
    }
    private byte[] decrypt(String value) throws Exception {
        byte[] all=Base64.getDecoder().decode(value);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(secret,"AES"),new GCMParameterSpec(128,Arrays.copyOfRange(all,0,12)));return cipher.doFinal(Arrays.copyOfRange(all,12,all.length));
    }
    private ResponseStatusException bad(String message){return new ResponseStatusException(BAD_REQUEST,message);}
}
