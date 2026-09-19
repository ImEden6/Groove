# Feedback loops and late joiners

Why every audio feedback loop has a gain limit, what the limit guarantees, and what it does not.
The rule itself is in [signal graph usage](PHASE-2-SIGNALS.md#feedback-and-state-ownership); the
code is `SignalGraph.feedbackLoops` and `checkLoopGain`.

## The problem

Every client renders the same patch from the same server clock, but a player who joins late, or
whose client resyncs, only replays the last second of history (see
[Stage 4 known limitations](ENGINE-UPGRADE-STAGES.md#known-limitations)). Anything older is missing
from their delay lines, filters and reverbs. Outside a feedback loop that difference ends once the
effect's own tail does. Inside a loop it goes round again every trip, and it only fades if the loop
loses energy on each trip. At a loop gain of 1 or more it never fades, so that player hears a
different echo tail from everyone else for as long as the patch plays.

## The rule

The compiler finds each feedback loop (a strongly connected set of audio nodes, which must contain
at least one `delay`), cuts the audio edges entering each delay, and bounds the amplitude that
leaves delay $j$ and arrives back at delay $i$, $M_{ij}$, by multiplying along every path:

| Node on the path | Bound |
| --- | --- |
| `delay` | 1 |
| `mix_bus` | its `gain`, times the sum of its inputs; 1 when the gain is modulated |
| low- or high-pass `filter` | resonance peak $Q / \sqrt{1 - 1/(4Q^2)}$ for $Q > 1/\sqrt2$, else 1, times a 1.05 margin |
| band-pass or notch `filter` | 1 |
| `reverb` | 0.95 (the loudness contract is 0.891, −1 dB) |

A graph is rejected when any delay's incoming total $\sum_j M_{ij}$ exceeds **0.95** (−0.45 dB),
unless one of that loop's delays has `freeRun = 1`.

## Why that is enough

Take two listeners, A and B, and look at the difference $e = x_A - x_B$ at every node. Sources are
identical (same patch, same server clock), so they cancel. What remains:

- **Delay:** $e$ is shifted in time. Its energy is unchanged.
- **Mix:** $e = g \sum_k e_k$ with $0 \le g \le 1$. Modulation is clamped to $[0, 1]$ and is the
  same for both listeners.
- **The ±8 clamp:** $|\mathrm{clamp}(a) - \mathrm{clamp}(b)| \le |a - b|$. Saturation never makes a
  difference larger, so it cannot break convergence.
- **Filter with a fixed cutoff:** a linear time-invariant system, so its energy gain is the peak of
  its magnitude response. The RBJ low-pass prototype $1/(s^2 + s/Q + 1)$ peaks at the value in the
  table. The bilinear transform maps the whole analog frequency axis onto the digital one, so the
  digital filter has exactly the same peak; `FeedbackLoopTests` confirms this against the real
  `Biquad` coefficients at every mode, $Q$ from 0.1 to 20 and cutoffs from 20 Hz to 20 kHz. The
  band-pass is the cookbook's constant 0 dB peak form.
- **Reverb:** peak magnitude response is fixed at −1 dB for every setting.

Let $v_i$ be the energy of the difference arriving at delay $i$. Then
$v_i \le \sum_j M_{ij} v_j + c_i$, where $c_i$ is the finite leftover from the two listeners'
different starting states. $M$ has no negative entries and every row sums to at most 0.95, so its
spectral radius is below 1 and $v \le (I - M)^{-1} c$ is finite. A difference with finite total
energy dies away to zero. This is the vector small-gain theorem, the same argument that keeps a
feedback delay network stable whatever its delay lengths.

## How fast

The difference shrinks by roughly the bound on each trip round the loop. At the limit, 0.95, a
40 dB drop takes about 90 trips: around 22 s for a 250 ms delay and 90 s for the 1 s maximum. A
match to within 1e-6 (−120 dB) takes about 270 trips; the tests measure 13.2 s for a 50 ms loop at 0.95, against
a predicted 13.5 s. Lower gains converge much faster: at 0.65 the same match takes 33 trips.

## What is not guaranteed

- **Per-sample peaks.** The guarantee is about energy. A resonant low-pass's worst-case
  sample-by-sample gain is about $(4/\pi) Q$, roughly 1.27 times its peak response, so an individual
  sample of the difference can briefly shrink more slowly than the energy does.
- **Filters with a modulated cutoff.** The peak response does not depend on cutoff, so the bound is
  the same at every cutoff, but a filter whose coefficients change every frame is not a fixed linear
  system and no general theorem covers it. Control values ramp over 64-frame blocks and LFOs top out
  at 40 Hz, so changes are slow. `FeedbackLoopTests` checks a loop swept at 40 Hz and random loops
  with filters; this is evidence, not proof.
- **Free-running loops.** A delay with `freeRun = 1` exempts its whole loop, because every delay in
  a loop depends on the others' history. Those tails can differ between players indefinitely, by
  design. The editor says so in its status line.

## Saved patches

Patches saved before the limit may contain loops over it. When a session file, legacy patch file
or editor project is loaded from disk, every delay in such a loop is marked free-running
(`FeedbackMigration`). `freeRun` only changes validation, never the audio, so the patch loads and
sounds exactly as before; the server log (and the `/groove load` reply) names the delays it marked.
Patches submitted over the network are not migrated: a new edit must stay within the limit or set
`freeRun` itself.
