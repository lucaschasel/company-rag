package com.enterprise.knowledge;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="TEST_POSTGRES_URL",matches=".+")
class PostgresVectorTest {
    @Test void migratesVariableDimensionsBuildsPartialIndexesAndUsesThem() throws Exception {
        String schema="rag_test_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=DriverManager.getConnection(System.getenv("TEST_POSTGRES_URL"),System.getenv().getOrDefault("TEST_POSTGRES_USER","knowledge"),System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD",""))) {
            var db=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            // Dedicated disposable test schema only. Requires pgvector extension installed in public.
            db.execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");db.execute("CREATE SCHEMA "+schema);
            try {
                db.execute("SET search_path TO "+schema+",public");
                Flyway.configure().dataSource(new SingleConnectionDataSource(connection,true)).schemas(schema).defaultSchema(schema).locations("classpath:db/migration","classpath:db/postgres").load().migrate();
                db.update("INSERT INTO app_user(id,username,password_hash,role) VALUES('u','test','unused','ADMIN')");
                db.update("INSERT INTO knowledge_base(id,name,description,owner_id) VALUES('k','test','','u')");
                db.update("INSERT INTO document(id,kb_id,name,storage_key,size_bytes,status) VALUES('d','k','test','test',1,'READY')");
                String a="a".repeat(64),b="b".repeat(64);
                db.update("INSERT INTO chunk(id,document_id,ordinal,content,embedding_model,vector_embedding) VALUES('c1','d',1,'text',?,CAST('[1,0,0]' AS vector)),('c2','d',2,'text',?,CAST('[1,0]' AS vector))",a,b);
                var env=new StandardEnvironment();env.setActiveProfiles("postgres");new VectorIndexes(db,env).refresh();
                assertThat(db.queryForObject("SELECT COUNT(*) FROM pg_indexes WHERE schemaname=? AND indexname LIKE 'idx_vec_%'",Integer.class,schema)).isEqualTo(2);
                String query="SELECT id FROM chunk WHERE "+VectorIndexes.predicate(a,3,"")+" ORDER BY "+VectorIndexes.expression(a,3,"")+" <=> CAST('[1,0,0]' AS vector(3)) LIMIT 5";
                assertThat(db.queryForList(query,String.class)).containsExactly("c1");
                db.execute("SET enable_seqscan=off");
                assertThat(String.join("\n",db.queryForList("EXPLAIN "+query,String.class))).contains("idx_vec_");
            } finally {db.execute("SET search_path TO public");db.execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
}
