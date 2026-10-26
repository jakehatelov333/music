package cn.edu.seig.vibemusic.tool;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecifications;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Agent 当前注册的工具清单。
 *
 * <p>可观测性页面的工具筛选需要同时提供“近期调用过的工具”和“已注册但还没被调用过的工具”。
 * 只从 Trace 明细取候选会让新工具在首次调用前永远无法被选择，看起来像系统里只有一个工具。
 * 这里直接从 {@link Tool} 注解生成名称，避免再维护一份容易漂移的手写列表。
 *
 * <p>新增工具类时必须把它加入 {@link #TOOL_CLASSES}；该类只读取方法签名，不执行工具。
 */
public final class AgentToolCatalog {

    private static final List<Class<?>> TOOL_CLASSES = List.of(
            MusicTools.class,
            PlaylistActionTools.class
    );

    private AgentToolCatalog() {
    }

    /** 返回按名称排序的全部已注册工具。 */
    public static List<String> names() {
        Set<String> names = new LinkedHashSet<>();
        for (Class<?> toolClass : TOOL_CLASSES) {
            for (Method method : toolClass.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(Tool.class)) continue;
                names.add(ToolSpecifications.toolSpecificationFrom(method).name());
            }
        }
        List<String> sorted = new ArrayList<>(names);
        Collections.sort(sorted);
        return List.copyOf(sorted);
    }
}
