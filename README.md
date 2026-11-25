# Logisim-ITA AI 操作层

用 JSON-RPC 2.0 over stdio 构建和修改 `.circ`、运行 Logisim-ITA 原生仿真并导出报告 PNG。Java host 在同一个 JVM 中使用 Logisim-ITA 的组件库、布线模型、传播器和绘图。Python 客户端只负责编排。

## Windows 启动

从 GitHub Releases 下载并解压 `logisim-agent-windows.zip`，或者克隆仓库后在仓库根目录运行。需要 Python 3.10+ 和 JDK 11+。本项目在 Windows 11、Python 3.13 和 JDK 21 上验收。可通过 `JAVA_HOME` 指定 JDK。首次启动编译 host，之后复用 `work/host-cache/`。

```powershell
.\logisim-agent.cmd capabilities
.\logisim-agent.cmd serve
```

CLI 以当前目录为工作区。用 `--workspace` 指定其他已存在的目录。运行时优先选用本地 Maven 构建的上游 JAR，其次使用发行包内置的 JAR，最后使用根目录的原版 `Logisim-ITA.exe`。有明确目标时可使用 `--core` 或 `LOGISIM_ITA_CORE` 选择 EXE/JAR。

## 生成和使用样例

```powershell
python examples/generate.py work/demo
.\logisim-agent.cmd render work/demo/half-adder.circ work/report.png --scale 3
.\logisim-agent.cmd render work/demo/half-adder.circ work/half-adder-states.zip --states examples/half-adder-states.json
.\logisim-agent.cmd vectors work/demo/register4.circ examples/register-vectors.json work/register-report.json
```

Windows ZIP 的 `outputs/` 包含半加器、四位寄存器、配有隧道和分线器的多路选择器、报告 PNG、状态图集和测试向量结果。用原版 Logisim-ITA 打开其中的 `.circ`。浏览器打开各状态目录的 `index.html` 可预览，直接复制独立 PNG 到实验报告。状态 ZIP 和目录使用新路径生成。

## 构建和验收

```powershell
powershell -ExecutionPolicy Bypass -File scripts/build-upstream.ps1
python tests/verify.py m0
python tests/verify.py m1
python tests/verify.py m2
python tests/verify.py m3
python tests/verify.py m4
python tests/verify.py m5
```

从仓库根目录运行验收。打包前用 `python examples/generate.py outputs` 在新目录中生成样例，再运行 `python scripts/package.py`。源码构建需要 Git 和 Maven。M4 像素回归需要 Pillow。CLI 和运行时无需第三方 Python 库。每次验收在 `work/reports/` 记录命令、实际结果和日志。

- [Agent API 与事务规则](docs/api.md)
- [兼容矩阵和排障](docs/compatibility.md)
- [Agent 操作提示词示例](docs/agent-prompt.md)
- [JSON Schema](schemas/rpc.schema.json)
- [上游来源及许可](NOTICE.md)
