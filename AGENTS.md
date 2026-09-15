# RadioApp 项目强制工作流（不可遗忘的铁律）

> 以下规则由用户多次强调，必须无条件遵守。每次在本项目内做任何改动时，都必须完整执行，禁止省略。
> **本文件是"软约束"（依赖每次主动读取）。真正的"永久固化"已落地到代码层的 `enforceHardRules()` 运行时硬校验（程序级强制不变量），两者互为双保险。**

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
- 编译成功后，把新 APK 复制到 `/workspace/radioapp/releases/` 目录。
- 同步更新 `/workspace/radioapp/releases/index.html`：版本号、更新说明、下载链接 `href` 全部改为新版本。

## 铁律 2：分享 APK 必须用可点击的 computer:// 链接
- 向用户交付 APK 时，**必须**使用 `computer://` 协议的可点击安装链接（形如 `[标签](computer:///workspace/radioapp/releases/RadioApp-vX.Y.Z.apk)`），而**不是** GitHub 链接、也不只是文件路径。
- 链接文本用自然语言说明（如"下载安装"），让用户能在结果里直接点击安装。

## 铁律 3：每次改动代码 → 备份到 GitHub
- 每次改完代码/页面，必须在交付前把改动 commit 并 push 到 GitHub（`git add` 相关源码文件 → `git commit` → `git push origin main`）。
- 注意 `.apk` 构建产物已被 `.gitignore` 忽略（`releases/*.apk`），只备份源码，不要提交 APK。

## 执行顺序
每次改动后，按此顺序完成三件事后再向用户汇报：
1. 编译并复制 APK 到 `releases/`
2. 更新 `releases/index.html`
3. 提交并推送源码到 GitHub
4. 最后用 computer:// 链接把 APK 交付给用户