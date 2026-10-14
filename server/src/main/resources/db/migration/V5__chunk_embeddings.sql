-- Dimension is not hard-coded: each row records its model and dimension, and retrieval only compares
-- vectors from the same model and dimension. No ANN index: first version uses exact search as the baseline.
CREATE TABLE chunk_embedding (
  chunk_id  uuid PRIMARY KEY REFERENCES chunk (id) ON DELETE CASCADE,
  model     text    NOT NULL CHECK (char_length(model) BETWEEN 1 AND 200),
  dimension integer NOT NULL CHECK (dimension > 0),
  embedding vector  NOT NULL,
  CHECK (vector_dims(embedding) = dimension)
);
CREATE INDEX chunk_embedding_model_idx ON chunk_embedding (model, dimension);
