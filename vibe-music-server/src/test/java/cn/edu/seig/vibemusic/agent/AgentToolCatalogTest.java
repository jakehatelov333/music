package cn.edu.seig.vibemusic.agent;

import cn.edu.seig.vibemusic.tool.AgentToolCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolCatalogTest {

    @Test
    @DisplayName("工具筛选候选来自全部已注册工具，而不是只看历史 Trace")
    void catalogContainsEveryRegisteredTool() {
        List<String> names = AgentToolCatalog.names();

        assertEquals(10, names.size());
        assertEquals(names.stream().sorted().toList(), names, "候选应稳定排序");
        assertTrue(names.contains("searchSongsByArtist"));
        assertTrue(names.contains("getRecentPlayedSongs"));
        assertTrue(names.contains("preparePlaylistCreation"));
    }
}
