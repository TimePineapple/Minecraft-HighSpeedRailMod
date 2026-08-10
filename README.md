# highSpeedRail

highSpeedRail is a server-side Fabric mod.

Clients DO NOT need to install this mod.

它为普通可乘坐矿车提供“长距离、连续、已通电动力铁轨”高速模式，同时继续使用 Minecraft 原版矿车移动、碰撞、乘客和实体同步逻辑。

## 兼容性

- Minecraft Java Edition：`1.21.11`
- Java：21
- Fabric Loader：`0.19.3`（工程与模组元数据均精确固定）
- Fabric API：`0.141.6+1.21.11`
- Yarn mappings：`1.21.11+build.5`
- 环境：纯服务端（`fabric.mod.json` 中为 `"environment": "server"`）

Loader 和 Fabric API 使用不同的版本编号。本项目要求 Fabric Loader `0.19.3`，Fabric API 仍使用兼容 Minecraft 1.21.11 的版本。

## 安装

1. 建立 Minecraft 1.21.11 Fabric Dedicated Server，并使用 Fabric Loader 0.19.3。
2. 将兼容 1.21.11 的 Fabric API JAR 放入服务器 `mods/`。
3. 将 `highspeedrail-1.1.0.jar` 放入服务器 `mods/`。
4. 启动服务器。客户端不安装 highSpeedRail，也不需要 Fabric API。

模组不注册方块、物品、实体或自定义网络 payload。矿车的位置和速度仅通过原版实体 tracking 数据同步给客户端。

## 配置

首次启动会生成：

```text
config/highspeedrail.json
```

默认配置：

```json
{
  "enable": true,
  "maxSpeed": 1.2,
  "activeBlocks": 16
}
```

| 参数 | 含义 | 单位 |
| --- | --- | --- |
| `enable` | 是否启用高速逻辑 | 布尔值 |
| `maxSpeed` | 高速模式最高速度 | blocks/tick |
| `activeBlocks` | 从原版上限加到自定义上限、或反向减速时的基准距离 | 轨道格 |

20 TPS 时：`1 block/tick = 20 blocks/second`。默认 `1.2 blocks/tick` 相当于理论水平速度 `24 blocks/second`。

`maxSpeed` 必须为有限数且严格大于 `0.4`，`activeBlocks` 必须至少为 `1`。非法指令会返回 `illegal value` 且不修改实时值或 JSON；非法 `reload` 会保留最后一组合法配置。运行时若实验 controller 的原版速度上限高于 `maxSpeed`，模组不会降低原版上限。

加速度、减速度和扫描距离均不再是配置项。模组按 `a=(maxSpeed²-v2²)/(2×activeBlocks)` 实时计算加减速共用的基准加速度，再使用 `smootherstep` 按实际经过的连续轨道格距离缓入缓出。默认陆地 `v2=0.4` 时基准 `a=0.04`；从 `0.4` 到 `1.2` 需要 16 格，从静止理论上需要 18 格，制动从 `1.2` 到 `0.4` 精确规划为 16 格。

管理员可实时查看或修改配置。`set` 成功后立即影响已加载矿车，并写回 JSON，重启后仍保留：

```mcfunction
/highspeedrail get
/highspeedrail set enable true
/highspeedrail set maxSpeed 1.5
/highspeedrail set activeBlocks 24
/highspeedrail reload
```

`reload` 用于重新读取手动编辑的 JSON。所有命令要求原版权限等级 2（`GAMEMASTERS`）。

## 工作原理

### 状态机

```text
NORMAL -> ACCELERATING -> HIGH_SPEED -> DECELERATING -> BRAKE_HOLD -> NORMAL
```

- `NORMAL`：完全使用原版速度上限。原版先为静止矿车产生非零方向，模组随后可从实际速度接管。
- `ACCELERATING`：剩余连续动力铁轨距离超过 `activeBlocks` 时启动，按实际起速和轨道格进度计算平滑速度曲线。
- `HIGH_SPEED`：允许并保持 `maxSpeed`。
- `DECELERATING`：按当前速度实时计算所需制动距离；正常从 `maxSpeed` 制动时等于 `activeBlocks`，超速或参数变化时可以更早开始，但基准 `a` 不变。
- `BRAKE_HOLD`：提前到达原版上限后保持该速度直到动力段末端；只有线路延长到足以再次完成加速和制动时才恢复加速。

