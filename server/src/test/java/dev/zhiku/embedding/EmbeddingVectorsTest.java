package dev.zhiku.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class EmbeddingVectorsTest {
  @Test
  void acceptsFiniteNonZeroVectorsOfOneDimension() {
    assertThat(EmbeddingVectors.validate(List.of(new float[] {1, 0}, new float[] {0.5f, 0.5f}), 2)).isEqualTo(2);
  }

  @Test
  void rejectsInvalidVectors() {
    assertThatThrownBy(() -> EmbeddingVectors.validate(List.of(new float[] {1, 0}), 2)).hasMessageContaining("count");
    assertThatThrownBy(() -> EmbeddingVectors.validate(List.of(new float[] {1, 0}, new float[] {1}), 2)).hasMessageContaining("dimension");
    assertThatThrownBy(() -> EmbeddingVectors.validate(List.of(new float[] {Float.NaN, 1}), 1)).hasMessageContaining("finite");
    assertThatThrownBy(() -> EmbeddingVectors.validate(List.of(new float[] {Float.POSITIVE_INFINITY}), 1)).hasMessageContaining("finite");
    assertThatThrownBy(() -> EmbeddingVectors.validate(List.of(new float[] {0, 0}), 1)).hasMessageContaining("zero");
    assertThatThrownBy(() -> EmbeddingVectors.validate(List.of(new float[0]), 1)).hasMessageContaining("dimension");
  }

  @Test
  void literalIsPgvectorText() {
    assertThat(EmbeddingVectors.literal(new float[] {1f, -0.5f, 2.5e-6f})).isEqualTo("[1.0,-0.5,2.5E-6]");
  }
}
