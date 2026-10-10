# 安全事件处置记录与密钥管理规范

> 最近一次事件：2026-10 发现 DashScope API Key 与 QQ 邮箱 SMTP 授权码被明文提交到公开仓库历史中。

## 一、泄露内容

| 凭据 | 用途 | 泄露位置（提交历史） | 状态 |
|---|---|---|---|
| DashScope API Key（`sk-...`） | 阿里云百炼 / 通义千问大模型调用 | `vibe-music-server` commit `bc735e2`、本仓库 commit `60c3e69` | 需在阿里云控制台吊销并重新生成 |
| QQ 邮箱 SMTP 授权码 | 后端邮件服务（验证码等） | 同上 | 需在 QQ 邮箱设置中关闭并重新生成 |

## 二、立即要做的事（按顺序）

1. **吊销并重新生成 DashScope API Key**
   - 登录 [阿里云百炼控制台](https://bailian.console.aliyun.com/) → API-KEY 管理 → 删除旧 Key → 创建新 Key。
2. **重置 QQ 邮箱授权码**
   - 登录 QQ 邮箱 → 设置 → 账户 → POP3/SMTP 服务 → 关闭旧授权码并重新生成。
3. **把新密钥填入本地 `vibe-music-server/.env`**（该文件已被 `.gitignore` 忽略，不会进入仓库）。
4. **清理 Git 历史**：见下文第四节。只要旧提交还在历史里，泄露就仍然存在。

## 三、代码层面已完成的修补

- `vibe-music-server/src/main/resources/application.yml`：所有敏感项改为 `${ENV_NAME:}` 占位符（`MYSQL_PASSWORD`、`MAIL_USERNAME`、`MAIL_PASSWORD`、`MINIO_ACCESS_KEY`、`MINIO_SECRET_KEY`、`DASHSCOPE_API_KEY`），并通过 `spring.config.import` 自动加载项目根目录的 `.env`。
- 新增 `vibe-music-server/.env.example`（提交到仓库的模板）与 `vibe-music-server/.env`（本地私密文件，已忽略）。
- 新增 `SecretsGuard`：启动时校验敏感配置是否已注入；`prod` 环境缺失任一密钥直接启动失败，本地环境打印醒目告警。
- `vibe-music-server/.gitignore` 与本仓库 `.gitignore`：忽略 `.env`、`.env.*`（保留 `.env.example`）及各类本地配置文件。
- `change_files.md`、`vibe-music-server/README.md`：移除明文密钥与邮箱账号，改为占位符说明。
- 构建产物 `target/classes/application.yml` 中的旧明文副本已用清理后的版本覆盖。

## 四、历史清理（必须执行）

仅改当前文件不会删除历史提交里的密钥。执行以下命令重写两个仓库的完整历史：

```powershell
# 0) 安装工具（任选其一）
pip install git-filter-repo
# 或 winget install --id=NewAM.git-filter-repo

# 1) 先在 music 仓库提交本次修补（保证工作区干净）
# 2) 重写本地历史（密钥替换规则在 scripts/secret-expressions.txt，已被 gitignore）
powershell -ExecutionPolicy Bypass -File scripts/purge-git-history.ps1
# 3) 检查无误后强制推送覆盖远端
powershell -ExecutionPolicy Bypass -File scripts/purge-git-history.ps1 -Push
```

**注意事项：**
- 强制推送会覆盖 `origin/main`，其他协作者必须重新克隆（旧的本地仓库会把密钥再次推回来）。
- GitHub 仍可能通过旧 commit SHA / Fork / 缓存短暂访问到旧内容；若仓库是 public，可联系 GitHub Support 请求清除缓存。

## 五、历史清理之后

1. 通知所有协作者删除旧克隆、重新 `git clone`。
2. 在每个仓库执行 `git reflog expire --expire=now --all` 与 `git gc --prune=now` 清理本地残留对象。
3. 用新密钥验证后端邮件发送与 AI 对话功能正常。
4. 安装 pre-commit 钩子，防止密钥再次被提交：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/check-secrets.ps1 -Install -Repo .
powershell -ExecutionPolicy Bypass -File scripts/check-secrets.ps1 -Install -Repo vibe-music-server
powershell -ExecutionPolicy Bypass -File scripts/check-secrets.ps1 -Install -Repo vibe-music-admin
powershell -ExecutionPolicy Bypass -File scripts/check-secrets.ps1 -Install -Repo vibe-music-client
```

## 六、长期规范

- **任何密钥、授权码、口令只允许通过环境变量 / `.env` 注入**，`.env` 永远不提交；模板放在 `.env.example` 且不含真实值。
- 提交前钩子（`scripts/check-secrets.ps1`）会拦截疑似明文密钥。
- 新增第三方服务时：先在 `.env.example` 里登记变量名，再在 `SecretsGuard` 的 `REQUIRED_SECRETS` 中登记，最后写配置占位符。
- 密钥一旦出现在聊天记录、截图、Issue 中，一律视为泄露，立即吊销重发。