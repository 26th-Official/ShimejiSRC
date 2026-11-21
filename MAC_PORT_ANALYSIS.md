# macOS Support Analysis — ShimejiSRC

Date: 2026-11-19
Status: research/analysis only. No source or build changes were made for macOS.
Purpose: leave enough context for another agent (or a future session) to continue a Mac port without redoing the investigation.

---

## TL;DR

**Will the current build run on macOS? No — not as shipped.**

There is a dormant, upstream-derived Mac backend in `src_mac/`, but:

1. **It is not compiled.** `build.xml` only compiles `src`, `src_win`, `src_generic`, and `src_virtual`. The built jar contains zero `com/group_finity/mascot/mac` classes. On macOS, `NativeFactory` selects the `mac` factory, then fails with `ClassNotFoundException` when the first mascot is created.
2. **The bundled JNA is JNA 3.2.7 from 2010.** Its macOS native library is a universal `ppc / i386 / x86_64` binary with **no arm64 slice**, so it cannot load in a native Apple Silicon JDK. This is a hard blocker on M-series Macs.
3. **The Mac backend is old and minimal.** It tracks only the frontmost window through Carbon + Accessibility APIs, returns no window title, has no multi-monitor logic, and needs macOS Accessibility permission. It is not equivalent to the Windows environment implementation.
4. **The AI layer is partly portable, partly Windows-only.** Ollama chat, Whisper, microphone capture, screen capture, and Swing bubbles can work on macOS. WASAPI system-audio capture, Win32 window/agent control, volume/media keys, TempSensor/Cooler Boost, and NVIDIA telemetry are Windows-only.
5. **The current codebase has never validated the Mac path.** `git log -- src_mac` shows only the fork's initial upload; there is no Mac test, CI, packaging, or release artifact.

**Practical verdict:**

- **Intel Mac (x86_64):** a basic desktop pet is plausible after a port (compile `src_mac`, verify Carbon/AX on current macOS, grant Accessibility, use an x86_64 JDK 17). Expect degraded window interaction.
- **Apple Silicon (arm64):** not viable without replacing JNA, updating the Mac structures/APIs, and retesting the whole native layer.
- **AI companion features:** a useful subset can work; the Windows-only half must be stubbed or replaced.

---

## What was verified (not assumed)

Commands run in this checkout on Linux:

```bash
ant jar
# BUILD SUCCESSFUL
# target/Shimeji-ee.jar — 5.4 MB, 2474 entries
```

The Mac sources also compile in isolation against the project's existing JNA:

```bash
mkdir -p target/mac-classes
javac --release 17 -encoding UTF-8 \
  -cp "target/classes:lib/*" \
  -d target/mac-classes $(find src_mac -name '*.java')
# exit 0 — no compile errors
```

This only proves syntax/API compatibility with the current JNA. It does not prove the native libraries load on a real Mac.

The test suite passes on Linux (8/8) when `conf` is on the classpath. `ConfigurationSplashTest` needs `conf` for the `schema` resource bundle:

```bash
mkdir -p target/test-classes
javac --release 17 -encoding UTF-8 \
  -cp "target/classes:lib/*" \
  -d target/test-classes $(find test -name '*.java')

java -Djava.awt.headless=true \
  -cp "target/test-classes:target/classes:conf:lib/*" \
  com.group_finity.mascot.config.ConfigurationSplashTest
```

No macOS hardware was available. All runtime claims below are static analysis, not observed behavior.

---

## Current build/runtime facts

### Build excludes the Mac backend

`build.xml`:

```xml
<src path="src"/>
<src path="src_win"/>
<src path="src_generic"/>
<src path="src_virtual"/>
```

Confirmed:

```bash
unzip -l target/Shimeji-ee.jar | grep -c 'mascot/mac'
# 0
```

So the current jar cannot instantiate the Mac factory.

### NativeFactory selects Mac automatically

