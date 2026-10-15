package com.enterprise.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IngestionService {
    private final JdbcTemplate db; private final ModelSettings settings; private final ObjectMapper json;
    private final TransactionTemplate tx; private final boolean postgres; private final FileStore store;
    private final OcrService ocr; private final int maxTokens; private final int overlapTokens; private final DocumentSecurity security; private final int leaseSeconds;
    public IngestionService(JdbcTemplate db,ModelSettings settings,ObjectMapper json,TransactionTemplate tx,Environment env,FileStore store,
            @Value("${app.chunk.max-tokens:512}") int maxTokens,
            @Value("${app.chunk.overlap-tokens:64}") int overlapTokens,
            OcrService ocr, DocumentSecurity security,
            @Value("${app.ingestion.lease-seconds:300}") int leaseSeconds) {
        this.db=db;this.settings=settings;this.json=json;this.tx=tx;this.store=store;
        this.postgres=Arrays.asList(env.getActiveProfiles()).contains("postgres");this.maxTokens=maxTokens;this.overlapTokens=overlapTokens;this.ocr=ocr;this.security=security;this.leaseSeconds=leaseSeconds;
    }
    private record Page(Integer number,String text) {}
    private record Chunk(String content,Integer page) {}
    /** 回收过期租约：崩溃实例遗留的 PROCESSING 任务在租约到期后重新变为 PENDING。 */
    @Scheduled(fixedDelay=30000,initialDelay=30000)
    public void reclaimExpiredLeases() {
        db.update("UPDATE document SET status='PENDING' WHERE status='PROCESSING' AND lease_expires_at < CURRENT_TIMESTAMP");
    }
    @Scheduled(fixedDelay=2000,initialDelay=5000)
    public void processNext() {
        var jobs=db.queryForList("SELECT * FROM document WHERE status='PENDING' ORDER BY created_at LIMIT 1");
        if(jobs.isEmpty())return;
        var job=jobs.get(0);String id=(String)job.get("id");
        var lease=new java.sql.Timestamp(System.currentTimeMillis()+leaseSeconds*1000L);
        if(db.update("UPDATE document SET status='PROCESSING',error_message=NULL,lease_expires_at=? WHERE id=? AND (status='PENDING' OR (status='PROCESSING' AND (lease_expires_at IS NULL OR lease_expires_at < CURRENT_TIMESTAMP)))",lease,id)==0)return;
        try {
            var model=settings.client();
            String key=(String)job.get("storage_key");String name=(String)job.get("name");
            Path file=store.toLocalFile(key);
            try {
                security.check(file,name);
                List<Page> pages=extract(file,name);
                List<Chunk> chunks=new ArrayList<>();
                for(Page p:pages)for(String c:TextChunks.splitByTokens(p.text(),maxTokens,overlapTokens))chunks.add(new Chunk(c,p.number()));
                if(chunks.isEmpty())throw new IllegalArgumentException("文档没有可提取文本；扫描 PDF 需要先进行 OCR");
                List<String> vectors=new ArrayList<>();
                for(Chunk chunk:chunks)vectors.add(model.embeddingEnabled?json.writeValueAsString(model.embed(chunk.content())):null);
                tx.executeWithoutResult(status -> {
                    // Lock against concurrent deletion, so no partial index becomes visible.
                    if(db.queryForList("SELECT id FROM document WHERE id=? FOR UPDATE",id).isEmpty())return;
                    db.update("DELETE FROM chunk WHERE document_id=?",id);
                    for(int i=0;i<chunks.size();i++) {
                        String cid=UUID.randomUUID().toString();
                        db.update("INSERT INTO chunk(id,document_id,ordinal,content,embedding,embedding_model,page) VALUES(?,?,?,?,?,?,?)",
                            cid,id,i+1,chunks.get(i).content(),vectors.get(i),model.embeddingEnabled?model.signature():null,chunks.get(i).page());
                        if(postgres && vectors.get(i)!=null)db.update("UPDATE chunk SET vector_embedding=CAST(? AS vector) WHERE id=?",vectors.get(i),cid);
                    }
                    db.update("UPDATE document SET status='READY',chunk_count=?,error_message=NULL WHERE id=?",chunks.size(),id);
                });
            } finally {
                store.releaseLocal(file);
            }
        } catch(Exception ex) {
            String error=ex instanceof IllegalArgumentException?ex.getMessage():"解析或模型调用失败，请检查文件及模型配置后重试";
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("文档 {} 入库失败：{}",id,ex.getClass().getSimpleName());
            db.update("UPDATE document SET status='FAILED',error_message=? WHERE id=?",error,id);
        }
    }
    private List<Page> extract(Path path,String name) throws Exception {
        String lower=name.toLowerCase(Locale.ROOT);
        if(lower.endsWith(".pdf"))try(var pdf=Loader.loadPDF(path.toFile())) {
            int count=pdf.getNumberOfPages();List<Page> pages=new ArrayList<>();
            for(int p=1;p<=count;p++){PDFTextStripper s=new PDFTextStripper();s.setStartPage(p);s.setEndPage(p);String t=s.getText(pdf);if(ocr.needed(t)){String recognized=ocr.recognize(path,pdf,p);if(!recognized.isBlank())t=recognized;}pages.add(new Page(p,t));}
            return pages;
        }
        if(lower.endsWith(".docx"))try(var in=Files.newInputStream(path);var doc=new XWPFDocument(in);var extractor=new XWPFWordExtractor(doc)){return List.of(new Page(null,extractor.getText()));}
        return List.of(new Page(null,Files.readString(path)));
    }
}
