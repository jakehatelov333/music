package cn.edu.seig.vibemusic.service.impl;

import cn.edu.seig.vibemusic.model.dto.AudioMetadataDTO;
import cn.edu.seig.vibemusic.security.InvalidMediaException;
import cn.edu.seig.vibemusic.security.MediaPolicy;
import cn.edu.seig.vibemusic.service.IAudioMetadataService;
import lombok.extern.slf4j.Slf4j;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.AudioHeader;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于 Jaudiotagger 读取 MP3、FLAC、WAV 等格式的标签、封面和真实时长。
 */
@Slf4j
@Service
public class AudioMetadataServiceImpl implements IAudioMetadataService {

    private static final Pattern YEAR_PATTERN = Pattern.compile("(\\d{4})");
    private static final Pattern BRACKET_TITLE_PATTERN = Pattern.compile("《([^》]+)》");
    private static final Pattern ARTIST_SEPARATOR_PATTERN = Pattern.compile("\\s+[-–—]|[-–—]\\s+");

    @Override
    public AudioMetadataDTO parse(MultipartFile audio) {
        validateDeclaredAudio(audio);
        String extension = extensionOf(audio.getOriginalFilename());
        AudioMetadataDTO result = AudioMetadataDTO.builder()
                .audioFormat(extension.toUpperCase(Locale.ROOT))
                .build();
        Path tempFile = null;

        try {
            tempFile = Files.createTempFile(
                    "vibe-music-audio-",
                    extension.isEmpty() ? ".tmp" : "." + extension
            );
            audio.transferTo(tempFile);

            AudioFile audioFile = readAudioFile(tempFile, extension);
            fillAudioHeader(result, audioFile);
            fillTags(result, audioFile);
            fillArtwork(result, audioFile);
        } catch (Exception e) {
            log.warn("音频元数据解析失败，file={}, reason={}", audio.getOriginalFilename(), e.getMessage());
            throw new InvalidMediaException("无法解析音频文件，请确认文件未损坏且格式正确", e);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception e) {
                    log.warn("删除音频解析临时文件失败，path={}", tempFile, e);
                }
            }
        }

        requireDuration(result);
        applyFilenameFallback(result, audio.getOriginalFilename());
        return result;
    }

    @Override
    public AudioMetadataDTO parse(InputStream input, String fileName, long size) {
        validateDeclaredAudio(fileName, size);
        String extension = extensionOf(fileName);
        AudioMetadataDTO result = AudioMetadataDTO.builder().audioFormat(extension.toUpperCase(Locale.ROOT)).build();
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("vibe-music-staging-", extension.isEmpty() ? ".tmp" : "." + extension);
            copyBounded(input, tempFile, size);
            AudioFile audioFile = readAudioFile(tempFile, extension);
            fillAudioHeader(result, audioFile);
            fillTags(result, audioFile);
            fillArtwork(result, audioFile);
        } catch (Exception e) {
            log.warn("staging 音频元数据解析失败，file={}, reason={}", fileName, e.getMessage());
            throw new InvalidMediaException("无法解析音频文件，请确认文件未损坏且格式正确", e);
        } finally {
            if (tempFile != null) try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
        }
        requireDuration(result);
        applyFilenameFallback(result, fileName);
        return result;
    }

    private void validateDeclaredAudio(MultipartFile audio) {
        if (audio == null || audio.isEmpty()) {
            throw new InvalidMediaException("请选择音频文件");
        }
        validateDeclaredAudio(audio.getOriginalFilename(), audio.getSize());
    }

    private void validateDeclaredAudio(String fileName, long size) {
        if (size <= 0 || size > MediaPolicy.MAX_AUDIO_BYTES) {
            throw new InvalidMediaException("单个音频不能超过 200 MB");
        }
        String extension = extensionOf(fileName);
        if (!MediaPolicy.SONG_AUDIO_EXTENSIONS.contains(extension)
                && !MediaPolicy.ALBUM_AUDIO_EXTENSIONS.contains(extension)) {
            throw new InvalidMediaException("不支持的音频格式");
        }
    }

    /**
     * Copy only the declared object size and fail if the stream contains more bytes.
     */
    private void copyBounded(InputStream input, Path target, long declaredSize) throws IOException {
        if (input == null) {
            throw new InvalidMediaException("音频内容为空");
        }
        try (OutputStream output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > declaredSize || total > MediaPolicy.MAX_AUDIO_BYTES) {
                    throw new InvalidMediaException("音频实际大小与声明不一致");
                }
                output.write(buffer, 0, read);
            }
            if (total != declaredSize) {
                throw new InvalidMediaException("音频实际大小与声明不一致");
            }
        }
    }

    private void requireDuration(AudioMetadataDTO metadata) {
        Double duration = metadata.getDurationSeconds();
        if (!metadata.isDurationParsed() || duration == null || duration <= 0) {
            throw new InvalidMediaException("无法读取音频时长，文件可能已损坏");
        }
        if (duration > MediaPolicy.MAX_AUDIO_DURATION_SECONDS) {
            throw new InvalidMediaException("音频时长不能超过 6 小时");
        }
    }

    private AudioFile readAudioFile(Path tempFile, String extension) throws Exception {
        try {
            return extension.isEmpty()
                    ? AudioFileIO.readMagic(tempFile.toFile())
                    : AudioFileIO.read(tempFile.toFile());
        } catch (Exception firstException) {
            if (extension.isEmpty()) {
                throw firstException;
            }
            return AudioFileIO.readMagic(tempFile.toFile());
        }
    }

    private void fillAudioHeader(AudioMetadataDTO result, AudioFile audioFile) {
        try {
            AudioHeader header = audioFile.getAudioHeader();
            if (header == null) {
                return;
            }

            double preciseLength = header.getPreciseTrackLength();
            if (preciseLength <= 0) {
                preciseLength = header.getTrackLength();
            }
            if (preciseLength > 0) {
                result.setDurationSeconds(preciseLength);
                result.setDurationText(formatDuration(preciseLength));
                result.setDurationParsed(true);
            }

            result.setAudioFormat(header.getFormat());
            result.setSampleRate(header.getSampleRateAsNumber());
            result.setBitRate(header.getBitRateAsNumber());
        } catch (Exception e) {
            log.warn("读取音频头信息失败，file={}, reason={}", audioFile.getFile(), e.getMessage());
        }
    }

    private void fillTags(AudioMetadataDTO result, AudioFile audioFile) {
        try {
            Tag tag = audioFile.getTag();
            if (tag == null) {
                return;
            }

            String title = first(tag, FieldKey.TITLE);
            String artist = first(tag, FieldKey.ARTIST, FieldKey.ALBUM_ARTIST);
            String album = first(tag, FieldKey.ALBUM);
            Integer year = parseYear(first(tag, FieldKey.YEAR, FieldKey.ORIGINAL_YEAR));
            Integer track = parseNumber(first(tag, FieldKey.TRACK));
            Integer disc = parseDiscNumber(tag);

            result.setTitle(clean(title));
            result.setArtist(clean(artist));
            result.setAlbum(clean(album));
            result.setYear(year);
            result.setTrackNumber(track);
            result.setDiscNumber(disc);
            result.setMetadataAvailable(
                    StringUtils.hasText(title)
                            || StringUtils.hasText(artist)
                            || StringUtils.hasText(album)
                            || year != null
            );
        } catch (Exception e) {
            log.warn("读取音频标签失败，file={}, reason={}", audioFile.getFile(), e.getMessage());
        }
    }

    private void fillArtwork(AudioMetadataDTO result, AudioFile audioFile) {
        try {
            Tag tag = audioFile.getTag();
            if (tag == null) {
                return;
            }
            Artwork artwork = tag.getFirstArtwork();
            if (artwork == null || artwork.getBinaryData() == null || artwork.getBinaryData().length == 0) {
                return;
            }

            result.setEmbeddedCover(artwork.getBinaryData());
            result.setEmbeddedCoverMimeType(artwork.getMimeType());
            result.setHasEmbeddedCover(true);
            result.setMetadataAvailable(true);
        } catch (Exception e) {
            log.warn("读取音频内嵌封面失败，file={}, reason={}", audioFile.getFile(), e.getMessage());
        }
    }

    private String first(Tag tag, FieldKey... keys) {
        for (FieldKey key : keys) {
            try {
                String value = tag.getFirst(key);
                if (StringUtils.hasText(value)) {
                    return value;
                }
            } catch (Exception ignored) {
                // 不同音频格式可能不支持某些标签键，忽略后继续读取其他字段。
            }
        }
        return null;
    }

    private void applyFilenameFallback(AudioMetadataDTO metadata, String originalFilename) {
        String baseName = baseName(originalFilename);
        if (!StringUtils.hasText(baseName)) {
            return;
        }

        Matcher bracketMatcher = BRACKET_TITLE_PATTERN.matcher(baseName);
        if (!StringUtils.hasText(metadata.getTitle()) && bracketMatcher.find()) {
            metadata.setTitle(bracketMatcher.group(1).trim());
        }

        if (!StringUtils.hasText(metadata.getArtist())) {
            String artist = inferArtist(baseName);
            if (StringUtils.hasText(artist)) {
                metadata.setArtist(artist);
            }
        }

        if (!StringUtils.hasText(metadata.getTitle())) {
            String title = inferTitle(baseName);
            if (StringUtils.hasText(title)) {
                metadata.setTitle(title);
            }
        }
    }

    private String inferArtist(String baseName) {
        int bracketEnd = baseName.indexOf('》');
        if (bracketEnd >= 0 && bracketEnd < baseName.length() - 1) {
            String afterTitle = baseName.substring(bracketEnd + 1)
                    .replaceFirst("^\\s*[-–—]\\s*", "")
                    .trim();
            int separator = firstSeparator(afterTitle);
            if (separator > 0) {
                return afterTitle.substring(0, separator).trim();
            }
        }

        int separator = baseName.indexOf(" - ");
        return separator > 0 ? baseName.substring(0, separator).trim() : null;
    }

    private String inferTitle(String baseName) {
        int separator = baseName.indexOf(" - ");
        if (separator > 0 && separator < baseName.length() - 3) {
            return baseName.substring(separator + 3).trim();
        }
        return baseName.trim();
    }

    private int firstSeparator(String value) {
        Matcher matcher = ARTIST_SEPARATOR_PATTERN.matcher(value);
        return matcher.find() ? matcher.start() : -1;
    }

    private Integer parseYear(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        Matcher matcher = YEAR_PATTERN.matcher(value);
        if (!matcher.find()) {
            return null;
        }
        int year = Integer.parseInt(matcher.group(1));
        return year >= 1900 && year <= 2100 ? year : null;
    }

    private Integer parseNumber(String value) {
        if (!StringUtils.hasText(value)) return null;
        Matcher matcher = YEAR_PATTERN.matcher(value);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    private Integer parseDiscNumber(Tag tag) {
        Integer disc = parseNumber(first(tag, FieldKey.DISC_NO));
        if (disc != null) return disc;
        for (String key : new String[]{"DISCNUMBER", "DISC", "TPOS"}) {
            try {
                Integer parsed = parseNumber(tag.getFirst(key));
                if (parsed != null) return parsed;
            } catch (Exception ignored) {
                // FLAC and ID3 implementations expose disc tags under different names.
            }
        }
        return null;
    }

    public static String formatDuration(Double seconds) {
        if (seconds == null || seconds <= 0) {
            return null;
        }
        long totalSeconds = (long) Math.floor(seconds);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long remainingSeconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, remainingSeconds);
        }
        return String.format(Locale.ROOT, "%02d:%02d", minutes, remainingSeconds);
    }

    private String extensionOf(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "";
        }
        String normalized = filename.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        int index = name.lastIndexOf('.');
        if (index <= 0 || index >= name.length() - 1) {
            return "";
        }
        return name.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private String baseName(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "";
        }
        String normalized = filename.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        int index = name.lastIndexOf('.');
        return index > 0 ? name.substring(0, index) : name;
    }

    private String clean(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
