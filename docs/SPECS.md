# Unified Technical Specification Sheet: Modular Audio Workstation Mod

## Still unimplemented (from this doc)

Parts of the Strudel-comparison gap analysis ([FUTURE-WORK.md](FUTURE-WORK.md#inception-gap-analysis)) have landed: `alternate`, `probability` and `polymeter`; note names, `scale_sequence`, `transpose` and `chord`; `sample_slice`, reversed playback and sustained loops. Still missing:

- Pitch: a continuous `ScaleQuantizer` node.
- Sampling: live slice-index modulation and time stretching.
- Environmental modulator nodes: Day/Night cycle, Weather/Rain intensity, Proximity, Biome/Altitude.
- Physical pattern-archiving item (Vinyl/Punch Card disc) for burning/loading patches in survival.

This specification freezes all technical, algorithmic, architectural, and visual requirements for the mod.

---

## 1. System Identity & Scope

* **Target Names:** *Brioche*, *Canelé*, *Turnover*, *Syncope*, or *Polycycle*.
* **Runtime Platform:** Minecraft (Fabric / Architectury Loom, Java 21).
* **System Boundaries:**
* **Strict Audio Decoupling:** No dependency on Minecraft redstone logic, block state schedules, or the 20 TPS server tick loop.
* **Zero External Native Binaries:** Relies exclusively on platform-native LWJGL bindings (`lwjgl-openal`, `lwjgl-stb`) bundled directly with Minecraft.
* **Thread Safety:** Thread-decoupled rendering, editing, compiling, and DSP audio evaluation.



---

## 2. Technical Stack & Dependencies

| Layer | Implementation Choice | Integration Vector | Rationale |
| --- | --- | --- | --- |
| **Loader / Mod Core** | **Fabric Loader** | `fabric-loom` | Native mixin injection, minimal server footprint, clean access to client sound classes. |
| **Logic & Math Core** | **Plain Java 21 (`:core-engine`)** | Decoupled subproject | Fully testable via headless JUnit without initializing Minecraft or LWJGL contexts. |
| **Audio Driver** | **LWJGL 3 OpenAL (`AL10`, `ALC10`)** | Bundled MC OpenAL context | Hardware-accelerated 3D spatial attenuation (`AL_POSITION`) with zero third-party dynamic libraries. |
| **Sample Decoder** | **LWJGL STB (`stb_vorbis`)** | Native direct memory bridge | Rapid mono/stereo `.ogg` decompression straight into off-heap direct `ByteBuffer` memory. |
| **DSP Mixing Engine** | **Hand-rolled Pure Java DSP** | Single streaming source loop | Eliminates the 32-channel voice cap; supports arbitrary polyphony, dynamic biquad sweeps, and soft clipping. |
| **Timing Driver** | **`LockSupport.parkNanos` + `System.nanoTime**` | Dedicated high-priority worker | Sub-millisecond execution precision immune to coarse OS timer intervals. |
| **Serialization** | **Mojang `Codec` + `Gson**` | Data fixers & clipboard | Standardized NBT chunk persistence and compressed Base64 patch sharing. |
| **Networking** | **Fabric `ServerPlayNetworking**` | `CustomPayload` packets | Low-overhead AST synchronization across dedicated servers. |

---

## 3. Core Engine Architecture

```
 ┌──────────────────────────────────────────────────────────┐
 │ GUI Interaction Canvas / Block Entity State (Main Thread) │
 └────────────────────────────┬─────────────────────────────┘
                              │
                    Compiles on change (debounced 50ms)
                              │
                              ▼
        ┌───────────────────────────────────────────┐
        │   AtomicReference<CompiledPatternRoot>    │
        └─────────────────────┬─────────────────────┘
                              │
               Lock-free read on buffer boundaries
                              │
                              ▼
 ┌──────────────────────────────────────────────────────────┐
 │          Audio Scheduler Thread (High Priority)          │
 │  - Continuous Cycle Clock: c(t) = c_0 + Δt * (BPM / 240) │
 │  - Functional Pattern Evaluation over TimeArc [t0, t1)   │
 │  - Active Voice Pool Management (32–64 Virtual Voices)   │
 │  - DSP: PolyBLEP Oscillators, Biquad IIR Low-Pass Filter │
 │  - Soft Limiting / Saturation: y = tanh(x)               │
 └────────────────────────────┬─────────────────────────────┘
                              │
            Fills interleaved 16-bit Stereo PCM
                              │
                              ▼
 ┌──────────────────────────────────────────────────────────┐
 │         OpenAL Hardware Streaming (Ring Queue)           │
 │  - alSourceQueueBuffers / alSourceUnqueueBuffers         │
 │  - 3 x 512-sample hardware buffers (~11.6ms each)        │
 │  - Positional Falloff via AL_POSITION (or Headphone Bus) │
 └──────────────────────────────────────────────────────────┘

```

---

## 4. Mathematical Engine Specifications

### Time Representation

Time is modeled as a continuous real number representing elapsed musical cycles:


$$t \in \mathbb{R}_{\ge 0}$$

One cycle represents a complete measure (default $4/4$ bar). Progression rate relative to wall-clock time is:


$$\frac{dc}{dt} = \frac{\text{BPM}}{60 \times 4}$$

### Core Primitives & Interfaces

*(Implemented in `:core-engine` as `Arc`, `Event`, `Tone`, and `SampleVoice`; early prototype record shapes are shown below for architectural reference)*

```java
public record TimeArc(double start, double end) {
    public TimeArc {
        if (start > end) throw new IllegalArgumentException("Invalid arc: " + start + " > " + end);
    }
    public double duration() { return end - start; }
    public TimeArc intersect(TimeArc other) {
        double s = Math.max(this.start, other.start);
        double e = Math.min(this.end, other.end);
        return s < e ? new TimeArc(s, e) : null;
    }
    public TimeArc scale(double factor) { return new TimeArc(start * factor, end * factor); }
    public TimeArc shift(double delta) { return new TimeArc(start + delta, end + delta); }
}

public record SoundEvent(
    TimeArc whole,
    TimeArc part,
    String assetUri,
    float pitchRatio,
    float velocity,
    float cutoffHz,
    float pan
) {}

@FunctionalInterface
public interface Pattern {
    List<SoundEvent> query(TimeArc arc);
}

```

### Euclidean Generator (Bresenham-Form Bjorklund)

For $n$ steps, $k$ pulses, and rotational offset $r$, step index $i \in [0, n - 1]$ evaluates to active if:


$$\text{isHit}(i) = \left(\left( (i + r) \bmod n \times k \right) \bmod n\right) < k$$

---

## 5. DSP & Audio Output Pipeline

* **Output Format:** $48,000\text{ Hz}$, 16-bit Signed Integer PCM, Interleaved Stereo.
* **Buffer Strategy:** 3-Buffer OpenAL Hardware Ring Queue.
* **Frame Chunk Size:** $512\text{ samples}$ ($\approx 11.61\text{ ms}$).
* **PolyBLEP Anti-Aliasing (Sawtooth):**

$$\text{polyBlep}(t, dt) = \begin{cases} \frac{t}{dt} + \frac{t}{dt} - \left(\frac{t}{dt}\right)^2 - 1.0 & 0 \le t < dt \\ \frac{t - 1.0}{dt} + \left(\frac{t - 1.0}{dt}\right)^2 + 1.0 & 1.0 - dt < t \le 1.0 \\ 0.0 & \text{otherwise} \end{cases}$$


$$\text{saw}(t) = (2t - 1.0) - \text{polyBlep}(t, dt)$$


* **Resonant Low-Pass Filter:** Direct Form I 2nd-order Biquad IIR:

$$y[n] = b_0 x[n] + b_1 x[n-1] + b_2 x[n-2] - a_1 y[n-1] - a_2 y[n-2]$$


* **Output Stage Limiter:** Hyperbolic tangent soft saturation applied per sample:

$$y = \tanh(x)$$



---

## 6. Node Graph & Data Schema

### Port Definitions

* `PATTERN` (Cyan, `#00E5FF`): Functional pattern references (`Pattern`).
* `MOD_FLOAT` (Lime, `#A6FF00`): Dynamic parameter signals ($[-1.0, 1.0]$ or $[0.0, 1.0]$).
* `TRIGGER` (Amber, `#FFAA00`): Discrete clock/reset pulse triggers.

### JSON Patch Schema (Clipboard & NBT Storage)

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "SequencerPatch",
  "type": "object",
  "required": ["version", "tempoBpm", "nodes", "edges"],
  "properties": {
    "version": { "type": "integer", "const": 1 },
    "tempoBpm": { "type": "number", "minimum": 20.0, "maximum": 999.0 },
    "swing": { "type": "number", "minimum": 0.0, "maximum": 1.0 },
    "nodes": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["id", "type", "x", "y", "params"],
        "properties": {
          "id": { "type": "string" },
          "type": { "type": "string" },
          "x": { "type": "number" },
          "y": { "type": "number" },
          "params": { "type": "object" }
        }
      }
    },
    "edges": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["id", "fromNode", "fromPort", "toNode", "toPort"],
        "properties": {
          "id": { "type": "string" },
          "fromNode": { "type": "string" },
          "fromPort": { "type": "string" },
          "toNode": { "type": "string" },
          "toPort": { "type": "string" }
        }
      }
    }
  }
}

