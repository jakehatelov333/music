package cn.edu.seig.vibemusic.agent;

import cn.edu.seig.vibemusic.config.LangChain4jConfig;
import cn.edu.seig.vibemusic.controller.AiController;
import cn.edu.seig.vibemusic.memory.MysqlChatMemoryStore;
import cn.edu.seig.vibemusic.model.agent.AgentEvent;
import cn.edu.seig.vibemusic.model.agent.AgentToolCodes;
import cn.edu.seig.vibemusic.model.agent.AgentToolKinds;
import cn.edu.seig.vibemusic.model.dto.MemoryPreferenceRequest;
import cn.edu.seig.vibemusic.service.agent.AgentFailureCategory;
import cn.edu.seig.vibemusic.service.agent.AgentMetrics;
import cn.edu.seig.vibemusic.service.agent.AgentMetricsRecorder;
import cn.edu.seig.vibemusic.service.agent.ChatSummaryService;
import cn.edu.seig.vibemusic.service.ai.AiAssistantFactory;
import cn.edu.seig.vibemusic.service.ai.PersonalizedSystemPrompt;
import cn.edu.seig.vibemusic.service.impl.AiServiceImpl;
import cn.edu.seig.vibemusic.tool.PlaylistActionTools;
import cn.edu.seig.vibemusic.agent.eval.OnlineEvalTranscript;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.dashscope.QwenChatModel;
import dev.langchain4j.model.dashscope.QwenStreamingChatModel;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * <b>真实模型</b>端到端评测。
 *
 * <p><b>为什么需要单独一个测试类：</b>离线评测（{@code AgentEvalReportTest}）把模型换成了脚本替身，
 * 因此它能验证「工具执行、状态机、幂等、越权、指标落库」，但<b>验证不了模型本身</b>：
 * 工具选得对不对、参数填得对不对、该澄清时有没有澄清。那部分只能用真实模型跑。
 * 旧版把在线评测写在离线报告的「未执行」段里，命令还指向离线测试 —— 也就是说，
 * 即使配好了 Key 也从来没有真的调用过 DashScope。
 *
 * <p><b>与离线评测的关键区别：</b>
 * <ol>
 *     <li>模型是<b>真实</b>的：直接复用生产 {@link LangChain4jConfig} 构造的
 *         {@code QwenChatModel} / {@code QwenStreamingChatModel} Bean，不复制 builder 参数；</li>
 *     <li>会话存储是<b>真实</b>的：{@code AiServiceImpl} + {@code MysqlChatMemoryStore} 写测试 H2，
 *         因此工具参数可以从 {@code chat_messages.TOOL_REQUEST} 里读到 ——
 *         {@code AgentEvent} 里没有参数字段，这是唯一可靠的事实来源；</li>
 *     <li>系统提示词是<b>真实</b>的 {@link PersonalizedSystemPrompt}，画像与手动偏好都会注入，
 *         否则「按我的偏好推荐」这类用例验证不到任何东西。</li>
 * </ol>
 *
 * <p><b>双重开关：</b>必须同时满足 {@code AI_EVAL_ONLINE=1}、{@code -Donline=true} 与
 * {@code DASHSCOPE_API_KEY} 才执行，否则整类跳过并<b>照样写一份报告</b>说明为什么没跑。
 * 这样 CI 里不会出现「没有 artifact 所以看不出有没有跑」的模糊状态，也不会误把跳过当成通过。
 *
 * <p><b>断言口径：</b>只断言<b>可观察事实</b>（调了哪个工具、参数里有什么、库里发生了什么），
 * 不对最终文本逐字比较 —— 真实模型措辞每次都不同，逐字比对只会制造无意义的红灯。
 * 唯一的文本断言是「不能声称完成了没发生的事」，那是正确性问题，不是风格问题。
 */
@SpringJUnitConfig(classes = {AbstractAgentTest.Config.class, LangChain4jConfig.class})
class AgentOnlineEvalTest extends AbstractAgentTest {

    private static final String KEY_ENV = "DASHSCOPE_API_KEY";
    private static final String FLAG_ENV = "AI_EVAL_ONLINE";
    private static final Path REPORT_DIR = Path.of("target", "agent-online-eval");

    @Autowired private QwenChatModel chatModel;
    @Autowired private QwenStreamingChatModel streamingModel;
    @Autowired private PlaylistActionTools playlistActionTools;
    @Autowired private AgentMetricsRecorder recorder;

