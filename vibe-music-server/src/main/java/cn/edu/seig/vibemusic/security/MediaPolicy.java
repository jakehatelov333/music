package cn.edu.seig.vibemusic.security;

import java.util.Locale;
import java.util.Set;

/**
 * Central upload limits shared by multipart and direct-upload paths.
 */
public final class MediaPolicy {

    public static final long MAX_AVATAR_BYTES = 2L * 1024 * 1024;
    public static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    public static final long MAX_AUDIO_BYTES = 200L * 1024 * 1024;
    public static final long MAX_ALBUM_AUDIO_BYTES = 1024L * 1024 * 1024;
    public static final int MAX_ALBUM_AUDIO_FILES = 50;
    public static final int MAX_IMAGE_DIMENSION = 4096;
    public static final int MAX_IMAGE_PIXELS = 16_000_000;
    public static final double MAX_AUDIO_DURATION_SECONDS = 6d * 60 * 60;

    public static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png");
    public static final Set<String> SONG_AUDIO_EXTENSIONS = Set.of("mp3", "flac", "wav");
    public static final Set<String> ALBUM_AUDIO_EXTENSIONS =
            Set.of("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus");

    private MediaPolicy() {
    }

    public static String extensionOf(String filename) {
        if (filename == null || filename.isBlank()) {
            return "";
        }
        String normalized = filename.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1
                ? ""
                : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean isImageExtension(String extension) {
        return IMAGE_EXTENSIONS.contains(extension);
    }

    public static boolean isAudioContentType(String contentType) {
        return contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith("audio/");
    }

    public static boolean isImageContentType(String contentType) {
        return contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith("image/");
    }

    public static boolean isPngContentType(String contentType) {
        return contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith("image/png");
    }
}
