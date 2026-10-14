/**
 * 统一的密码强度规则。前端所有「设置新密码」的入口（注册、忘记密码、修改密码）
 * 必须共用这里的正则与提示，避免不同入口行为不一致。
 *
 * 规则：8-18 位；必须同时包含至少一个 ASCII 字母与至少一个 ASCII 数字；
 * 允许可见 ASCII 字符（0x21-0x7E）；不允许空格。
 *
 * 注意：登录表单不套用该规则 —— 老用户的密码可能不满足新规则，
 * 登录时只校验必填，把强度判断交给服务端。
 */
export const PASSWORD_PATTERN = /^(?=.*[A-Za-z])(?=.*[0-9])[\x21-\x7E]{8,18}$/

export const PASSWORD_RULE_MESSAGE = '密码为 8-18 位，必须同时包含字母和数字，不能包含空格'

export const isPasswordValid = (value: string) => PASSWORD_PATTERN.test(value)
