package cn.edu.seig.vibemusic.security;

import cn.edu.seig.vibemusic.constant.MessageConstant;
import cn.edu.seig.vibemusic.service.MinioService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;

/**
 * Server-side image validation and re-encoding.
 *
 * <p>Never trusts filename or client Content-Type: the bytes must decode as an image,
 * and the stored object is always a newly encoded JPEG with a server-chosen key.
 */
@Service
@RequiredArgsConstructor
public class ImageStorageService {

    private static final long MAX_AVATAR_BYTES = MediaPolicy.MAX_AVATAR_BYTES;
    private static final long MAX_COVER_BYTES = MediaPolicy.MAX_IMAGE_BYTES;
    private static final int MAX_DIMENSION = MediaPolicy.MAX_IMAGE_DIMENSION;
    private static final long MAX_PIXELS = MediaPolicy.MAX_IMAGE_PIXELS;
    private static final int AVATAR_SIZE = 512;

    private final MinioService minioService;

    public String storeAvatar(MultipartFile file, String folder) {
        return store(file, folder, MAX_AVATAR_BYTES, AVATAR_SIZE);
    }

    public String storeCover(MultipartFile file, String folder) {
        return store(file, folder, MAX_COVER_BYTES, 1600);
    }

    public String storeImage(byte[] content, String originalFilename, String folder, int maxEdge) {
        DecodedImage decoded = decode(content, originalFilename, MAX_COVER_BYTES, true);
        return storeScaled(decoded.image(), folder, maxEdge);
    }

    /**
     * Stores bytes fetched by the server (for example an embedded artwork or a
     * provider response). The bytes still have to decode as JPEG/PNG, but no
     * client-provided filename or MIME type is trusted.
     */
    public String storeImage(byte[] content, String folder, int maxEdge) {
        DecodedImage decoded = decode(content, null, MAX_COVER_BYTES, false);
        return storeScaled(decoded.image(), folder, maxEdge);
    }

    public void validateCover(MultipartFile file) {
        decode(file, MAX_COVER_BYTES);
    }

    /**
     * Validates an object read from MinIO without buffering more than the image limit.
     *
     * @return the server-detected content type to use when copying the object
     */
    public ValidatedImage validateCover(InputStream input, String originalFilename, long declaredSize) {
        if (declaredSize <= 0 || declaredSize > MAX_COVER_BYTES) {
            throw tooLarge(MAX_COVER_BYTES);
        }
        byte[] content = readBounded(input, MAX_COVER_BYTES);
        if (content.length != declaredSize) {
            throw badRequest("图片实际大小与声明不一致");
        }
        return new ValidatedImage(decode(content, originalFilename, MAX_COVER_BYTES, true).contentType());
    }

    private String store(MultipartFile file, String folder, long maxBytes, int maxEdge) {
        DecodedImage decoded = decode(file, maxBytes);
        return storeScaled(decoded.image(), folder, maxEdge);
    }

    private String storeScaled(BufferedImage source, String folder, int maxEdge) {
        BufferedImage scaled = scale(source, maxEdge);
        byte[] jpeg = encodeJpeg(scaled);
        String objectName = UUID.randomUUID() + ".jpg";
        return minioService.uploadBytes(jpeg, folder, objectName, "image/jpeg");
    }

    private DecodedImage decode(MultipartFile file, long maxBytes) {
        if (file == null || file.isEmpty()) {
            throw badRequest("请选择图片文件");
        }
        if (file.getSize() > maxBytes) {
            throw tooLarge(maxBytes);
        }
        String name = file.getOriginalFilename();
        if (!MediaPolicy.isImageExtension(MediaPolicy.extensionOf(name))) {
            throw badRequest("图片仅支持 JPG、PNG 格式");
        }
        try (InputStream input = file.getInputStream()) {
            byte[] content = readBounded(input, maxBytes);
            if (content.length != file.getSize()) {
                throw badRequest("图片实际大小与声明不一致");
            }
            return decode(content, name, maxBytes, true);
        } catch (IOException e) {
            throw badRequest("文件内容不是有效图片");
        }
    }