`src/com/group_finity/mascot/NativeFactory.java`:

```java
String subpkg = Main.getInstance().getProperties().getProperty("Environment", "generic");
if (subpkg.equals("generic")) {
    if (Platform.isWindows()) subpkg = "win";
    else if (Platform.isMac()) subpkg = "mac";
}
...
Class.forName(basepkg + "." + subpkg + ".NativeFactoryImpl")
```

On macOS with the default `Environment=generic`, it tries:

```text
com.group_finity.mascot.mac.NativeFactoryImpl
```

That class is not in the jar. Result: a `RuntimeException`/`ClassNotFoundException` during `Mascot` construction, before the mascot appears.

There is no way to force the generic factory on Mac through settings: the code converts `generic` to `mac` when `Platform.isMac()`. `Environment=virtual` selects a separate JFrame-based virtual desktop, not a desktop overlay.

### The Windows scanner is called unconditionally from the tick

`src/com/group_finity/mascot/Manager.java` calls:

```java
com.group_finity.mascot.win.WindowsEnvironment.ensureScanner();
```

on every tick, regardless of platform. On macOS this starts a daemon that repeatedly attempts Win32 `User32` calls; the errors are caught and logged at `FINE` inside `WindowsEnvironment.scanLoop()`, so it likely will not kill the process, but it is a platform leak and must be guarded (`if (Platform.isWindows())`) for a clean Mac build. It also means a Mac port should not assume the Windows environment class is inert.

`WindowsEnvironment` is compiled into the fat jar even on Mac, so the class itself exists; only the native calls fail.

### JNA 3.2.7 (2010) — the Apple Silicon blocker

`lib/jna.jar` manifest:

```text
Implementation-Version: 3.2.7 (b0)
Created-By: 11.0-b16 (Sun Microsystems Inc.)
```

Extracted macOS native library:

```text
com/sun/jna/darwin/libjnidispatch.jnilib:
Mach-O universal binary with 3 architectures:
  ppc_7400
  i386
  x86_64
```

No `arm64`. A native arm64 JDK on Apple Silicon will reject it (wrong architecture / library not found). Running an x86_64 JDK under Rosetta is theoretically possible, but it is not a normal supported setup and does not solve the rest of the Mac backend work.

JNA 3.2.7 is also far older than Java 17. It predates the module system and modern macOS releases; even on Intel Mac, it is a compatibility risk. A real port should move to JNA 5.x.

---

## What the Mac backend actually implements

`src_mac/` is 13 files / ~636 lines:

```text
mac/NativeFactoryImpl.java
mac/MacEnvironment.java
mac/MacTranslucentWindow.java
mac/jna/AXUIElementRef.java
mac/jna/AXValueRef.java
mac/jna/CFArrayRef.java
mac/jna/CFNumberRef.java
mac/jna/CFStringRef.java
mac/jna/CFTypeRef.java
mac/jna/CGPoint.java
mac/jna/CGSize.java
mac/jna/Carbon.java
mac/jna/ProcessSerialNumber.java
```

### `MacEnvironment`

- Loads `Carbon` via `Native.loadLibrary("Carbon")` in a static initializer.
- Uses Carbon Process Manager (`GetFrontProcess`, `GetProcessPID`) to find the frontmost app.
- Uses Accessibility (`AXUIElementCopyAttributeValue`) to get the focused window and its position/size.
- Supports only the **frontmost window** as `activeIE`.
- `getActiveIETitle()` returns `null`.
- Single-screen assumption: static screen width/height at class init; `getWorkArea()` returns `getScreen()`.
- Hardcodes a 22 px menu bar and a 100 px Dock reservation; not reliable on modern macOS/notched displays.
- `moveActiveIE` moves the frontmost window via the Accessibility API.
- `restoreIE` iterates "touched" processes and moves off-screen windows back.
- `refreshDockState` / `CFPreferencesCopyValue` are legacy Carbon/CoreFoundation calls.

