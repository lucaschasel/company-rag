-- The vector column intentionally supports different model dimensions.
-- HNSW expression/partial indexes are built per model and dimension by VectorIndexes.
-- Never create HNSW directly on an unbounded vector column.
CREATE INDEX IF NOT EXISTS idx_chunk_embedding_model ON chunk (embedding_model);
