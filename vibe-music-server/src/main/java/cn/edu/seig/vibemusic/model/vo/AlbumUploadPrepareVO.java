package cn.edu.seig.vibemusic.model.vo;

import lombok.Data;
import java.io.Serializable;
import java.util.List;

@Data
public class AlbumUploadPrepareVO implements Serializable {
    private String uploadId;
    private String albumName;
    private String artistName;
    private Long artistId;
    private String releaseDate;
    private String coverUrl;
    private String warning;
    private List<Song> songs;

    @Data
    public static class Song implements Serializable {
        private String stagingKey;
        private String fileName;
        private String songName;
        private String artistName;
        private Long artistId;
        private Integer trackNumber;
        private Integer discNumber;
        private Double duration;
        private String durationText;
        private Long fileSize;
        private String coverUrl;
    }
}
