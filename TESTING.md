# highSpeedRail 人工验收清单

构建通过只证明代码、Mappings、Mixin 描述和 remap 能够完成。以下项目需要在 Minecraft 1.21.11 Dedicated Fabric Server 上用未安装本模组的原版客户端验证。

默认配置只有 `enable=true`、`maxSpeed=1.2`、`activeBlocks=16`。测试时可用 `/highspeedrail get` 确认实时值。

1. 普通铁轨与少量动力铁轨：全程保持原版行为，不进入 `ACCELERATING`。
2. 静止矿车先由原版动力铁轨产生非零运动，随后从实际速度进入模组加速曲线。
3. 当前格加前方连续已通电动力铁轨不超过 16 格：不能激活。
4. 当前格加前方连续已通电动力铁轨超过 16 格：进入 `ACCELERATING`。
5. 使用 `maxSpeed=4`、`activeBlocks=64` 采样速度：加速度从原版 `0.06` 开始，按轨道距离单调增加到 `0.1875`，并在 64 格达到 `4`。
6. 达到 `1.2` 后：进入 `HIGH_SPEED` 且不继续增长。
7. 使用 `maxSpeed=4`、`activeBlocks=64` 从 `4` 开始制动：制动力从 `0.1875` 单调降低到 `0.06`，在 64 格降至 `0.4`。
8. 使用默认 `maxSpeed=1.2`、`activeBlocks=16`：要求的平均加速度 `0.04` 低于原版下限，实际恒定使用 `0.06`；从 `0.4` 约 10.667 格到速，理论静止起步距离为 12 格。
9. 异常超速时按实际有效平均加速度动态提前制动；提前到达 `0.4` 后进入 `BRAKE_HOLD`。
10. 东西/南北直轨：路径扫描和速度状态正常。
11. 四种 90 度弯轨：扫描跟随弯向，不沿旧坐标直线继续。
12. 四种上坡方向：每格坡轨计一个进度单位，速度沿三维轨道切线并包含正确 Y 方向。
13. 四种下坡方向：路径和减速状态正确。
14. 高速段中途断电：立即进入 `DECELERATING`，不永久保持高速。
15. 高速段中途拆轨：不崩溃、不出现 NaN、不永久保持高速状态。
16. 高速矿车撞另一辆矿车或实体：服务器不崩溃，碰撞后不会被模组强制穿透。
17. 未安装模组的原版客户端乘坐、观察、下车：无 unknown payload、无缺失 mod 拒绝。
18. 路线接近未加载 chunk：扫描不生成新区块；已确认的动力铁轨距离随实际行驶量连续递减，不因区块边界短暂波动反复切换加速与制动；真实断轨或未供电终点仍立即触发制动。
19. 多辆矿车并行：对比开启/关闭模组的 MSPT/TPS；静止普通矿车不触发长扫描。
20. 重启服务器：旧矿车从 `NORMAL` 重新开始，不依赖持久化运行时状态。

补充兼容验证：

- 关闭 Experimental Minecart Improvements，完成以上测试。
- 开启 Experimental Minecart Improvements，至少重复 4、5、8、9、11、12、16、17、18。
- 将普通铁轨、Detector Rail、Activator Rail、未供电 Powered Rail 分别接入路径，确认它们都会终止连续高速动力铁轨计数。
- 在 `DECELERATING/BRAKE_HOLD` 时补长并重新供电；只有剩余距离足够再次完成加速和完整制动时才返回 `ACCELERATING`。
- 分别实时修改 `enable`、`maxSpeed`、`activeBlocks`，确认立即生效并写入 `config/highspeedrail.json`；确认不存在其他 `set` 子参数。
- 尝试设置 `maxSpeed=0.4`、非有限速度、`activeBlocks<1` 及非法 JSON，确认反馈包含 `illegal value`，且最后合法实时配置不变。