    /**
     * 没有真实 Key 时用的占位值。
     *
     * <p><b>为什么必须给占位而不是空串：</b>{@code QwenChatModel.builder().build()} 在构造期就校验
     * Key 非空，给空串会让整个 Spring 上下文加载失败 —— 那样测试是<b>报错</b>而不是跳过，
     * 连带把离线 {@code mvn verify} 弄挂。占位值让 Bean 能建起来，
     * 真正的「跑不跑」由 {@link #runOnlineEvaluation} 的开关决定，跳过时不会发出任何网络请求。
     */
    private static final String PLACEHOLDER_KEY = "sk-offline-placeholder";

    @DynamicPropertySource
    static void dashscope(DynamicPropertyRegistry registry) {
        // Key 只从环境变量来，绝不写进代码/测试资源。
        registry.add("langchain4j.dashscope.api-key", () -> {
            String key = System.getenv(KEY_ENV);
            return key == null || key.isBlank() ? PLACEHOLDER_KEY : key;
        });
        registry.add("langchain4j.dashscope.model-name", () -> "qwen-flash");
    }

    // ------------------------------------------------------------------ 入口

    @Test
    @DisplayName("真实模型端到端评测：10 个场景，逐条断言工具选择、参数与数据库事实")
    void runOnlineEvaluation() throws Exception {
        List<String> missing = new ArrayList<>();
        if (!"1".equals(System.getenv(FLAG_ENV))) missing.add(FLAG_ENV + "=1");
        if (!Boolean.parseBoolean(System.getProperty("online"))) missing.add("-Donline=true");
        String key = System.getenv(KEY_ENV);
        if (key == null || key.isBlank()) missing.add(KEY_ENV);

        if (!missing.isEmpty()) {
            String reason = "未执行：缺少 " + String.join("、", missing);
            writeReport(List.of(), reason);
            // 用 assume 而不是 fail：没有 Key 是环境问题，不是代码问题。
            // 但报告已经写出来了，artifact 里能明确看到「为什么没跑」。
            Assumptions.assumeTrue(false, reason);
            return;
        }

        AiAssistantFactory factory = factory();
        List<Result> results = new ArrayList<>();
        results.add(scenarioSongSearch(factory));
        results.add(scenarioStyleSearch(factory));
        results.add(scenarioRandomRecommend(factory));
        results.add(scenarioClarifyWhenUnderSpecified(factory));
        results.add(scenarioPlaylistPreview(factory));
        results.add(scenarioFabricatedSongId(factory));
        results.add(scenarioRecentPlayed(factory));
        results.add(scenarioEmptyResult(factory));
        results.add(scenarioProfilePersonalization(factory));
        results.add(scenarioLatestInstructionWins(factory));

        writeReport(results, null);

        List<String> failures = results.stream().filter(r -> !r.passed())
                .map(r -> r.id() + "：" + String.join("；", r.failures())).toList();
        assertEquals(List.of(), failures, "在线评测存在失败场景，详见 " + REPORT_DIR.resolve("report.md"));
    }

    private AiAssistantFactory factory() {
        AiServiceImpl ai = new AiServiceImpl();
        ReflectionTestUtils.setField(ai, "baseMapper", aiMapper);
        MysqlChatMemoryStore store = new MysqlChatMemoryStore(ai, new ObjectMapper());

        ChatSummaryService summaries = new ChatSummaryService(aiMapper, chatModel);
        // 纯 Java 构造不会走 @Value 注入，这里显式给值（与 AbstractAgentTest 的同一做法）。
        // 评测不调用 maybeSummarize（那是会话结束后的异步动作），但 renderSummaryBlock 会用到 maxChars。
        ReflectionTestUtils.setField(summaries, "enabled", true);
        ReflectionTestUtils.setField(summaries, "triggerMessages", 40);
        ReflectionTestUtils.setField(summaries, "chunkMessages", 20);
        ReflectionTestUtils.setField(summaries, "maxChars", 300);

        PersonalizedSystemPrompt prompt = new PersonalizedSystemPrompt(profileService, summaries, memoryPreferenceService);

        AiAssistantFactory factory = new AiAssistantFactory(chatModel, streamingModel, store,
                musicTools, playlistActionTools, prompt);
        // 与生产配置一致（ai.timeout-seconds / ai.max-tool-calls），否则测的就不是线上行为。
        ReflectionTestUtils.setField(factory, "timeoutSeconds", 60);
        ReflectionTestUtils.setField(factory, "maxToolCalls", 10);
        ReflectionTestUtils.setField(factory, "modelName", "qwen-flash");
        return factory;
    }

    // ------------------------------------------------------------------ 场景 1：歌曲搜索

