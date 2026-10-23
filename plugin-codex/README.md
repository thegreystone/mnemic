# Mnemic for Codex

Persistent local memory shared by your AI assistants. This plugin includes the memory protocol, a setup
workflow and a launcher for the Mnemic MCP server. The server stores dated facts in one SQLite file on your
machine. Use the same data home as Claude or another MCP client to share a memory.

## Install

Requires **Node.js 18 or newer** on the Codex process's PATH. There are no npm packages to install or publish.
The launcher downloads a native executable directly from the
[Mnemic GitHub release](https://github.com/thegreystone/mnemic/releases/tag/v0.3.4) and caches it outside the
plugin installation. Supported release platforms are Linux x86_64 and ARM64, macOS Apple silicon, and
Windows x86_64. A separately installed binary can be used instead.

The launcher detects the host OS and native architecture, including Apple silicon under Rosetta and
Windows hosts running an emulated Node process. It selects exactly one release asset:

| Host | Release asset |
|------|---------------|
| Linux x86_64 | `mnemic-0.3.4-linux-x86_64` |
| Linux ARM64 | `mnemic-0.3.4-linux-aarch64` |
| macOS ARM64 | `mnemic-0.3.4-macos-aarch64` |
| Windows x86_64 | `mnemic-0.3.4-windows-x86_64.exe` |

Unsupported hosts, including Intel Macs and Windows ARM64, get an explicit error before any download.
Use `MNEMIC_BINARY` or `--binary` for a compatible executable you built or installed yourself.

Add the [AI Plugins marketplace](https://github.com/thegreystone/ai-plugins) to your Codex plugin sources,
refresh the directory and install **Mnemic**. The Codex marketplace entry selects this `plugin-codex/`
directory; Claude Code uses the separate [`plugin/`](../plugin/) package and its MCP Bundle.
Repository plugin marketplaces and local MCP execution must be supported by the Codex surface you use;
see [OpenAI's plugin packaging documentation](https://developers.openai.com/plugins/build/plugins).

Run the plugin's **Setup** workflow. It asks for your name, a data home (default `~/.mnemic`), and any optional
aliases, email addresses or handles. It then saves your settings and downloads the executable before the
next server start. Start a new Codex chat after setup and ask it to check Mnemic's status.

The plugin already supplies the MCP registration. Keep a single active Mnemic registration if you previously
added one manually; a second registration exposes the same memory tools twice.

## Configure from a terminal

Use the actual installed plugin path in these commands:

```text
node "<plugin-root>/scripts/mnemic.mjs" --configure --owner "Alice Example" --home "/home/alice/.mnemic"
node "<plugin-root>/scripts/mnemic.mjs" --install
node "<plugin-root>/scripts/mnemic.mjs" --show-config
```

`--install` prepares the executable without starting the server. Run it during setup because the first
download may exceed Codex's MCP startup timeout. Normal launch, with no flags, starts the cached executable
over stdio. Launcher diagnostics go to stderr so stdout remains the MCP transport.

Optional `--configure` flags mirror the server's environment variables:

| Flag | Environment override | Value |
|------|----------------------|-------|
| `--owner` | `MNEMIC_OWNER` | The memory owner's name |
| `--home` | `MNEMIC_HOME` | Data home; use the same path in each assistant to share a memory |
| `--language` | `MNEMIC_LANGUAGE` | `en` or `de` |
| `--owner-aliases` | `MNEMIC_OWNER_ALIASES` | Comma-separated names |
| `--owner-emails` | `MNEMIC_OWNER_EMAILS` | Comma-separated email addresses |
| `--owner-github` | `MNEMIC_OWNER_GITHUB` | GitHub handle |
| `--owner-handles` | `MNEMIC_OWNER_HANDLES` | Comma-separated other handles |
| `--binary` | `MNEMIC_BINARY` | Absolute path to an existing native executable; skips release download |

Environment variables take precedence over saved settings. All other inherited `MNEMIC_*` server settings
are passed through. `MNEMIC_CODEX_CONFIG` overrides the configuration file path, and `MNEMIC_CODEX_CACHE`
overrides the binary cache directory. Configuration and cached binaries are kept outside the installed
plugin so an update does not discard them. `--show-config` reports the paths and effective settings.

Default launcher locations are:

| Platform | Configuration file | Binary cache |
|----------|--------------------|--------------|
| Windows | `%LOCALAPPDATA%\mnemic\codex\config.json` | `%LOCALAPPDATA%\mnemic\cache` |
| macOS | `~/Library/Application Support/mnemic/codex/config.json` | `~/Library/Caches/mnemic` |
| Linux | `~/.config/mnemic/codex/config.json` | `~/.cache/mnemic` |

Linux respects `XDG_CONFIG_HOME` and `XDG_CACHE_HOME` when set. Windows falls back to `~/AppData/Local` if
`LOCALAPPDATA` is absent. Each downloaded release gets its own version directory in the cache.
The download must match GitHub's published asset size and SHA-256 digest when the release provides one.
The launcher records a SHA-256 hash with each cached executable and checks it again before use.

For an existing executable:

```text
node "<plugin-root>/scripts/mnemic.mjs" --configure --owner "Alice Example" --binary "/opt/mnemic/mnemic"
node "<plugin-root>/scripts/mnemic.mjs" --install
```

The memory itself remains in `<data home>/mnemic.db`. The launcher configuration and binary cache do not
contain the memory. Mnemic separately downloads its embedding model on first server start as described in
the [main README](../README.md#recall-by-meaning).

## Maintaining the release pin

After publishing and signing a native release, update `RELEASE_VERSION` in `scripts/mnemic.mjs` and `version`
in `plugin.json` together. Verify that the release has every supported native asset.
The launcher installs exactly that version, rather than following `latest`. The Claude plugin's MCP Bundle
URL in [`plugin/.claude-plugin/plugin.json`](../plugin/.claude-plugin/plugin.json) is a separate pin and must
also be updated for a release intended for both clients.

The memory skill repeats [the server protocol](../server/src/main/resources/protocol/instructions.md), with
client-specific setup guidance around it. Keep the protocol text synchronized when it changes.

To check the launcher without accessing a real memory or downloading a release, run its Node test suite:

```text
node --test plugin-codex/tests/launcher.test.mjs
```
