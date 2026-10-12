<div align="center">

```
 ██████╗ ██████╗  █████╗ ██╗   ██╗███████╗████████╗██████╗ ██╗██╗  ██╗███████╗
██╔════╝ ██╔══██╗██╔══██╗██║   ██║██╔════╝╚══██╔══╝██╔══██╗██║██║ ██╔╝██╔════╝
██║  ███╗██████╔╝███████║██║   ██║███████╗   ██║   ██████╔╝██║█████╔╝ █████╗  
██║   ██║██╔══██╗██╔══██║╚██╗ ██╔╝╚════██║   ██║   ██╔══██╗██║██╔═██╗ ██╔══╝  
╚██████╔╝██║  ██║██║  ██║ ╚████╔╝ ███████║   ██║   ██║  ██║██║██║  ██╗███████╗
 ╚═════╝ ╚═╝  ╚═╝╚═╝  ╚═╝  ╚═══╝  ╚══════╝   ╚═╝   ╚═╝  ╚═╝╚═╝╚═╝  ╚═╝╚══════╝
```

**`// ORBITAL MECHANICS TERMINAL // HOLLYWOOD ACCEPTED //`**

![JAVA](https://img.shields.io/badge/JAVA-16%2B-39ff14?style=for-the-badge&labelColor=000000)
![SWING](https://img.shields.io/badge/UI-SWING-39ff14?style=for-the-badge&labelColor=000000)
![DEPS](https://img.shields.io/badge/DEPENDENCIES-ZERO-39ff14?style=for-the-badge&labelColor=000000)
![STATUS](https://img.shields.io/badge/STATUS-ONLINE-39ff14?style=for-the-badge&labelColor=000000)

</div>

<!-- Add a screenshot once you have one:  ![GravStrike](docs/screenshot.png) -->

```
╔══════════════════════════════════════════════════════════════════════════╗
║  FILE:      GRAVSTRIKE                                                   ║
║  OPERATOR:  MORGAN MCDONALD                                              ║
║  FILED:     2026-10-12                                                   ║
║  STATUS:    DECLASSIFIED FOR PUBLIC RELEASE                              ║
╚══════════════════════════════════════════════════════════════════════════╝
```

## `> BRIEFING`

GravStrike is a real-time 3D gravity simulator drawn like a 1980s intelligence agency terminal: black screen, phosphor green, chunky pixels, scanlines. Planets orbit a heavy sun in full 3D, and you can reshape the system live: change masses, place new bodies, rename and recolor them, or zoom into one through a military scope and destroy it with a missile.

It is pure Java with no libraries. Swing for the interface, a software 3D camera for the view, and `javax.sound.sampled` for audio, all synthesized in code.

## `> CAPABILITIES`

```
[■] N-BODY GRAVITY ........ full 3D, softened, semi-implicit Euler
[■] 3D CAMERA ............. orbit and zoom, perspective projection
[■] PIXEL RENDERING ....... low-res buffer, nearest-neighbor upscale, CRT scanlines
[■] DEPTH READOUT ......... floor grid, axes, dashed drop lines to the floor
[■] BODY DRAWER ........... slide-out panel: mass sliders, rename, recolor, remove
[■] 3-AXIS PLACEMENT ...... lock X, then Z, then Y to drop a new planet
[■] STRIKE MODE ........... zoom into a procedural planet, scope, fire, shatter
[■] SECURE LOGIN .......... access code, security token, fingerprint scan
[■] TERMINATE ............. dramatic countdown and CRT power-down
[■] SOUND ................. chiptune effects, ambient CRT hum, optional WAV overrides
[■] STAR FIELD ............ sparse background sky that drifts as you orbit
```

## `> BOOT SEQUENCE`

**Requirements:** JDK 16 or newer (the code uses `record`). Nothing else.

```bash
git clone <your-repo-url>
cd <your-repo-folder>
```

Open the folder in IntelliJ IDEA, make sure a JDK 16+ is selected, and run `Main`.

**Access credentials** (the terminal will ask for them):

```
STEP 1   ACCESS CODE ........ MOONROCK26
STEP 2   SECURITY CHECK ..... retype the random 5-character token shown
STEP 3   BIOMETRIC SCAN ..... hold SPACE (or press and hold the sensor pad)
```

Three wrong answers trigger a lockout. The code lives in `LoginWindow.java` as `ACCESS_CODE`. To skip the gate while developing, replace `new LoginWindow(Main::launch).setVisible(true);` with `launch();` in `Main.main`.

## `> FIELD CONTROLS`

| INPUT | ACTION |
|---|---|
| `DRAG` | Orbit the camera |
| `WHEEL` | Zoom in and out |
| `HOVER A PLANET` | Target brackets appear |
| `CLICK A PLANET` | Begin strike sequence |
| `MOUSE TO RIGHT EDGE` | Open the body drawer |
| `+ ADD PLANET` | Start 3-axis placement |
| `CLICK` (placing) | Lock the current axis |
| `RIGHT-CLICK` (placing / strike) | Step back / abort |
| `ESC` | Cancel placement or abort strike |
| `G` | Show or hide the graph (grid, axes, drop lines) |
| `M` | Mute or unmute sound |
| `CLICK NAME` (drawer) | Rename a body |
| `CLICK SWATCH` (drawer) | Recolor a body |

## `> OPERATION: PLANET STRIKE`

```
PHASE 1   TARGET ........ click a hovered planet, lock-on tones
PHASE 2   DIVE .......... frozen scene rushes past, ~80x zoom into the planet
PHASE 3   SCOPE ......... procedural terrain, mil-dot reticle, live LAT / LON
PHASE 4   FIRE .......... missile streaks in, impact, molten crater, crust cracks
PHASE 5   SHATTER ....... the planet breaks into glowing shards
PHASE 6   EXTRACT ....... zoom back out, planet gone, simulation resumes
```

Every planet gets its own terrain. It is generated per pixel from noise, with bump lighting, ordered dithering, contour lines, and craters. Right-click or `ESC` aborts safely before firing. Any body can be a target, including the sun.

## `> TECHNICAL NOTES`

- **Physics.** Pairwise Newtonian gravity with a softening term, `F = G·m₁·m₂ / (r² + ε)`, integrated with semi-implicit Euler on a fixed timestep.
- **Rendering.** The scene is drawn at `1/PIXEL` resolution with antialiasing off, then scaled up with hard edges. Text and UI widgets are custom-drawn so they stay crisp.
- **Placement.** The cursor is turned into a ray and intersected with the active axis, which is exact under perspective.
- **Audio.** Sounds are synthesized as square waves, sweeps and noise. Short sounds are pooled to avoid latency, and any sound can be replaced by dropping `resources/sounds/<name>.wav` into the project.
- **Font.** For the full effect, add a pixel font such as VT323 or Press Start 2P as `pixel.ttf` in the project root. Without it, a bold monospace is used.

## `> TUNING`

| CONSTANT | FILE | EFFECT |
|---|---|---|
| `Retro.PIXEL` | `Retro.java` | Pixel chunkiness (2 = finer, 4 = chunkier) |
| `Retro.SCANLINES` | `Retro.java` | CRT scanlines on or off |
| `World.G` | `World.java` | Gravity strength |
| `STAR_COUNT` | `SimulationPanel.java` | Background star density |
| `HUM_VOLUME` | `Sfx.java` | Ambient hum loudness |
| `S` | `StrikeView.java` | Strike-view pixel size (raise it if the zoom is slow) |

## `> FILE MANIFEST`

```
src/
├── Main.java               entry point and scene setup
├── LoginWindow.java        three-stage secure access terminal
├── World.java              bodies and the gravity step
├── Body.java               position, velocity, mass, color, trail
├── Camera.java             3D projection and mouse-ray math
├── Placement.java          three-axis placement state machine
├── SimulationPanel.java    scene rendering, camera input, hover and targeting
├── SidePanel.java          slide-out drawer: masses, names, colors, add/remove
├── OverlayPane.java        layers the drawer, button and strike view
├── StrikeView.java         zoom, scope, missile, shatter sequence
├── TerminateButton.java    big red pixel button
├── TerminateOverlay.java   shutdown countdown and CRT collapse
├── Retro.java              colors, font, and pixel-styled widgets
└── Sfx.java                synthesized sound effects and ambient hum
resources/
└── sounds/                 optional WAV overrides
```

## `> ROADMAP`

```
[ ] pause and time-scale controls
[ ] click-and-drag initial velocity arrows
[ ] collision merging
[ ] boot-up text crawl before the login screen
[ ] phosphor glow and screen curvature pass
```

---

<div align="center">

```
┌──────────────────────────────────────────────┐
│  AUTHOR      MORGAN MCDONALD                 │
│  DATE        2026-10-12                      │
│  CLASSIFIED  NO LONGER. ENJOY.               │
└──────────────────────────────────────────────┘
```

**`>> END OF FILE <<`**

</div>
