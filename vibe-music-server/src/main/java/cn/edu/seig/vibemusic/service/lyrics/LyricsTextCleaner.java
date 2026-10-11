package cn.edu.seig.vibemusic.service.lyrics;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 歌词文本清洗与头部重组。
 * <p>
 * 目标格式对齐 QQ 音乐：
 * <pre>
 * [ti:青花瓷]
 * [ar:周杰伦]
 * [al:我很忙]
 * [by:Vibe Music]
 * [offset:0]
 * [00:00.02]青花瓷 - 周杰伦
 * [00:05.49]词：方文山
 * [00:10.98]曲：周杰伦
 * [00:16.47]编曲：钟兴民
 * [00:21.97]素胚勾勒出青花笔锋浓转淡
 * </pre>
 * 注意：<b>「词 / 曲 / 编曲」这类制作信息行会被完整保留</b>，它们与普通歌词一样带时间轴，
 * 前端 parseLrc 会正常渲染，与 QQ 音乐表现一致。此处只丢弃空行与源自带的 ID 标签行。
 */
@Component
public class LyricsTextCleaner {

    /** LRC 元信息标签（非歌词内容），例如 [ti:xxx]、[by:xxx] */
    private static final Pattern META_TAG = Pattern.compile(
            "^\\[(ti|ar|al|by|re|ve|offset|length|kana|t_time):.*]$", Pattern.CASE_INSENSITIVE);

    /** 带时间轴的歌词行 */
    private static final Pattern TIMED_LINE = Pattern.compile(
            "^\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?]");

    /** 无意义的占位歌词（纯音乐提示） */
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "^(纯音乐[，,]?\\s*请欣赏|此歌曲为没有填词的纯音乐.*|暂无歌词.*)$");

    /**
     * 清洗并重组歌词。
     *
     * @return 组装好的 LRC；若清洗后没有任何正文行则返回 null
     */
    public String clean(String rawLyric, String songName, String artistName, String albumName) {
        if (!StringUtils.hasText(rawLyric)) {
            return null;
        }

        String normalized = rawLyric.replace("\r\n", "\n").replace('\r', '\n');
        List<String> body = new ArrayList<>();
        for (String line : normalized.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            // 丢弃源自带的 ID 标签，稍后统一重建
            if (META_TAG.matcher(trimmed).matches()) {
                continue;
            }
            // 丢弃"纯音乐请欣赏"这类占位内容
            if (PLACEHOLDER.matcher(stripTimedPrefix(trimmed)).matches()) {
                continue;
            }
            body.add(trimmed);
        }

        if (body.isEmpty()) {
            return null;
        }

        StringBuilder builder = new StringBuilder();
        appendMeta(builder, "ti", songName);
        appendMeta(builder, "ar", artistName);
        appendMeta(builder, "al", albumName);
        builder.append("[by:Vibe Music]\n");
        builder.append("[offset:0]\n");

        if (!containsTitleLine(body, songName) && StringUtils.hasText(songName)) {
            builder.append("[00:00.00]").append(songName.trim());
            if (StringUtils.hasText(artistName)) {
                builder.append(" - ").append(artistName.trim());
            }
            builder.append('\n');
        }

        for (String line : body) {
            builder.append(line).append('\n');
        }
        return builder.toString().stripTrailing();
    }

    private void appendMeta(StringBuilder builder, String key, String value) {
        builder.append('[').append(key).append(':');
        if (StringUtils.hasText(value)) {
            builder.append(value.trim());
        }
        builder.append("]\n");
    }

    /** 正文里是否已经存在"歌名 - 歌手"这样的标题行 */
    private boolean containsTitleLine(List<String> body, String songName) {
        if (!StringUtils.hasText(songName)) {
            return true;
        }
        String target = songName.trim();
        for (String line : body) {
            if (TIMED_LINE.matcher(line).find() && line.contains(target)) {
                return true;
            }
        }
        return false;
    }

    private String stripTimedPrefix(String line) {
        return TIMED_LINE.matcher(line).replaceFirst("").strip();
    }

}