Modern macOS requirements not handled:

- Accessibility permission must be granted to the running app/JVM, or AX calls fail (`kAXErrorAPIDisabled`).
- Screen Recording permission is required for `java.awt.Robot` screen capture.
- Input Monitoring may be required for global hotkeys (JNativeHook).
- Carbon Process Manager APIs are deprecated and may be unavailable or unreliable on current macOS.
- No multi-monitor, spaces, Stage Manager, or window-title support.

### `MacTranslucentWindow`

- Delegates to `com.group_finity.mascot.generic.NativeFactoryImpl` for the actual window.
- Wraps a `JWindow`, disables the macOS window shadow, and calls `setAlwaysOnTop`.
- The generic window uses `com.sun.jna.examples.WindowUtils` from `lib/examples.jar`, which is also from 2009 and compiled against old JNA. It has a `MacWindowUtils` path and is mostly AWT client properties, but it is a compatibility risk with a modern JNA or modern JDK.

### Mac transparency path

`GenericTranslucentWindow` uses:

- `WindowUtils.getAlphaCompatibleGraphicsConfiguration()`
- `WindowUtils.setWindowTransparent(...)`
- `WindowUtils.setWindowMask(...)`
- `sun.java2d.opengl=true`

This may work on macOS, but it is untested and depends on old `examples.jar` behavior. If a modern port replaces JNA, the old `examples.jar` may need to be replaced with a small native-free translucent window implementation using AWT per-pixel transparency (`setBackground(new Color(0, 0, 0, 0))` + an undecorated `JWindow`).

---

## Platform leakage elsewhere

These are the places to audit for a Mac build:

| Area | File / behavior | Mac impact |
|---|---|---|
| Tick | `Manager.tick()` → `WindowsEnvironment.ensureScanner()` | Unconditional Win32 scanner; guard it |
| Tick watchdog | `WindowsEnvironment.lastEnumWindowsNs` | Harmless read, but another Windows reference |
| Window scanning | `WindowsEnvironment` | Win32-only; Mac has a separate, minimal environment |
| Priority | `environment/ProcessPriorityUtil` | Guards on `os.name` containing `windows`; safe no-op |
| Sensors | `environment/CpuTempMonitor` | kernel32 battery read, `nvidia-smi`, `TempSensor.exe`; mostly caught/failing gracefully, but CPU/GPU temps unavailable |
| Fan control | `environment/FanController` | MSI Windows-only; should never activate without the CoolerBoost affordance |
| System audio | `assistant/WasapiLoopbackCapture` | Windows COM/WASAPI; `AudioTranscriptBuffer.start()` calls `new WasapiLoopbackCapture().open()` without a top-level guard |
| Voice | `assistant/VoiceCommandListener` | Java Sound microphone path can work on macOS with mic permission |
| Screen capture | `assistant/AssistantSupport` | `java.awt.Robot`; needs Screen Recording permission |
| Volume/media keys | `assistant/SystemControl` | Reflective `WindowsSystemControl`; returns `null` on Mac |
| Window control / agent | `assistant/WindowControl`, `AgentTools`, `AgentLayout`, `AgentCarry` | Reflective Win32 implementation; returns `null`/no-op on Mac |
| Console readout | `assistant/ConsoleReadout` imports `win.jna.User32` | Only used by opt-in mascots (e.g. 2B); avoid on Mac |
| Tray | `Main.createTrayIcon` | `SystemTray` works on macOS; menu-bar behavior differs |
| Hotkeys | `HotkeyManager` / JNativeHook | Works on macOS but needs Input Monitoring permission |
| Video areas | `VideoAreaServer` | Pure Java HTTP server; can work; surface geometry still depends on environment support |
| AI chat | `OllamaClient` | HTTP to local Ollama; works on macOS |
| Whisper | `whisper_server.py` / `WhisperProcess` | Python/faster-whisper; works on macOS CPU, but not bundled |
| Memory/drive index/situation | `assistant/*` | Mostly pure Java; file paths and home directory assumptions need checking |