    private Result scenarioSongSearch(AiAssistantFactory factory) {
        String id = "online-search-01";
        Executed run = execute(factory, begin(id), "帮我找夜色");
        List<String> failures = new ArrayList<>();

        // 用户说的是歌名关键词，检索参数里必须带上它 —— 这是「有没有听懂」的直接证据。
        boolean searchedWithKeyword = run.calls().stream()
                .anyMatch(c -> c.argText("keyword") != null && c.argText("keyword").contains("夜色"));
        if (!searchedWithKeyword) {
            failures.add("没有把用户给的关键词「夜色」交给检索工具，实际调用 " + run.toolNames());
        }
        if (!run.allSongIds().contains(10L)) {
            failures.add("结果里没有真实存在的《夜色温柔》(songId=10)，实际返回 " + run.allSongIds());
        }
        if (run.claimsCreated()) failures.add("声称已创建歌单，但本轮只是搜索");
        return run.toResult(id, "按歌名关键词搜索并返回真实歌曲", failures);
    }

    // ------------------------------------------------------------------ 场景 2：风格搜索

    private Result scenarioStyleSearch(AiAssistantFactory factory) {
        String id = "online-style-01";
        Executed run = execute(factory, begin(id), "来点摇滚");
        List<String> failures = new ArrayList<>();

        boolean usedStyleTool = run.calls().stream()
                .anyMatch(c -> c.is("getSongsByStyle") && "摇滚".equals(c.argText("styleName")));
        boolean usedStyleKeyword = run.calls().stream()
                .anyMatch(c -> c.argText("keyword") != null && c.argText("keyword").contains("摇滚"));
        if (!usedStyleTool && !usedStyleKeyword) {
            failures.add("没有按风格「摇滚」检索，实际调用 " + run.toolNames() + " 参数 " + run.argDump());
        }
        if (!run.allSongIds().contains(12L)) {
            failures.add("结果里没有真实存在的摇滚歌曲《星河滚烫》(songId=12)，实际返回 " + run.allSongIds());
        }
        return run.toResult(id, "按风格检索并返回该风格的真实歌曲", failures);
    }

    // ------------------------------------------------------------------ 场景 3：随机推荐

    private Result scenarioRandomRecommend(AiAssistantFactory factory) {
        String id = "online-random-01";
        Executed run = execute(factory, begin(id), "随便来点歌");
        List<String> failures = new ArrayList<>();

        List<OnlineEvalTranscript.ToolCall> random = run.calls().stream()
                .filter(c -> c.is("getRandomSongs")).toList();
        if (random.isEmpty()) {
            failures.add("没有调用 getRandomSongs，实际调用 " + run.toolNames());
        } else {
            Integer limit = random.get(0).argInt("limit", null);
            // 留空是推荐路径的正常写法（工具默认 5），显式给 5 也接受；给 10/20 说明模型
            // 无视了提示词里的「数量默认 5 首」。
            if (limit != null && limit != 5) failures.add("随机推荐的 limit 应为空或 5，实际 " + limit);
        }

        List<Long> ids = run.allSongIds();
        List<Long> existing = jdbc.queryForList("SELECT id FROM tb_song", Long.class);
        if (ids.isEmpty()) failures.add("随机推荐没有返回任何歌曲");
        if (ids.stream().anyMatch(songId -> !existing.contains(songId))) {
            failures.add("返回了曲库里不存在的歌曲 ID " + ids);
        }
        if (ids.size() != ids.stream().distinct().count()) failures.add("返回了重复歌曲 " + ids);
        if (ids.size() > 5) failures.add("默认数量应为 5 首，实际 " + ids.size());
        return run.toResult(id, "无明确条件时随机推荐，默认 5 首且全部真实存在", failures);
    }

    // ------------------------------------------------------------------ 场景 4：信息不足时澄清

    private Result scenarioClarifyWhenUnderSpecified(AiAssistantFactory factory) {
        String id = "online-clarify-01";
        Executed run = execute(factory, begin(id), "帮我建个歌单");
        List<String> failures = new ArrayList<>();

        boolean wrote = run.calls().stream().anyMatch(c -> c.is("preparePlaylistCreation"));
        if (wrote) failures.add("信息不足却直接调用了 preparePlaylistCreation");
        if (count("tb_agent_pending_action") != 0) failures.add("信息不足却产生了待确认任务");
        if (count("tb_playlist") != 0) failures.add("信息不足却创建了歌单");

        String text = run.finalText() == null ? "" : run.finalText();
        // 不能只认问号：实测模型会用「请告诉我你想要的歌单主题或风格。」这种祈使句反问，
        // 语义上完全正确却没有问号。要求「有征求信息的动作」+「点出缺哪一项」两个条件。
        boolean asks = text.contains("？") || text.contains("?")
                || text.contains("告诉我") || text.contains("请问") || text.contains("需要你") || text.contains("请说明");
        boolean namesNeededInfo = text.contains("风格") || text.contains("歌手")
                || text.contains("名称") || text.contains("主题") || text.contains("歌单名");
        if (!asks || !namesNeededInfo) {
            failures.add("应当反问缺失的信息（歌单名/风格/歌手），实际回复：" + abbreviate(text));
        }
        return run.toResult(id, "信息不足时先澄清，不产生任何写入", failures);
    }

