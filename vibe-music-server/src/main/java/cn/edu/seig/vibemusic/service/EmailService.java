package cn.edu.seig.vibemusic.service;

/**
 * <p>
 * 服务类
 * </p>
 *
 * @author sunpingli
 * @since 2025-01-09
 */
public interface EmailService {

    // 发送邮件
    boolean sendEmail(String to, String subject, String content);
}
