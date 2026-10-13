package cn.edu.seig.vibemusic.controller;

import cn.edu.seig.vibemusic.service.agent.AgentSongQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** 卡片翻页直接查询曲库，无需再次调用模型。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/ai/songs")
public class AgentSongController {
    private final AgentSongQueryService songs;

    @GetMapping
    public AgentSongQueryService.SongPage page(
            @RequestParam(required = false) @Size(max = 255) String keyword,
            @RequestParam(required = false) @Size(max = 255) String artistName,
            @RequestParam(required = false) @Size(max = 255) String style,
            @RequestParam(defaultValue = "1") @Min(1) @Max(100000) int page,
            @RequestParam(defaultValue = "5") @Min(1) @Max(20) int size) {
        return songs.page(keyword, artistName, style, page, size);
    }
}