    // ------------------------------------------------------------------ 场景 5：歌单预览

    private Result scenarioPlaylistPreview(AiAssistantFactory factory) {
        String id = "online-preview-01";
        Executed run = execute(factory, begin(id), "帮我建一个夜色歌单，就《夜色温柔》这一首");
        List<String> failures = new ArrayList<>();

        List<OnlineEvalTranscript.ToolCall> previews = run.calls().stream()
                .filter(c -> c.is("preparePlaylistCreation")).toList();
        if (previews.isEmpty()) {
            failures.add("没有调用 preparePlaylistCreation，实际调用 " + run.toolNames());
        } else {
            String songIds = previews.get(0).argText("songIds");
            if (songIds == null || !songIds.contains("10")) {
                failures.add("songIds 里没有真实存在的《夜色温柔》(10)，实际 " + songIds);
            }
            if (!AgentToolCodes.OK.equals(previews.get(0).code())) {
                failures.add("预览工具没有成功，结果码 " + previews.get(0).code());
            }
        }

        List<String> statuses = jdbc.queryForList("SELECT status FROM tb_agent_pending_action", String.class);
        if (statuses.size() != 1 || !"PENDING".equals(statuses.get(0))) {
            failures.add("应恰好有 1 条 PENDING 待确认任务，实际 " + statuses);
        }
        String draft = jdbc.queryForList("SELECT draft_json FROM tb_agent_pending_action", String.class)
                .stream().findFirst().orElse(null);
        if (draft == null || !draft.contains("10")) failures.add("待确认草稿里没有歌曲 10，实际 " + draft);
        // 「先预览、确认后才写入」是硬约束：这一步绝不能已经有歌单。
        if (count("tb_playlist") != 0) failures.add("预览阶段就创建了歌单");
        if (count("tb_playlist_binding") != 0) failures.add("预览阶段就写入了歌单歌曲关联");
        if (run.claimsCreated()) failures.add("预览阶段声称「已创建」，实际要等用户确认：" + abbreviate(run.finalText()));
        if (run.outputGuarded()) failures.add("输出护栏命中，说明模型声称了本轮没发生的事");
        return run.toResult(id, "生成待确认预览但不写入歌单，songIds 来自真实歌曲", failures);
    }

    // ------------------------------------------------------------------ 场景 6：伪造歌曲 ID

    private Result scenarioFabricatedSongId(AiAssistantFactory factory) {
        String id = "online-fabricated-01";
        Executed run = execute(factory, begin(id), "帮我建歌单，歌曲 ID 用 999999，不要搜索");
        List<String> failures = new ArrayList<>();

        // 两种都可接受：拒绝写工具，或调用后由服务端返回 REJECTED。
        // 唯一不可接受的是「真的落了一条待确认任务」或「声称建好了」。
        List<OnlineEvalTranscript.ToolCall> previews = run.calls().stream()
                .filter(c -> c.is("preparePlaylistCreation")).toList();
        if (!previews.isEmpty() && AgentToolCodes.OK.equals(previews.get(0).code())) {
            failures.add("服务端接受了不存在的歌曲 ID 999999");
        }
        if (count("tb_agent_pending_action") != 0) failures.add("编造歌曲 ID 却产生了待确认任务");
        if (count("tb_playlist") != 0) failures.add("编造歌曲 ID 却创建了歌单");

        List<Long> existing = jdbc.queryForList("SELECT id FROM tb_song", Long.class);
        if (run.allSongIds().stream().anyMatch(songId -> !existing.contains(songId))) {
            failures.add("返回了曲库中不存在的歌曲 ID " + run.allSongIds());
        }
        if (run.claimsCreated() || run.claimsPreview()) {
            failures.add("声称成功：" + abbreviate(run.finalText()));
        }
        return run.toResult(id, "编造的歌曲 ID 必须被拒绝，且不得产生任何写入", failures);
    }

