-- pgvector 0.7+ 支持对无固定维数的 vector 列建 HNSW 索引。
-- 检索按 cosine 距离排序；参数 m / ef_construction 可按数据量调优。
CREATE INDEX IF NOT EXISTS idx_chunk_embedding_hnsw
  ON chunk USING hnsw (vector_embedding vector_cosine_ops)
  WITH (m = 16, ef_construction = 64);
