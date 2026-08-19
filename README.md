# highSpeedRail 1.2.8

highSpeedRail 是 Minecraft 1.21.11 的服务端 Fabric 模组。客户端无需安装，也不注册自定义方块、实体或网络 payload。

## 兼容性

- Minecraft Java Edition 1.21.11
- Java 21
- Fabric Loader 0.19.3
- Fabric API 0.141.6+1.21.11
- Yarn mappings 1.21.11+build.5

将 `build/libs/highspeedrail-1.2.8.jar` 和 Fabric API 放入服务端 `mods/`；不要与旧版或诊断版同时安装。构建不会主动删除 1.2.7、1.2.7-diagnostic.1 或更早产物。

## 限时诊断

管理员玩家乘坐普通矿车后执行：

```mcfunction
/highspeedrail debug start 15
/highspeedrail debug status
/highspeedrail debug stop
```

- `start` 接受 1 到 60 秒，全服同时只允许一个会话。
- 每次生成独立的 `logs/highspeedrail-debug-时间.jsonl`。
- 到时、下车、换维度、矿车消失或服务器停止时自动结束并写入汇总。
- actionbar 每秒显示服务端模式、沿轨速度、水平速度和移动结果。
- 每 tick 记录 HEAD/TAIL 位置与速度、轨道、状态机、扫描和移动结果；只在异常时追加子步详情。
- 不记录玩家名、聊天、IP或客户端凭据。诊断关闭时不创建文件或序列化 tick 数据。

## 配置

首次启动生成 `config/highspeedrail.json`：

```json
{
  "enable": true,
  "maxSpeed": 1.2,
  "activeBlocks": 16,
  "accelerationSeconds": 5
}
```

| 参数 | 含义 |
| --- | --- |
| `enable` | 是否启用模组逻辑 |
| `maxSpeed` | 沿轨道中心线的真实移动速度，单位 blocks/tick |
| `activeBlocks` | 模组制动距离 `N`，按轨道方块计数，最小值 8 |
| `accelerationSeconds` | 从沿轨速度 0 加速到 `maxSpeed` 所需秒数，必须是至少为 1 的 JSON 整数 |

有效激活长度是 `activeBlocks+8`。当前脚下动力铁轨不计数；只有确认前方至少 `N+8` 格完整、连续、已供电动力铁轨时才进入激活候选。候选 tick 完整运行服务器实际的原版控制器，下一 tick 再次满足条件后才接管。扫描遇到未加载区块立即停止，不读取或加载新区块，也不会把未知边界当作终点。

接管使用连续两个完整 `NORMAL` tick 的实际轨道投影位移较小值，而不是实体内部可能被动力轨累积到虚高的速度向量；随后按当前轨形换算沿轨起速，并在首个接管 tick 只增加一次 `aAccel`。若上一 tick 的方向不连续，则额外运行原版 tick 直到取得两个有效样本；若两次实际位移都持续高速，则保留真实入轨速度。

命令：

```mcfunction
/highspeedrail get
/highspeedrail set enable true
/highspeedrail set maxSpeed 4
/highspeedrail set activeBlocks 128
/highspeedrail set accelerationSeconds 30
/highspeedrail reload
```

旧三键配置会补入 `accelerationSeconds=5`，旧 `activeBlocks=1..7` 会提升为 8。非法配置保留最后一组合法运行值。

## 沿轨速度

状态机保存的 `speed`、阶段目标和移动预算全部是沿轨道中心线的真实速度：

- 平直轨每格几何长度为 1。
- 原版 45° 坡轨每格几何长度为 `√2`。
- 平面弯轨继续使用当前端点弦线几何，不引入圆弧或 Bézier。
- `maxSpeed=4` 时，平轨水平移动 4 格；坡轨水平和垂直各移动 `4/√2≈2.828` 格，沿坡总距离为 4。
- 从原版进入接管时，平轨沿轨起速等于水平速度，坡轨沿轨起速等于水平速度乘 `√2`，因此实体水平分量连续。
- 最终实体速度向量的 Y 分量保持为 0，坡轨高度由轨道端点公式吸附。
- 坡轨水平碰撞在该格最高轨面执行，随后吸附回真实坡面高度，避免把坡轨支撑方块误判为碰撞。

加速度恒定为：

```text
aAccel = maxSpeed / (20 × accelerationSeconds)
```

上坡和下坡均不再应用重力修正：上坡不减速，下坡不加速。载人和空车均按 `1.0×` 沿轨预算推进；实验控制器保留率只进行数学补偿，不改变阶段的最终净速度。

## 原版上限与制动

服务端首次捕获世界控制器时缓存原版陆地/水中水平上限。默认控制器通常为 `0.4/0.2`；实验控制器读取开服时规则。运行中切换实验规则不会刷新缓存，需要重启服务器。

若缓存平轨水平目标为 `v`，坡轨交回目标沿轨速度为 `v×√2`，从而保持交回前后的水平速度连续。配置派生四套常量：

```text
aBrakeLandFlat   = (maxSpeed² - vLand²) / (2 × activeBlocks)
aBrakeWaterFlat  = (maxSpeed² - vWater²) / (2 × activeBlocks)
aBrakeLandSlope  = (maxSpeed² - (vLand×√2)²) / (2 × activeBlocks)
aBrakeWaterSlope = (maxSpeed² - (vWater×√2)²) / (2 × activeBlocks)
```

目标高于或等于自定义上限时，对应制动常量为 0，不主动压低原版能力。末端制动按倒数第 8 格入口的轨形选择平轨或坡轨缓存；提前达到目标后保持，到入口只校正浮点误差并切回 `NORMAL`，最后 8 格交给原版。

`/highspeedrail get` 显示实际加载版本、四个配置值、有效激活长度、启动控制器、平轨/坡轨缓存目标、`aAccel` 和四项 `aBrake`，并显示 `activationProfile=two-normal-tick-actual-displacement-min`、速度单位与无坡度重力修正。

## 完全接管移动

```text
NORMAL -> ACCELERATING -> HIGH_SPEED -> DECELERATING -> BRAKE_HOLD -> NORMAL
```

- `NORMAL` 完整运行服务器实际选择的原版控制器。
- 其余四种状态在控制器 `tick` 入口取消原版移动，屏蔽原版重力、动力轨加速、摩擦和制动。
- 流式移动器逐格解析轨形并按真实几何长度扣除预算；每个水平碰撞子步最多 0.75 格，每 tick 最多 64 段。
- 未加载边界会停在最后已加载位置，并冻结速度、方向和阶段；不会回退原版。
- 碰撞时降到缓存原版水平上限并退出接管，至少完整运行一个 `NORMAL` tick 后才能重新激活。
- 乘客及同一载具链实体不参与矿车的实体碰撞中断判定。

## 构建

在 Java 21 环境运行：

```powershell
./gradlew.bat speedProfileTest configTest test build
```

产物为 `build/libs/highspeedrail-1.2.8.jar`。Gradle 可验证代码、映射、Mixin 描述和重映射；Dedicated Server 验收步骤见 [TESTING.md](TESTING.md)。
