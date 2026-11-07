# 0.5 双主题 UI 说明

0.5分别调整Miuix和Material 3。主题选择与系统深浅色继续保存/跟随，旧偏好键不变。Miuix首页从0.4的旧Material外观适配中迁出，纳入同一套Miuix组件与排版；Material 3使用自身的颜色和控件语义。

## 组件对应

| 项目 | Miuix | Material 3 |
|---|---|---|
| 页面标题 | Miuix title1，32sp、半粗体 | headlineSmall，28sp、常规字重 |
| 卡片标题 | Miuix title3，20sp | titleMedium，16sp、中等字重 |
| 正文/辅助文字 | main 17sp、body1 16sp、body2 14sp | 原生Typography层级，bodyLarge 16sp、bodyMedium 14sp、bodySmall 12sp |
| 页面容器 | 官方Miuix深浅色背景，分组白/深色卡片 | 明确的surfaceContainer、primaryContainer色阶 |
| 卡片 | 原生Miuix Card，28dp G2连续圆角、无额外描边 | 原生Card，中等圆角、1dp层级；首页主卡使用28dp圆角和primaryContainer |
| 主按钮 | 原生Miuix Button，柔和方圆角与button文字角色 | 原生Material Button、胶囊形状与labelLarge |
| 后端/主题选择 | 等宽Miuix选择按钮 | 原生SingleChoiceSegmentedButtonRow和勾选标记 |
| 输入框 | 原生Miuix填充输入框 | 原生OutlinedTextField、4dp角与明确焦点描边 |
| 开关 | Miuix Switch | Material Switch |
| 底部导航 | 官方Miuix NavigationBar | Material NavigationBar、原生选中指示器和3dp色调层级 |
| 对话框 | Miuix SuperDialog与唯一根层PopupHost | 原生AlertDialog |

字体映射清除固定前景色，交由控件提供onPrimary/onSurface，避免按钮和深色模式中出现错误的黑白文字。Miuix自身使用官方主题；少量通用辅助容器通过颜色映射保持一致。

## 对齐、缩放与交互

- 内容居中，最大宽680dp；页面水平边距和卡片内容边距均20dp。
- Miuix标题区垂直留白24dp，Material 3为16dp；卡片间距16dp。
- 卡片内按主题使用14/16dp间距。状态行共用80dp标签列；窄屏或大字体改为上下排列，不挤压值。
- 保持系统字体缩放。普通按钮至少48dp触控高度，文字可自然增高；Miuix对话框操作区使用可换行布局。
- 连接页两个长操作保留13sp文字和实际文字测量，放不下时上下排列，不使用省略号或截断来掩盖问题。
- IP输入的Next仍聚焦端口，Done收起键盘。输入时隐藏底部导航。
- 二维码仍为黑白图形，独立于主题；允许截屏、默认隐藏、风险确认、后台清理等行为保持不变。

## 源码位置

- android/app/src/main/java/dev/agentbridge/BridgeTheme.kt：颜色、字体角色和双主题控件适配。
- android/app/src/main/java/dev/agentbridge/MainActivity.kt：页面布局、响应式行、卡片、导航和对话框。
- android/app/src/main/java/dev/agentbridge/ConnectionQr.kt：精简的AgentConnectionPrompt。
- docs/AGENT_START.md：agent初始化的准备条件、安装与验证步骤、精简提示词。

本次改动覆盖原生Android Compose界面。辅助输入法是独立的Android View输入面板，继续跟随系统深浅色；不将其描述为另一套Compose界面。

## 验证范围

两套主题分支均经过Android编译、lint及既有协议回归测试。环境adb没有连接设备，SDK没有安装模拟器，/dev/kvm不存在；没有把源码检查当作真机视觉验收。需在真实手机确认：深浅色、大字体、横竖屏、键盘、长文案、主题切换、Miuix弹窗和二维码页的排版。
