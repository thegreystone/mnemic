# Mnemic

A memory for your AI assistant that lasts. Tell your assistant something once, and every later conversation,
in any tool that supports it, can build on it: who someone is, what you decided, where you worked in 2015,
what has changed since. Everything stays in one file on your own machine.

Mnemic is an MCP server, the open standard that assistants such as Claude Desktop and Claude Code use to reach
tools. You do not talk to Mnemic. You talk to your assistant, and it remembers and recalls on your behalf.

## What it does for you

**It remembers what you said, in your words.** Every entry keeps the exact text, where it came from, and
when. Nothing is paraphrased away. The structured reading the assistant makes of it, "Alice works at
Example Corp since 2018", sits beside the words, never instead of them, so you can always get back to what
you actually said.

**It knows when things were true.** Facts carry the period they held. "Where did I work in 2015" is answered
by the job you had in 2015, not by whatever you mentioned last. "I left Example Corp in 2018" closes the
earlier fact instead of contradicting it, and the history stays readable. A plan with a date in the future
is recorded as a plan, reported as *not yet* until the date, and flagged for confirmation once the date has
passed without a word from you.

**It knows what is not so.** "I don't own a boat", "I only own property in Switzerland", and "those are all
the properties I own" are each stored as what they are. A yes/no question is answered *no* only when
something you said supports it, and an honest "not known" is never dressed up as a no.

**It asks instead of guessing.** When "Anna" could be two people it knows, when a new job overlaps an old
one, when a new relation looks like an existing one, or when it cannot tell whether one place lies inside
another, it puts a question to your assistant, holds the fact, and waits for your answer.

**It tells you how sure it is.** A thing you said firmly, a thing you said you *think*, a fact read from a
document, and a fact you have repeated several times all carry different confidence, and the assistant sees
it. Facts that change with time, such as jobs and homes, show when they were last confirmed and how long ago
that was.

**It corrects without erasing.** "No, it was Schübelbach, not Zürich" replaces the fact and keeps the old one
marked as corrected. "That was never true" withdraws a fact and keeps the reason in its history. "That note
was wrong, the later one is right" retires the note: it stays, marked with what superseded it, and stops
being an answer. Only "forget what I told you about my passport" removes: the entry and everything derived
from it go, and the bytes in the file are overwritten.

**It starts every conversation oriented.** Asked for a briefing, it returns the facts that place you, your
current job, home, and family first, the people and projects touched recently, and any open questions, in a
size the assistant can afford.

**It keeps your data yours.** One SQLite file in a folder you choose. No account, no service, no model call
unless you configure one. You can keep several memories, one for work and one for home, and point each
assistant at the one it should use.

## Install

Download the binary for your platform from the releases page, or build one yourself
([docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)):

| Platform | File |
|---|---|
| Linux x86_64 | `mnemic-<version>-linux-x86_64` |
| Linux ARM64 | `mnemic-<version>-linux-aarch64` |
| macOS Apple silicon | `mnemic-<version>-macos-aarch64` |
| Windows x86_64 | `mnemic-<version>-windows-x86_64.exe` |
| any, with a JDK 25 | `mnemic-server-<version>-runner.jar` |