```

### Deterministic Asset URIs

All audio files resolve through an immutable namespaced identifier:


$$\texttt{<namespace>:<pack\_id>/<category>/<asset\_id>} \quad [\texttt{crc32}]$$

* Missing assets evaluate to silent events without interrupting thread processing.
* Mismatched CRC32 tokens trigger visual warning stripes on node cards while attempting playback.

---

## 7. UI/UX Interaction Matrix

```
┌────────────────────────────────────────────────────────────────────────────┐
│ Key / Input Event Priority Hierarchy:                                     │
│ 1. Active Text Input ──> Consumes all character keys & Backspace           │
│ 2. Audition Active   ──> Escape halts sound preview                        │
│ 3. Cable In-Flight   ──> Escape cancels active connection line             │
│ 4. Node Selected     ──> Escape deselects & closes right-side Inspector    │
│ 5. Default Canvas    ──> Escape exits GUI to Minecraft world               │
└────────────────────────────────────────────────────────────────────────────┘

```

| Domain | Key / Input | Functional Target |
| --- | --- | --- |
| **Global Transport** | `Space` | Toggles continuous playback clock (Play / Stop). |
| **History Engine** | `Ctrl + Z` / `Ctrl + Y` | Undo / Redo graph mutations (debounced 50ms engine compile). |
| **Sample Drawer** | `Ctrl + B` | Toggles left-hand sample browser between expanded and 24px icon rail. |
| **Sample Drawer** | `↑` / `↓` Arrow Keys | Navigates asset tree silently without triggering sound. |
| **Sample Drawer** | `Enter` / `[►]` Click | Auditions the highlighted asset (dry stereo). |
| **Sample Drawer** | `Mouse Left-Drag` | Silent drag-and-drop to canvas or existing node. |
| **Canvas Pan/Zoom** | `Middle-Drag` | Translates canvas world coordinates. |
| **Canvas Pan/Zoom** | `Scroll Wheel` | Scales canvas zoom between `0.5x` and `2.0x` anchored to cursor. |
| **Quick Palette** | `Shift + A` / `Tab` | Opens fuzzy-search node creation dialog at cursor position. |
| **Port Action** | `Right-Click` on Port | Disconnects all attached patch cables with an audible pop. |

---

## 8. Visual Aesthetics & Skinning Profiles

The interface implements a modular `ThemeRenderer` supporting four selectable visual identities:

```
┌─────────────────────────┬─────────────────────────┬─────────────────────────┬─────────────────────────┐
│ 1. Tactical Studio Rack │ 2. Acoustic Clockwork   │ 3. Retro CRT Terminal   │ 4. Vanilla Clean        │
├─────────────────────────┼─────────────────────────┼─────────────────────────┼─────────────────────────┤
│ • Matte Charcoal BG     │ • Blueprint / Brass BG  │ • Glass Obsidian BG     │ • Standard Container BG │
│ • Neon Signal Traces    │ • Woven Fabric Cables   │ • P1 Phosphor Green     │ • Pixelated 2px Cables  │
│ • Machined Rotary Knobs │ • Escapement Gear Dials │ • Monospaced Pixel Font │ • 16x16 Pixel Art Icons │
│ • Modern Eurorack Look  │ • Create-Mod Adjacent   │ • 80s Tracker Aesthetic │ • Mojang UI Language    │
└─────────────────────────┴─────────────────────────┴─────────────────────────┴─────────────────────────┘

```

* **Tactile Studio Rack (Default):** `#1A1C20` base, `#FFAA00` trigger traces, `#00E5FF` audio lines, `#A6FF00` modulation curves. 1px card borders with subtle drop shadows.
* **Acoustic Clockwork:** `#E8DFD0` parchment base, `#D4AF37` brushed brass frames, mechanical escapement wheel animations for Euclidean steps.
* **Retro CRT Terminal:** `#080C0A` dark glass, `#33FF66` phosphor vector beam cables, subtle scanlines, full monospaced readout typography.
* **Vanilla Clean:** Standard `#C6C6C6` container textures, nine-patch panels, 2px stepped pixel cables, vanilla item iconography.