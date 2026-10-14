package com.enterprise.knowledge;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/kbs")
public class KnowledgeController {
    private final JdbcTemplate db;private final AccessService access;private final IngestionService ingestion;private final TransactionTemplate tx;private final FileStore store;
    public KnowledgeController(JdbcTemplate db,AccessService a,IngestionService i,TransactionTemplate tx,FileStore store){this.db=db;access=a;ingestion=i;this.tx=tx;this.store=store;}
    record NewKb(@NotBlank @Size(max=120) String name,@NotNull @Size(max=2000) String description) {}
    record Member(@NotBlank @Size(max=80) String username) {}
    @GetMapping List<Map<String,Object>> list(Authentication a) {
        String sql="SELECT k.*, (SELECT COUNT(*) FROM document d WHERE d.kb_id=k.id) AS document_count FROM knowledge_base k";
        if(access.admin(a))return db.queryForList(sql+" ORDER BY k.created_at DESC");
        String uid=access.userId(a);
        return db.queryForList(sql+" WHERE k.owner_id=? OR EXISTS(SELECT 1 FROM kb_member m WHERE m.kb_id=k.id AND m.user_id=?) ORDER BY k.created_at DESC",uid,uid);
    }
    @PostMapping Map<String,String> create(@Valid @RequestBody NewKb body,Authentication a) {
        access.editor(a);String id=UUID.randomUUID().toString();
        db.update("INSERT INTO knowledge_base(id,name,description,owner_id) VALUES(?,?,?,?)",id,body.name().strip(),body.description(),access.userId(a));access.audit(a,"KB_CREATE",id);return Map.of("id",id);
    }
    @GetMapping("/{id}/members") List<Map<String,Object>> members(@PathVariable String id,Authentication a) {
        access.owner(id,a);return db.queryForList("SELECT u.id,u.username,u.role FROM app_user u JOIN kb_member m ON m.user_id=u.id WHERE m.kb_id=?",id);
    }
    @PostMapping("/{id}/members") void addMember(@PathVariable String id,@Valid @RequestBody Member body,Authentication a) {
        access.owner(id,a);var users=db.queryForList("SELECT id FROM app_user WHERE username=?",body.username());
        if(users.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"用户不存在");
        db.update("INSERT INTO kb_member(kb_id,user_id) VALUES(?,?)",id,users.get(0).get("id"));access.audit(a,"MEMBER_ADD",id);
    }
    @DeleteMapping("/{id}/members/{uid}") void removeMember(@PathVariable String id,@PathVariable String uid,Authentication a) {
        access.owner(id,a);db.update("DELETE FROM kb_member WHERE kb_id=? AND user_id=?",id,uid);access.audit(a,"MEMBER_REMOVE",id);
    }
    @GetMapping("/{id}/documents") List<Map<String,Object>> documents(@PathVariable String id,Authentication a) {
        access.kb(id,a,false);return db.queryForList("SELECT id,name,size_bytes,status,error_message,chunk_count,created_at FROM document WHERE kb_id=? ORDER BY created_at DESC",id);
    }
    @PostMapping("/{id}/documents") Map<String,String> upload(@PathVariable String id,@RequestParam("file") MultipartFile file,Authentication a) throws Exception {
        access.kb(id,a,true);String original=Objects.requireNonNullElse(file.getOriginalFilename(),"document.txt").replace('\\','/');
        String name=original.substring(original.lastIndexOf('/')+1);
        if(file.isEmpty() || name.length()>255 || !name.toLowerCase(Locale.ROOT).matches(".+\\.(txt|md|pdf|docx)"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"支持非空的 TXT、MD、PDF、DOCX 文件");
        String doc=UUID.randomUUID().toString(),key=UUID.randomUUID().toString();
        try {
            store.save(key,file.getInputStream(),file.getSize());
            db.update("INSERT INTO document(id,kb_id,name,storage_key,size_bytes,status) VALUES(?,?,?,?,?,'PENDING')",doc,id,name,key,file.getSize());
        }catch(Exception e){try{store.delete(key);}catch(Exception ignored){}throw e;}
        access.audit(a,"DOCUMENT_UPLOAD",doc);return Map.of("id",doc);
    }
    private Map<String,Object> doc(String id,String did,Authentication a,boolean write) {
        access.kb(id,a,write);var rows=db.queryForList("SELECT * FROM document WHERE id=? AND kb_id=?",did,id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"文档不存在");return rows.get(0);
    }
    @GetMapping("/{id}/documents/{did}/download") ResponseEntity<Resource> download(@PathVariable String id,@PathVariable String did,Authentication a) throws Exception {
        var d=doc(id,did,a,false);access.audit(a,"DOCUMENT_DOWNLOAD",did);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename((String)d.get("name"),java.nio.charset.StandardCharsets.UTF_8).build().toString())
            .body(new InputStreamResource(store.open((String)d.get("storage_key"))));
    }
    @GetMapping("/{id}/documents/{did}/chunks") List<Map<String,Object>> chunks(@PathVariable String id,@PathVariable String did,Authentication a) {
        doc(id,did,a,false);return db.queryForList("SELECT id,ordinal,content FROM chunk WHERE document_id=? ORDER BY ordinal",did);
    }
    @PostMapping("/{id}/documents/{did}/retry") void retry(@PathVariable String id,@PathVariable String did,Authentication a) {
        doc(id,did,a,true);
        if(db.update("UPDATE document SET status='PENDING',error_message=NULL WHERE id=? AND status IN ('FAILED','READY')",did)==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"文档正在处理中");
        access.audit(a,"DOCUMENT_REINDEX",did);
    }
    @DeleteMapping("/{id}/documents/{did}") void delete(@PathVariable String id,@PathVariable String did,Authentication a) throws Exception {
        var d=doc(id,did,a,true);
        tx.executeWithoutResult(s -> {db.update("DELETE FROM document WHERE id=?",did);access.audit(a,"DOCUMENT_DELETE",did);});
        store.delete((String)d.get("storage_key"));
    }
}
