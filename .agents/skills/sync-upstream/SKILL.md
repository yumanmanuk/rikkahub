---
name: sync-upstream
description: Use this skill when the user wants to pull/sync upstream changes into their local fork, resolve merge conflicts, and preserve locally added features. Triggered by phrases like "拉取upstream代码", "同步上游", "sync upstream", "pull upstream", "merge upstream", or "rebase upstream".
---

# Sync Upstream (同步上游代码)

本 Skill 用于将 upstream 的最新代码合并到本地 fork，并在出现冲突时**优先保留本地 fork 添加的功能**。

## 冲突分析原则（重要）

| 冲突类型 | 处理策略 |
|---|---|
| 上游修改了本地**未改动**的代码 | 直接采用上游版本 |
| 上游和本地**都修改**了同一处 | 语义分析，融合两者，优先保留本地功能 |
| 上游**删除**了本地新增的代码块 | 保留本地新增代码 |
| 上游新增了全新代码，本地无对应 | 采用上游新增 |
| 本地新增了全新代码，上游无对应 | 保留本地新增 |

---

## 注意事项

- **永远不要** 在没有分析的情况下直接 `git checkout --theirs` 或 `git checkout --ours` 覆盖整个文件
- 对于复杂冲突，应该逐段阅读冲突块，理解上下游双方的意图
- 涉及数据库 schema、API 接口定义等关键文件时，更要仔细
- 如果冲突过多，应先告知用户并列出冲突文件清单，再逐一解决