### Important assistant startup issue

In `Mascot.tick()` (and `AssistantReactions.fireAudioReaction()`), when assistant audio is active:

```java
audioBuffer = new AudioTranscriptBuffer();
if (!audioBuffer.start()) { ... }
```

`AudioTranscriptBuffer.start()` does:

```java
final WasapiLoopbackCapture w = new WasapiLoopbackCapture();
if (w.open()) { ... }
```

`WasapiLoopbackCapture.open()` touches `Ole32.INSTANCE` immediately. On macOS this can throw `UnsatisfiedLinkError` / `ExceptionInInitializerError`, not merely return `false`, because there is no try/catch around the COM load. A Mac port must guard this class by OS or catch the linkage failure and skip system-audio capture. This matters only when assistant audio/reactions are enabled, but a user will enable them.

---

## AI layer on macOS: realistic feature matrix

| Feature | Mac outlook |
|---|---|
| Core pet: sprites, physics, drag/throw, breeding, tray, XML behaviors | Plausible after porting native window/environment |
| Local Ollama chat / personality / memory | Should work (Ollama supports macOS) |
| Chat bubbles / input dialog / settings UI | Should work (Swing) |
| Microphone / name triggers | Plausible with mic permission |
| Whisper transcription | Plausible if Python + faster-whisper installed; not bundled |
| Screen glances / vision | Plausible with Screen Recording permission; window title context is missing |
| System audio reactions | **No** without a CoreAudio tap/loopback replacement; current WASAPI path cannot run |
| Agent window control / window carry / monitor moves | **No** without a Mac `WindowControl` implementation using Accessibility |
| Agent file moves/tidy | Mostly Java NIO; could work, but paths/platform assumptions need testing |
| Volume/media keys | **No** without a Mac `SystemControl` implementation |
| CPU/GPU temps, Cooler Boost, fans | **No** (Windows/MSI/LibreHardwareMonitor-specific) |
| Global hotkeys | Plausible, with Input Monitoring permission |
| Browser video surfaces | Server can run; geometry must come from a Mac environment implementation |
| Mascot Shop | Mostly Java/download/unzip; likely works |

---

## Port strategy (suggested order)

### Phase 0 — Make the Mac backend compile into the product

1. Add `<src path="src_mac"/>` to the `compile` target in `build.xml` (or a separate `mac` target).
2. Build and confirm `target/Shimeji-ee.jar` contains `com/group_finity/mascot/mac/*`.
3. Add a runtime guard in `Manager.tick()` around `WindowsEnvironment.ensureScanner()`:
   ```java
   if (com.sun.jna.Platform.isWindows()) {
       com.group_finity.mascot.win.WindowsEnvironment.ensureScanner();
   }
   ```
   Also guard the TickWatch `lastEnumWindowsNs` read if desired.
4. Guard `AudioTranscriptBuffer.start()` / `WasapiLoopbackCapture` for non-Windows, returning `false` cleanly.

This gets a Mac build to the point where it can choose `MacEnvironment` instead of throwing `ClassNotFoundException`.

### Phase 1 — Decide native strategy

Choose one:

**A. Keep old JNA 3.2.7, Intel-only**

- Use an x86_64 JDK 17.
- Keep `lib/jna.jar` and `lib/examples.jar`.
- Verify `Carbon` loads and AX works on the target macOS.
- This is the shortest path for an Intel Mac but not for Apple Silicon.

**B. Upgrade to JNA 5.x (recommended for any modern Mac)**

