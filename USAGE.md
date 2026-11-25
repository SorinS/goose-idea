# Using the Goose plugin

Step by step: install the plugin, point it at a compiler, and check that
each feature works. [README.md](README.md) describes the features and
settings in full.

The plugin is on the JetBrains Marketplace as
[**Goose Language**](https://plugins.jetbrains.com/plugin/36132-goose-language):
**Settings | Plugins | Marketplace**, search for *Goose Language*, install,
and restart the IDE if asked. Then go on at [step 3](#3-point-it-at-the-compiler).
Steps 1 and 2 build it from source and install that build instead.

## 1. Build

Gradle needs Java 25, the runtime IntelliJ IDEA 2026.2 bundles (a JDK 17,
21 or 27 does not do):

```bash
cd goose-idea
export JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
./gradlew buildPlugin
```

The plugin is `build/distributions/goose-idea-<version>.zip`, the version
being `pluginVersion` in `gradle.properties`. The first build
downloads Gradle and its plugins and takes a few minutes; later ones take
seconds.

The build is against `/Applications/IntelliJ IDEA.app`. If your IDEA is
elsewhere, add `-PplatformLocalPath=/path/to/IDE.app`.

## 2. Install

In IntelliJ IDEA, GoLand or CLion (2026.2 or later):

1. **Settings | Plugins**.
2. The gear icon, **Install Plugin from Disk...**.
3. Pick `build/distributions/goose-idea-<version>.zip`.
4. Restart the IDE when asked.

**Settings | Plugins | Installed** should now list **Goose Language**.

To update after a rebuild, install the new zip the same way; it replaces
the old one. To remove it, **Settings | Plugins | Installed**, Goose
Language, **Uninstall**.

## 3. Point it at the compiler

The plugin needs a Goose compiler that has `goose lsp`. Check yours:

```bash
/path/to/goose.git/build/goose lsp --probe     # prints "goose lsp <version>"
```

The plugin looks for the compiler in the project (`build/goose` and the
like), on `PATH`, in `~/.local/bin`, `/opt/homebrew/bin` and
`/usr/local/bin`. If your compiler is in none of those (a project other
than goose itself, and `goose` not on your `PATH`), set it:

1. Open the project.
2. **Settings | Languages & Frameworks | Goose**.
3. **Compiler**: the full path to `goose`, e.g.
   `/path/to/goose.git/build/goose`. The page shows which compiler it will
   use.
4. **Standard library**: leave it empty; the compiler finds `stdlib/` next
   to itself. Fill it in only if you keep the standard library elsewhere.
5. **Entry files** (optional): the program's main file(s), relative to the
   project, one per line. With them, the server checks every module the
   program imports, and the Goose actions run that file whatever file is
   open. Without them, the open file is the program.
6. **OK**. The language server restarts.

The settings are per project, in `.idea/goose.xml`. If you would rather
not repeat this for each project, link the compiler onto your `PATH` once
instead:

```bash
mkdir -p ~/.local/bin && ln -s /path/to/goose.git/build/goose ~/.local/bin/goose
```

### To debug as well

A plain `goose` compiler has no debugger. To debug Goose programs, you need:

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

Then set **Compiler** to the package's `bin/goose-debug`, with **Standard
library** empty and **Debug adapter** on *Auto*. The page shows the adapter
it found, which should be the package's `goose-dap`.

## 4. Check it works

Open a `.goose` file. Then:

| Try | You should see |
|---|---|
| Look at the file | Keywords, strings, numbers and comments in color; the goose icon on the file's tab. |
| The status bar | The language services widget (the `{}` icon) lists **Goose** as running. |
| Type a mistake, e.g. `let x: i64 = "a";` | A red underline within a second; hover it for the compiler's message. |
| Type a name and Ctrl+Space, or `value.` | Completion: names in scope, or the members of `value`. |
| Hover a function name, or F1 (Ctrl+Q off macOS) | Its declaration, type and doc comment. |
| Cmd+B (Ctrl+B) or Cmd+click on a name | Jumps to its definition, into imported files too. |
| Alt+F7 on a function | Find usages lists its calls. |
| Shift+F6 on a name | Rename, across the program's files. |
| Cmd+F12 (Ctrl+F12) | The file's structure: functions, structs, enums. |
| Inside a call's parentheses, Cmd+P (Ctrl+P) | The parameters, the current one in bold. |
| `let` without a type | An inlay hint with the inferred type. |
| Above `fn main` | A **Run** code lens: click it to run the file with the JIT. |
| Above a generic function | A count of its instantiations; click it for the list. |
| Cmd+Alt+L (Ctrl+Alt+L) | The file reformatted as `goose format` would. |
| Type `fn` then Tab | The snippet expands. |
| Cmd+/ (Ctrl+/) | Comments out the line. |

Running and building, from **Tools | Goose** or the editor's right-click
menu:

- **Run with JIT**: output in the Run window. Compiler errors there
  (`file:line: error: ...`) are links to the source.
- **Check Program**: parse and typecheck only.
- **Build Executable**: an executable next to the source.
- **Generate C**: `file.c` next to the source.

A library module (a file of the project's package with no `fn main`,
such as gslib's `crypto/tls/handshake.goose`) is not a program: **Check
Program** checks it as part of the package, and the other actions say so
instead of compiling it alone. Run configurations are named with their
folder (`handshake.goose (examples/tls)`), so files of the same name
are told apart in the Run toolbar.

For a saved setup with arguments, **Run | Edit Configurations...**, **+**,
**Goose**: pick the action, the program file, its arguments and the working
directory. Right-clicking a `.goose` file and choosing **Run 'file.goose'**
makes one for you.

Debugging (with the goose-debug package as the compiler, as above):

| Try | You should see |
|---|---|
| Click in the gutter beside a line of `fn main`, then right-click the file, **Debug 'file.goose'** | The program is built with `goose build -g`, and the session stops on the breakpoint. |
| The Debugger's Frames | Goose names (`main`, `geo::area`); the C `main` and system frames are marked subtle. |
| Variables | The locals in scope under their Goose names, with Goose values (`Point { x: 3, y: -4 }`). |
| Step Over / Step Into | One Goose line at a time. |
| A watch such as `total + 1` | Its value, from Goose names. |
| A failed `assert` in the program | A stop at that Goose line, with the runtime's message. |

If the session does not start, see [DEBUGGING.md](DEBUGGING.md), "When
something goes wrong".

## 5. When something does not work

- **No colors for keywords, strings and comments** (only names colored,
  by the server): the TextMate bundle did not load. The plugin reloads
  TextMate's bundles when it is installed without a restart; if colors are
  still missing, restart the IDE. Otherwise **Help | Show Log in Finder**
  (Explorer, Files), open `idea.log` and search for
  `Goose TextMate bundle not found`. Installing the jar alone, rather than
  the zip, leaves the bundle out.
- **Colors but no errors, completion or hover**: the language server is not
  running.
  - A balloon saying no compiler was found: set **Compiler** (step 3).
  - The status bar widget shows Goose with an error: restart the server
    from the widget or from **Tools | Goose | Restart Language Server**.
  - Check **Start the language server** is on in the Goose settings.
  - In `idea.log`, look for `GooseLspClientDescriptor` lines: they give the
    exact command the plugin ran.
- **Imports not found** (errors on `import` lines): a library module's
  imports (`import crypto.bignum;` in gslib) resolve against the package's
  root. When the project folder is a Goose package (it has `name.goose`
  declaring `namespace name;`, as gslib has `gslib.goose`), the plugin has
  each open module checked as part of the package by itself. Open the
  package's folder as the project, not a folder inside it. For `import std`
  and other standard modules, set **Standard library** if the compiler
  cannot find it.
- **Errors only in the open file, not the rest of the program**: set
  **Entry files** so the server knows the whole program.
- **A compiler set in your shell profile is not found** when the IDE is
  started from the Dock: the plugin reads your login shell's environment,
  but a full path in **Compiler** always works.
- **The plugin does not load at all** (not listed, or listed as
  incompatible): the IDE is older than 2026.2, or it lacks the platform's
  LSP client (Android Studio, for one).

## 6. Trying changes to the plugin

To run the plugin without installing it, in a separate sandbox IDE:

```bash
./gradlew runIde
```

The sandbox has its own settings (in `.intellijPlatform/sandbox`) and asks
for a license or a trial the first time. Open a scratch project there, not
one you use in your normal IDE.

`./gradlew test` runs the plugin's tests; `-PgooseCompiler=/path/to/goose`
tells the language-server tests which compiler to use (they skip if they
find none).
