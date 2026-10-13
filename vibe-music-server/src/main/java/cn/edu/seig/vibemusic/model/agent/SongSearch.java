package cn.edu.seig.vibemusic.model.agent;

/** 可重放的只读搜索条件，随工具结果持久化，供卡片翻页使用。 */
public record SongSearch(String keyword, String artistName, String style) {}
