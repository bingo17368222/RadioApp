# RadioApp 项目强制工作流（不可遗忘的铁律）

> 以下规则由用户多次强调，必须无条件遵守。每次在本项目内做任何改动时，都必须完整执行，禁止省略。

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