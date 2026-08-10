# highSpeedRail 人工验收清单

构建通过只证明代码、Mappings、Mixin 描述和 remap 能够完成。以下项目需要在 Minecraft 1.21.11 Dedicated Fabric Server 上用未安装本模组的原版客户端验证。

默认配置只有 `enable=true`、`maxSpeed=1.2`、`activeBlocks=16`。测试时可用 `/highspeedrail get` 确认实时值。

1. 普通铁轨与少量动力铁轨：全程保持原版行为，不进入 `ACCELERATING`。
2. 低于原版上限进入 100 格动力铁轨：先由原版加速，到原版上限附近后才进入高速状态。
3. 当前格加前方连续已通电动力铁轨不超过 16 格：不能激活。
4. 当前格加前方连续已通电动力铁轨超过 16 格：进入 `ACCELERATING`。
5. 采样每 tick 速度：默认三项下高速阶段每 tick 增加约 `0.04`，该值由三项配置自动计算。
6. 达到 `1.2` 后：进入 `HIGH_SPEED` 且不继续增长。
7. 前方剩余动力铁轨少于 16 格：立即进入 `DECELERATING`，不再等待离开动力段。
8. 最后 16 格制动区间与加速区间使用同一个 `activeBlocks`，不存在独立制动参数。
9. 减速采样：默认三项下每 tick 减少约 `0.04`，动力段结束处回到原版上限。
10. 东西/南北直轨：路径扫描和速度状态正常。
11. 四种 90 度弯轨：扫描跟随弯向，不沿旧坐标直线继续。
12. 四种上坡方向：不飞离轨道，Y 运动仍由原版决定。
13. 四种下坡方向：路径和减速状态正确。
14. 高速段中途断电：立即进入 `DECELERATING`，不永久保持高速。
15. 高速段中途拆轨：不崩溃、不出现 NaN、不永久保持高速状态。
16. 高速矿车撞另一辆矿车或实体：服务器不崩溃，碰撞后不会被模组强制穿透。
17. 未安装模组的原版客户端乘坐、观察、下车：无 unknown payload、无缺失 mod 拒绝。
18. 路线接近未加载 chunk：扫描不生成新区块；矿车按状态机保守退出高速。
19. 多辆矿车并行：对比开启/关闭模组的 MSPT/TPS；静止普通矿车不触发长扫描。
20. 重启服务器：旧矿车从 `NORMAL` 重新开始，不依赖持久化运行时状态。

补充兼容验证：

- 关闭 Experimental Minecart Improvements，完成以上测试。
- 开启 Experimental Minecart Improvements，至少重复 4、5、8、9、11、12、16、17、18。
- 将普通铁轨、Detector Rail、Activator Rail、未供电 Powered Rail 分别接入路径，确认它们都会终止连续高速动力铁轨计数。
- 在 `DECELERATING` 时补长并重新供电，使当前格加前方连续动力铁轨重新超过 16 格，确认返回 `ACCELERATING`。
- 分别实时修改 `enable`、`maxSpeed`、`activeBlocks`，确认立即生效并写入 `config/highspeedrail.json`；确认不存在其他 `set` 子参数。
