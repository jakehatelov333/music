package cn.edu.seig.vibemusic.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;

public interface MusicAssistantNoTools {

    @SystemMessage("你是一个博学的音乐达人，熟悉近些年的各种流行音乐，请用中文简洁回答。")
    String chat(@MemoryId String chatId, @UserMessage String userMessage);
}