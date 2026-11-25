# 兼容矩阵

已固定 Logisim-Ita/Logisim commit `ad7c02a59e4dc3296423e16fd2d017b59ee4d7dc`。本机原版 `-version` 输出 `2.16.2.2.exe`，源码构建运行时输出 `2.16.2.2.jar`。原版 `-tty stats` 给出解析后组件统计，`-tty table` 在带 `halt` 的输入变体中提供独立原版仿真结果。

| 文件 | 文件头 | 原版 Logisim-ITA 2.16.2.2.exe | 行为或结构验收 |
| --- | --- | --- | --- |
| 生成的半加器 | `2.16.2.2.jar` | 已重开 | 原版四组输入变体全部通过。 |
| 生成的四位寄存器 | `2.16.2.2.jar` | 已重开 | 原生寄存器向量通过，覆盖上升沿采样、下降沿保持和连续时序。 |
| 生成的四位 2 选 1 MUX | `2.16.2.2.jar` | 已重开 | 两路选择向量经过总线、同名隧道和分线器。 |
| 自动生成的 RAM 和 ROM 验收文件 | `2.16.2.2.jar` | 已重开 | RAM 清除、时钟写入与读回，ROM 四地址查表全部通过。 |

测试通过 `-tty stats` 调用本机原版，不需要 GUI 坐标或 OCR。M4 另在同一个原版中调用“导出图像”动作比较门和线的像素。该样例上原版导出裁掉了最外的引脚标签边缘，Agent 输出保留了完整文字和留白。验收日志由测试脚本写入 `work/reports/`。

## 排障

- 找不到 Java 时，在 PowerShell 运行 `java -version` 和 `javac -version`，或将 `JAVA_HOME` 指向 JDK。Logisim-ITA 原版的 `-version` 打印版本后退出码为 `-1`，自动验收核对实际版本文本。
- 受限终端执行上游 Maven 构建时，Launch4j 的 `windres.exe` 可能报 Windows DLL 加载错误。用正常权限的 PowerShell 运行构建脚本。解压 Windows ZIP 后启动 Agent 只需要 JDK 和 Python。
- 导线端点检查失败时，从 `get_circuit` 或 `describe_component` 读取引脚，显式传入正交折点。支路接头需要把一个端点放在现有网线上。
- `-32003` 可能来自损坏的 XML 或缺失的库。外部 `.circ` 库应放在工作区内，保持文件中的相对路径。外部 Java 插件需要桌面版环境，Host 遇到该引用会报告错误并保留输入文件。
- 旧版宽四输入门是上游已知布局差异。遇到该类警告时应检查受影响引脚并用对应课堂样例回归。 [上游兼容说明](https://github.com/Logisim-Ita/Logisim/tree/ad7c02a59e4dc3296423e16fd2d017b59ee4d7dc#retro-compatibility)
- 原版 `-tty table` 是连续模拟。检查重开时用 `-tty stats`。行为自动验收可以用 `run_vectors`，或为原版 `-tty table` 提供会拉高的 `halt` 输出。
