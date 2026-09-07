package com.videoagent.rag.rerank;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.videoagent.rag.config.RagProperties;
import com.videoagent.rag.retrieval.HybridCandidate;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class HttpTranscriptReranker implements TranscriptReranker {
    private final RagProperties.Reranker properties;
    private final RestClient client;

    @org.springframework.beans.factory.annotation.Autowired
    public HttpTranscriptReranker(RagProperties ragProperties) {
        this.properties = ragProperties.reranker();
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.timeout())
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.timeout());
        this.client = RestClient.builder().requestFactory(requestFactory).build();
    }

    HttpTranscriptReranker(RagProperties.Reranker properties, RestClient client) {
        this.properties = properties;
        this.client = client;
    }

    @Override
    public boolean enabled() {
        return properties.enabled();
    }

    @Override
    public List<HybridCandidate> rerank(String query, List<HybridCandidate> candidates) {
        RerankRequest request = new RerankRequest(properties.model(), query,
            candidates.stream().map(HybridCandidate::text).toList());
        var requestSpec = client.post()
            .uri(properties.baseUrl() + "/rerank")
            .contentType(MediaType.APPLICATION_JSON);
        if (!properties.apiKey().isBlank()) {
            requestSpec = requestSpec.header("Authorization", "Bearer " + properties.apiKey());
        }
        RerankResponse response = requestSpec.body(request).retrieve().body(RerankResponse.class);
        if (response == null || response.results() == null || response.results().size() != candidates.size()) {
            throw new IllegalStateException("Reranker returned an incomplete response");
        }
        List<HybridCandidate> reranked = new ArrayList<>(candidates.size());
        boolean[] seen = new boolean[candidates.size()];
        for (RerankResult result : response.results()) {
            if (result.index() < 0 || result.index() >= candidates.size() || seen[result.index()]) {
                throw new IllegalStateException("Reranker returned an invalid candidate index");
            }
            seen[result.index()] = true;
            reranked.add(candidates.get(result.index()).withRerankScore(result.relevanceScore()));
        }
        reranked.sort(Comparator.comparing(HybridCandidate::rerankScore).reversed()
            .thenComparing(HybridCandidate::chunkId));
        return List.copyOf(reranked);
    }

    private record RerankRequest(String model, String query, List<String> documents) { }
    private record RerankResponse(List<RerankResult> results) { }
    private record RerankResult(int index, @JsonProperty("relevance_score") double relevanceScore) { }
}
