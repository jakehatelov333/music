package cn.edu.seig.vibemusic.security;

import cn.edu.seig.vibemusic.controller.CommentController;
import cn.edu.seig.vibemusic.controller.SongController;
import cn.edu.seig.vibemusic.handler.GlobalExceptionHandler;
import cn.edu.seig.vibemusic.service.ICommentService;
import cn.edu.seig.vibemusic.service.ISongService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InputValidationTest {
    @Test void invalidPaginationNeverReachesDatabaseQueries() throws Exception {
        SongController controller = new SongController();
        ISongService service = mock(ISongService.class);
        ReflectionTestUtils.setField(controller, "songService", service);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        for (String body : new String[]{"{\"pageNum\":0,\"pageSize\":10}", "{\"pageNum\":1,\"pageSize\":101}"}) {
            mvc.perform(post("/song/getAllSongs").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1));
        }
        verifyNoInteractions(service);
    }

    @Test void blankOrOversizedCommentsNeverReachWrites() throws Exception {
        CommentController controller = new CommentController();
        ICommentService service = mock(ICommentService.class);
        ReflectionTestUtils.setField(controller, "commentService", service);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        for (String content : new String[]{" ", "x".repeat(256)}) {
            mvc.perform(post("/comment/addSongComment").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"songId\":1,\"content\":\"" + content + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
}
