package com.enterprise.knowledge;

import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.*;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean UserDetailsService users(JdbcTemplate db) {
        return username -> db.query("SELECT username,password_hash,role FROM app_user WHERE username=?",
            (rs,n) -> User.withUsername(rs.getString(1)).password(rs.getString(2)).roles(rs.getString(3)).build(), username)
            .stream().findFirst().orElseThrow(() -> new UsernameNotFoundException("用户不存在"));
    }
    @Bean AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
        var provider = new DaoAuthenticationProvider(users); provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }
    @Bean HttpSessionSecurityContextRepository contextRepository() { return new HttpSessionSecurityContextRepository(); }
    @Bean SecurityFilterChain security(HttpSecurity http, HttpSessionSecurityContextRepository repository) throws Exception {
        return http.securityContext(c -> c.securityContextRepository(repository))
            .authorizeHttpRequests(a -> a.requestMatchers("/api/auth/csrf", "/api/auth/login", "/actuator/health").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN").anyRequest().authenticated())
            .exceptionHandling(e -> e.authenticationEntryPoint((req,res,ex) -> res.sendError(401))
                .accessDeniedHandler((req,res,ex) -> res.sendError(403)))
            .logout(l -> l.logoutUrl("/api/auth/logout").logoutSuccessHandler((req,res,a) -> res.setStatus(204)))
            .build();
    }
    @Bean ApplicationRunner bootstrap(JdbcTemplate db, PasswordEncoder encoder,
            @Value("${app.admin-user}") String user, @Value("${app.admin-password}") String password) {
        return args -> {
            if (db.queryForObject("SELECT COUNT(*) FROM app_user", Long.class) == 0) {
                if (password.isEmpty()) throw new IllegalStateException("首次启动必须设置 ADMIN_PASSWORD 环境变量");
                db.update("INSERT INTO app_user(id,username,password_hash,role) VALUES(?,?,?,?)", UUID.randomUUID().toString(), user, encoder.encode(password), "ADMIN");
                LoggerFactory.getLogger(SecurityConfig.class).info("初始管理员已创建：{}", user);
            }
        };
    }
}
