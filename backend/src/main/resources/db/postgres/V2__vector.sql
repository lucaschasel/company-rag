CREATE EXTENSION IF NOT EXISTS vector;
ALTER TABLE chunk ADD COLUMN vector_embedding vector;
