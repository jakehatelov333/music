package cn.edu.seig.vibemusic.service.lyrics;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LyricsHttpClientTest {

    private HttpServer server;
    private LyricsHttpClient client;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new LyricsHttpClient();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("正常响应：返回完整 UTF-8 文本")
    void returnsNormalResponse() {
        server.createContext("/lyrics", exchange -> respond(exchange, 200,
                "[00:00.00]故事的小黄花".getBytes(StandardCharsets.UTF_8)));

        String body = get("/lyrics");

        assertEquals("[00:00.00]故事的小黄花", body);
    }

    @Test
    @DisplayName("Content-Length 超限：在读取正文前拒绝")
    void rejectsOversizedContentLength() {
        server.createContext("/too-large", exchange -> {
            long declared = LyricsHttpClient.MAX_RESPONSE_BYTES + 1;
            exchange.sendResponseHeaders(200, declared);
            exchange.getResponseBody().write(new byte[16]);
            exchange.close();
        });

        assertNull(get("/too-large"));
    }

    @Test
    @DisplayName("chunked 响应超限：边读边停，不缓冲完整正文")
    void rejectsOversizedChunkedBody() {
        server.createContext("/chunked", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            byte[] chunk = new byte[64 * 1024];
            for (int i = 0; i < 40; i++) {
                exchange.getResponseBody().write(chunk);
            }
            exchange.close();
        });

        assertNull(get("/chunked"));
    }

    private String get(String path) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        return client.get(http, baseUrl + path, Map.of(), 3000, 0, "test");
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
