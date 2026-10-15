# Mod input blocking (D1 standard)

## Problem

BepInEx-Android mods draw their config UI with Unity IMGUI (`OnGUI`). The
host games drive their own UI through uGUI (`EventSystem` + `GraphicRaycaster`).
When a mod panel is open, touches on game UI areas are consumed by the game's
`EventSystem` first, so a tap meant for the mod panel can also trigger the
game underneath — and taps on game UI never reach IMGUI at all.

IMGUI-level consumption (`Event.Use`) cannot fix this: the game's EventSystem
raycast runs before IMGUI sees the event. Disabling the game's EventSystem
works but queues input, causing a burst of stale taps when it is re-enabled.

## Standard solution: D1 modal uGUI blocker

The supported approach is implemented in the shared mod contract assembly
(`ModShared/ModalBlocker.cs`, used by TheTowerMods and RevIdleMods). While a
mod's modal panel is visible, a transparent fullscreen uGUI `Image` sits on a
dedicated canvas and swallows every `EventSystem` raycast before any game UI
sees it:

- `Canvas.renderMode = ScreenSpaceOverlay`
- `Canvas.overrideSorting = true`, `sortingOrder = 32767` (maximum; observed
  games top out far below — The Tower's highest active game canvas is 16)
- `GraphicRaycaster` on the same GameObject (**required** — without it the
  canvas never participates in raycasts and touches fall through)
- Fullscreen transparent `Image` with `raycastTarget = true`
- `DontDestroyOnLoad`, toggled with panel visibility (`SetBlocked(bool)`)

IMGUI does not go through the EventSystem raycast path, so the mod panel
itself keeps working while the game behind it is fully blocked. Touches are
consumed at raycast time, so nothing queues up.

### Verified (2026-10-15, on-device)

- The Tower: blocker up + tap on a game button → no reaction; blocker down +
  same tap → game reacts. Scene survey: 131 canvases, max active game order
  16, one EventSystem, no UIToolkit usage in game code.
- RevIdle: blocker up/down follows the panel; scene survey: 5 canvases, max
  order 2, one EventSystem.

### Limits

D1 only blocks uGUI raycast input. It does **not** stop:

- games that poll touches directly (`Input.touches` / `Input.GetTouch`),
- games that drive input through UIToolkit (`UIDocument`),

If a game uses either, the fallback is a native input gate in the launcher
(D2). D3 (top overlay that forwards input to mods) was rejected: it forces
every mod UI to change its input structure.

### Build gotcha

`Canvas` and `RenderMode` live in `UnityEngine.UIModule.dll`, **not**
`UnityEngine.UI.dll` (`Image`/`GraphicRaycaster` are in the latter). Reference
both (Private=false); pull them from the device's `BepInEx/interop/` like the
other UnityEngine assemblies.

## Appendix: stale restart-alarm loop (launcher issue)

`BootstrapActivity` restarts the `:game` process via an `AlarmManager` alarm
(`RTC`, +300ms) + `System.exit(0)`. If a stale alarm from an earlier session
survives, the game process dies and recreates roughly every 7 seconds forever
(log: `Game already launched in this process. Restarting :game process...`).
Workaround: `adb shell am force-stop com.bepinex.android` clears the alarm,
then launch fresh. A robust fix would cancel any pending restart alarm (or
guard with a session token) before bootstrapping.
