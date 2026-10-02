# Coopanion 桌宠 · 安卓外壳

把 [Coopanion](https://github.com/Pal-AI-Lab/Coopanion) 的桌宠搬到安卓上的外壳实现。

## 做法

上游的桌宠（`packages/cortico-world-desktop-pet`）本身是**网页**渲染的 ——
`web/pet.html` + `rig/` + `whale/` 分层素材。所以这里不重写动画，而是：

1. `AssetServer`：应用内起一个极简本地 HTTP 服务，把 APK 里的 `assets/web/` 按
   `/web/...` 的 URL 结构端出来（上游用的是绝对路径，必须保持同样的路径结构）。
2. `PetService`：前台服务 + `TYPE_APPLICATION_OVERLAY` 透明悬浮窗，窗口只占**屏幕底边一条**
   （默认 300dp 高），里面是背景透明的 WebView 加载 `http://127.0.0.1:<port>/web/pet.html`。
   窗口之外的区域触摸正常透传。
3. `MainActivity`：授权悬浮窗 → 启动/停止桌宠 → 打开装扮页。

**好处**：上游更新形象/配色，只要重新构建就跟着更新，不需要重新实现整个动画系统。

## 构建

桌宠素材不提交到本仓库，由 CI 在构建时从上游拉取：

- GitHub Actions：推送到 `main` 或手动 `workflow_dispatch` → 产出 `coopanion-pet-debug` artifact
- 本地：先把上游的 `packages/cortico-world-desktop-pet/web/` 复制到 `app/src/main/assets/web/`，
  再 `gradle assembleDebug`

## 已知限制（v0.1）

- 只有桌宠形象与装扮页；**聊天/记忆/语音**还没接（需要接 Cortico 的 `/socket`）
- 装扮页的「保存」需要后端，目前会提示没保存上
- 悬浮窗需要手动授予；开机自启在部分 MIUI 上需要在系统设置里允许

## 许可

外壳代码 MIT。桌宠素材与形象版权归上游 Coopanion 项目（AGPL-3.0-or-later）所有，
本仓库不包含这些素材。
