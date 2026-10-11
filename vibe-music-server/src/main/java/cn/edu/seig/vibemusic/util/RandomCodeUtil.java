package cn.edu.seig.vibemusic.util;

import java.util.Random;

public class RandomCodeUtil {

    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int LENGTH = 6; // 生成的字符串长度
    private static final Random random = new Random();

    /**
     * 生成随机的6个字符的字符串
     *
     * @return 随机字符串
     */
    public static String generateRandomCode() {
        //创建一个可变字符串对象，初始容量为 LENGTH（6），用于高效拼接字符。
        StringBuilder stringBuilder = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            int index = random.nextInt(CHARACTERS.length());
            stringBuilder.append(CHARACTERS.charAt(index));
        }
        return stringBuilder.toString();
    }

}
