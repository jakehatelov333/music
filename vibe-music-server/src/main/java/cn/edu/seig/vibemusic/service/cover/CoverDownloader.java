package cn.edu.seig.vibemusic.service.cover;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * 封面下载器：把一个 CDN 上的图片取回内存，交给调用方写入 staging。
 *
 * <h3>三条硬约束</h3>
 * <ol>
 *   <li><b>流式截断，不是读完再判断</b>：先按 Content-Length 预判，再用
 *       {@code BODY_CONSUMER} 边读边计数，超过上限立即中断并丢弃。若先把整个响应
 *       读进内存再比大小，一个坏响应（或恶意的超大文件）就能把堆打爆。</li>
 *   <li><b>必须校验 Content-Type 是图片</b>：不校验的话，CDN 返回一段 HTML 错误页
 *       也会被当成封面存进 MinIO，之后前端显示破图，而且问题被推迟到用户看到时才暴露。</li>
 *   <li><b>失败要能区分原因</b>：404 是「这张专辑没封面」，超限是「图太大」，
 *       网络错误是「稍后重试」。都返回 null 的话，管理员只会看到一句无用的"失败"。</li>
 * </ol>
 */
@Slf4j
@Component
public class CoverDownloader {

    /** 单张封面体积上限。实测 800px 最大约 171 KB，5 MB 留足余量。 */
    static final long MAX_BYTES = 5L * 1024 * 1024;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    /** 下载结果。失败时 {@code bytes} 为 null，{@code error} 是可读原因。 */
    public record Result(byte[] bytes, String contentType, String error) {

        static Result ok(byte[] bytes, String contentType) {
            return new Result(bytes, contentType, null);
        }

        static Result fail(String error) {
            return new Result(null, null, error);
        }

        public boolean success() {
            return bytes != null && bytes.length > 0;
        }
    }

    private final HttpClient client;

    public CoverDownloader() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 下载封面。
     *
     * @param url 封面地址，必须由 {@link QqCoverUrl} 拼出（调用方保证来源受限）
     * @return 结果；任何失败都返回带可读原因的 {@link Result#fail}
     */
    public Result download(String url) {
        if (url == null || url.isBlank()) {
            return Result.fail("封面地址为空");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(READ_TIMEOUT)
                    .GET()
                    .build();

            // 用 InputStream 而不是 ofByteArray：只有流式才能做到"读到超限就停"
            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());

            int status = response.statusCode();
            if (status == 404) {
                return Result.fail("该专辑没有可用的封面图");
            }
            if (status != 200) {
                return Result.fail("封面下载失败，服务返回 " + status);
            }

            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!isImage(contentType)) {
                closeQuietly(response.body());
                return Result.fail("返回内容不是图片（" + contentType + "），可能该专辑无封面");
            }

            long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (declared > MAX_BYTES) {
                closeQuietly(response.body());
                return Result.fail("封面体积超出上限（" + readableSize(declared) + "）");
            }

            byte[] bytes;
            try (InputStream input = response.body()) {
                bytes = readAtMost(input, MAX_BYTES);
            }
            if (bytes == null) {
                return Result.fail("封面体积超出上限（超过 " + readableSize(MAX_BYTES) + "）");
            }
            if (bytes.length == 0) {
                return Result.fail("封面内容为空");
            }
            return Result.ok(bytes, normalizeContentType(contentType));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.fail("封面下载被中断");
        } catch (Exception e) {
            log.warn("封面下载失败：url={}", url, e);
            return Result.fail("封面下载失败，请稍后重试");
        }
    }

    /**
     * 最多读取 {@code max} 字节；超出返回 {@code null}。
     *
     * <p>关键在「超出就停」：不能读完整个流再判断长度。
     */
    private byte[] readAtMost(InputStream input, long max) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(chunk)) != -1) {
            total += read;
            if (total > max) {
                return null;
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private boolean isImage(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            // 有些 CDN 不返回 Content-Type，此时宁可继续也不要误杀 —— 后续还有体积与
            // 解码环节兜底，且 URL 模板本身已经限定为 .jpg
            return true;
        }
        return contentType.toLowerCase(Locale.ROOT).startsWith("image/");
    }

    /** 统一成 {@code image/jpeg} 这种形态，去掉 {@code ;charset=...} 之类的参数。 */
    private String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "image/jpeg";
        }
        String base = contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        return base.startsWith("image/") ? base : "image/jpeg";
    }

    private String readableSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.0f KB", bytes / 1024d);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1024d / 1024d);
    }

    private void closeQuietly(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (Exception ignored) {
            // 关闭失败不影响判定结果
        }
    }
}
