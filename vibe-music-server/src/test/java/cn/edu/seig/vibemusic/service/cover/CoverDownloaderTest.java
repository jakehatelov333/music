package cn.edu.seig.vibemusic.service.cover;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 封面下载器的三条硬约束。
 *
 * <p>用本地 {@link HttpServer} 而不是 mock：这里要验证的正是「流式读取时如何中断」，
 * mock 掉的流根本走不到那段逻辑。真实 CDN 无法构造超大响应，本地服务器可以。
 */
class CoverDownloaderTest {

    private HttpServer server;
    private CoverDownloader downloader;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        downloader = new CoverDownloader();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("正常图片：返回字节与归一化后的 Content-Type")
    void downloadsImage() {
        byte[] payload = jpegBytes();
        route("/ok.jpg", 200, "image/jpeg", payload);

        CoverDownloader.Result result = downloader.download(baseUrl + "/ok.jpg");

        assertTrue(result.success());
        assertEquals(payload.length, result.bytes().length);
        assertEquals("image/jpeg", result.contentType());
    }

    /** 404 要说清楚是「这张专辑没封面」，而不是笼统的"失败"。 */
    @Test
    @DisplayName("404 给出「该专辑没有可用封面图」而不是笼统失败")
    void reportsMissingCover() {
        route("/missing.jpg", 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));

        CoverDownloader.Result result = downloader.download(baseUrl + "/missing.jpg");

        assertFalse(result.success());
        assertTrue(result.error().contains("没有可用的封面"), "实际：" + result.error());
    }

    /**
     * 返回 HTML 错误页时必须拒绝。
     *
     * <p>不校验 Content-Type 的话，一段 HTML 会被当成封面存进 MinIO，
     * 前端显示破图，问题被推迟到用户看到时才暴露。
     */
    @Test
    @DisplayName("Content-Type 不是图片时拒绝")
    void rejectsNonImageContentType() {
        route("/error.html", 200, "text/html",
                "<html>error</html>".getBytes(StandardCharsets.UTF_8));

        CoverDownloader.Result result = downloader.download(baseUrl + "/error.html");

        assertFalse(result.success());
        assertTrue(result.error().contains("不是图片"), "实际：" + result.error());
    }

    /**
     * 超过 5 MB 必须拒绝，且是<b>边读边停</b>。
     *
     * <p>这条测试跑得很快（不是先读完 6 MB 再判断），说明实现确实是流式截断。
     * 若改成「读完整流再比大小」，一个超大响应就能把堆打爆。
     */
    @Test
    @DisplayName("体积超限时拒绝，且流式中断而非读完再判断")
    void rejectsOversizedImage() {
        // 6 MB，超过 5 MB 上限
        byte[] huge = new byte[6 * 1024 * 1024];
        huge[0] = (byte) 0xFF;
        huge[1] = (byte) 0xD8;
        route("/huge.jpg", 200, "image/jpeg", huge);

        long start = System.nanoTime();
        CoverDownloader.Result result = downloader.download(baseUrl + "/huge.jpg");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertFalse(result.success());
        assertTrue(result.error().contains("超出上限"), "实际：" + result.error());
        assertTrue(elapsedMs < 5000, "流式截断应在读完 6 MB 前就中断，实际耗时 " + elapsedMs + " ms");
    }

    /** Content-Length 声明超限时，应在读流之前就拒绝。 */
    @Test
    @DisplayName("Content-Length 声明超限时提前拒绝")
    void rejectsByDeclaredLength() {
        route("/declared.jpg", 200, "image/jpeg", new byte[1024], 10L * 1024 * 1024);

        CoverDownloader.Result result = downloader.download(baseUrl + "/declared.jpg");

        assertFalse(result.success());
        assertTrue(result.error().contains("超出上限"), "实际：" + result.error());
    }

    @Test
    @DisplayName("空响应体被拒绝")
    void rejectsEmptyBody() {
        route("/empty.jpg", 200, "image/jpeg", new byte[0]);

        CoverDownloader.Result result = downloader.download(baseUrl + "/empty.jpg");

        assertFalse(result.success());
    }

    @Test
    @DisplayName("地址为空时直接失败，不发起请求")
    void rejectsBlankUrl() {
        assertFalse(downloader.download(null).success());
        assertFalse(downloader.download("").success());
        assertFalse(downloader.download("   ").success());
    }

    @Test
    @DisplayName("连接失败时返回可读错误，不抛异常")
    void reportsConnectionFailure() {
        // 端口 1 几乎不可能有服务
        CoverDownloader.Result result = downloader.download("http://127.0.0.1:1/x.jpg");

        assertFalse(result.success());
        assertNotNull(result.error());
        assertTrue(result.error().contains("稍后重试") || result.error().contains("失败"),
                "实际：" + result.error());
    }

    // ------------------------------------------------------------------ 夹具

    private void route(String path, int status, String contentType, byte[] body) {
        route(path, status, contentType, body, null);
    }

    /**
     * 注册一个路由。
     *
     * @param declaredLength 非 null 时用它覆盖 Content-Length（用于测试"声明超限"）
     */
    private void route(String path, int status, String contentType, byte[] body, Long declaredLength) {
        server.createContext(path, (HttpExchange exchange) -> {
            exchange.getResponseHeaders().set("Content-Type", contentType);
            if (declaredLength != null) {
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(declaredLength));
                exchange.sendResponseHeaders(status, declaredLength);
                // 只写实际字节，模拟"声明很大、实际很小"的响应
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
                return;
            }
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
    }

    private static byte[] jpegBytes() {
        byte[] bytes = new byte[256];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        bytes[3] = (byte) 0xE0;
        return bytes;
    }
}
