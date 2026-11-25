# Agent API

启动 `logisim-agent.cmd serve` 后，每行发送一个 UTF-8 JSON-RPC 2.0 请求。每个含 `id` 的请求收到一行响应。支持通知与批量请求。上游诊断打印到 stderr，stdout 只承载协议。调用参数使用对象，可使用 `project_id` 或 `project`。省略 `circuit` 时选择顶层电路。

```json
{"jsonrpc":"2.0","id":1,"method":"logisim.create_project","params":{"path":"outputs/my-circuit.circ","top_circuit":"Main"}}
```

将响应的 `project_id` 传递给后续调用。`get_capabilities` 返回实际引擎版本、网格和最大位宽。`open_project` 另返回文件头 `source_version` 和格式版本。声明兼容性时应同时记录两个来源。

## 项目与结构

| 方法 | 关键参数与结果 |
| --- | --- |
| `logisim.open_project` | `path`, `mode` 为 `read_write`（默认）或 `read_only`。返回项目、版本、库和电路。 |
| `logisim.create_project` | `path`, `top_circuit`。先在内存创建。 |
| `logisim.close_project` | `project_id`, `discard=false`。有待保存内容时返回冲突。 |
| `logisim.list_circuits` / `logisim.create_circuit` | 查询电路名，或使用 `name` 创建子电路。 |
| `logisim.get_circuit` | 返回部件 ID、工厂、属性、引脚坐标和位宽、线段、连通网、电路边界和连接点。 |
| `logisim.list_component_factories` | 可用 `category` 或 `project_id` 筛选。后一参数也列出项目的子电路工厂。 |
| `logisim.describe_component` | 提供 `factory` 和可选 `attrs`，获得属性及以原点为锚的引脚几何。工厂重名时用 `类别:工厂名`。 |
| `logisim.save` | 原子写入 `.circ`，返回 revision 和格式诊断。保存前检查目标文件是否在打开后发生变化。 |
| `logisim.validate` | `reopen=true` 重新解析当前快照并比较结构图，返回宽度、潜在多驱动、浮空和未知组件属性诊断。`original_program_reopen` 由独立原版验收提供。 |

## 编辑

| 方法 | 关键参数与结果 |
| --- | --- |
| `logisim.add_component` | `factory`, `x`, `y`, `attrs`, 可选 `facing` 和 `label`。返回稳定 ID、原生属性和引脚。 |
| `logisim.update_component` | `id`, `attrs`, 可选 `label`、`facing`、`position={x,y}`。 |
| `logisim.set_attribute` | `id`, `attribute`, `value`。 |
| `logisim.move_component` | `id`, `x`, `y`, `reroute="preserve"`。能保留连接时追加正交线段。如会短接或无法安全保留，返回冲突。`reroute="detach"` 保留原有线段。 |
| `logisim.remove_component` | `id`, `cascade=false`。为 `true` 时同时移除连接到组件引脚的线段。 |
| `logisim.connect` | `points=[{x,y},...]`, `width=1`, 可选 `label`。起终点默认要求有引脚或已有网线。显式设置 `allow_dangling=true` 可创建待接线。 |
| `logisim.disconnect` | `wire_or_net_id`。按查询到的线段 ID 或网 ID 删除。 |
| `logisim.undo` / `logisim.redo` | 撤销或恢复结构编辑，每次操作产生新 revision，保留最近 32 次撤销记录。 |

坐标是 Logisim 的电路坐标。网格步长来自上游的对齐方法。普通组件和连线使用网格坐标，原生支持自由放置的文字可使用整数坐标。导线是轴对齐折线，交叉处是否连接由原生拓扑决定。需形成 T 型接头时，将折线端点明确放在现有网线上。输入、输出和控件位置应以返回的引脚为准。

原版从引脚推导网线位宽。Agent 把 `connect` 声明的位宽保留为扩展属性，在浮空网线接入组件时检查两者是否一致。

每次编辑在候选快照中执行，原生解析和语义检查成功后提交。`if_revision` 可用于乐观并发检查。失败的编辑不增加 revision 或改动文件。`save` 才将内存快照写到磁盘。现有文件的 XML 版本头、库引用、子电路、布局和未编辑的未知属性由 XML 保真层保留。原生模型负责组件解析、布局、布线和行为。

## 仿真和图片

| 方法 | 关键参数与结果 |
| --- | --- |
| `logisim.set_input` | `pin_id`, `value`。值是无符号整数或等宽位串。三态引脚可接受 `x`。 |
| `logisim.simulate` | `settle=true` 时传播至稳定，返回输出/探针值、未知态、冲突和振荡。`settle=false` 查看当前状态。 |
| `logisim.clock_step` | `clock_id`, `edges=1`。推进原生全局 tick 并报告所选时钟的上升/下降沿。只有一个时钟时可省略 ID。 |
| `logisim.reset_simulation` | 重置指定电路的独立运行状态。结构编辑和撤销也会重置运行状态。 |
| `logisim.run_vectors` | `vectors`, `independent=false`。每行支持 `label`, `inputs`, `expect`, `clock_id`, `edges`, `reset`。同一报告可验证连续时序。 |
| `logisim.export_circuit_image` | `path`, `format="png"`, `fit="content"`, `scale=2`, `padding=24`, `dpi=300`, `background="white"`, `show_grid=false`, `include_state=true`。 |
| `logisim.export_state_sheet` | 上述 PNG 选项加 `states`, `layout="grid"`, `labels=true`, `independent=true`。`path` 为新目录或 `.zip`。 |

向量的 `inputs` 与 `expect` 使用 ID 或唯一标签。端口宽度取自原生引脚或连网。连续向量中省略的输入保持上一行值。状态清单包含每行实际有效输入、时钟边沿、输出、期望与实测。着色、裁切与抗锯齿由同一原生绘制路径生成。静态模式将网线绘为黑色，原生元件仍使用其打印视图配色。背景也可设 `transparent` 或 `#RRGGBB`。图像尺寸最多 8192 像素每边、1600 万像素每图，状态集最多 8000 万像素。

单张和状态图默认关闭网点。使用 `show_grid=true` 或 CLI 的 `--grid` 开启。导出复用 Logisim `GridPainter` 的网点样式，按相同的电路坐标、缩放及裁切偏移对齐。成图依次合成背景、网点和电路。网点不参与内容裁切，可延伸到留白区。

## 错误

错误含 `code`、`message`、`details`、`recoverable`。JSON-RPC 客户端也可从 `error.data` 读取 `details` 与 `recoverable`。

| 代码 | 含义 |
| --- | --- |
| `-32700`, `-32600`, `-32601`, `-32602`, `-32603` | JSON 语法、请求包络、未知方法、参数不合法、内部故障。 |
| `-32001`, `-32002`, `-32003` | 对象不存在、冲突、XML/格式/库错误。 |
| `-32004`, `-32005`, `-32006` | 文件 I/O、只读项目、资源上限。 |
