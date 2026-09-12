# 世界资源包与 Mod Menu 共用图标

采用用户选定的第一个候选图：原地球＋左向铁砧造型，保留原图的图案像素。
仅将与画布外边缘连通的浅灰/白色棋盘格背景转为透明，没有采用第二个重新设计版本。
ImageGen 的背景提取候选仍含棋盘格，未使用；最终背景清理由确定性边缘连通处理完成。

唯一资产：`src/main/resources/assets/worldsmith/textures/gui/sprites/icon/world_pack.png`。
主菜单使用 GUI sprite `worldsmith:icon/world_pack`；`fabric.mod.json` 的 `icon` 同样指向
`assets/worldsmith/textures/gui/sprites/icon/world_pack.png`，Mod Menu 从该元数据读取同一张图。
菜单位置与按钮逻辑未变。本次仅更新资产、图标元数据与已有交付 JAR；未编译或测试。

## 黑底修复

旧候选文件为 RGB 图片，之前直接在该 Bitmap 上写 alpha 后保存，通道被原位转换丢弃，
透明像素因此变黑。现在先建立 `Format32bppArgb` 画布，再保留所选原图的图案并清除外侧
棋盘格，最终按真正 RGBA PNG 保存。主菜单与 Mod Menu 仍共用这同一资产，未重画图案。
