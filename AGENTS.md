# RadioApp 项目强制工作流（不可遗忘的铁律）

> 以下规则由用户多次强调，必须无条件遵守。每次在本项目内做任何改动时，都必须完整执行，禁止省略。
> **本文件是"软约束"（依赖每次主动读取）。真正的"永久固化"已落地到代码层的 `enforceHardRules()` 运行时硬校验（程序级强制不变量），两者互为双保险。**

## 铁律 00：全流程输出语言 = 中文
对用户的所有输出、以及我的一切思考/推理过程，一律使用中文（简体中文优先）。即便用户的提问里夹带其他语言（含英文、乌克兰语等），回答仍用中文；仅当用户以中文以外的语言明确提问时才以其语言回复。此规则与本项目其他铁律同样必须遵守。

## 铁律 0：分段算法的高频用户规则（必须维护为运行时硬校验，禁止只靠注释"记得"）
以下规则已固化为 `SegmentGenerator.enforceHardRules()` 里的不变量，**每次 `generateJiuAiTingSegments` 运行时必然强制执行**。今后任何修改分段逻辑时：
- 不得删除 `enforceHardRules()` 的调用（它是规则的兜底，删了规则就"失忆"）。
- 新增处理步骤后，若可能产生"无缝相邻同类型段/过短碎段/时间洞/超长段/非法标签"，必须自行核对这组不变量；如有新增需固化的规则，同步加入 `enforceHardRules`。
- 已固化不变量：
  - R1 时间轴连续：任意时间点必须有分段覆盖。
  - R2 无同类型碎段：无缝相邻同类型段合并、过短碎段（<1.5s）剔除。
  - R3 长度上限：干货≤30分钟、水段≤5分钟。
  - R4 标签合法：不得出现"分类失败"或 label=null。

## 铁律 1：每次改动代码 → 必须编译 APK
- 凡是对本项目代码/配置/页面做了任何修改，都必须立刻运行 `./gradlew assembleRelease` 编译出对应版本的 Release APK。
- 产出路径：`/workspace/radioapp/app/build/outputs/apk/release/RadioApp-v<版本号>.apk`。
- 编译成功后，把新 APK 复制到工作区根目录：`/workspace/RadioApp-v<版本号>.apk`。
- 同步更新 `/workspace/radioapp/releases/index.html`：版本号、更新说明、下载链接 `href` 全部改为新版本。

## 铁律 2：交付 APK 唯一方式 = 工作区根目录 computer:// 链接（用户强制，不可用其他方式）
- 向用户交付 APK 时，**唯一且必须**：先把 APK 复制到工作区根目录 `/workspace/RadioApp-vX.Y.Z.apk`，再用 `computer://` 协议的可点击安装链接分享，形如 `[下载安装](computer:///workspace/RadioApp-vX.Y.Z.apk)`。
- **禁止**使用其他任何方式交付 APK：不得用 GitHub Release、不得用 GitHub 链接、不得用 `releases/` 目录路径、不得只贴文件路径、不得只写文字说明"APK 在 XX 目录"。
- 链接文本用自然语言说明（如"下载安装"），让用户能在结果里直接点击安装。
- 每次交付都必须附上该链接，无例外。

## 铁律 3：每次改动代码 → 备份到 GitHub
- 每次改完代码/页面，必须在交付前把改动 commit 并 push 到 GitHub（`git add` 相关源码文件 → `git commit` → `git push origin main`）。
- 注意 `.apk` 构建产物已被 `.gitignore` 忽略（`releases/*.apk`），只备份源码，不要提交 APK。

## 执行顺序
每次改动后，按此顺序完成三件事后再向用户汇报：
1. 编译 APK，并把新 APK 复制到工作区根目录 `/workspace/RadioApp-v<版本号>.apk`
2. 更新 `releases/index.html`
3. 提交并推送源码到 GitHub
4. 最后用 `computer:///workspace/RadioApp-vX.Y.Z.apk` 链接把 APK 交付给用户