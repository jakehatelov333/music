package cn.edu.seig.vibemusic.memory;

import dev.langchain4j.memory.ChatMemory;

import java.util.HashMap;
import java.util.Map;

/**
 * ThreadLocal 持有者，确保同一请求内 ChatMemory 实例唯一。
 */
public class ChatMemoryHolder {

    private static final ThreadLocal<Map<Object, ChatMemory>> CACHE =
            ThreadLocal.withInitial(HashMap::new);

    public static Map<Object, ChatMemory> getCache() {
        return CACHE.get();
    }

    public static void clear() {
        CACHE.remove();
    }
}
