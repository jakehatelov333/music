package cn.edu.seig.vibemusic;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "minio.accessKey=test-access",
        "minio.secretKey=test-secret"
})
class VibeMusicServerApplicationTests {

    @Test
    void contextLoads() {
    }

}
