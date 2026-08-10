package com.videoagent.rag.embedding;

import java.util.List;

/**
 * Deterministic mock embedding for unit tests, infra tests and local
 * development without a paid embedding API. It is NOT a real semantic model:
 * it maps a fixed keyword vocabulary to fixed coordinate positions and hashes
 * the remaining text for a stable vector. The same text always produces the
 * same vector, so retrieval ordering is repeatable.
 */
public class MockEmbeddingProvider implements EmbeddingProvider {

    private static final int DIMENSION = 384;

    private static final List<String> VOCABULARY = List.of(
        "redis", "缓存", "进度",
        "rocketmq", "异步", "消息",
        "spring", "security", "jwt",
        "mysql", "数据库", "保存",
        "minio", "存储", "对象",
        "ffmpeg", "音频", "提取",
        "transcript", "字幕", "时间戳",
        "summary", "总结", "章节",
        "视频", "问答", "检索"
    );

    @Override
    public String providerName() {
        return "mock";
    }

    @Override
    public int dimension() {
        return DIMENSION;
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }

    @Override
    public float[] embedQuery(String text) {
        return embed(text);
    }

    private float[] embed(String text) {
        String normalized = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
        float[] vector = new float[DIMENSION];
        for (int i = 0; i < VOCABULARY.size(); i++) {
            String keyword = VOCABULARY.get(i);
            if (normalized.contains(keyword)) {
                vector[i] = 1.0f;
            }
        }
        // Deterministic hash of the remaining text spreads content that does not
        // hit the vocabulary across the vector tail so distinct texts are not
        // identical.
        int hash = deterministicHash(normalized);
        vector[VOCABULARY.size()] = (hash & 0xFF) / 255.0f;
        return vector;
    }

    private int deterministicHash(String text) {
        int hash = 17;
        for (int i = 0; i < text.length(); i++) {
            hash = hash * 31 + text.charAt(i);
        }
        return hash;
    }
}
