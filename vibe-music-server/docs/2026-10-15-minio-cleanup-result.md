**MinIO 清理执行记录（2026-10-15）**

按用户本轮授权执行，仅清理上一份审计报告中列出的 10 个对象。每次调用只接受一个完整对象 key，逐项重新核对引用、备份、删除并检查对象已不存在，未使用前缀、通配符或多对象删除 API。

| 项目 | 结果 |
| --- | ---: |
| 清理对象数 | 10 |
| 移除对象总大小 | 15,857,324 字节（15.12 MiB） |
| MinIO 对象数 | 999 → 989 |
| 清理后未引用候选 | 0 |
| 清理后引用缺失 | 0 |
| 数据库媒体引用 | 1,508，全部有效 |
| 其余对象大小/修改时间 | 未变化 |
| 本地恢复副本 | 10 份，SHA-256 全部校验通过 |

执行完成时间：2026-10-15T00:13:31.151010+08:00。没有修改业务源码或数据库结构。

| 已删除的精确对象 key | 字节数 |
| --- | ---: |
| `.idea/.gitignore` | 190 |
| `.idea/misc.xml` | 276 |
| `.idea/modules.xml` | 289 |
| `.idea/vibe-music-data.iml` | 344 |
| `.idea/workspace.xml` | 2,343 |
| `artists/02d5b3f0-3aa8-473a-b037-a5beef327413-blob` | 58,226 |
| `songCovers/7d5e35eb-ea81-447a-b709-b237549de862-blob` | 1,384,455 |
| `songCovers/8f8a6233-d178-4ae1-aef6-e4d254ad96aa-blob` | 1,643,987 |
| `songs/1cc99b92-1a13-477e-9544-f2e60fa401c4-Aaron Carter - Sooner Or Later.mp3` | 8,617,964 |
| `songs/8bcb006d-b4d2-41a1-92e3-bfd207a0adf6-ELSIE - Real Love (EUNJUNG SOLO).mp3` | 4,149,250 |

[删除前审计](D:/java_code/music/out/minio-cleanup-2026-10-15/before/minio-audit.json) · [删除后审计](D:/java_code/music/out/minio-cleanup-2026-10-15/after/minio-audit.json) · [执行结果与恢复映射](D:/java_code/music/out/minio-cleanup-2026-10-15/cleanup-result.json) · [逐项执行日志](D:/java_code/music/out/minio-cleanup-2026-10-15/deletion-journal.jsonl)

本地备份保存在 `out/minio-cleanup-2026-10-15/backups/`，每个备份的原始 bucket、完整对象 key、Content-Type、大小及 SHA-256 记录在执行结果中。若将来需要恢复，应先核查原 key 没有被新对象占用，再单独恢复指定对象；本次没有自动恢复或删除备份。

本次清理是对已核实清单的一次性操作；应用里的遗留文件根因尚未修复，修复方案仍等待用户批准。
