package com.enterprise.knowledge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class KnowledgeApplication {
    public static void main(String[] args) { SpringApplication.run(KnowledgeApplication.class, args); }
}
