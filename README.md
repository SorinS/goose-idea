# Goose for IntelliJ

An IntelliJ Platform plugin for the [Goose language](https://github.com/aardappel/goose),
for IntelliJ IDEA, GoLand and CLion 2026.2 and later. It does what the
[VS Code extension](https://github.com/aardappel/goose/tree/main/vscode) does:
the compiler is the language server (`goose lsp`), and the plugin starts it;
the extension's TextMate grammar colors keywords, strings and comments, which
the server's semantic tokens do not cover. It also debugs Goose programs,
through the IDE's own Debug Adapter Protocol client and
[goose-debugger](https://github.com/SorinS/goose-debugger)'s `goose-dap`
([DEBUGGING.md](DEBUGGING.md)).

The plugin's version follows goose-debugger's: plugin 0.3.x goes with
goose-debugger 0.3.x. Patch releases of either are independent.

## Install

From the JetBrains Marketplace, as
[**Goose Language**](https://plugins.jetbrains.com/plugin/36132-goose-language):
**Settings | Plugins | Marketplace**, search for *Goose Language*, and
install. The IDE then updates it with its other plugins.

Or build it from source and install the zip ([USAGE.md](USAGE.md) goes
through it step by step, with a checklist of the features to try). The build
needs Java 25, the runtime the 2026.2 IDEs bundle, so point `JAVA_HOME` at
the IDE's:

```bash
export JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
./gradlew buildPlugin
```

This makes `build/distributions/goose-idea-<version>.zip` (the version is
`pluginVersion` in `gradle.properties`). In the IDE:
**Settings | Plugins**, the gear icon, **Install Plugin from Disk...**, pick the
zip, and restart the IDE when asked.

The plugin needs a Goose compiler that has `goose lsp` (check with
`goose lsp --probe`). It looks for one by itself (see [Settings](#settings));
if it finds none, it says so when you open a `.goose` file.

### For debugging

Debugging needs, besides the plugin:

- **The Goose debugger**: the goose-debug package for your platform from
  [goose-debugger's releases](https://github.com/SorinS/goose-debugger/releases)
  (v0.3.0: macOS arm64, Linux x86-64, Windows x64). Unpack it anywhere. It
  has a Goose compiler with `-g` (`bin/goose-debug`), its own standard
  library, and the debug adapter `goose-dap` beside the compiler.
- **lldb-dap**, LLVM's debug adapter, which goose-dap drives:
  - macOS: the Xcode Command Line Tools (`xcode-select --install`);
  - Linux: LLVM 16 or newer (`lldb-dap`, `lldb-vscode` or `lldb-dap-NN`,
    e.g. Ubuntu's `lldb-18`);
  - Windows: LLVM's installer (`C:\Program Files\LLVM\bin\lldb-dap.exe`).
- **A C compiler**, since Debug builds the program natively with
  `goose build -g`: the Command Line Tools' clang on macOS, gcc or clang on
  Linux, MSVC (Visual Studio Build Tools) on Windows.
- **Optional, Windows only:** a Python install, which LLVM's lldb needs to
  show values in Goose form; without it, values show as their generated C.
  On macOS and Linux lldb brings its own.

Then, in **Settings | Languages & Frameworks | Goose**, set **Compiler** to
the package's `bin/goose-debug` (`bin\goose-debug.exe` on Windows), leave
**Standard library** empty, and leave **Debug adapter** on *Auto*: the
plugin finds `goose-dap` beside the compiler. The same compiler serves the
language server, so editing and debugging see the same standard library.
Plugin 0.3.x goes with goose-debugger 0.3.x. [DEBUGGING.md](DEBUGGING.md)
has the rest.

To check the install, open a `.goose` file: keywords and strings are
colored, the file has the goose icon, the language services widget in the
status bar shows Goose running, and hovering a function name shows its
declaration. If not, **Help | Show Log in Finder** and look in `idea.log` for
`Goose TextMate bundle not found` or for `GooseLspClientDescriptor` lines.

## Features

From the language server, in `.goose` files:

- errors and warnings as you type, for the whole program (the entry files
  set in the settings, or the open file);
- completion of names in scope and of members after a `.`;
- hover (Quick Documentation, F1 on macOS) with declarations, types and
  documentation comments;
- go to definition (Cmd/Ctrl+B, Cmd/Ctrl+click), including across imports;
- find usages (Alt+F7) and rename (Shift+F6) across the program's files;
- the structure view (Cmd/Ctrl+F12) from the document symbols;
- semantic highlighting of names: functions, types, fields, variants,
  parameters and variables;
- parameter info (Cmd/Ctrl+P) while typing a call's arguments;
- inlay hints for the types of `let`s and `var`s and parameter names at calls;
- folding of bodies and comment runs;
- code lenses: above a generic function, the number of its instantiations,
  which a click lists in a popup, and **Run** above `main`, which runs that
  file with the JIT;
- Reformat Code (Cmd+Alt+L / Ctrl+Alt+L) lays the file out as `goose format`.

The language server widget in the status bar shows the server's state and
can restart it; so does **Tools | Goose | Restart Language Server**.

From the TextMate bundle (the VS Code extension's grammar, language
configuration and snippets):

- syntax highlighting of keywords, strings (escapes, raw `"""` strings),
  numbers, comments (nested block comments too) and builtins;
- bracket matching, auto-closing of brackets and quotes, indentation, and
  Comment with Line Comment (Cmd/Ctrl+/) and Block Comment;
- the extension's snippets (`main`, `fn`, `struct`, `enum`, `for`, `if`,
  `match`, ...): type the prefix and press Tab;
- ```` ```goose ```` code blocks in Markdown should be highlighted too,
  through the IDE's TextMate support for Markdown fences (not checked yet).

`.goose` files show the goose icon.

Running and building:

- **Tools | Goose** (and the editor's context menu): **Run with JIT**
  (`goose --jit file.goose`), **Check Program** (`goose check`), **Build
  Executable** (`goose build`) and **Generate C** (`goose -o file.c`). They
  act on the first entry file when one is set, else on the current file, and
  save open files first.
- A **Goose** run configuration type, with the same four actions, the program
  file, its arguments and a working directory. Right-click a `.goose` file
  (or Ctrl+Shift+R / Ctrl+Shift+F10 in it) for **Run 'file.goose'**.
- In the Run window, `file:line: error: ...` lines from the compiler link to
  the source.

Debugging ([DEBUGGING.md](DEBUGGING.md) has the details):

- Debug a Goose run configuration (or right-click a `.goose` file, **Debug
  'file.goose'**): the plugin builds it with `goose build -g` and runs it
  under LLDB through the IDE's DAP client.
- Line breakpoints in `.goose` files, conditional breakpoints and logpoints;
  step over, into and out, one Goose line at a time.
- With `goose-dap`, the debugger speaks Goose: `geo::area` in the call stack,
  the locals in scope under their Goose names, Goose values
  (`Point { x: 3, y: -4 }`, `Color.Green`, resizables as lists), Goose names
  in watches and Evaluate, the program's globals, and a stop at the Goose
  line where a runtime error (a failed assert, a bounds or overflow check)
  happened, with its message.
- Without `goose-dap`, plain `lldb-dap` works too, with Goose values from the
  plugin's bundled formatters but the generated C's names.

## Settings

**Settings | Languages & Frameworks | Goose**, kept per project in
`.idea/goose.xml` (a shared project file: a machine-specific compiler path
set there goes with the project, as in VS Code's workspace settings). They
are the VS Code extension's settings:

| Setting | VS Code | What it does |
|---|---|---|
| Compiler | `goose.compilerPath` | The compiler. Empty searches `goose`, `build/Release/goose`, `build/Debug/goose` and `build/goose` in the project, then `PATH`, then `~/.local/bin`, `/opt/homebrew/bin` and `/usr/local/bin` (an IDE started from the Dock does not get the shell's `PATH`). A name without a slash is a command looked up on `PATH`; a relative path is relative to the project. `${workspaceFolder}` and `$PROJECT_DIR$` stand for the project directory. The page shows which compiler is used. |
| Standard library | `goose.stdlibPath` | Passed as `--stdlib` to the server and to every command. Empty leaves it to the compiler (`GOOSE_STDLIB`, or `stdlib/` near the executable). |
| Entry files | `goose.entryFile` | Program root files, one per line, relative to the project. The server checks the modules they import as part of their program (the `entryFiles` initialization option), and the Goose actions run the first one rather than the current file. |
| Program arguments | `goose.runArguments` | The program's arguments for Run with JIT, after `--`. |
| Start the language server | `goose.useLanguageServer` | Off, the plugin gives highlighting and running but no analysis. |
| Debug adapter | | *Auto* (the default) uses `goose-dap` when it finds one, else `lldb-dap`; *goose-dap* or *lldb-dap* uses that one only. |
| Adapter path | `goose.debugAdapterPath` | The adapter's executable, or a command on `PATH`. Empty searches for it, starting beside the compiler (where goose-debugger's package has `goose-dap`). The page shows the adapter that will be used. |
| Stop on Goose runtime errors | | On by default: the debugger stops where a failed assert or a bounds or overflow check aborts the program. |

Changing the compiler, standard library, entry files or the server switch
restarts the server. The compiler and the server run with the environment
of your login shell (as the IDE's terminal sees it), so `GOOSE_STDLIB` set in
your shell profile applies.

Not carried over from VS Code: `goose.checkOnSave` and `goose.checkTimeout`
(the extension's checks of saved files when there is no server: this plugin
needs a compiler with `goose lsp`) and `goose.useRunKeybindings` (IntelliJ's
own Run shortcuts apply to the run configurations).

## How it works

- **Language server**: the IntelliJ Platform's own LSP client
  (`com.intellij.platform.lsp`), not Red Hat's LSP4IJ. The platform client is
  bundled in IntelliJ IDEA, GoLand and CLion 2026.2 (all three are installed
  here and the plugin verifier passes against each), so the plugin needs no
  other plugin, and the platform shows the server's features through its own
  UI (completion, Quick Documentation, find usages, the structure view, the
  language services widget). The cost is that the plugin depends on
  `com.intellij.modules.lsp`, which IDEs without the platform LSP client
  (Android Studio, for one) do not have; there it does not load. LSP4IJ would
  cover those, at the price of a third-party plugin dependency.
  `GooseLspIntegrationProvider` starts one server per project when a `.goose`
  file is opened: `goose lsp [--stdlib dir]`, in the project directory, with
  the initialization options of the VS Code extension
  (`{entryFiles, stdlib, commands: ["goose.run", "goose.showInstantiations"]}`).
  Those two commands, which the server's code lenses name, run in the plugin;
  others go to the server.
- **Package modules**: the server analyzes an open file that no entry file
  reaches as a program rooted at the file's directory, where a library
  module's imports (`import crypto.bignum;` inside gslib) do not resolve:
  they resolve against the package's root. When the project directory is a
  package (`name.goose` declaring `namespace name;`; with several, the one
  with a `name.build` sidecar), `GoosePackageRoots` writes, for each open
  module of it, a two-line program in the IDE's system directory
  (`import name from "<project>"; import name.crypto.bignum;`) and adds it
  to the entry files, through `workspace/didChangeConfiguration` as editors
  open and close. Files with `fn main` (tests, tools, examples) are programs
  of their own and are left alone.
- **Highlighting, braces, comments, snippets**: the plugin registers no file
  type of its own. It gives the IDE's TextMate plugin a bundle in the layout of
  a VS Code extension (`textmate/goose`: `package.json`, the grammar,
  `language-configuration.json`, the snippets), shipped in the plugin's
  directory next to `lib/`. The TextMate plugin then claims `.goose` and
  provides highlighting, brace matching, auto-closing, commenting, indentation
  and snippets from it. (A file type of its own would lose all of those, which
  the TextMate plugin gives only to its own file type.) A file icon provider
  gives `.goose` files the goose icon.
- **Running**: a run configuration type that runs the compiler, with a console
  filter for its error lines.

The plugin is written in Java; the build script is Groovy (`build.gradle`), so
the repository has no Kotlin.

## Development

The build uses the IntelliJ Platform Gradle Plugin 2.x and builds against the
IntelliJ IDEA installed in `/Applications` (`platformLocalPath` in
`gradle.properties`; override with `-PplatformLocalPath=/path/to/IDE.app`).
Gradle needs Java 25, the runtime the 2026.2 IDEs run on (the IntelliJ
Platform Gradle Plugin asks for the target IDE's Java; the code itself is
compiled for Java 21). The IDE's bundled runtime is one; a JDK 17, 21 or 27
is not:

```bash
export JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
./gradlew buildPlugin            # build/distributions/goose-idea-<version>.zip
./gradlew test                   # unit, platform and language-server tests
./gradlew verifyPluginStructure  # plugin.xml and the zip's layout
./gradlew verifyPlugin           # the plugin verifier, against the installed IDEA, GoLand and CLion
./gradlew runIde                 # a sandbox IDE with the plugin
```

`runIde` starts a sandbox IDEA with its own configuration in
`.intellijPlatform/sandbox`; it asks for a license or a trial the first time.
Open a project from it, not one you use in your normal IDE.

The tests:

- `GooseCompilerTest`: finding the compiler and the command lines (plain JUnit).
- `GooseLanguageServerTest`: starts `goose lsp` with the plugin's arguments
  and initialization options, sends `initialize`, and checks the server
  advertises the features above, then shuts it down; also checks
  `goose lsp --probe`.
- `GooseLspClientTest`: the platform's LSP client starts the server through
  the plugin's descriptor and initializes it.
- `GoosePluginTest`: the bundle ships with the plugin and the TextMate plugin
  reads it (grammar for `.goose`, brackets, auto-closing pairs, comments,
  snippets); the file icon; the settings' defaults; the console filter.

The language-server tests use the compiler the plugin would find (with no
project), and skip when there is none; `-PgooseCompiler=/path/to/goose`
names one.

## License

Apache License 2.0, as the Goose compiler and its VS Code extension; see
[LICENSE](LICENSE). The grammar, language configuration, snippets and icons are
copied from the VS Code extension; [NOTICE](NOTICE) lists them.