- Replace `lib/jna.jar` with JNA 5.14+ (arm64 + x86_64).
- Recompile `src_mac`; add `getFieldOrder()` / `@FieldOrder` to `CGPoint`, `CGSize`, and `ProcessSerialNumber` (JNA 5 requires field order for `Structure`).
- Replace `Native.loadLibrary` calls with `Native.load` (or keep the deprecated method if it still compiles).
- Audit `NativeLibrary.getProcess()` and `getGlobalVariableAddress()` against JNA 5.
- Replace `examples.jar` `WindowUtils` with a native-free AWT translucent window, or verify the old class still works with JNA 5.
- Point macOS framework loading at `ApplicationServices` (Accessibility/AX) and/or `CoreGraphics` where appropriate, rather than relying on Carbon re-exports.

This is the only sane path for Apple Silicon.

### Phase 2 — Replace or modernize `MacEnvironment`

Recommended implementation pieces:

- **Frontmost app/window:** use `NSWorkspace` / Cocoa via JNA, or `CGWindowListCopyWindowInfo` for the window list, plus AX for titles.
- **Window title:** implement `getActiveIETitle()` and `getForegroundWindowTitle()`; they currently return `null`/empty, which starves AI screen reactions and agent window matching.
- **Permission check:** call `AXIsProcessTrustedWithOptions` (with prompt) at startup; show a clear message if Accessibility is not granted.
- **Multiple monitors:** enumerate `NSScreen.screens` or `CGGetActiveDisplayList`; stop hardcoding 22 px menu bar / 100 px Dock.
- **Spaces / fullscreen / Stage Manager:** at least avoid treating invisible windows as surfaces.
- **Window list:** support more than the frontmost window if the pet is meant to walk on multiple windows.
- **Graceful degradation:** if AX is unavailable, fall back to screen-floor-only physics rather than failing startup.

### Phase 3 — Packaging and permissions

macOS does not treat a random `java -jar` process like a normal app for TCC permissions. Options:

- Package with `jpackage` into a `.app` (or use a small launcher) so Accessibility/Screen Recording/Input Monitoring can be granted to a stable app identity.
- Add `Info.plist` usage descriptions where applicable.
- Document the permission steps:
  - System Settings → Privacy & Security → Accessibility
  - Screen Recording
  - Input Monitoring (global hotkeys)
  - Microphone
- Consider `-Dapple.awt.UIElement=true` if the app should avoid a Dock icon.
- Build a Mac-specific distribution; the current release pipeline is Windows/Launch4j/Inno Setup only.

### Phase 4 — AI feature gating

- Guard WASAPI and any Win32-only tool behind `Platform.isWindows()`.
- Add Mac `SystemControl` / `WindowControl` implementations (Accessibility/CoreAudio) or return clear “not supported on this platform” responses.
- Disable Cooler Boost/TempSensor/NVIDIA-specific ambient features on Mac.
- Verify drive index paths (`Downloads`, `Documents`, etc.) on macOS; they are likely hardcoded Windows-style somewhere.
- Test Ollama endpoint/model naming on macOS; the HTTP layer should be unchanged.

---

## Files a port will touch

Minimum:

- `build.xml` — add `src_mac`; possibly add a `mac` target.
- `src/com/group_finity/mascot/Manager.java` — guard Windows scanner.
- `src/com/group_finity/mascot/NativeFactory.java` — optionally make platform selection explicit / failure-tolerant.
- `src_mac/.../MacEnvironment.java` — modernize APIs, permissions, monitors, titles.
- `src_mac/.../jna/*.java` — field order for JNA 5; framework names.
- `src_generic/.../GenericTranslucentWindow.java` and/or `lib/examples.jar` — transparency path.
- `src/com/group_finity/mascot/assistant/AudioTranscriptBuffer.java` / `WasapiLoopbackCapture.java` — OS guard.
- `src/com/group_finity/mascot/assistant/SystemControl.java` / `WindowControl.java` — Mac implementations or no-op gating.
- `conf/settings.properties` — document/validate `Environment` and Mac defaults.
- Packaging files — `.app`/`jpackage` or a Mac launch script.

