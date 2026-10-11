package cn.edu.seig.vibemusic.controller;

import cn.edu.seig.vibemusic.model.vo.GlobalSearchVO;
import cn.edu.seig.vibemusic.result.Result;
import cn.edu.seig.vibemusic.service.ISearchService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全局音乐搜索控制器。
 */
@RestController
@RequestMapping("/search")
public class SearchController {

    @Autowired
    private ISearchService searchService;

    @GetMapping
    public Result<GlobalSearchVO> search(@RequestParam(defaultValue = "") String keyword,
                                         @RequestParam(defaultValue = "10") int limit,
                                         HttpServletRequest request) {
        return searchService.search(keyword, limit, request);
    }
}
