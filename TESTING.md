# highSpeedRail 人工验收清单

构建通过只证明代码、Mappings、Mixin 描述和 remap 能够完成。以下项目需要在 Minecraft 1.21.11 Dedicated Fabric Server 中，用未安装本模组的原版客户端验证。

默认配置为 `enable=true`、`maxSpeed=1.2`、`activeBlocks=16`、`accelerationSeconds=5`。测试时可用 `/highspeedrail get` 确认实时值和 `configuredAcceleration=0.012`。

1. 普通铁轨与少量动力铁轨：全程保持原版行为，不进入 `ACCELERATING`。
2. 静止矿车先由原版动力铁轨产生非零方向，模组随后从实际速度接管；模组不主动猜测静止矿车方向。
3. 当前格之外只有 15 个完整、连续、已通电动力轨：默认 `activeBlocks=16` 时不能激活。
4. 当前格之外恰好有 16 个完整、连续、已通电动力轨：允许进入 `ACCELERATING`；当前格本身不计数。
5. 默认配置采样速度：每 tick 增加约 `0.012 blocks/tick`，从 0 理论上 100 tick 到 1.2，从 0.4 接管约 66.667 tick 到速；不得出现旧版 `0.06` 下限。
6. 达到 `maxSpeed` 后进入 `HIGH_SPEED`，速度不继续增长。
7. 加速中把 `accelerationSeconds` 从 5 改为 10：当前阶段仍保持原 `a`；离开并重新进入 `ACCELERATING` 后改用新 `a=0.006`。
8. 加速或高速中降低 `maxSpeed`：使用新配置对应的固定 `a` 平滑降到新上限，不应被原版上限在单 tick 内直接截断。
9. 在已激活矿车前方构造确认终点，使完整前方动力轨少于 `activeBlocks`：进入 `DECELERATING`。
10. 采样末端制动：制动过程中 `(v前²-v后²)/(2×行驶距离)` 保持常量，并在进入最后一格已供电动力轨时达到实时原版上限。
11. `BRAKE_HOLD` 覆盖最后一格动力轨：该格内保持原版上限，不被动力轨重新加速；离开连续动力段后返回 `NORMAL`。
12. 制动中延长线路但仍不足 `activeBlocks`：按新最后一格入口重新计算常量 `a`；延长到重新确认至少 `activeBlocks` 格时恢复固定 `a` 加速。
13. 制动中拆短或断电：按更近的新最后一格入口重新计算更大的常量 `a`；若已经进入最后一格，则使用该格剩余路程补救。
14. 当前动力轨突然断电、被拆或已经没有可用制动距离：下一 tick 立即限制到实时原版上限，不崩溃、不出现 NaN。
15. 东西/南北直轨：完整格计数、路径距离和状态切换正确。
16. 四种 90 度弯轨：扫描跟随弯向，最后一格入口位置正确，不沿旧坐标直线继续。
17. 四种上坡与下坡方向：每格坡轨计一个完整格，速度沿三维轨道切线并包含正确 Y 方向。
18. 高速矿车碰撞另一辆矿车或实体：服务器不崩溃，明显碰撞/反向后不会被模组强制穿透。
19. 未安装模组的原版客户端乘坐、观察、下车：无 unknown payload、无缺失 mod 拒绝。
20. 当前格外只有 5 个已确认动力轨、第 6 格位于未加载区块，且 `activeBlocks=64`：不得激活。
21. 已确认满 `activeBlocks` 格、其后才是未加载区块：可以激活；未知边界本身不得触发新制动或被当作真实终点。
22. 路线接近未加载区块：扫描不得生成或强制加载新区块；红石机器未完整加载时不得因缩短门槛而误激活。
23. 多辆矿车并行：对比开启/关闭模组的 MSPT/TPS；静止普通矿车不触发长扫描。
24. 重启服务器：旧矿车从 `NORMAL` 重新开始，不依赖持久化运行时状态。

补充兼容与配置验证：

- 关闭 Experimental Minecart Improvements 完成上述测试；开启后至少重复 4、5、8、10、16、17、18、20、21。
- 在水中和修改 `MAX_MINECART_SPEED` 游戏规则后重复末端制动，确认目标使用矿车实时 controller 原版上限；制动中改变该上限会从当前速度重算 `a`。
- 将普通铁轨、Detector Rail、Activator Rail、未供电 Powered Rail 分别接入路径，确认它们都会终止连续高速动力轨计数。
- 分别实时修改 `enable`、`maxSpeed`、`activeBlocks`、`accelerationSeconds`，确认命令成功后写入 `config/highspeedrail.json`，并符合各自的阶段生效规则。
- 用旧三键 JSON 启动，确认自动补入 `accelerationSeconds=5`；用旧 `activeBlocks=1` 启动，确认自动迁移并写回 `2`。
- 尝试设置 `maxSpeed=0.4`、非有限速度、`activeBlocks<2`、`accelerationSeconds<1`、JSON 小数 `accelerationSeconds=5.5` 及非法 JSON，确认反馈包含 `illegal value` 或读取失败，且最后合法实时配置保持不变。