---

## Test plan for a Mac port

1. Build jar with `src_mac`; confirm Mac classes are present.
2. Launch from a directory containing `conf/` and `img/`:
   ```bash
   java -jar Shimeji-ee.jar
   ```
3. Confirm:
   - Mascot appears, transparent, always on top.
   - It falls to the screen floor and walks; drag/throw works.
   - Tray menu opens; Dismiss/Quit works.
   - Right-click behavior menu works.
   - No repeating Windows scanner errors.
4. Grant Accessibility; confirm frontmost window is detected and the mascot can stand on/climb it.
5. Grant Screen Recording; confirm chat screen glance works.
6. Grant Microphone; confirm voice trigger path does not crash.
7. Enable assistant audio; confirm it degrades cleanly (no system-audio capture) instead of throwing.
8. Verify multi-monitor behavior or document single-monitor limitation.
9. Test Intel and Apple Silicon separately; record JNA/JDK architecture.

---

## Open questions / decisions for the next agent

1. **Target hardware:** Intel only, Apple Silicon only, or both? This decides whether JNA 5 is mandatory.
2. **Target macOS version:** Sonoma/Sequoia/Tahoe-era? Carbon API availability and TCC behavior differ.
3. **Scope:** “pet runs on Mac” vs “full AI companion parity.” The latter is a much larger port.
4. **JNA strategy:** upgrade to 5.x vs stay on 3.2.7 for an Intel-only experiment.
5. **Transparency strategy:** keep old `examples.jar` vs replace with AWT-native per-pixel transparency.
6. **Packaging:** bare jar, `.app` via `jpackage`, or a launcher script?
7. **Window interaction scope:** frontmost window only (current Mac backend) vs full window enumeration.
8. **AI gating:** stub Windows-only tools with user-facing “not available on macOS” messages, or implement Mac equivalents?

---

## Key evidence references

- `build.xml` — compile source paths exclude `src_mac`.
- `src/com/group_finity/mascot/NativeFactory.java` — `generic` → `mac` on macOS.
- `src_mac/com/group_finity/mascot/mac/NativeFactoryImpl.java` — delegates rendering to generic, environment to `MacEnvironment`.
- `src_mac/com/group_finity/mascot/mac/MacEnvironment.java` — Carbon + AX, frontmost window only, no titles, single screen.
- `src_mac/com/group_finity/mascot/mac/MacTranslucentWindow.java` — generic JWindow + shadow disable.
- `src/com/group_finity/mascot/Manager.java` — unconditional `WindowsEnvironment.ensureScanner()`.
- `src_win/com/group_finity/mascot/win/WindowsEnvironment.java` — Win32-only scanner.
- `src/com/group_finity/mascot/assistant/WasapiLoopbackCapture.java` — Windows COM/WASAPI.
- `src/com/group_finity/mascot/assistant/AudioTranscriptBuffer.java` — starts WASAPI without a platform guard.
- `lib/jna.jar` — JNA 3.2.7; darwin native lib is ppc/i386/x86_64, no arm64.
- `lib/examples.jar` — 2009 JNA examples, `WindowUtils` transparency helper.
- `CLAUDE.md` — architecture notes; line 54 says `NativeFactory` selects Windows/Mac/Generic/Virtual, line 55 says `MacEnvironment: JNA → Carbon/Cocoa` (but it is not built).

---

## Bottom line for a future agent

Do **not** assume “there is a Mac backend, so it works.” It is an unbuilt, untested 2010-era backend. The fastest useful experiment is an **Intel Mac + x86_64 JDK 17 + add `src_mac` to the build + guard the Windows scanner**, then see how far `MacEnvironment` gets on current macOS. For **Apple Silicon**, budget for a real JNA 5 upgrade and a modern Accessibility/CoreGraphics environment implementation; that is a port, not a configuration change.
