# 固定自定义生物召唤图标

项目资源：`src/main/resources/assets/worldsmith/textures/item/creature_summoner.png`。
模型：`src/main/resources/assets/worldsmith/models/item/creature_summoner.json`，layer0绑定`worldsmith:item/creature_summoner`。

由内置ImageGen生成，参考现有world_pack地球与铁砧徽记。提示目标：浅色蛋壳轮廓、中央蓝绿地球与灰色铁砧、原版MC粗像素粒度、清晰大色块、透明背景、无文字、无外部阴影，作为统一的32像素召唤物图标。

原始生成图保存在本轮build/immersion-upgrade/assets目录，图像工具还做过一次背景修订。资源导入时做32x32最近邻取样，并按封闭深色蛋壳外轮廓将外部转为真实alpha；未改动现有world_pack图标或任何世界包贴图。

最终PNG：RGBA，32x32，558个透明像素、466个不透明像素。
SHA-256：`b45b8f4c75a3468d2fb793fea107228193dc57eeb0776526f905d4e67847c587`。

只改变已有通用召唤物的外观；各生物名称与world/species数据绑定、创造模式召唤语义保持现状。