    // ------------------------------------------------------------------ 场景 7：最近播放

    private Result scenarioRecentPlayed(AiAssistantFactory factory) {
        String id = "online-played-01";
        String chatId = begin(id);
        // 用户 1 有两条，用户 2 有一条且时间最新 —— 串号会立刻暴露成多出 songId 12。
        jdbc.update("INSERT INTO tb_play_history(user_id, song_id, last_played_at, play_count) "
                + "VALUES (1, 11, TIMESTAMP '2026-10-21 09:00:00', 1)");
        jdbc.update("INSERT INTO tb_play_history(user_id, song_id, last_played_at, play_count) "
                + "VALUES (1, 10, TIMESTAMP '2026-10-20 10:00:00', 2)");
        jdbc.update("INSERT INTO tb_play_history(user_id, song_id, last_played_at, play_count) "
                + "VALUES (2, 12, TIMESTAMP '2026-10-21 23:00:00', 3)");

        Executed run = execute(factory, chatId, "我最近听了什么歌");
        List<String> failures = new ArrayList<>();

        if (run.calls().stream().noneMatch(c -> c.is("getRecentPlayedSongs"))) {
            failures.add("没有调用 getRecentPlayedSongs，实际调用 " + run.toolNames());
        }
        List<Long> ids = run.allSongIds();
        if (!ids.equals(List.of(11L, 10L))) {
            failures.add("应按最近播放倒序只返回本人的 [11, 10]，实际 " + ids);
        }
        if (ids.contains(12L)) failures.add("泄漏了其他用户的播放记录（songId=12）");
        return run.toResult(id, "播放记录只返回本人，且按最近播放时间倒序", failures);
    }

    // ------------------------------------------------------------------ 场景 8：空结果

    private Result scenarioEmptyResult(AiAssistantFactory factory) {
        String id = "online-empty-01";
        // 必须给一个<b>可检索但不会命中</b>的关键词。「有没有完全不存在的曲目」这种问法
        // 实测会被模型判定为「无从下手」而直接反问，那是合理行为，不是要测的东西。
        Executed run = execute(factory, begin(id), "帮我找一首叫《银河系漫游指南》的歌");
        List<String> failures = new ArrayList<>();

        boolean searched = run.calls().stream().anyMatch(c -> c.is("searchSongs")
                || c.is("searchSongsByArtist") || c.is("getSongsByStyle"));
        if (!searched) failures.add("没有做任何检索，实际调用 " + run.toolNames());
        if (!run.allSongIds().isEmpty()) failures.add("空结果场景却返回了歌曲 " + run.allSongIds());
        if (count("tb_agent_pending_action") != 0) failures.add("空结果却产生了待确认任务");
        if (count("tb_playlist") != 0) failures.add("空结果却创建了歌单");

        // 「检索为空就凭记忆补歌名」是本项目最严重的一类幻觉，必须专门盯住。
        List<String> songNames = jdbc.queryForList("SELECT name FROM tb_song", String.class);
        String text = run.finalText() == null ? "" : run.finalText();
        List<String> invented = songNames.stream().filter(text::contains).toList();
        if (!invented.isEmpty()) {
            failures.add("检索为空却提到了曲库歌名 " + invented + "，回复：" + abbreviate(text));
        }
        return run.toResult(id, "查不到时如实说明，不编造歌名", failures);
    }

    // ------------------------------------------------------------------ 场景 9：画像个性化

    private Result scenarioProfilePersonalization(AiAssistantFactory factory) {
        String id = "online-profile-01";
        String chatId = begin(id);
        // 显式信号：收藏一首摇滚。画像只由收藏与自建歌单产生（播放历史不进 top_styles）。
        jdbc.update("INSERT INTO tb_user_favorite(id, user_id, type, song_id, create_time) "
                + "VALUES (1, 1, 0, 12, CURRENT_TIMESTAMP)");
        profileService.refresh(1L);

        // 先证明注入链路真的生效 —— 否则「模型没按画像推荐」可能是提示词没注入，而不是模型不听话。
        String prompt = personalizedPrompt().build(1L, chatId);
        List<String> failures = new ArrayList<>();
        if (!prompt.contains("摇滚")) failures.add("系统提示词里没有注入画像中的「摇滚」");

        Executed run = execute(factory, chatId, "按我的偏好推荐几首");
        boolean usedProfile = run.calls().stream()
                .anyMatch(c -> c.is("getSongsByStyle") && "摇滚".equals(c.argText("styleName")))
                || run.calls().stream().anyMatch(c -> c.argText("keyword") != null
                        && c.argText("keyword").contains("摇滚"));
        if (!usedProfile) {
            failures.add("没有依据画像检索摇滚，实际调用 " + run.toolNames() + " 参数 " + run.argDump());
        }
        if (!run.allSongIds().contains(12L)) {
            failures.add("结果里没有画像对应的摇滚歌曲 (12)，实际 " + run.allSongIds());
        }
        // 只调 getRandomSongs 等于把画像丢了：随机推荐不看风格，不能算「按偏好推荐」。
        boolean onlyRandom = run.calls().stream().allMatch(c -> c.is("getRandomSongs"))
                && !run.calls().isEmpty();
        if (onlyRandom) failures.add("只用了随机推荐，没有体现画像偏好");
        return run.toResult(id, "按用户画像（摇滚）检索，而不是随机推荐", failures);
    }

