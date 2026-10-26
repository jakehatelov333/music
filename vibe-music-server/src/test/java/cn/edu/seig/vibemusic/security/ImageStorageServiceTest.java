package cn.edu.seig.vibemusic.security;

import cn.edu.seig.vibemusic.service.MinioService;
import cn.edu.seig.vibemusic.service.TestImages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageStorageServiceTest {

    private MinioService minio;
    private ImageStorageService images;

    @BeforeEach
    void setUp() {
        minio = mock(MinioService.class);
        images = new ImageStorageService(minio);
        when(minio.uploadBytes(any(byte[].class), anyString(), anyString(), eq("image/jpeg")))
                .thenReturn("http://minio.local/stored.jpg");
    }

    @Test
    @DisplayName("真实 JPEG：解码、重编码，并以服务端对象名存储")
    void acceptsRealJpeg() {
        String url = images.storeAvatar(
                new MockMultipartFile("avatar", "a.jpg", "image/jpeg", TestImages.jpeg()), "users");

        assertEquals("http://minio.local/stored.jpg", url);
        verify(minio).uploadBytes(any(byte[].class), eq("users"),
                matches("[0-9a-f-]+\\.jpg"),
                eq("image/jpeg"));
    }

    @Test
    @DisplayName("伪造 MIME：字节不是图片时拒绝，且不写入 MinIO")
    void rejectsForgedMime() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                images.storeAvatar(new MockMultipartFile(
                        "avatar", "evil.jpg", "image/jpeg",
                        "not an image".getBytes(StandardCharsets.UTF_8)), "users"));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(minio, never()).uploadBytes(any(byte[].class), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("扩展名与实际格式不一致：拒绝")
    void rejectsExtensionFormatMismatch() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                images.storeAvatar(new MockMultipartFile(
                        "avatar", "photo.jpg", "image/jpeg", TestImages.png()), "users"));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(minio, never()).uploadBytes(any(byte[].class), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("超过头像字节上限：在读取/写入前拒绝")
    void rejectsOversizedAvatar() {
        byte[] oversized = new byte[(int) MediaPolicy.MAX_AVATAR_BYTES + 1];
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                images.storeAvatar(new MockMultipartFile(
                        "avatar", "huge.jpg", "image/jpeg", oversized), "users"));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(minio, never()).uploadBytes(any(byte[].class), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("服务端字节入口不依赖文件名，真实 PNG 可接受")
    void serverBytesAcceptPngWithoutFilename() {
        String url = images.storeImage(TestImages.png(), "artists", 512);

        assertEquals("http://minio.local/stored.jpg", url);
        verify(minio).uploadBytes(any(byte[].class), eq("artists"), anyString(), eq("image/jpeg"));
    }

    @Test
    @DisplayName("staging 校验读取不超过上限，并返回服务端检测的类型")
    void validatesBoundedCoverStream() {
        byte[] png = TestImages.png();
        ImageStorageService.ValidatedImage result = images.validateCover(
                new ByteArrayInputStream(png), "cover.png", png.length);

        assertEquals("image/png", result.contentType());
    }
}
