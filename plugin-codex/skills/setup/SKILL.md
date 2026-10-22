---
name: setup
description: Set up the local Mnemic memory server after installing the Codex plugin, or change its owner, data home, language, aliases or binary.
---

<!-- Copyright (C) 2026 Marcus Hirt. See LICENSE for license details. -->

# Set up Mnemic

This workflow configures the MCP server already supplied by this plugin. Keep the plugin's MCP registration;
do not create another entry with `codex mcp add` or edit global MCP configuration to register it twice.

1. Locate the plugin root from this skill's installed path: two directories above this skill's directory.
   The launcher is `scripts/mnemic.mjs` inside that root. Use its actual absolute path in shell commands;
   `${PLUGIN_ROOT}` in `mcp.json` is expanded by the plugin loader, not by the shell.
2. Check that `node` is available and version 18 or newer. The launcher uses only built-in Node modules;
   there is no npm package to install. If Node is absent, explain that it must be installed and made available
   to the Codex process, then continue setup after that prerequisite is met.
3. Run `node "<plugin-root>/scripts/mnemic.mjs" --show-config` to inspect the existing configuration.
   Ask for the owner's name if it is not already provided. The owner makes "I", "me" and "my" mean the user.
   Offer the default data home `~/.mnemic`, or the existing Mnemic data home when sharing a memory with
   another assistant. Do not guess the owner from a machine username or choose a different existing memory
   without the user's instruction. Optional settings are language (`en` or `de`), aliases, email addresses,
   GitHub handle and other handles. Multiple aliases, emails or handles are comma-separated strings.
4. Save the values the user supplied, omitting optional flags they did not request:

   ```text
   node "<plugin-root>/scripts/mnemic.mjs" --configure --owner "Alice Example" --home "/path/to/.mnemic" --language en --owner-github alice-example --owner-emails "alice@example.com,alice@work.example"
   ```

   The other supported settings are `--owner-aliases`, `--owner-handles` and `--binary`. For an existing
   native executable, add `--binary "/path/to/mnemic"` to use it instead of downloading a release.
   Configuration is stored outside the plugin installation so plugin updates preserve it.
   `MNEMIC_CODEX_CONFIG` selects another configuration file; `MNEMIC_CODEX_CACHE` selects another binary
   cache directory. Existing `MNEMIC_*` environment variables override saved settings, including
   `MNEMIC_BINARY` for an executable the user already installed. Do not change those variables silently.
5. Run `node "<plugin-root>/scripts/mnemic.mjs" --install` to download and verify the pinned native release,
   or check the configured existing binary. Do this before restarting: a first download can take longer
   than the MCP startup timeout. Report a download, checksum or unsupported-platform error clearly;
   configuration alone does not prove the server works. `--install` prepares the binary without starting
   the memory server.
6. Run `--show-config` again to confirm the intended owner and data home, then tell the user to start a new
   Codex chat or restart the client so its plugin MCP server reconnects. In that conversation call the
   plugin's `status` tool and confirm the data home and owner. Tool names may include a client-assigned
   namespace: discover the Mnemic tools instead of assuming an exact prefix. If the tools are still absent,
   check that the plugin is enabled, Node is on the Codex process's PATH, and setup completed successfully.

If a separately configured Mnemic server already exists, explain that both registrations would expose the
same tools. Keep one active registration. Do not remove an existing registration until the user has chosen
the plugin and its status has been verified.
