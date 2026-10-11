package cn.edu.seig.vibemusic.service;

import cn.edu.seig.vibemusic.model.vo.GlobalSearchVO;
import cn.edu.seig.vibemusic.result.Result;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 全局音乐搜索服务。
 */
public interface ISearchService {

    Result<GlobalSearchVO> search(String keyword, int limit, HttpServletRequest request);
}
