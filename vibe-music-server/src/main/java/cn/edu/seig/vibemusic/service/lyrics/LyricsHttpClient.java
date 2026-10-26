package cn.edu.seig.vibemusic.service.lyrics;

import cn.edu.seig.vibemusic.config.LyricsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 歌词数据源的 HTTP 支撑：统一构建带超时/可选代理的客户端，并处理重试。
 * <p>
 * 重试策略：仅对网络异常与 429/5xx 重试，最多 {@code maxRetries} 次，线性退避。
 * 4xx（除 429）视为确定性失败，立即返回 null，避免无谓重试。
 */
@Slf4j
@Component
public class LyricsHttpClient {

    /** A lyrics payload is text; 2 MB leaves ample room while bounding a hostile response. */
    static final long MAX_RESPONSE_BYTES = 2L * 1024 * 1024;

    /** 依据配置构建 HttpClient（连接超时、读取超时、可选代理） */
    public HttpClient create(LyricsProperties.Provider config, String proxy) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(500, config.getConnectTimeoutMs())))
                .followRedirects(HttpClient.Redirect.NORMAL);

        if (StringUtils.hasText(proxy)) {
            try {
                URI uri = URI.create(proxy.trim());
                int port = uri.getPort() > 0 ? uri.getPort() : 80;
                builder.proxy(ProxySelector.of(new InetSocketAddress(uri.getHost(), port)));
                log.info("歌词抓取已启用代理: {}", proxy);
            } catch (Exception e) {
                log.warn("歌词抓取代理配置无效，已回退为直连: {}", proxy, e);
            }
        }
        return builder.build();
    }

    /**
     * 发起 GET 请求并返回响应体文本。
     *
     * @return 响应体；失败时返回 null
     */
    public String get(HttpClient client, String url, Map<String, String> headers,
                      int readTimeoutMs, int maxRetries, String source) {
        int attempts = Math.max(1, maxRetries + 1);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofMillis(Math.max(1000, readTimeoutMs)))
                        .GET();
                headers.forEach(requestBuilder::header);

                HttpResponse<InputStream> response = client.send(
                        requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();

                if (status == 200) {
                    long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                    if (declared > MAX_RESPONSE_BYTES) {
                        closeQuietly(response.body());
                        log.warn("歌词源 {} 响应超过 {} 字节上限，已拒绝", source, MAX_RESPONSE_BYTES);
                        return null;
                    }
                    try (InputStream input = response.body()) {
                        byte[] content = readAtMost(input, MAX_RESPONSE_BYTES);
                        if (content == null) {
                            log.warn("歌词源 {} 响应超过 {} 字节上限，已中止读取",
                                    source, MAX_RESPONSE_BYTES);
                            return null;
                        }
                        return new String(content, StandardCharsets.UTF_8);
                    }
                }
                closeQuietly(response.body());
                if (status == 429 || status >= 500) {
                    log.debug("歌词源 {} 返回 {}，第 {}/{} 次尝试", source, status, attempt, attempts);
                } else {
                    log.warn("歌词源 {} 请求失败，HTTP {}，url={}", source, status, url);
                    return null;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                log.debug("歌词源 {} 请求异常（第 {}/{} 次）：{}", source, attempt, attempts, e.toString());
            }

            if (attempt < attempts) {
                try {
                    Thread.sleep(400L * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        log.warn("歌词源 {} 重试 {} 次后仍失败", source, maxRetries);
        return null;
    }

    private static byte[] readAtMost(InputStream input, long maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                return null;
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (IOException ignored) {
            // The response is already being discarded.
        }
    }

}
