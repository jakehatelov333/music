package cn.edu.seig.vibemusic.model.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 「把搜到的专辑封面下载到 staging」的请求。
 *
 * <p>刻意只包含 {@code albumMid} 与 {@code size}，<b>不含 URL</b>。
 * 如果允许调用方传 URL，这个端点就变成了「让服务端去抓任意地址」的入口：
 * 内网服务、云元数据接口、任意协议都会被尝试（SSRF）。改成由服务端按固定模板
 * 拼地址后，抓取目标被限制在单个 CDN 的固定路径上，{@code albumMid} 再走白名单校验。
 */
@Data
public class CoverFromAlbumDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 专辑 mid，形如 {@code 002eFUFm2XYZ7z}；由搜索接口的候选回传 */
    private String albumMid;

    /** 封面尺寸，300 / 500 / 800；为空或非法时取 500 */
    private Integer size;
}