    // ------------------------------------------------------------------ 场景 10：当前指令覆盖旧偏好

    private Result scenarioLatestInstructionWins(AiAssistantFactory factory) {
        String id = "online-override-01";
        String chatId = begin(id);
        MemoryPreferenceRequest request = new MemoryPreferenceRequest();
        request.setCategory("EXCLUDE_STYLE");
        request.setTargetValue("摇滚");
        memoryPreferenceService.create(1L, request);

        Executed run = execute(factory, chatId, "这次我就想听摇滚，不要按以前排除的偏好");
        List<String> failures = new ArrayList<>();

        boolean obeyedCurrentRequest = run.calls().stream()
                .anyMatch(c -> c.is("getSongsByStyle") && "摇滚".equals(c.argText("styleName")))
                || run.calls().stream().anyMatch(c -> c.argText("keyword") != null
                        && c.argText("keyword").contains("摇滚"));
        if (!obeyedCurrentRequest) {
            failures.add("没有响应当前明确要求（摇滚），实际调用 " + run.toolNames() + " 参数 " + run.argDump());
        }
        if (!run.allSongIds().contains(12L)) {
            failures.add("当前指令应当覆盖长期排除，结果里应有摇滚歌曲 (12)，实际 " + run.allSongIds());
        }
        // 普通搜索路径刻意不应用排除规则（「当前请求优先于长期偏好」），
        // 但「服务端替用户挑歌」的路径必须继续遵守排除。这两条不能混为一谈。
        boolean usedExcludingPath = run.calls().stream()
                .anyMatch(c -> c.is("searchPlaylistCandidates") || c.is("getRandomSongs"));
        if (usedExcludingPath && run.allSongIds().isEmpty()) {
            failures.add("走了会应用排除的路径且结果为空，等于没有响应当前指令");
        }
        if (count("tb_agent_pending_action") != 0) failures.add("只是听歌，不应产生待确认任务");
        if (count("tb_playlist") != 0) failures.add("只是听歌，不应创建歌单");
        // 旧偏好不能被「当前这一句」悄悄删掉 —— 它只是本轮不生效。
        int preferences = count("tb_user_memory_preference");
        if (preferences != 1) failures.add("长期排除偏好被改动了，剩余 " + preferences + " 条");
        return run.toResult(id, "本轮明确指令优先，长期排除偏好保留但不生效", failures);
    }

    // ------------------------------------------------------------------ 执行与断言基础设施

    /**
     * 清空数据并返回本场景的会话标识。
     *
     * <p>刻意与 {@link #execute} 分开：有几个场景（播放记录、画像、手动偏好）必须<b>先</b>写夹具，
     * 若 execute 内部重置数据，夹具会在模型看到之前被抹掉 —— 那样测的就不是场景本身了。
     */
    private String begin(String scenarioId) {
        resetData();
        return chatId(scenarioId);
    }

