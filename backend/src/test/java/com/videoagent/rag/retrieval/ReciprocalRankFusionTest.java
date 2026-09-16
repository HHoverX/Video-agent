package com.videoagent.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.videoagent.rag.vector.VectorPoint;
import org.junit.jupiter.api.Test;

import java.util.List;

class ReciprocalRankFusionTest {

    @Test
    void shouldFuseOneBasedRanksByStableChunkIdentityAndKeepSingleSourceHits() {
        List<VectorPoint> dense = List.of(
            dense(9, 0, "A"), dense(9, 1, "B"), dense(9, 2, "C")
        );
        List<LexicalChunk> lexical = List.of(
            lexical(9, 2, "C", 9.0), lexical(9, 3, "D", 8.0), lexical(9, 0, "A", 7.0)
        );

        List<HybridCandidate> result = new ReciprocalRankFusion().fuse(7L, dense, lexical, 60);

        assertThat(result).extracting(HybridCandidate::text).containsExactly("A", "C", "B", "D");
        assertThat(result).extracting(HybridCandidate::chunkId).doesNotHaveDuplicates();
        assertThat(result.getFirst().rrfScore()).isEqualTo(1.0 / 61 + 1.0 / 63);
        assertThat(result.get(1).rrfScore()).isEqualTo(1.0 / 63 + 1.0 / 61);
        assertThat(result.get(2).lexicalScore()).isNull();
        assertThat(result.get(3).denseScore()).isNull();
    }

    private VectorPoint dense(long taskId, int index, String text) {
        return VectorPoint.retrieved(taskId, index, text, index * 1000L, index * 1000L + 900,
            List.of(index), 1.0f - index / 10.0f);
    }

    private LexicalChunk lexical(long taskId, int index, String text, double score) {
        return new LexicalChunk(ChunkIdentity.of(7L, taskId, index), index, text,
            index * 1000L, index * 1000L + 900, List.of(index), score);
    }
}
