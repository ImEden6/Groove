# Musical pattern nodes

`alternate`, `probability`, and `polymeter` are available in the editor's
Shift+A / Tab palette. They work with tones, samples, audio_render, and
trigger_render. All decisions use musical cycle time, so lookahead queries,
late joins, and backward queries produce the same events.

## Alternation

Connect 1..16 patterns to `alternate.in`. Connections play in the order they
were added (the saved graph edge order), one child per cycle. A, B, C produces
A / B / C / A across four cycles. Each child advances one local cycle per
complete rotation; nested alternation therefore produces longer progressions.
Disconnect and reconnect inputs in the desired order to change the sequence.
There is no dedicated reorder control yet.

## Probability

Connect one pattern to `probability.in`. `chance` is the fraction retained,
from 0 (silence) to 1 (all events), default 0.5. `seed` is an integer from 0
to 65535, default 0. A stable hash of the seed and the event's whole time arc
chooses whether to keep it. Query clipping and query order do not change the
choice. Notes with identical whole arcs share a decision, keeping chords
together; use different seeds on separate branches for independent choices.

Placement matters: probability before a repeating Euclid transformation
repeats its local decision; place it after the rhythm to vary absolute hits.
This node drops whole events; it does not randomize velocity or timing.

## Polymeter

Connect 1..16 ordered patterns to `polymeter.in`. `stepsPerCycle` is an integer
from 1 to 64, default 4. Each connected child occupies one pulse slot; the
sequence wraps after the last child without restarting at the bar line.
With A, B, C and stepsPerCycle=2, successive bars contain A B / C A / B C.
The child pattern's cycle is compressed into a pulse slot. Nested children
advance one local cycle per full sequence rotation, as with alternation.
Stack multiple polymeters at the same pulse rate to combine different meters.
Sample playback tails retain the existing one-shot behavior across slots.

## Bounds and compatibility

The compiler budgets the busiest alternative, multiplied by the polymeter
pulse rate. Probability retains its child's full cost: chance cannot bypass
the shared 128-event budget. Existing node and graph-depth limits still apply.
No audio-thread queries or random-number state were added.

The nodes use the existing graph versions and JSON/network layout. Older
builds cannot read these new node names; all players need an updated build.
Connection order is preserved by JSON, packets, and editor undo/redo.

## Verification

`MusicalPatternTests` checks negative cycles, nested progressions, polymetric
phase, seeded replay, partitioned windows, probability endpoints, invalid
parameters, signal routing, and rejection of an expensive later branch.
Backend tests cover saved parameters and network snapshot round trips.
Run `./gradlew.bat build` for the full regression suites. In-game listening
and two-machine multiplayer testing remain manual checks.