    private DecodedImage decode(byte[] content, String filename, long maxBytes, boolean requireExtensionMatch) {
        if (content == null || content.length == 0) {
            throw badRequest("图片内容为空");
        }
        if (content.length > maxBytes) {
            throw tooLarge(maxBytes);
        }
        if (requireExtensionMatch
                && !MediaPolicy.isImageExtension(MediaPolicy.extensionOf(filename))) {
            throw badRequest("图片仅支持 JPG、PNG 格式");
        }
        try (InputStream input = new ByteArrayInputStream(content)) {
            return decodeImage(input, filename, requireExtensionMatch);
        } catch (IOException e) {
            throw badRequest("文件内容不是有效图片");
        }
    }

    /**
     * Read dimensions before decoding pixels. This closes the "small compressed file,
     * enormous raster" memory-exhaustion window.
     */
    private DecodedImage decodeImage(InputStream input, String filename, boolean requireExtensionMatch)
            throws IOException {
        try (ImageInputStream imageInput = ImageIO.createImageInputStream(input)) {
            if (imageInput == null) {
                throw badRequest("文件内容不是有效图片");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw badRequest("文件内容不是有效图片");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    throw badRequest("文件内容不是有效图片");
                }
                if (width > MAX_DIMENSION || height > MAX_DIMENSION
                        || (long) width * height > MAX_PIXELS) {
                    throw badRequest("图片尺寸或像素数超过安全上限");
                }
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                String contentType = contentTypeForFormat(format);
                String extension = MediaPolicy.extensionOf(filename);
                if (requireExtensionMatch && !formatMatchesExtension(format, extension)) {
                    throw badRequest("图片扩展名与实际格式不一致");
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw badRequest("文件内容不是有效图片");
                }
                return new DecodedImage(image, contentType);
            } finally {
                reader.dispose();
            }
        }
    }

    private String contentTypeForFormat(String format) {
        if (format.contains("jpeg") || format.contains("jpg")) {
            return "image/jpeg";
        }
        if (format.contains("png")) {
            return "image/png";
        }
        throw badRequest("图片仅支持 JPG、PNG 格式");
    }

    private boolean formatMatchesExtension(String format, String extension) {
        if ("jpg".equals(extension) || "jpeg".equals(extension)) {
            return format.contains("jpeg") || format.contains("jpg");
        }
        return "png".equals(extension) && format.contains("png");
    }

    private byte[] readBounded(InputStream input, long maxBytes) {
        if (input == null) {
            throw badRequest("图片内容为空");
        }
        int initial = (int) Math.min(maxBytes, 8192);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(initial)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw tooLarge(maxBytes);
                }
                output.write(buffer, 0, read);
            }
            if (total == 0) {
                throw badRequest("图片内容为空");
            }
            return output.toByteArray();
        } catch (IOException e) {
            throw badRequest("图片读取失败");
        }
    }

    private BufferedImage scale(BufferedImage source, int maxEdge) {
        int width = source.getWidth();
        int height = source.getHeight();
        double ratio = Math.min(1d, (double) maxEdge / Math.max(width, height));
        int targetWidth = Math.max(1, (int) Math.round(width * ratio));
        int targetHeight = Math.max(1, (int) Math.round(height * ratio));
        BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private byte[] encodeJpeg(BufferedImage image) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    MessageConstant.FILE_UPLOAD + MessageConstant.FAILED);
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            ImageWriteParam params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(0.88f);
            }
            writer.write(null, new IIOImage(image, null, null), params);
            return output.toByteArray();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    MessageConstant.FILE_UPLOAD + MessageConstant.FAILED);
        } finally {
            writer.dispose();
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException tooLarge(long maxBytes) {
        return badRequest("图片不能超过 " + (maxBytes / 1024 / 1024) + " MB");
    }

    public record ValidatedImage(String contentType) {
    }

    private record DecodedImage(BufferedImage image, String contentType) {
    }
}
