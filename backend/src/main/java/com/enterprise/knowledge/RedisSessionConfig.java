package com.enterprise.knowledge;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
@Configuration
@Profile("redis")
@EnableRedisHttpSession(maxInactiveIntervalInSeconds=1800)
public class RedisSessionConfig {}
