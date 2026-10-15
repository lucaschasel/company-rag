package com.enterprise.knowledge;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {
    private final JdbcTemplate db;
    private final AccessService access;
    private final ConversationAccess conversations;

    public ConversationController(JdbcTemplate db, AccessService access, ConversationAccess conversations) { this.db = db; this.access = access; this.conversations=conversations; }

    record Rename(@NotBlank @Size(max = 200) String title) {}

    @GetMapping
    List<Map<String, Object>> list(Authentication a) {
        return db.queryForList(
            "SELECT c.id, c.title, c.updated_at, " +
            "(SELECT COUNT(*) FROM conversation_message m WHERE m.conversation_id = c.id) AS message_count " +
            "FROM conversation c WHERE c.user_id = ? ORDER BY c.updated_at DESC, c.id DESC",
            access.userId(a)).stream().filter(row -> conversations.readable((String)row.get("id"),a)).toList();
    }

    @GetMapping("/{id}/messages")
    List<Map<String, Object>> messages(@PathVariable String id, Authentication a) {
        conversations.requireReadable(id, a);
        return db.queryForList(
            "SELECT id, role, content, mode, citations, error, created_at " +
            "FROM conversation_message WHERE conversation_id = ? ORDER BY seq",
            id);
    }

    @PutMapping("/{id}")
    Map<String, String> rename(@PathVariable String id, @Valid @RequestBody Rename body, Authentication a) {
        checkOwner(id, a);
        String title = body.title().strip();
        db.update("UPDATE conversation SET title = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", title, id);
        return Map.of("id", id, "title", title);
    }

    @DeleteMapping("/{id}")
    void delete(@PathVariable String id, Authentication a) {
        checkOwner(id, a);
        db.update("DELETE FROM conversation WHERE id = ?", id);
    }

    private void checkOwner(String id, Authentication a) {
        var rows = db.queryForList("SELECT user_id FROM conversation WHERE id = ?", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        if (!rows.get(0).get("user_id").equals(access.userId(a)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问此会话");
    }
}
