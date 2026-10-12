package cn.edu.seig.vibemusic.model.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Agent 协议层共用的 JSON 序列化器。
 *
 * <p>工具返回值直接以 JSON 文本交给 LangChain4j，因此这里的配置必须与业务层一致：
 * 注册 JSR-310 模块并把时间写成 ISO 字符串，否则工具返回里只要带 {@code LocalDateTime}
 * 就会整体序列化失败（曾经因此让待确认预览退化成“结果序列化失败”）。
 */
public final class AgentJson {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private AgentJson() {}

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            // 序列化失败属于实现缺陷：返回一个合法的失败结构，避免把异常抛回模型循环。
            return "{\"ok\":false,\"code\":\"ERROR\",\"message\":\"结果序列化失败\",\"kind\":\"TEXT\",\"total\":0,\"data\":null}";
        }
    }
}
