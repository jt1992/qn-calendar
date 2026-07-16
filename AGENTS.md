# AGENTS.md

本文件是本專案給 Codex / AI coding agent 的根目錄協作規則。實作前請先閱讀本文件，再依工作範圍閱讀子目錄規則。

## 導航

- 前端規則：[`frontend/AGENTS.md`](frontend/AGENTS.md)
- 後端規則：[`backend/AGENTS.md`](backend/AGENTS.md)

## 專案目標

建立一套「XLSX 訂單匯入與工單排程系統」：

1. 前端上傳 XLSX。
2. 後端解析、去重並建立待排工單。
3. 前端顯示待排工單與日曆。
4. 使用者可拖曳、移動、resize 工單。
5. 排程結束時間不可超過最晚發貨時間。
6. 工單可標記完成，完成後淡化顯示。
7. 不同訂單編號的工單不可重疊。
8. 同一訂單編號的分割片段若時間相鄰或重疊，需自動融合成同一片段。
9. 使用者可寄送 A4 横向 PDF 附件 Email。

## 通用協作守則

- 先明示假設與不確定性，不要靜默猜測。
- 優先採用最簡單且足以完成需求的方案，不額外引入抽象、配置或擴充點。
- 只修改與當前需求直接相關的內容；若發現無關問題，指出即可，不主動擴大重構。
- 先把任務轉成可驗證的完成條件，再依條件實作與檢查。
- 系统用户可见中文文案一律使用简体中文；仅为 XLSX 导入兼容保留繁体列名识别。
- 系统所有源码、模板、HTTP/Email/PDF 内容与附件文件名编码一律使用 UTF-8；不得依赖平台默认编码。
- 不要個別啟動前端或後端開發服務器；若需要運行中系統協助驗證，直接使用 Docker Compose 重新建置並重啟整套服務後再測試，不需請使用者手動啟動。
- 提交時必須納入所有工作樹變更；不要自行把看似本機偏好的檔案排除在 commit 外，除非使用者明確要求排除。
- 前端以 JavaScript、Vue 3.5.35、Vite 8.0.16 為基準。
- 後端以本專案 Spring Boot 架構為基準。
- 容器化以 Docker 為主。
- 雲端架構以 AWS 為主。

## 文件維護規則

- `hand-off-doc.md` 只記錄尚未完成、需要接手或後續處理的功能與事項。
- 已完成的功能不要保留在 `hand-off-doc.md`；完成後應刪除對應交接紀錄，並改記錄在根目錄 `README.md`。

## GitHub Flow

### Branch naming

Branch naming follows the main purpose of the work item. If a feature branch
already exists for the current task, continue implementing related feature and
maintenance/documentation changes on that same branch; use commit messages to
distinguish `feat:` and `chore:` changes instead of creating another branch.

| Change type                            | Format                         |
| -------------------------------------- | ------------------------------ |
| New feature                            | `feature/codex-<description>`  |
| Maintenance, bug fix, refactor, config | `chore/codex-<description>`    |

Conventions:

- `codex-` prefix is fixed (= "Codex-created").
- `<description>` lowercase English, words joined with `-`.
- Keep short and specific.

Examples: `feature/codex-user-login`, `feature/codex-payment-flow`,
`chore/codex-eslint-config`, `chore/codex-refactor-api`.

### General Git rules

- Always use `git switch` (never `git checkout`) to change branches.
- Run `git status` before switching and confirm the working tree is clean.
- If unrelated changes exist: `git stash` or ask the user first.
- Announce the new branch name to the user before creating it.
- Do not modify `main` directly.
- Do not create a separate branch only to split related feature and maintenance/documentation changes during the same task.
- Within one branch, use separate commits to distinguish change type when useful.
- New feature commit messages must start with `feat:`.
- Maintenance commit messages must start with `chore:`.

### Prohibited for AI agents

The agent must not unilaterally:

- Commit changes
- Push to remote
- Create a PR

After finishing edits, wait for the user's confirmation. Trigger phrases and what they authorize:

| Phrase                                    | Authorizes                                                                                       |
| ----------------------------------------- | ------------------------------------------------------------------------------------------------ |
| **"可以提交"**                            | Full pipeline: commit -> push -> open PR (run all three back-to-back without further confirmation) |
| "檢查完了" / "looks good" / "可以 commit" | Commit only                                                                                      |
| "幫我 push"                               | Push the current branch                                                                          |
| "幫我開 PR"                               | Open a PR (use Squash Merge default)                                                             |

**"可以提交"** is the convenience shortcut: when the user says it, the
agent runs commit -> push -> open PR in sequence and reports the PR URL.
Use any available PR-creation path that works in the current environment
(GitHub connector, GitHub CLI, or another approved repository tool); do not
require a specific CLI command. The other phrases remain as fine-grained
controls if the user wants to step through individually.

For this repository, publish through the configured SSH Git remote and the
GitHub connector/API path that has already succeeded in this workspace. Do
not use GitHub CLI (`gh`) authentication or `gh pr create` unless the user
explicitly asks for it.

PRs opened by the agent must be ready-for-review PRs, not draft PRs,
unless the user explicitly asks for a draft.

### Allowed exceptions

Direct edits on `main` are allowed only for:

- Edits limited to `CLAUDE.md`
- Edits limited to `.gitignore`
- Edits limited to `hand-off-doc.md` session handoff updates
- New / reorganized `README.md`
- User explicitly says "這次直接在 main 改" or equivalent

Reminder: after every completed feature or bug fix, update documentation according to the file maintenance rules above. `hand-off-doc.md` should only keep unfinished follow-up items, while completed functionality belongs in the root `README.md`. Session handoff updates limited to `hand-off-doc.md` may still be committed directly on `main` without creating a feature branch.

### Full sequence

Unless a merge conflict occurs, do not perform excessive checks during this flow; run the listed commands in order.

```bash
# 1. Sync main and branch off it
git switch main
git pull origin main
git switch -c feature/codex-xxx     # or chore/codex-xxx

# 2. Develop, commit by actual change type
git add <files>
git commit -m "feat: xxx"
# or
git commit -m "chore: xxx"

# 3. Before push, rebase onto latest main (linear history)
git fetch origin
git rebase origin/main

# 4. Push (after rebase use --force-with-lease)
git push -u origin feature/codex-xxx
# if already pushed and rebased again:
git push --force-with-lease

# 5. Open PR -> Squash Merge -> delete the remote branch
```

### After merge: local cleanup

```bash
git switch main
git pull origin main
git branch -D feature/codex-xxx
```

### Release flow

Release tags must point to commits that already belong to `main`.

Correct release sequence:

```bash
# 1. Finish code or workflow changes through the normal PR flow
git switch main
git pull origin main

# 2. Create the release tag from latest main
git tag v1.0.0
git push origin v1.0.0
```

Do not push or force-push a release tag to a commit that only exists on an
unmerged feature/chore branch. GitHub will show "This commit does not belong to
any branch on this repository" for that tag, even if the release workflow itself
succeeds. If a release workflow needs fixes, first merge the fix into `main`,
then create or move the release tag from the updated `main` commit.

### Why `--force-with-lease`

`--force-with-lease` checks the remote state before pushing, so it will not clobber someone else's commits. Plain `--force` is unsafe and forbidden.
