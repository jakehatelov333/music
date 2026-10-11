package cn.edu.seig.vibemusic.model.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

@Data
public class AlbumUploadCommitDTO implements Serializable {
    private String uploadId;
    private AlbumInfo album;
    private List<SongInfo> songs;

    @Data
    public static class AlbumInfo implements Serializable {
        private String albumName;
        private Long artistId;
        private String artistName;
        private String coverUrl;
        private LocalDate releaseDate;
        private String description;
        private String status;
    }

    @Data
    public static class SongInfo implements Serializable {
        private String stagingKey;
        private String fileName;
        private String songName;
        private Long artistId;
        private String artistName;
        private Integer trackNumber;
        private Integer discNumber;
        private String lyrics;
    }
}
