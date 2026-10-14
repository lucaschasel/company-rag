package com.enterprise.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.admin-password=TestPassword123!","app.model.enabled=false","app.storage=./target/test-uploads"})
@AutoConfigureMockMvc
class KnowledgeIntegrationTest {
    @Autowired MockMvc mvc;@Autowired JdbcTemplate db;@Autowired ObjectMapper json;@Autowired IngestionService ingestion;
    private String kb;
    @BeforeEach void setup() {
        db.update("DELETE FROM model_settings");
        db.update("DELETE FROM chunk");db.update("DELETE FROM document");db.update("DELETE FROM kb_member");db.update("DELETE FROM knowledge_base");
        for(String name:List.of("outsider","reader"))if(db.queryForObject("SELECT COUNT(*) FROM app_user WHERE username=?",Integer.class,name)==0)db.update("INSERT INTO app_user(id,username,password_hash,role) VALUES(?,?,?,'VIEWER')",name,name,"unused");
        kb=UUID.randomUUID().toString();String admin=db.queryForObject("SELECT id FROM app_user WHERE username='admin'",String.class);
        db.update("INSERT INTO knowledge_base(id,name,description,owner_id) VALUES(?,?,?,?)",kb,"研发规范","内部研发资料",admin);
        db.update("INSERT INTO kb_member(kb_id,user_id) VALUES(?,?)",kb,"reader");
    }
    @Test void anonymousAndCsrfAreRejected() throws Exception {
        mvc.perform(get("/api/kbs")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/kbs").with(user("admin").roles("ADMIN")).contentType("application/json").content("{\"name\":\"test\",\"description\":\"\"}")).andExpect(status().isForbidden());
    }
    @Test void knowledgeIsolationAndRoleEnforcement() throws Exception {
        mvc.perform(get("/api/kbs").with(user("outsider").roles("VIEWER"))).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/kbs/"+kb+"/documents").with(user("outsider").roles("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/chat").with(user("outsider").roles("VIEWER")).with(csrf()).contentType("application/json").content(json.writeValueAsString(Map.of("kbId",kb,"question","研发","topK",5)))).andExpect(status().isForbidden());
        mvc.perform(get("/api/kbs/"+kb+"/documents").with(user("reader").roles("VIEWER"))).andExpect(status().isOk());
        mvc.perform(multipart("/api/kbs/"+kb+"/documents").file(new MockMultipartFile("file","a.txt","text/plain","abc".getBytes())).with(user("reader").roles("VIEWER")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users").with(user("reader").roles("VIEWER"))).andExpect(status().isForbidden());
    }
    @Test void uploadIndexSearchCitationAndDelete() throws Exception {
        var file=new MockMultipartFile("file","开发规范.md","text/markdown","研发规范：项目上线前必须完成代码审查和安全测试。发布需要技术负责人审批。".getBytes(StandardCharsets.UTF_8));
        String body=mvc.perform(multipart("/api/kbs/"+kb+"/documents").file(file).with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String did=json.readTree(body).get("id").asText();ingestion.processNext();
        assertThat(db.queryForObject("SELECT status FROM document WHERE id=?",String.class,did)).isEqualTo("READY");
        mvc.perform(post("/api/chat").with(user("reader").roles("VIEWER")).with(csrf()).contentType("application/json").content(json.writeValueAsString(Map.of("kbId",kb,"question","代码审查","topK",5))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("KEYWORD")).andExpect(jsonPath("$.citations[0].documentId").value(did));
        mvc.perform(get("/api/kbs/"+kb+"/documents/"+did+"/download").with(user("outsider").roles("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/kbs/"+kb+"/documents/"+did).with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM chunk WHERE document_id=?",Integer.class,did)).isZero();
    }
    private String readyDocument(String base,String text) {
        String id=UUID.randomUUID().toString();
        db.update("INSERT INTO document(id,kb_id,name,storage_key,size_bytes,status,chunk_count) VALUES(?,?,?,?,1,'READY',1)",id,base,"规范.md",id);
        db.update("INSERT INTO chunk(id,document_id,ordinal,content) VALUES(?,?,1,?)",UUID.randomUUID().toString(),id,text);
        return id;
    }
    @Test void globalRetrievalIncludesOnlyAuthorizedLibrariesAndTracksSources() throws Exception {
        String owned=UUID.randomUUID().toString(),hidden=UUID.randomUUID().toString();
        String admin=db.queryForObject("SELECT id FROM app_user WHERE username='admin'",String.class);
        db.update("INSERT INTO knowledge_base(id,name,description,owner_id) VALUES(?,?,?,?)",owned,"个人研发库","","reader");
        db.update("INSERT INTO knowledge_base(id,name,description,owner_id) VALUES(?,?,?,?)",hidden,"保密库","",admin);
        String first=readyDocument(kb,"研发规范 代码审查"),second=readyDocument(owned,"研发规范 上线审批");
        readyDocument(hidden,"研发规范 保密资料禁止泄露");
        String query="{\"question\":\"研发规范\",\"topK\":10}";
        var result=mvc.perform(post("/api/chat").with(user("reader").roles("VIEWER")).with(csrf()).contentType("application/json").content(query))
            .andExpect(status().isOk()).andExpect(jsonPath("$.citations.length()").value(2)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var citations=json.readTree(result).path("citations");
        Set<String> ids=new HashSet<>();for(var c:citations){ids.add(c.path("documentId").asText());assertThat(c.path("kbName").asText()).isIn("研发规范","个人研发库");assertThat(c.path("kbId").asText()).isIn(kb,owned);}
        assertThat(ids).containsExactlyInAnyOrder(first,second);assertThat(result).doesNotContain("保密");
        mvc.perform(post("/api/search").with(user("reader").roles("VIEWER")).with(csrf()).contentType("application/json").content(query)).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(post("/api/search").with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json").content(query)).andExpect(jsonPath("$.length()").value(3));
        mvc.perform(post("/api/chat").with(user("outsider").roles("VIEWER")).with(csrf()).contentType("application/json").content(query)).andExpect(status().isOk()).andExpect(jsonPath("$.citations").isEmpty());
        mvc.perform(post("/api/search").with(user("reader").roles("VIEWER")).with(csrf()).contentType("application/json").content(json.writeValueAsString(Map.of("kbId",kb,"question","研发规范","topK",10)))).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].kbId").value(kb));
        // Membership changes must affect the next request, including global search.
        db.update("DELETE FROM kb_member WHERE kb_id=? AND user_id='reader'",kb);
        mvc.perform(post("/api/chat").with(user("reader").roles("VIEWER")).with(csrf()).contentType("application/json").content(query)).andExpect(jsonPath("$.citations.length()").value(1)).andExpect(jsonPath("$.citations[0].kbId").value(owned));
    }
    @Test void badLoginDoesNotCreateAuthenticatedSession() throws Exception {
        mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json").content("{\"username\":\"admin\",\"password\":\"incorrect\"}")).andExpect(status().isUnauthorized());
    }
    @Test void loginPersistsSession() throws Exception {
        var result=mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json").content("{\"username\":\"admin\",\"password\":\"TestPassword123!\"}")).andExpect(status().isOk()).andReturn();
        mvc.perform(get("/api/auth/me").session((org.springframework.mock.web.MockHttpSession)result.getRequest().getSession())).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN"));
    }
    @Test void invalidUploadsAndTopKRejected() throws Exception {
        mvc.perform(multipart("/api/kbs/"+kb+"/documents").file(new MockMultipartFile("file","app.exe","application/octet-stream",new byte[]{1})).with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(post("/api/search").with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json").content(json.writeValueAsString(Map.of("kbId",kb,"question","test","topK",100)))).andExpect(status().isBadRequest());
    }
    @Test void modelSettingsAreAdminOnlyEncryptedAndDoNotLeakCredentialsToNewHosts() throws Exception {
        mvc.perform(get("/api/admin/model-settings").with(user("reader").roles("VIEWER"))).andExpect(status().isForbidden());
        var chat=new ModelSettings.Endpoint("OPENAI","https://example.com/v1","secret-test-token","chat");
        var embed=new ModelSettings.Endpoint("OLLAMA","http://localhost:11434","","embed");
        var config=new ModelSettings.Config(true,false,chat,embed,"v2");
        mvc.perform(put("/api/admin/model-settings").with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json").content(json.writeValueAsString(Map.of("config",config))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.chat.apiKey").value("")).andExpect(jsonPath("$.chat.keyConfigured").value(true));
        assertThat(db.queryForObject("SELECT encrypted_config FROM model_settings WHERE id=1",String.class)).doesNotContain("secret-test-token").doesNotContain("example.com");
        mvc.perform(get("/api/system").with(user("admin").roles("ADMIN"))).andExpect(jsonPath("$.modelEnabled").value(true)).andExpect(jsonPath("$.embeddingEnabled").value(false));
        // Blank preserves only for the same endpoint.
        config=new ModelSettings.Config(true,false,new ModelSettings.Endpoint("OPENAI","https://example.com/v1","","chat"),embed,"v2");
        mvc.perform(put("/api/admin/model-settings").with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json").content(json.writeValueAsString(Map.of("config",config)))).andExpect(jsonPath("$.chat.keyConfigured").value(true));
        config=new ModelSettings.Config(true,false,new ModelSettings.Endpoint("OPENAI","https://other.example/v1","","chat"),embed,"v2");
        mvc.perform(put("/api/admin/model-settings").with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json").content(json.writeValueAsString(Map.of("config",config)))).andExpect(jsonPath("$.chat.keyConfigured").value(false));
        config=new ModelSettings.Config(true,false,new ModelSettings.Endpoint("OPENAI","file:///etc/passwd","","chat"),embed,"v2");
        mvc.perform(put("/api/admin/model-settings").with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json").content(json.writeValueAsString(Map.of("config",config)))).andExpect(status().isBadRequest());
    }
}
