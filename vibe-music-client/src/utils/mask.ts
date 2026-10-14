/**
 * 邮箱脱敏：保留首字符与 @ 后域名，其余以 *** 代替。
 * 例如 `abc@domain.com` → `a***@domain.com`；异常输入原样返回。
 */
export const maskEmail = (email: string | null | undefined): string => {
  if (!email) return ''
  const at = email.indexOf('@')
  if (at <= 0) return email
  return `${email[0]}***${email.slice(at)}`
}

/**
 * 手机号脱敏：保留前 3 位与后 4 位。
 * 例如 `13812341234` → `138****1234`；长度不足时原样返回。
 */
export const maskPhone = (phone: string | null | undefined): string => {
  if (!phone) return ''
  if (phone.length !== 11) return phone
  return `${phone.slice(0, 3)}****${phone.slice(7)}`
}