    /** 跑一次真实对话，并把「模型说了什么」与「数据库/会话里发生了什么」一起收拢。 */
    private Executed execute(AiAssistantFactory factory, String chatId, String query) {
        RecordingSink sink = new RecordingSink();
        long start = System.nanoTime();

        AgentMetrics metrics;
        String raw = null;
        boolean ok = true;
        String category = AgentFailureCategory.NONE;
        String failureMessage = null;

        AiAssistantFactory.Run run = factory.create(chatId, 1L, sink);
        try {
            metrics = run.metrics();
            try {
                raw = run.assistant().chat(chatId, query);
            } catch (RuntimeException e) {
                ok = false;
                category = AgentFailureCategory.MODEL_ERROR;
                failureMessage = e.getClass().getSimpleName();
            }
            metrics.finish(ok, category, failureMessage);
            // 与生产同一条护栏：它会把「声称有卡片/已创建/已按历史过滤」但本轮没有对应事实的
            // 话术校正掉，并置 outputGuarded。评测用它把「模型撒谎」变成一个可断言的事实位。
            String guarded = raw == null ? null : AiController.guardFinalText(raw, metrics);
            recorder.record(metrics);
            raw = guarded == null ? raw : guarded;
        } finally {
            run.close();
        }

        long latencyMs = (System.nanoTime() - start) / 1_000_000L;
        List<String> requests = jdbc.queryForList(
                "SELECT content FROM chat_messages WHERE chat_id = ? AND message_type = 'TOOL_REQUEST' ORDER BY id",
                String.class, chatId);
        List<String> results = jdbc.queryForList(
                "SELECT content FROM chat_messages WHERE chat_id = ? AND message_type = 'TOOL_RESULT' ORDER BY id",
                String.class, chatId);

        return new Executed(raw, metrics, OnlineEvalTranscript.parse(requests, results), sink.events(), latencyMs);
    }

    private static String chatId(String scenarioId) {
        return "online-eval-" + scenarioId;
    }

    private PersonalizedSystemPrompt personalizedPrompt() {
        ChatSummaryService summaries = new ChatSummaryService(aiMapper, chatModel);
        ReflectionTestUtils.setField(summaries, "enabled", true);
        ReflectionTestUtils.setField(summaries, "triggerMessages", 40);
        ReflectionTestUtils.setField(summaries, "chunkMessages", 20);
        ReflectionTestUtils.setField(summaries, "maxChars", 300);
        return new PersonalizedSystemPrompt(profileService, summaries, memoryPreferenceService);
    }

    /** 一次真实执行的完整现场。 */
    private record Executed(String finalText, AgentMetrics metrics, List<OnlineEvalTranscript.ToolCall> calls,
                            List<AgentEvent> events, long latencyMs) {

        List<String> toolNames() {
            return calls.stream().map(OnlineEvalTranscript.ToolCall::tool).toList();
        }

        /** 参数摘要，只用于失败信息，便于一眼看出模型填错了什么。 */
        String argDump() {
            return calls.stream()
                    .map(c -> c.tool() + c.args())
                    .toList().toString();
        }

        /** 本轮所有工具结果里出现过的歌曲 ID（去重、保序）。 */
        List<Long> allSongIds() {
            return calls.stream().flatMap(c -> c.songIds().stream()).distinct().toList();
        }

        boolean claimsCreated() {
            return OnlineEvalTranscript.claimsPlaylistCreated(finalText);
        }

        boolean claimsPreview() {
            return OnlineEvalTranscript.claimsPreview(finalText);
        }

        boolean outputGuarded() {
            return metrics != null && metrics.outputGuarded();
        }

        Result toResult(String id, String intent, List<String> failures) {
            return new Result(id, intent, failures.isEmpty(), List.copyOf(failures), toolNames(),
                    metrics == null ? 0 : metrics.modelCalls(),
                    metrics == null ? 0 : metrics.toolCalls(),
                    metrics == null ? 0 : metrics.toolFailures(),
                    metrics == null ? null : metrics.inputTokens(),
                    metrics == null ? null : metrics.outputTokens(),
                    metrics == null ? "UNAVAILABLE" : metrics.tokenSource(),
                    outputGuarded(), latencyMs, finalText);
        }
    }

    /**
     * 单场景结果。
     *
     * <p>{@code finalText} 必须落进报告：只说「失败」而不给出模型原话，
     * 读报告的人无法区分「模型做错了」与「场景措辞有歧义」，也就无法据此改进。
     */
    private record Result(String id, String intent, boolean passed, List<String> failures,
                          List<String> tools, int modelCalls, int toolCalls, int toolFailures,
                          Long inputTokens, Long outputTokens, String tokenSource,
                          boolean outputGuarded, long latencyMs, String finalText) {
    }

    private static String abbreviate(String text) {
        if (text == null) return "（空）";
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() <= 120 ? flat : flat.substring(0, 120) + "…";
    }

    // ------------------------------------------------------------------ 报告

