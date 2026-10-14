package com.enterprise.knowledge;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.*;
import org.springframework.security.core.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AuthController {
    private final AuthenticationManager manager; private final HttpSessionSecurityContextRepository repository;
    private final JdbcTemplate db; private final PasswordEncoder encoder; private final AccessService access;
    public AuthController(AuthenticationManager m,HttpSessionSecurityContextRepository r,JdbcTemplate d,PasswordEncoder e,AccessService a) {manager=m;repository=r;db=d;encoder=e;access=a;}
    record Login(@NotBlank @Size(max=80) String username,@NotBlank @Size(max=100) String password) {}
    record NewUser(@Pattern(regexp="[a-zA-Z0-9_.-]{3,80}") @NotNull String username,@Size(min=1,max=72) @NotNull String password,@Pattern(regexp="ADMIN|EDITOR|VIEWER") @NotNull String role) {}
    @GetMapping("/auth/csrf") Map<String,String> csrf(CsrfToken token) { return Map.of("token",token.getToken(),"headerName",token.getHeaderName()); }
    @PostMapping("/auth/login") Map<String,Object> login(@Valid @RequestBody Login body,HttpServletRequest req,HttpServletResponse res) {
        var auth=manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(body.username(),body.password()));
        if(req.getSession(false)!=null) req.changeSessionId();
        var context=SecurityContextHolder.createEmptyContext(); context.setAuthentication(auth); SecurityContextHolder.setContext(context);
        repository.saveContext(context,req,res); access.audit(auth,"LOGIN",auth.getName()); return me(auth);
    }
    @GetMapping("/auth/me") Map<String,Object> me(Authentication a) { return Map.of("username",a.getName(),"id",access.userId(a),"role",a.getAuthorities().iterator().next().getAuthority().replace("ROLE_","")); }
    @GetMapping("/admin/users") List<Map<String,Object>> users() { return db.queryForList("SELECT id,username,role,created_at FROM app_user ORDER BY created_at"); }
    @PostMapping("/admin/users") Map<String,String> create(@Valid @RequestBody NewUser body,Authentication a) {
        String id=UUID.randomUUID().toString(); db.update("INSERT INTO app_user(id,username,password_hash,role) VALUES(?,?,?,?)",id,body.username(),encoder.encode(body.password()),body.role());
        access.audit(a,"USER_CREATE",id); return Map.of("id",id);
    }
    @GetMapping("/admin/audit") List<Map<String,Object>> audit() { return db.queryForList("SELECT * FROM audit_event ORDER BY created_at DESC LIMIT 200"); }
}
