# Rust Compiler Reader

## Overview | 概览

**English** — Rust Compiler Reader shows the macro expansion, MIR, and rustc-generated assembly for the current Rust item in RustRover. The three views live in one tool window and help you understand how your source code looks to the compiler.

**中文** — Rust Compiler Reader 在 RustRover 中展示当前 Rust 代码项的宏展开、MIR 和 rustc 生成的汇编。三份产物集中在同一个工具窗里，用来回答「这段代码在编译器眼里长什么样」。

- 插件 ID：`io.github.murphy-955.rustreader`
- 支持 IDE：RustRover 2026.2.5+（`sinceBuild = 262.11879.102`）
- 许可证：MIT
- Marketplace：公开、免费
- Issue：<https://github.com/murphy-955/rust-cargo-exapnad-mir-llvm/issues>

## 功能

| 视图 | 内容 | 来源 |
|---|---|---|
| Macro Expansion | 当前项或整个 crate 的宏展开 | `cargo expand`，否则 `RUSTC_BOOTSTRAP=1 cargo rustc --profile=check -- -Zunpretty=expanded` |
| MIR | 当前函数、impl 方法和闭包的 MIR | `cargo rustc -- --emit=mir` |
| Assembly | 当前函数对应的汇编块 | `CARGO_INCREMENTAL=0 cargo rustc -- -C codegen-units=1 --emit=asm` |

- 从光标位置解析当前函数、impl 方法、闭包、结构体/枚举/trait、`macro_rules!` 等项。
- 自动定位 `target/**/deps/<crate>-<hash>.mir` / `.s`，按「运行时间 + crate 名」过滤旧产物。
- MIR 支持 `<impl at file:line:col>` 和 `{closure#N}`；汇编支持 v0/legacy mangled symbol 启发式匹配。
- 编译失败、函数缺失、函数被内联、工具缺失时都有明确提示。
- 后台执行、可取消、默认 120 秒超时；同一项目串行运行，避免 cargo target 锁竞争。
- Build output 面板显示完整 command/stdout/stderr，`path:line:col` 可点击跳转。
- Macro Expansion 使用 Rust 语法高亮，Assembly 使用 ASM 语法高亮；MIR 仍为纯文本。
- Copy command、Open in Editor、Save、行号、折叠、查找。

## 安装

1. 从 JetBrains Marketplace 安装（发布后）。
2. 或本地构建：`./gradlew buildPlugin`，然后在 RustRover 中
   `Settings | Plugins | ⚙ | Install Plugin from Disk…` 选择
   `build/distributions/rust-compiler-reader-0.1.0.zip`。

## 使用

1. 在 Rust 文件中把光标放到函数、impl 方法或项上。
2. 右键 → **Show Compiler Reader**，或 `Shift+Shift` 搜索该 Action。
3. 也可以直接打开右侧的 **Rust Compiler Reader** 工具窗，点击空状态里的
   **Use Current File**（或工具栏的刷新按钮）让它读取当前编辑器中的 Rust 文件。
   只打开工具窗不会自动编译。
4. 工具窗左侧默认打开上次使用的视图：首次为 MIR。
5. 点击 **Refresh** 重新生成；取消任务会结束 cargo 进程树。

插件**不设默认快捷键**：`Ctrl+Alt+Shift+M` 已被 RustRover 的 Expand Macro 占用。可在
`Settings | Keymap` 中为 `Show Compiler Reader` 绑定自己的快捷键。

### 视图选项

- **Macro Expansion**：`Whole crate` 在「当前项」和「整 crate」之间切换；`#[derive]` 展开出的
  `#[automatically_derived] impl` 会自动附加到目标类型后面。
- **MIR**：`Include closures` 控制是否同时显示 `fn x::{closure#N}` 块。
- **Assembly**：`Whole assembly` 显示完整汇编文件。

## 设置

`Settings | Tools | Rust Compiler Reader`，持久化到 `.idea/rust-compiler-reader.xml`：

- Cargo 可执行文件路径、工具链（auto / stable / nightly / custom）
- Profile、target triple、features、额外 rustc 参数
- 宏展开后端（auto / cargo-expand / rustc）
- 是否允许 `RUSTC_BOOTSTRAP=1`（默认允许；stable 下没有它无法 `-Zunpretty=expanded`）
- 是否用 rustfmt 格式化展开结果；rustfmt 路径
- 是否优先使用 `cargo-show-asm`
- 超时时间
- 是否使用隔离 target 目录（`.rust-compiler-reader/target`，默认关闭）
- 默认视图、Whole crate / Include closures 默认值
- 每个 package 上次选择的 target 会记住

工具窗内的 **Profile** 下拉框会读取 `Cargo.toml` 中的 `[profile.*]` 自定义配置。它与 RustRover
顶部的 Cargo 配置下拉框相互独立；`Copy command` 使用工具窗内选中的 Profile。

`cargo-expand` / `cargo-show-asm` / `rustfmt` **不会自动安装**；设置页会显示是否在 PATH 中找到。

## 已知限制

- 宏展开结果没有源码映射，无法精确对应到某个宏调用点。
- stable 的 `--emit=mir` 是编译前端 MIR，未单态化、未优化；泛型函数只显示泛型 MIR。
- 汇编是 rustc 生成的 `.s`，不是链接后二进制的反汇编；函数可能因内联、泛型未实例化、非 `pub`
  或被优化而不出现，此时请尝试 `#[inline(never)]`、单态化 wrapper 或切换 profile。
- `RUSTC_BOOTSTRAP=1` 可能影响 build script / proc macro 的 feature 检测；可在设置中关闭或改用 nightly。
- 本插件执行 cargo，等同于在项目目录运行 `cargo build`，因此尊重 IDE 的 Trusted Projects 机制。
- Linux/macOS 优先，Windows 为 best-effort。
- LLVM IR 视图和链接后反汇编不在 0.1.0 范围内。

## 构建与测试

```bash
# 需要 JDK 25 和 Gradle 9.x（仓库自带 wrapper）
./gradlew build test

# 复用本机已安装的 RustRover，避免下载约 1.4 GB 的 IDE
./gradlew build test -PrustRoverLocalPath=/path/to/RustRover

# 启动沙箱 IDE
./gradlew runIde

# 对 RustRover 2026.2.5 做插件验证
./gradlew verifyPlugin
# 只在本地缓存已就绪、想跳过 Marketplace 下载时：
./gradlew verifyPlugin -PverifyOffline=true
```

集成冒烟测试默认跳过，需要真实 cargo：

```bash
./gradlew test -Prustreader.smoke=true
# or: ./gradlew test -Drustreader.smoke=true
```

## 隐私

无遥测、无崩溃上报、无网络请求。唯一的外部进程是本地 `cargo` / `rustc` / `rustfmt`。

## License

[MIT](LICENSE)
