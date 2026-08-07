# intellij-scala-lsp

An LSP server that runs IntelliJ IDEA headless + the [intellij-scala](https://github.com/JetBrains/intellij-scala) plugin as its backend. LSP clients (Claude Code, VS Code, Zed, Neovim) get the same Scala intelligence that IntelliJ IDEA provides — PSI-based resolution, type inference, refactoring, and documentation — over the standard Language Server Protocol.

## Why

IntelliJ-Scala's static analysis engine is significantly faster than compiler-based approaches. It uses PSI (Program Structure Interface) — a lightweight, incremental AST that resolves types, references, and implicits without running the Scala compiler. This makes operations like go-to-definition, find-references, and code completion near-instant even on large codebases, where compiler-based tools like Metals can struggle with latency.

This project exposes IntelliJ's analysis engine over LSP, bringing its performance to any editor.

## Getting Started

```bash
# 1. Install the launcher and download LSP JARs
curl -fsSL https://github.com/hoangmaihuy/intellij-scala-lsp/releases/latest/download/install.sh | bash

# 2. Start the daemon
scalij --daemon

# 3. Import your project (auto-detects sbt or Mill)
scalij --import /path/to/your/project

# 4. Set up your editor
scalij --setup-claude-code-mcp   # Claude Code (recommended)
scalij --setup-vscode            # VS Code
scalij --setup-zed               # Zed
```

After setup, open your editor — Scala intelligence (completions, go-to-definition, references, diagnostics) is available immediately.

## Usage

```bash
# Start daemon with project pre-warming
scalij --daemon /path/to/project1 /path/to/project2

# Import a project (auto-detects Mill or sbt)
scalij --import /path/to/project

# Stop the daemon
scalij --stop

# List projects open in the daemon
scalij --list-projects

# Download LSP JARs and SDK
scalij --install

# Update to latest version (launcher checks daily automatically)
scalij --update
```

Running without flags proxies stdio to the daemon (auto-starts if needed) — this is how editors connect.

### CLI Reference

| Flag | Description |
|------|-------------|
| `--daemon [projects...]` | Start daemon, optionally pre-warm projects |
| `--stop` | Stop the daemon |
| `--list-projects` | List projects open in the daemon |
| `--import <path>` | Import project (auto-detects Mill or sbt) |
| `--install` | Download LSP JARs and SDK |
| `--update` | Update to latest version |
| `--setup-claude-code-mcp` | Set up Claude Code MCP server (recommended) |
| `--setup-claude-code-lsp` | Set up Claude Code LSP plugin |
| `--setup-vscode` | Set up VS Code extension |
| `--setup-zed` | Set up Zed extension |
| `--version` | Print version info |

## Editor Setup

See the setup guides for details:
- [Claude Code](docs/setup-claude-code.md) — MCP server (12 tools) or LSP plugin (9 ops)
- [VS Code](docs/setup-vscode.md)
- [Zed](docs/setup-zed.md)
- **Neovim** — use `cmd = { "scalij" }` in your LSP config

## Features

30+ LSP methods covering navigation, completion, refactoring, diagnostics, and more. For Claude Code, the MCP server exposes these as 12 AI-friendly tools.

- [LSP Features](docs/lsp-features.md) — full list of supported LSP methods
- [MCP Tools](docs/mcp-tools.md) — Claude Code tool reference with parameters and behavior

## Environment Variables

| Variable | Description |
|---|---|
| `INTELLIJ_HOME` | Path to IntelliJ installation |
| `JAVA_HOME` | Path to JDK (falls back to IntelliJ's bundled JBR) |
| `LSP_PORT` | TCP port for the daemon (default: 5007) |
| `LSP_HEAP_SIZE` | JVM heap size (default: `4g`) |
| `LSP_MAX_CONCURRENT_REQUESTS` | Max LSP request handlers running at once (default: quarter of CPU cores, min 2). Lower it if large files make the editor stall; raise it on a spare machine. |

## Architecture

See [docs/architecture.md](docs/architecture.md) for the daemon lifecycle, LSP provider mapping, MCP server design, classloader safety, and design decisions.

## Development

```bash
sbt lsp-server/compile                            # Build
sbt lsp-server/test                               # Run tests
sbt "lsp-server/runLsp --daemon"                   # Build and run
./launcher/scalij --setup-claude-code-mcp-dev  # Dev setup
```

## Version Compatibility

| Component | Version |
|---|---|
| IntelliJ Platform | 261.22158.277 (2026.1) |
| Scala Plugin | auto-resolved by sbt-idea-plugin |
| Scala (build) | 3.8.2 |
| sbt | 1.11.7 |
| lsp4j | 0.23.1 |

## Acknowledgements

- **[IntelliJ IDEA Community Edition](https://github.com/JetBrains/intellij-community)** — PSI framework, indexing, and code analysis
- **[intellij-scala](https://github.com/JetBrains/intellij-scala)** — Scala plugin with type inference, resolution, and refactoring
- **[Metals](https://github.com/scalameta/metals)** — pioneered Scala LSP support and defined feature expectations

## License

MIT — see [LICENSE](LICENSE).
