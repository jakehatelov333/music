package cn.edu.seig.vibemusic.service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Generates valid image bytes so upload tests exercise the real decoding boundary. */
public final class TestImages {
    private TestImages() {
    }

    public static byte[] png() {
        return encode("png");
    }

    public static byte[] jpeg() {
        return encode("jpeg");
    }

    private static byte[] encode(String format) {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(42, 87, 141));
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, format, output)) {
                throw new IllegalStateException("No ImageIO writer for " + format);
            }
            return output.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
