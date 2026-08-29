package com.videoagent.rag.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.rag.config.QdrantProperties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

class QdrantVectorStoreTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldNotLogQdrantResponseBodyWhenSearchFails() throws Exception {
        String sentinel = "TOP_SECRET_PROVIDER_BODY_7F31";
        startServer(exchange -> respond(exchange, 500, sentinel));
        QdrantVectorStore store = new QdrantVectorStore(
            new QdrantProperties("127.0.0.1", server.getAddress().getPort(), "test_collection"),
            RestClient.builder().build(),
            new com.fasterxml.jackson.databind.ObjectMapper()
        );
        Logger logger = (Logger) LoggerFactory.getLogger(QdrantVectorStore.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> store.search(1L, 7L, new float[] {1.0f}, 3))
                .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.RAG_INDEX_BUILD_FAILED));
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
            .contains("RAG_INDEX_BUILD_FAILED", "httpStatus=500"));
        assertThat(appender.list).allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(sentinel));
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handler.handle(exchange));
        server.start();
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
