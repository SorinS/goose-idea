# Debugging Goose programs

The plugin debugs Goose programs with IntelliJ's own Debug Adapter Protocol
client (the platform module `intellij.platform.dap`, in the 2026.2 IDEs).
You can set line breakpoints in `.goose` files, step, and use the call stack,
variables, watches, conditional breakpoints and logpoints. It stops on Goose
runtime errors (a failed assert, a bounds or overflow check). The debugger
behind the client is LLDB, through one of two adapters:

- **goose-dap**, the Goose proxy in front of lldb-dap, from
  [goose-debugger](https://github.com/SorinS/goose-debugger) (`bin/goose-dap`
  in its release packages). The IDE then shows Goose: `geo::area` in the
  call stack rather than `geo_area_g3`, the locals in scope under their
  Goose names (`total`, not `total_g` and the temporaries), Goose names in
  watches, and a stop on a runtime error at the Goose line that failed.
  Since goose-debugger 0.3.0 it is a native program, written in Goose: it
  needs nothing but lldb-dap. (Up to 0.2.0 it was a Python script.)
- **lldb-dap**, LLVM's adapter, used as it is. The plugin has it load
  `goose_lldb.py` (shipped with the plugin), so values read as Goose values
  (`Point { x: 3, y: -4 }`, `Color.Green`). Frames, locals and expressions
  are those of the generated C: `main_g`, `total_g`, `t0`…`tN`.

Stepping goes one Goose line at a time with either adapter.

## What you need

- IntelliJ IDEA, GoLand or CLion 2026.2.
- A Goose compiler that has `-g` (`goose build --debug-info`). The simplest
  is goose-debugger's release package for your platform (goose-debug v0.3.0,
  for macOS arm64, Linux x86-64 and Windows x64): unpack it anywhere and set
  its `bin/goose-debug` as the compiler in **Settings | Languages &
  Frameworks | Goose**, with the standard library left empty. The package
  has its own standard library, and `goose-dap` beside the compiler. A
  compiler you built with the debugger patches works too (goose.git's
  `build-debug/goose-debug`); run `goose help build`, which has to list
  `--debug-info`.
- lldb-dap:
  - macOS: the Command Line Tools have one
    (`/Library/Developer/CommandLineTools/usr/bin/lldb-dap`;
    `xcode-select --install` installs them).
  - Linux: the `lldb` package, or LLVM's (`lldb-dap`, `lldb-vscode` or
    `lldb-dap-NN`).
  - Windows: LLVM's installer (`C:\Program Files\LLVM\bin\lldb-dap.exe`).
- For goose-dap: nothing more. It comes in goose-debugger's package, and
  finds lldb-dap itself.

## Setting it up

**Settings | Languages & Frameworks | Goose**, below the language server
options:

- **Debug adapter.** One of these:
  - *Auto* (the default) uses goose-dap when it finds one, else lldb-dap;
  - *goose-dap* or *lldb-dap* uses that one only.
- **Adapter path.** The adapter's executable, or a command on PATH. When it is
  empty, the plugin searches:
  - goose-dap: beside the Goose compiler, on PATH, in `~/.local/bin`,
    `/opt/homebrew/bin` and `/usr/local/bin`;
  - lldb-dap: on macOS the Command Line Tools' and Xcode's, then PATH,
    Homebrew's LLVM and `xcrun -f lldb-dap`; on Linux PATH (`lldb-dap`,
    `lldb-vscode`, then the newest `lldb-dap-NN`) and `/usr/lib/llvm-NN/bin`;
    on Windows PATH and `C:\Program Files\LLVM\bin`.

  Under *Auto*, a path whose file name starts with `goose-dap` counts as
  goose-dap, and any other path as lldb-dap. The line below the field shows
  the adapter that will be used.
- **Stop on Goose runtime errors** (on by default). goose-dap stops at the
  Goose line that failed, with the runtime's message. With lldb-dap the
  plugin sets a breakpoint on the runtime's `gs_abort`: the stop is in
  `gs_abort`, and the Goose frames that led to it are below it.

With goose-debugger's package as the compiler, *Auto* finds its
`bin/goose-dap` (`bin\goose-dap.exe` on Windows) beside the compiler. With
another compiler, note that an IDE started from the Dock or Finder does not
have your shell's PATH; to let *Auto* find goose-dap, link it somewhere the
plugin looks:

```
ln -s /path/to/goose-debug-v0.3.0-…/bin/goose-dap ~/.local/bin/goose-dap
```

