package com.enterprise.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IngestionService {
    private final JdbcTemplate db; private final ModelSettings settings; private final ObjectMapper json;
    private final TransactionTemplate tx; private final boolean postgres; public final Path storage;
    public IngestionService(JdbcTemplate db,ModelSettings settings,ObjectMapper json,TransactionTemplate tx,Environment env,@Value("${app.storage}") String storage) throws Exception {
        this.db=db;this.settings=settings;this.json=json;this.tx=tx;this.storage=Path.of(storage).toAbsolutePath().normalize();
        this.postgres=Arrays.asList(env.getActiveProfiles()).contains("postgres");Files.createDirectories(this.storage);
    }
    @Bean ApplicationRunner recoverJobs() {return args -> db.update("UPDATE document SET status='PENDING' WHERE status='PROCESSING'");}
    @Scheduled(fixedDelay=2000,initialDelay=5000)
    public void processNext() {
        var jobs=db.queryForList("SELECT * FROM document WHERE status='PENDING' ORDER BY created_at LIMIT 1");
        if(jobs.isEmpty())return;
        var job=jobs.get(0);String id=(String)job.get("id");
        if(db.update("UPDATE document SET status='PROCESSING',error_message=NULL WHERE id=? AND status='PENDING'",id)==0)return;
        try {
            var model=settings.client();
            String text=extract(storage.resolve((String)job.get("storage_key")),(String)job.get("name"));
            if(text.length()>2_000_000)throw new IllegalArgumentException("提取文本超过 200 万字符，请拆分文档");
            var chunks=TextChunks.split(text,900,150);
            if(chunks.isEmpty())throw new IllegalArgumentException("文档没有可提取文本；扫描 PDF 需要先进行 OCR");
            List<String> vectors=new ArrayList<>();
            for(String chunk:chunks)vectors.add(model.embeddingEnabled?json.writeValueAsString(model.embed(chunk)):null);
            tx.executeWithoutResult(status -> {
                // Lock against concurrent deletion, so no partial index becomes visible.
                if(db.queryForList("SELECT id FROM document WHERE id=? FOR UPDATE",id).isEmpty())return;
                db.update("DELETE FROM chunk WHERE document_id=?",id);
                for(int i=0;i<chunks.size();i++) {
                    String cid=UUID.randomUUID().toString();
                    db.update("INSERT INTO chunk(id,document_id,ordinal,content,embedding,embedding_model) VALUES(?,?,?,?,?,?)",cid,id,i+1,chunks.get(i),vectors.get(i),model.embeddingEnabled?model.signature():null);
                    if(postgres && vectors.get(i)!=null)db.update("UPDATE chunk SET vector_embedding=CAST(? AS vector) WHERE id=?",vectors.get(i),cid);
                }
                db.update("UPDATE document SET status='READY',chunk_count=?,error_message=NULL WHERE id=?",chunks.size(),id);
            });
        } catch(Exception ex) {
            String error=ex instanceof IllegalArgumentException?ex.getMessage():"解析或模型调用失败，请检查文件及模型配置后重试";
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("文档 {} 入库失败：{}",id,ex.getClass().getSimpleName());
            db.update("UPDATE document SET status='FAILED',error_message=? WHERE id=?",error,id);
        }
    }
    private String extract(Path path,String name) throws Exception {
        String lower=name.toLowerCase(Locale.ROOT);
        if(lower.endsWith(".pdf"))try(var pdf=Loader.loadPDF(path.toFile())) {return new PDFTextStripper().getText(pdf);}
        if(lower.endsWith(".docx"))try(var in=Files.newInputStream(path);var doc=new XWPFDocument(in);var extractor=new XWPFWordExtractor(doc)){return extractor.getText();}
        return Files.readString(path);
    }
}
