package com.enterprise.knowledge;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@Service
public class AccessService {
    private final JdbcTemplate db;
    public AccessService(JdbcTemplate db) { this.db=db; }
    public boolean admin(Authentication a) { return a.getAuthorities().stream().anyMatch(r -> r.getAuthority().equals("ROLE_ADMIN")); }
    public String userId(Authentication a) { return db.queryForObject("SELECT id FROM app_user WHERE username=?", String.class, a.getName()); }
    public void editor(Authentication a) {
        if (!admin(a) && a.getAuthorities().stream().noneMatch(r -> r.getAuthority().equals("ROLE_EDITOR"))) throw new ResponseStatusException(FORBIDDEN,"需要编辑者权限");
    }
    public void kb(String id, Authentication a, boolean write) {
        var rows=db.queryForList("SELECT owner_id FROM knowledge_base WHERE id=?",id);
        if(rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND,"知识库不存在");
        if(admin(a)) return;
        String user=userId(a);
        boolean owner=user.equals(rows.get(0).get("owner_id"));
        boolean member=db.queryForObject("SELECT COUNT(*) FROM kb_member WHERE kb_id=? AND user_id=?",Integer.class,id,user)>0;
        if(!owner && !member) throw new ResponseStatusException(FORBIDDEN,"无权访问此知识库");
        if(write) editor(a);
    }
    public void owner(String id,Authentication a) {
        kb(id,a,true);
        if(!admin(a) && !userId(a).equals(db.queryForObject("SELECT owner_id FROM knowledge_base WHERE id=?",String.class,id))) throw new ResponseStatusException(FORBIDDEN,"仅所有者可管理成员");
    }
    public void audit(Authentication a,String action,String resource) {
        db.update("INSERT INTO audit_event(id,username,action,resource_id) VALUES(?,?,?,?)",UUID.randomUUID().toString(),a.getName(),action,resource);
    }
}
