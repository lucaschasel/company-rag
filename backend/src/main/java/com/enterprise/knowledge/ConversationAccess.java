package com.enterprise.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@Service
public class ConversationAccess {
    private final JdbcTemplate db;
    private final AccessService access;
    private final ObjectMapper json;
    public ConversationAccess(JdbcTemplate db, AccessService access, ObjectMapper json) {
        this.db=db; this.access=access; this.json=json;
    }
    public void requireOwner(String id, Authentication a) {
        var rows=db.queryForList("SELECT user_id FROM conversation WHERE id=?",id);
        if(rows.isEmpty())throw new ResponseStatusException(NOT_FOUND,"会话不存在");
        if(!access.userId(a).equals(rows.get(0).get("user_id")))throw new ResponseStatusException(FORBIDDEN,"无权访问此会话");
    }
    public void requireReadable(String id, Authentication a) {
        requireOwner(id,a);
        // Check the whole conversation: later answers may depend on earlier sources.
        for(String raw:db.queryForList("SELECT citations FROM conversation_message WHERE conversation_id=? AND role='assistant' AND citations IS NOT NULL",String.class,id)) {
            try {
                var citations=json.readTree(raw);
                if(!citations.isArray())throw new IllegalArgumentException();
                for(var c:citations) {
                    String kb=c.path("kbId").asText(), doc=c.path("documentId").asText();
                    access.kb(kb,a,false);
                    if(db.queryForObject("SELECT COUNT(*) FROM document WHERE id=? AND kb_id=? AND status='READY'",Integer.class,doc,kb)!=1)
                        throw new IllegalArgumentException();
                }
            } catch(Exception e) {
                throw new ResponseStatusException(FORBIDDEN,"会话引用的资料已不可访问，请新建对话");
            }
        }
    }
    public boolean readable(String id, Authentication a) {
        try { requireReadable(id,a); return true; }
        catch(ResponseStatusException e) {
            if(e.getStatusCode().value()==403 || e.getStatusCode().value()==404)return false;
            throw e;
        }
    }
}