**Claude Desktop users:** the simplest install is the MCP Bundle, `mnemic-<version>-macos-aarch64.mcpb` or
`mnemic-<version>-windows-x86_64.mcpb`, from the same releases page; see [Claude Desktop](#claude-desktop-and-other-json-configured-clients).

Put the binary somewhere stable and, on Linux or macOS, make it executable. It starts in well under a second
and needs nothing else installed. The macOS binary is signed and notarized, so Gatekeeper accepts it as
downloaded; the one exception is a first launch while offline, because Gatekeeper fetches the notarization
ticket from Apple, and then `xattr -d com.apple.quarantine mnemic-<version>-macos-aarch64` clears the flag
once. The Windows binary is Authenticode-signed.

Mnemic keeps its memory in a **data home**, a folder holding `mnemic.db` and a log. The default is `.mnemic`
in your home directory. Keep the binary and the data home apart: one installed binary can serve several data
homes, and each assistant only needs to know which one it is meant to use.

## Configure

Everything is set through environment variables in the assistant's configuration. Three matter:

| Variable | What it does |
|---|---|
| `MNEMIC_HOME` | The data home. Default: `~/.mnemic`. |
| `MNEMIC_OWNER` | Your name, so that "I", "me", and "my" mean you. |
| `MNEMIC_LANGUAGE` | The language facts are written in, `en` (default) or `de`. What you say is kept in whatever language you said it; the facts read from it are rendered in this one, and changing it re-renders them all. |

Optional, for the other names you go by, so that a commit author, an issue mention, or a nickname is also
you:

| Variable | Example |
|---|---|
| `MNEMIC_OWNER_ALIASES` | `Ali,Example` (nicknames, or the surname alone) |
| `MNEMIC_OWNER_EMAILS` | `alice@example.com,alice@work.example` |
| `MNEMIC_OWNER_GITHUB` | `alice-example` |
| `MNEMIC_OWNER_HANDLES` | `@alice_example` |

If the memory will be used for code work, at least the GitHub handle and the addresses your commits carry are
worth setting. `status` lists what is configured.

### Claude Code

```text
claude mcp add mnemic -e MNEMIC_OWNER="Alice Example" -e MNEMIC_HOME=/home/alice/.mnemic -- /opt/mnemic/mnemic
```

### Claude Desktop and other JSON-configured clients

The easiest way into Claude Desktop is the MCP Bundle: download the `.mcpb` for macOS or Windows from the
releases page and double-click it, or open it from *Settings → Extensions*. Its settings page asks for your
name and, optionally, the data home and your other names, addresses, and handles; there is no file to edit.
The bundle wraps the same binary as the standalone download. The bundle file itself carries no signature,
so Claude Desktop shows its standard unsigned-extension notice before installing.

For other JSON-configured clients, or to point Claude Desktop at a binary you installed yourself:

```json
{
  "mcpServers": {
    "mnemic": {
      "command": "/opt/mnemic/mnemic",
      "env": {
        "MNEMIC_HOME": "/home/alice/.mnemic",
        "MNEMIC_OWNER": "Alice Example",
        "MNEMIC_OWNER_GITHUB": "alice-example",
        "MNEMIC_OWNER_EMAILS": "alice@example.com"
      }
    }
  }
}
```

### The jar instead of a binary

```json
{"command": "java", "args": ["-jar", "/opt/mnemic/mnemic-server-<version>-runner.jar"], "env": {"MNEMIC_OWNER": "Alice Example"}}
```

Restart the client and ask the assistant to check Mnemic's status. It should report the data home, the schema
version, and your name as owner.

### Teach the assistant when to remember

The tools describe themselves, but an assistant works best with a short standing instruction: recall before
answering anything about people, projects, places, decisions, or dates; remember at natural pauses rather
than after every message; pass questions on to you instead of guessing; treat "no, it was X" as a
correction and "that changed" as news. The full text, ready to paste into a `CLAUDE.md`, a project system
prompt, or the client's instruction field, is at
[server/src/main/resources/protocol/guide.md](server/src/main/resources/protocol/guide.md).

## Use

You talk to the assistant; it talks to Mnemic. Things you can say:

- "Remember that we decided to use SQLite for the project."
- "Who is Anna Lindqvist?" or "What do you know about the Mnemic project?"
- "Where did I work in 2015?" or "When did I move to Switzerland?"
- "Do I own anything in Sweden?" (answered *no* only if you said so; otherwise "not known")
- "Actually, it was Schübelbach, not Zürich." (a correction; the history is kept)
- "I left Example Corp last month." (a change; the earlier fact is closed, not erased)
- "I think the two companies were the same one." (stored as a belief, shown as one)
- "That was never true, drop it." (the fact is withdrawn, the reason kept)
- "That note about Slack was wrong; the later one is right." (the note is retired, not deleted)
- "Read that email and file what it says." (an entry that arrived from a connector gets its facts)
- "Forget what I told you about my passport." (removed; only a dated marker remains)
- "What did you believe about my employer before?" (the history of a fact)
- "What should I confirm?" (plans past their date, and facts long unconfirmed)
- "What vehicle am I about to collect?" (what is coming ranks first when the question asks)

Behind that are seven tools: `remember`, `recall`, `inspect`, `correct`, `forget`, `consolidate`, and `status`. Each
one tells the assistant when to use it and when not to, and everything the store holds has one kind of id (`obs-12`,
`f-12`, `ent-12`, `evt-3`, `q-3`, `pred:works_at`, `event:joined`, `type:place`) that `inspect` and `correct` take.

Every answer from `recall` starts with a verdict the assistant can rely on: *matched* (the fact is known),
*MISS* (the question was understood and no such fact is known, which is not the same as no), *KNOWN FALSE*
(something you said rules it out), or *NOT YET* (a plan whose date has not come). A second line says which
of the four ways of searching had their say, so an answer given while the model is still downloading is
marked as partial rather than passed off as complete. Facts come with their source, their period, their
status, and how sure they are.

## Who reads your words

By default, nobody but the assistant that already has them. The assistant proposes the structured reading;
Mnemic checks it and stores it. Nothing leaves the machine that the assistant did not already see.

**Hybrid mode** lets you give Mnemic its own model for the reading, so that memory works even when the
assistant sends only the text, or to get one fixed, predictable reader. Two more environment variables:

```text
MNEMIC_PROPOSER_MODEL=lmstudio:qwen/qwen3.5-9b     # a model loaded in LM Studio on this machine
MNEMIC_PROPOSER_MODE=sync                          # sync: at once; deferred: later, during consolidate
```

Local models need no key (`lmstudio:<model>`, `ollama:<model>`, `openai-compatible:<model>@http://host/v1`).
A hosted model (`anthropic:<model>`, `openai:<model>`) reads its key from `API_KEY_ANTHROPIC` or
`API_KEY_OPENAI` in the environment (the conventional `ANTHROPIC_API_KEY` and `OPENAI_API_KEY` work too, and
`MNEMIC_PROPOSER_API_KEY_ENV` names any other variable to read it from), and then your words do leave the
machine. The assistant's own reading
always wins; the configured model is asked only when the assistant sent none. Which local model is worth
running, and what it costs in quality against a hosted one, is measured rather than guessed:
[docs/LOCAL-MODELS.md](docs/LOCAL-MODELS.md). On a 16 GB graphics card, a 9B model reads a conversation in a
few seconds and was not measurably behind Claude Haiku on the questions that are hard for text search.

### Recall by meaning

Recall finds things by their words and by their structure, and also by meaning: "where do I bank" finds "my
accounts are at Nordbank", and a question in German finds an answer written in English. This needs a
small language model, and Mnemic fetches it for you.

**On first start** Mnemic downloads the `ibm-granite/granite-embedding-311m-multilingual-r2` model from
Hugging Face, about 350 MB (the 8-bit build on x86; the full-precision 1.3 GB one on ARM), into
`~/.mnemic/models`, shared by every memory on the machine. It was chosen in a bake-off of fourteen candidates
on English, German, and Swedish questions ([docs/BENCHMARKS.md](docs/BENCHMARKS.md)); an earlier default's
files can be deleted from that directory. Every file is checked
against a hash pinned in Mnemic's code before it is used. The download is data only: the ONNX Runtime library
that executes the model is built into Mnemic itself, so no program code is ever fetched at run time. The
download runs in the background: the assistant can remember and recall from the first second, and once the
model is loaded everything already stored is embedded on its own. Ask for `status` to see where it is: it
lists every recall channel and whether it answers, the download per file with a percentage, then `loading`,
`ready`, or `failed` with the reason. The model runs inside Mnemic on the CPU, a few milliseconds per
sentence; nothing you say is sent anywhere.

| Variable | What it does |
|---|---|
| `MNEMIC_EMBED` | `auto` (default) or `off` to run without recall by meaning. |
| `MNEMIC_MODELS_DIR` | Where fetched models live. Default: `~/.mnemic/models`. |
| `MNEMIC_EMBED_MODEL_URL` | A mirror for the model download (a URL or a `file:` URL), for machines without internet access. The hash stays the same. |
| `MNEMIC_ORT_LIBRARY`, `MNEMIC_EMBED_MODEL` | A library and a model folder you provide; no download. |

Measured on the LongMemEval benchmark, recall by meaning adds four to eight points of recall over words and
structure alone, most of it on questions that draw on several conversations
([docs/BENCHMARKS.md](docs/BENCHMARKS.md)).

## Where things are

- `<data home>/mnemic.db` is everything Mnemic knows. Copy it to back up, delete it to start over.
- `<data home>/mnemic.log` is the server log. Nothing is written to the console, which the assistant uses.
- Mnemic upgrades an older data file in place on start and refuses to open one written by a newer version.

## Trouble

- The client shows no Mnemic tools: check the command path and that the binary is executable, then look in
  the log.
- `status` says the owner is "the user": `MNEMIC_OWNER` was not passed; set it in the client's `env`.
- Facts are not being created: the assistant is sending text without a reading. Give it the standing
  instruction above, or configure hybrid mode; `status` shows how many entries are waiting.
- A hosted model is configured but `status` says the assistant proposes: the key variable was not set for
  the server process; the log says so.
- Recall by meaning is off right after a start: the model takes a few seconds to load from disk; `status`
  says `loading`, the first recall waits for it, and the answer's second line says what it was based on.
- The assistant does not see a tool that a new version added: clients keep the tool list they fetched when
  the conversation started. Start a new conversation.

## What is coming

Derived facts, such as who your colleagues are from where everyone works; GPU acceleration for large
stores; and package-manager installs (Homebrew, winget).

## About the name

*Mnemic* is Richard Semon's 1904 adjective for anything pertaining to memory, from the book that also coined
*engram*. Mnemic was designed under the working name "Engram" until 2026-09-07 and is not affiliated with any
other project of that name.

Design, benchmarks, and how to build and contribute: [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md); how the code is
put together: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## License

BSD-3-Clause. See [LICENSE](LICENSE).
