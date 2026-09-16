package com.videoagent.rag.chunk;

import org.springframework.stereotype.Component;

/**
 * Small, deterministic, local token estimator for transcript chunking.
 *
 * <p>This is not the hosted DashScope text-embedding-v4 tokenizer. It assigns
 * one unit to each CJK code point, punctuation/symbol, and line break, while
 * contiguous ASCII letters or digits use an approximate four-characters-per-
 * token scale. The result is only an engineering estimate used to keep chunk
 * sizes reasonably stable without adding network I/O or a model runtime.</p>
 */
@Component
public class HeuristicTokenEstimator implements TokenEstimator {

    private static final int ASCII_CHARS_PER_TOKEN = 4;

    @Override
    public int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }

        int tokens = 0;
        int asciiRunLength = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);

            if (isAsciiLetterOrDigit(codePoint)) {
                asciiRunLength++;
                continue;
            }

            tokens += estimateAsciiRun(asciiRunLength);
            asciiRunLength = 0;

            if (codePoint == '\n' || codePoint == '\r') {
                tokens++;
            } else if (Character.isWhitespace(codePoint)) {
                // Spaces separate ASCII pieces but usually do not need their
                // own budget unit in this intentionally coarse estimate.
                continue;
            } else {
                // CJK, non-ASCII letters, punctuation, symbols and emoji are
                // all counted by Unicode code point rather than UTF-16 char.
                tokens++;
            }
        }
        return tokens + estimateAsciiRun(asciiRunLength);
    }

    private boolean isAsciiLetterOrDigit(int codePoint) {
        return codePoint <= 0x7F && Character.isLetterOrDigit(codePoint);
    }

    private int estimateAsciiRun(int length) {
        return length == 0 ? 0 : (length + ASCII_CHARS_PER_TOKEN - 1) / ASCII_CHARS_PER_TOKEN;
    }
}
