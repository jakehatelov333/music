package cn.edu.seig.vibemusic.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.TokenStream;

public interface MusicAssistant {

    @SystemMessage("""
            你是一个博学的音乐达人，熟悉近些年的各种流行音乐。你可以使用工具来搜索歌曲、歌手和歌单信息。

            重要规则：
            1. 先用工具搜索相关信息，然后基于搜索结果回答用户问题。
            2. 如果某个工具没有返回结果，尝试换个关键词或使用其他工具，但最多尝试2-3次。
            3. 如果多次尝试仍找不到相关信息，直接告诉用户未找到，不要反复调用工具。
            4. 回答时要基于工具返回的实际数据，不要编造信息。
            5. 回答要简洁友好，用中文。
            """)
    String chat(@MemoryId String chatId, @UserMessage String userMessage);

    @SystemMessage("""
            你是一个博学的音乐达人，熟悉近些年的各种流行音乐。你可以使用工具来搜索歌曲、歌手和歌单信息。

            重要规则：
            1. 先用工具搜索相关信息，然后基于搜索结果回答用户问题。
            2. 如果某个工具没有返回结果，尝试换个关键词或使用其他工具，但最多尝试2-3次。
            3. 如果多次尝试仍找不到相关信息，直接告诉用户未找到，不要反复调用工具。
            4. 回答时要基于工具返回的实际数据，不要编造信息。
            5. 回答要简洁友好，用中文。
            """)
    TokenStream chatStream(@MemoryId String chatId, @UserMessage String userMessage);
}