goose-dap resolves the link and finds its own files (`../lldb/goose_lldb.py`
in the package). You can also set the **Adapter path** to it.

## Debugging

1. Open a program (a `.goose` file with `fn main`) and click in the gutter
   beside a line to set a breakpoint. Breakpoints go on lines that are not
   blank or a `//` comment. On a line without code (a `{` alone, say), the
   adapter moves the breakpoint to the next line with code when the session
   starts, and the IDE shows it there.
2. Start Debug:
   - **Debug 'file.goose'** in the editor's or the Project view's context
     menu;
   - the Run/Debug icon in the gutter of the file's first line (the TextMate
     grammar gives the IDE no finer structure, so it cannot go beside
     `fn main`);
   - or the Debug button with a Goose run configuration selected.

   Debug applies to configurations whose action is *Run with JIT*. *Check*,
   *Build* and *Generate C* have no Debug. Plain Run still runs the JIT.
3. Debug first builds the program with
   `goose build -g [--stdlib dir] -o <exe> file.goose` in the configuration's
   working directory. The executable goes to the IDE's system directory
   (`<system>/goose/debug/<project>/<name>-<hash>`), not into the project.
   If the build fails, the compiler's output opens in a Run tool window tab
   with the errors linked to the source, a notification quotes the first
   errors, and no session starts. A program built by the JIT (`goose run`)
   cannot be debugged, so Debug always builds.
4. The adapter starts the executable with the configuration's program
   arguments, working directory and environment variables. The configuration
   editor now has an *Environment variables* field, which Run with JIT uses
   too. The program's output appears in the Debug tool window's console.

In the session:

- **Stepping.** Step Over, Step Into, Step Out, Run to Cursor, Pause and
  Resume go by Goose lines.
- **Frames and Variables.** With goose-dap you see Goose names and only the
  locals in scope; goose-dap marks the C `main` and system frames as subtle, which the IDE should grey out. With
  lldb-dap you see the C names, temporaries and the runtime's globals.
- **Watches, hover and Evaluate.** With lldb-dap expressions are C, in the C
  names: `total_g + 1`, `u_g.pos_g.x_g`. With goose-dap you write Goose
  names and field paths (`total + 1`, `u.pos.x`), and goose-dap rewrites
  them to the C ones. In both, C operators work and Goose-only syntax does
  not.
- **Conditional breakpoints and logpoints.** Right-click a breakpoint. The
  condition is an expression as above.
- **Runtime errors and crashes.** A failed assert or check stops as
  described under *Stop on Goose runtime errors*. A crash (say a null write
  in C code from `--include`) stops where it happened, with the signal as the
  reason. Resume lets the runtime print its error and exit.

The breakpoints dialog (**Run | View Breakpoints**) lists a "Goose runtime
errors and crashes" entry. The platform's DAP client needs it to show any
stop that the adapter reports as an exception. Leave it enabled. To turn off
stopping on runtime errors, use the setting above.

## When something goes wrong

- *"This Goose compiler cannot build for debugging"*: the compiler has no
  `-g`. Point the settings at a patched compiler.
- *"No debug adapter found"*: install lldb-dap, or set the adapter path.
- *"goose build -g failed"*: the compiler's output is in the Run tool window.
- To see every DAP message, set the registry key `dap.message.trace.dir`
  (**Help | Find Action | Registry…**) to a directory. The platform writes a
  trace of each session there. goose-dap also logs both directions when
  `GOOSE_DAP_LOG` names a file in the IDE's environment.

## Limits

- **The DAP platform API is experimental.** Every class in
  `intellij.platform.dap` is `@ApiStatus.Experimental`, and no bundled plugin
  uses it yet. A later 2026.x IDE may change it. The plugin verifier reports
  the experimental use. The plugin keeps to `DebugAdapterDescriptor`,
  `DebugAdapterSupportProvider`, `DapLaunchArgumentsProvider`,
  `DapBreakpointsDescription` and `CommandLineDebugAdapterHandle`.
- **No input.** The platform has no `runInTerminal` support, so a program
  that reads stdin cannot be given input from the IDE.
- **LLDB commands** (`goose-locals`, `bt`) cannot be typed in the IDE.
  The platform's Evaluate sends expressions without a REPL context, and
  lldb-dap then evaluates them as C.
- **Windows.** goose-dap and goose-debugger's package are tested there (LLVM's
  lldb-dap reading the MSVC PDB), but the plugin has not been run on
  Windows yet. LLVM's lldb needs a Python installed for `goose_lldb.py`;
  without one, values show as their C.
