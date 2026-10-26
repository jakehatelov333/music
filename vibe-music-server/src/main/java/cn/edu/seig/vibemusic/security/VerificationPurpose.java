package cn.edu.seig.vibemusic.security;

/**
 * Verification codes are scoped by purpose, so a registration code can never be replayed
 * against password reset or email change.
 */
public enum VerificationPurpose {
    REGISTER,
    RESET_PASSWORD,
    EMAIL_CHANGE_OLD,
    EMAIL_CHANGE_NEW
}
