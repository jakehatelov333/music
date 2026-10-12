package cn.edu.seig.vibemusic.model.agent;

/**
 * 统一 Agent 工具返回协议。
 *
 * <p>工具方法统一返回本对象的 JSON 文本，同时服务两个消费方：
 * <ul>
 *     <li>大模型：读 {@code ok/message/data}，理解“成功但为空”与“调用失败”的区别；</li>
 *     <li>前端：读 {@code kind/total/data}，把结果渲染成歌曲卡片、歌单卡片或确认卡片。</li>
 * </ul>
 *
 * <p><b>业务类型标识（kind）</b>：SONG_LIST / ARTIST_LIST / PLAYLIST_LIST /
 * PLAYLIST_PREVIEW / PLAYLIST_CREATED / TEXT。前端只依据 kind 决定渲染方式，
 * 不解析 message 文本，避免模型措辞变化影响界面。
 *
 * <p><b>语义约定</b>：搜索成功但没有命中，是 {@code ok=true, code=EMPTY}，
 * 不是工具调用失败；参数非法或内部异常才是 {@code ok=false}。
 * 模型据此可以区分“换个关键词再试”和“放弃这个工具”。
 */
public record ToolResult<T>(
        boolean ok,
        String code,
        String message,
        String kind,
        int total,
        T data
) {
    public static <T> ToolResult<T> ok(String kind, String message, T data, int total) {
        return new ToolResult<>(true, AgentToolCodes.OK, message, kind, total, data);
    }

    /** 执行成功但没有任何业务数据，语义上区别于失败。 */
    public static <T> ToolResult<T> empty(String kind, String message) {
        return new ToolResult<>(true, AgentToolCodes.EMPTY, message, kind, 0, null);
    }

    /** 参数非法：模型应修正参数后重试，属于可纠正失败。 */
    public static <T> ToolResult<T> invalid(String message) {
        return new ToolResult<>(false, AgentToolCodes.INVALID_ARGUMENT, message, AgentToolKinds.TEXT, 0, null);
    }

    /** 未登录等权限问题：模型应转告用户去登录，不应反复重试。 */
    public static <T> ToolResult<T> unauthorized(String message) {
        return new ToolResult<>(false, AgentToolCodes.UNAUTHORIZED, message, AgentToolKinds.TEXT, 0, null);
    }

    /** 业务规则拒绝：参数合法但当前业务状态不允许。 */
    public static <T> ToolResult<T> rejected(String code, String message) {
        return new ToolResult<>(false, code, message, AgentToolKinds.TEXT, 0, null);
    }

    /** 工具执行异常：调用方（或前端）可展示为“工具执行失败”。 */
    public static <T> ToolResult<T> error(String message) {
        return new ToolResult<>(false, AgentToolCodes.ERROR, message, AgentToolKinds.TEXT, 0, null);
    }
}