每辆矿车的状态存储在 Mixin 注入的运行时字段中，不写入世界存档。字段与实体生命周期一致，实体卸载或移除后可正常回收，不使用全局强引用 Map。

### 轨道路径扫描

扫描读取当前 `RailShape` 的两个真实连接端点，以当前水平 velocity 的点积选择前进端。进入下一格后，用“上一格连接端”排除来路，再选择另一端，因此会在每格重新跟随：

- `NORTH_SOUTH`、`EAST_WEST`
- 四种 ascending rail
- `SOUTH_EAST`、`SOUTH_WEST`、`NORTH_EAST`、`NORTH_WEST`

扫描只把精确的 `Blocks.POWERED_RAIL` 且 `POWERED=true` 计入。1.21.11 的 Activator Rail 也使用 `PoweredRailBlock` 类，因此额外检查具体 block，确保 Activator/Detector/普通/未供电铁轨都不计数。

扫描复用 `BlockPos.Mutable`，不建立路径 List；每格轨道映射为连续的 `0..1` 进度，弯轨和坡轨也各计一格。单 tick 跨越多格或跨过制动边界时会按真实路径进度拆分计算。目标 chunk 未加载时调用 `isChunkLoaded` 后保守停止，不生成或强制加载区块。

### 原版速度上限与 Mixin

Minecraft 1.21.11 中，`AbstractMinecartEntity#getMaxSpeed(ServerWorld)` 委托给当前 `MinecartController#getMaxSpeed`：

- `DefaultMinecartController` 返回水中 `0.2`、其他情况 `0.4` blocks/tick。
- `ExperimentalMinecartController` 从 `MAX_MINECART_SPEED` 游戏规则计算上限。

Mixin 在 `AbstractMinecartEntity#getMaxSpeed` 返回处只为处于高速状态的普通 `MinecartEntity` 替换上限。`NORMAL` 和所有其他矿车子类保持原值。默认和实验 controller 都经过这一公共调用链，因此不需要 overwrite 原版移动方法。

Mixin 还在 `AbstractMinecartEntity#tick` 的 HEAD/TAIL 分别进行状态判定和速度调整。TAIL 使用原版当 tick 已经解析出的前进方向，并沿当前轨道的三维切线设置“轨道格/tick”速度；坡轨会包含对应 Y 分量。明显碰撞或方向反转会重置规划并保留原版碰撞结果，乘客和实体同步仍由原版执行。

## Experimental Minecart Improvements

Minecraft 1.21.11 的实验 controller 通过同一个 `AbstractMinecartEntity#getMaxSpeed` 调用链限速，本模组会读取它的动态游戏规则上限并使用同一状态机，因此代码层面同时支持默认和实验 controller。

实验 controller 在单 tick 内可能跨越多格，并使用不同的插值步骤。应按 [TESTING.md](TESTING.md) 在实际 Dedicated Server 上分别验证默认和实验 feature flag 世界；本仓库的 Gradle 构建不等同于游戏内物理验收。

## 已知限制

- 极高速度可能超过原版客户端插值的视觉舒适范围，出现抖动；客户端仍不需要安装模组。
- 高速弯道和碰撞保持原版处理，不加入传送或自定义防脱轨物理修正。
- 极少见的垂直堆叠轨道布局中，连接端会按原版常见的同层、上一层、下一层顺序寻找轨道，需进行现场验证。
- 当前只作用于普通可乘坐 `MinecartEntity`，不影响箱子、漏斗、TNT、命令方块、熔炉或刷怪笼矿车。

## 构建

在 Java 21 环境运行：

```powershell
./gradlew.bat build
```

生成文件：

```text
build/libs/highspeedrail-1.1.0.jar
```

详细人工验收步骤见 [TESTING.md](TESTING.md)。
