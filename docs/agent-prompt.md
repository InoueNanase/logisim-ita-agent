# Agent 提示词示例

在工作区启动 `logisim-agent.cmd serve` 并保持该进程。请构建一个带 `A`、`B`、`S`、`C` 引脚的半加器，保存为 `outputs/my-half-adder.circ`。

先调用 `logisim.get_capabilities` 获取网格，再用 `logisim.describe_component` 查找 `Pin`、`XOR Gate`、`AND Gate` 的原生属性与引脚。添加元件后使用返回的引脚坐标做正交布线，并给每根线指定 `width=1`。交叉处是否相连以 `logisim.get_circuit` 的网和连接点为准。

用 `logisim.run_vectors` 独立测试四组布尔输入。用 `logisim.validate` 确认原生重开和宽度一致。`save` 完成后，用 `logisim.export_state_sheet` 输出四张独立 PNG，并用 `scale=3`、白色背景和 `padding=24`。返回文件路径、向量结果、状态清单与已执行的兼容检查。
