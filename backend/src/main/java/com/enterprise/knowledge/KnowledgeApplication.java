package com.enterprise.knowledge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude=org.springframework.boot.autoconfigure.session.SessionAutoConfiguration.class)
@EnableScheduling
public class KnowledgeApplication {
    public static void main(String[] args) { SpringApplication.run(KnowledgeApplication.class, args); }
}