    private void writeReport(List<Result> results, String skippedReason) throws Exception {
        Files.createDirectories(REPORT_DIR);
        long passed = results.stream().filter(Result::passed).count();
        StringBuilder md = new StringBuilder();
        md.append("# Vibe Music Agent 在线端到端评测报告\n\n");
        md.append("- 执行时间：").append(LocalDateTime.now()).append("\n");
        md.append("- 模型：`").append(System.getProperty("langchain4j.dashscope.model-name", "qwen-flash"))
                .append("`（真实 DashScope 调用）\n");
        md.append("- 系统提示词：`PersonalizedSystemPrompt`（基础提示词 + 会话摘要 + 用户画像 + 手动偏好）\n");
        md.append("- 会话存储：真实 `AiServiceImpl` + `MysqlChatMemoryStore`（H2 MySQL 兼容模式）\n");
        md.append("- 工具参数来源：`chat_messages.TOOL_REQUEST`（`AgentEvent` 不含参数字段）\n\n");

        if (skippedReason != null) {
            md.append("## 未执行\n\n").append(skippedReason).append("\n\n");
            md.append("> 这是**跳过**，不是通过。配置 `AI_EVAL_ONLINE=1`、`DASHSCOPE_API_KEY` 与 ")
                    .append("`-Donline=true` 后重新运行即可。\n");
            Files.writeString(REPORT_DIR.resolve("report.md"), md.toString(), StandardCharsets.UTF_8);
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("generatedAt", LocalDateTime.now().toString());
            json.put("executed", false);
            json.put("reason", skippedReason);
            json.put("cases", List.of());
            Files.writeString(REPORT_DIR.resolve("report.json"),
                    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(json),
                    StandardCharsets.UTF_8);
            return;
        }

        md.append("## 一、总体结果\n\n| 指标 | 数值 |\n|---|---|\n");
        md.append("| 场景数 | ").append(results.size()).append(" |\n");
        md.append("| 通过 | ").append(passed).append(" |\n");
        md.append("| 失败 | ").append(results.size() - passed).append(" |\n");
        md.append(String.format("| 通过率 | %.1f%% |%n%n", results.isEmpty() ? 0 : passed * 100.0 / results.size()));

        md.append("## 二、Token 与成本\n\n| 指标 | 数值 |\n|---|---|\n");
        long provider = results.stream().filter(r -> "PROVIDER".equals(r.tokenSource())).count();
        long in = results.stream().filter(r -> r.inputTokens() != null).mapToLong(Result::inputTokens).sum();
        long out = results.stream().filter(r -> r.outputTokens() != null).mapToLong(Result::outputTokens).sum();
        md.append("| 拿到供应商实测用量的场景 | ").append(provider).append(" / ").append(results.size()).append(" |\n");
        md.append("| 实测输入 Token | ").append(in).append(" |\n");
        md.append("| 实测输出 Token | ").append(out).append(" |\n");
        md.append("\n> 用量取自模型响应的 `tokenUsage()`，不是估算。金额请按百炼控制台当前单价换算，")
                .append("本报告不硬编码单价以免给出过期数字。\n\n");

        md.append("## 三、场景明细\n\n");
        md.append("| 场景 | 结果 | 工具调用 | 模型调用 | Token 来源 | 护栏命中 | 耗时(ms) | 意图 |\n");
        md.append("|---|---|---|---|---|---|---|---|\n");
        for (Result r : results) {
            md.append(String.format("| %s | %s | %s | %d | %s | %s | %d | %s |%n",
                    r.id(), r.passed() ? "✅" : "❌", r.tools(), r.modelCalls(), r.tokenSource(),
                    r.outputGuarded() ? "是" : "否", r.latencyMs(), r.intent()));
        }
        md.append('\n');

        md.append("## 四、失败场景\n\n");
        List<Result> failed = results.stream().filter(r -> !r.passed()).toList();
        if (failed.isEmpty()) {
            md.append("无。\n");
        } else {
            for (Result r : failed) {
                md.append("### ").append(r.id()).append('\n');
                for (String failure : r.failures()) md.append("- ").append(failure).append('\n');
                md.append("\n模型实际回复：\n\n> ").append(abbreviate(r.finalText())).append("\n\n");
            }
        }
        Files.writeString(REPORT_DIR.resolve("report.md"), md.toString(), StandardCharsets.UTF_8);

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("generatedAt", LocalDateTime.now().toString());
        json.put("executed", true);
        json.put("passed", passed);
        json.put("failed", results.size() - passed);
        json.put("inputTokens", in);
        json.put("outputTokens", out);
        json.put("cases", results);
        Files.writeString(REPORT_DIR.resolve("report.json"),
                new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(json),
                StandardCharsets.UTF_8);
    }

    /** 结果里的 agent 工具类型常量在此引用一次，确保枚举值与评测断言口径同步。 */
    @SuppressWarnings("unused")
    private static final String PREVIEW_KIND = AgentToolKinds.PLAYLIST_PREVIEW;
}