- **Breakpoint lines** are not checked against the line table while you set
  them. The adapter moves or rejects them when the session starts.

## goose_lldb.py

The formatters ship in the plugin as `lldb/goose_lldb.py`, beside
`textmate/`. The repository keeps a copy in `lldb/` so that a build needs
nothing outside it. To refresh it from the debugger repository:

```
./gradlew syncGooseLldb                                    # ~/Dev/Goose/goose-debugger.git/lldb/goose_lldb.py
./gradlew syncGooseLldb -PgooseLldbSource=/path/goose_lldb.py
```

goose-dap does not use this copy: it loads its own.

## Tests

`./gradlew test` runs the debugger's tests headless, in a test IDE:

- `GooseDebugAdapterTest`: finding the adapter, the launch arguments for both
  adapters, the build command line;
- `GooseDebuggerPluginTest`: the breakpoint types and the lines they take,
  that the DAP runner takes Debug and not Run (nor Check, Build or Generate
  C), the launch arguments from a configuration, that goose_lldb.py ships, a
  compiler without `-g` refused, and the settings and the configuration's
  environment stored and read back;
- `GooseDebugSessionTest`: whole Debug sessions through the platform's DAP
  runner, `goose build -g` and the real adapter. With lldb-dap it checks a
  line breakpoint hit twice with `i_g` at 0 then 1, Goose values from the
  shipped goose_lldb.py (`p_g` reads `Point { x: 3, y: -4 }`), the stop in
  `gs_abort` on a failed assert, and a compile error ending the session.
  With goose-dap it checks Goose names in the stack and in expressions
  (`total + i + 1`), and the stop at `geo::area` (geo.goose:5) on a failed
  assert.

The session tests run only when they find what they need, and print why when
they skip:

```
./gradlew test \
  -PgooseDebugCompiler=$HOME/Dev/Goose/goose.git/build-debug/goose-debug \
  -PgooseDap=$HOME/Dev/Goose/goose-debugger.git/dap/goose-dap \
  -PlldbDap=/Library/Developer/CommandLineTools/usr/bin/lldb-dap   # optional; found by default
```

Without `-PgooseDebugCompiler` they use the compiler the plugin finds, if it
has `-g`. The goose-dap tests need `-PgooseDap`.

## Trying it in a sandbox IDE

The tests cover the session itself. The editor side has to be tried by hand:
the gutter, the Debug tool window, the build-output tab and the settings
page.

1. `./gradlew runIde` (with `JAVA_HOME` set to the IDE's JBR, as in
   `plugin_build.sh`). A sandbox IDE starts with the plugin installed. To try
   the release zip instead, use **Settings | Plugins | ⚙ | Install Plugin
   from Disk…** with `build/distributions/goose-idea-<version>.zip` in that
   sandbox.
2. Open a directory with Goose programs, for instance
   `~/Dev/Goose/goose-debugger.git/tests`.
3. In **Settings | Languages & Frameworks | Goose**, set the compiler to
   `~/Dev/Goose/goose.git/build-debug/goose-debug`. Leave the debug adapter on
   *Auto* and check what the line under **Adapter path** says. Then set the
   path to `~/Dev/Goose/goose-debugger.git/dap/goose-dap` and check again.
4. Open `area.goose` and click the gutter at line 9
   (`total = total + area(i, 2);`).
5. Right-click in the editor and choose **Debug 'area.goose'**. The session
   should stop at line 9. Check:
   - the Frames list (goose-dap: `main`, then greyed `main (C)` and `start`);
   - Variables (goose-dap: `total = 0`, `i = 0`);
   - a watch on `total + i`;
   - Step Into, which goes to `area` at line 2;
   - Step Out, then Resume twice. The console shows `6`.
6. Open `backtrace/assert.goose` and Debug it. The session should stop on the
   failed assert in `geo.goose` (goose-dap) or in `gs_abort` (lldb-dap).
7. Put a syntax error in a file and Debug it. A "Build …" tab should open
   with the compiler's errors linked to the source, and a notification
   should appear.
8. Switch the adapter to *lldb-dap* and repeat steps 5 and 6. The names are
   now C ones; `p_g` in `lldb/test/structs.goose` at line 24 reads
   `Point { x: 3, y: -4 }`.
9. Check that plain **Run** of a program still runs the JIT, with no build
   step and no debugger.
