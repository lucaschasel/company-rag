package com.enterprise.knowledge;

import java.sql.Statement;
import java.util.Arrays;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class VectorIndexes {
    private final JdbcTemplate db;
    private final boolean postgres;
    public VectorIndexes(JdbcTemplate db, Environment env) {
        this.db=db;postgres=Arrays.asList(env.getActiveProfiles()).contains("postgres");
    }
    // Signature comes from SHA-256, never from untrusted SQL fragments.
    static String predicate(String signature,int dimensions,String prefix) {
        if(!signature.matches("[a-f0-9]{64}")||dimensions<1||dimensions>16000)throw new IllegalArgumentException("向量模型或维数无效");
        return prefix+"embedding_model='"+signature+"' AND vector_dims("+prefix+"vector_embedding)="+dimensions;
    }
    static String expression(String signature,int dimensions,String prefix) {
        return "CAST(CASE WHEN "+predicate(signature,dimensions,prefix)+" THEN "+prefix+"vector_embedding END AS vector("+dimensions+"))";
    }
    @Scheduled(fixedDelay=300000,initialDelay=15000)
    public void refresh() {
        if(!postgres)return;
        var groups=db.queryForList("SELECT DISTINCT embedding_model,vector_dims(vector_embedding) AS dimensions FROM chunk WHERE vector_embedding IS NOT NULL AND embedding_model IS NOT NULL");
        for(var group:groups) {
            String signature=(String)group.get("embedding_model");int dims=((Number)group.get("dimensions")).intValue();
            // pgvector's vector HNSW supports up to 2000 dimensions. Larger vectors keep exact search.
            if(dims<1||dims>2000||!signature.matches("[a-f0-9]{64}"))continue;
            String name="idx_vec_"+signature.substring(0,40)+"_"+dims;
            try {
                db.execute((ConnectionCallback<Void>) connection -> {
                    // Serialize DDL across application instances; CONCURRENTLY must run outside a transaction.
                    try(Statement stmt=connection.createStatement()) {
                        boolean locked;
                        try(var result=stmt.executeQuery("SELECT pg_try_advisory_lock(742913580)")){result.next();locked=result.getBoolean(1);}
                        if(!locked)return null;
                        try {
                            stmt.setQueryTimeout(600);
                            // Failed concurrent index builds leave invalid indexes: remove only our own invalid index.
                            try(var result=stmt.executeQuery("SELECT indisvalid FROM pg_index WHERE indexrelid=to_regclass('"+name+"')")) {
                                if(result.next()&&!result.getBoolean(1)) {
                                    result.close();stmt.execute("DROP INDEX CONCURRENTLY IF EXISTS "+name);
                                }
                            }
                            stmt.execute("CREATE INDEX CONCURRENTLY IF NOT EXISTS "+name+" ON chunk USING hnsw (("+expression(signature,dims,"")+") vector_cosine_ops) WITH (m=16,ef_construction=64) WHERE "+predicate(signature,dims,""));
                        } finally {stmt.execute("SELECT pg_advisory_unlock(742913580)");}
                    }
                    return null;
                });
            } catch(Exception e) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("向量索引构建失败，将继续使用距离检索：{}",name);
            }
        }
    }
}
