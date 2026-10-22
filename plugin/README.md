# Mnemic plugin

Persistent local memory shared by all your AI assistants. The plugin pairs the `mnemic` skill with the Mnemic MCP server, which runs on your machine and keeps everything in one SQLite file in a data home you choose. No account and no service: the only network access is a one-time download of the embedding model on first start.

This directory holds the Claude Code plugin. It carries no binary. The manifest's `mcpServers` points at the universal MCP Bundle attached to a Mnemic release, and Claude Code downloads that bundle, about 50 MB, into the plugin's cache at the first session start after installation. The bundle contains the macOS Apple silicon and Windows x86_64 binaries and picks the right one at launch. Linux is not covered by the bundle: install the Linux binary from the [releases page](https://github.com/thegreystone/mnemic/releases) and register it as described in the [main README](../README.md#claude-code). Claude Desktop does not accept a plugin that references its bundle by URL; Desktop users install the per-platform `.mcpb` from the release page directly.

Install in Claude Code from the [AI Plugins marketplace](https://github.com/thegreystone/ai-plugins):

```
/plugin marketplace add thegreystone/ai-plugins
/plugin install mnemic@hirt-ai-plugins
```

The server does not start until your name is set, so that "I" and "me" mean you. Open `/plugin`, choose **mnemic** on the **Installed** tab, choose **Configure**, and enter the name; the same form takes the data home, your other names, e-mail addresses, GitHub handle and other handles. Then start a new session. Claude Code does not open this form on its own, and the shell command `claude plugin configure` cannot fill it for a bundle fetched from a URL; until the name is set, `/plugin` and the install message say that the server needs configuration. If you already run Mnemic from a user-level `claude mcp add` entry, remove that entry once the plugin works, or the two will serve the same data home side by side.

## Updating the bundled version

The bundle URL in `.claude-plugin/plugin.json` pins one release. After publishing a release and running `mcpb/Sign-Release.ps1`, which re-packs the universal bundle around the signed Windows binary, change the version in that URL and push. Marketplace users pick the change up with `claude plugin update mnemic@hirt-ai-plugins`.

The skill text mirrors [protocol/instructions.md](../server/src/main/resources/protocol/instructions.md). Keep the two in step when the protocol changes.
