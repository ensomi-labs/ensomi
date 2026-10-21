---
commit: 36a3c69
---

# Windows audio clock spike

`ensomi_win_audio` is a spike, not a client. It measures one thing: how the position
WASAPI reports for a playing stream relates to the host's QPC clock, and it builds the
clock a client would use from those readings. The playfield, the UI and the chart
generator are out of scope; the [client design](../platform-clients.md) explains why
this measurement comes first.

## What it does

It opens the default render endpoint in shared mode, plays a quiet 440 Hz tone, and
every 4 ms records a pair of readings: the host time from `QueryPerformanceCounter`
and the stream's played-frame position from `IAudioClock::GetPosition`. Each reading is
fed to `AudioClock`, which is asked for the audio time at that host time first, so the
spike can report how wrong the prediction was before the reading arrived.

## The clock

[`src/clock.rs`](src/clock.rs) turns the readings into a clock:

- it fits the device's rate over a sliding window of 400 readings (about 1.6 s),
  clamped to ±200 ppm, so a burst of noise cannot invent a wild rate;
- it extrapolates from the last reading at that rate, so a query between readings is
  answered without waiting for the next one;
- it never returns an earlier audio time than a previous call, so a late reading
  cannot move the game backwards;
- it treats a reading more than 50 ms from its prediction as a new stream — a device
  change, a seek, a driver that restarted its position — and starts a fresh fit.

The clock stays on the device's own timebase. It does **not** add an output latency,
because the distance between the stream's position and what a person hears differs
between people, headphones and rooms. That offset is calibration data the app keeps
per output device, as the [design](../platform-clients.md#platform-shell-contracts)
and the calibration screen already say; it is never a constant chosen in code.

Five unit tests cover a steady stream, a faster device, reading noise, backwards
queries and a position jump: `cargo test`.

## Run it

```
cargo run --release -- 8
```

The argument is the length in seconds; the default is six.

## What it measured

Windows 11, the default render endpoint of this development machine, eight-second runs
in October 2026. The first and last 0.5 s of samples are discarded from the fit, so the
spans below are about 7.34 s.

| Run | Fitted rate | Drift | Residual rms | p95 | max |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 0.999997376 | −5.7 ppm | 0.091 ms | 0.123 ms | 0.275 ms |
| 2 | 0.999999074 | −9.3 ppm | 0.067 ms | 0.109 ms | 0.247 ms |

The live clock, asked for the audio time at each reading's host time before that
reading was added:

| Run | Prediction rms | p95 | max | Readings |
| --- | ---: | ---: | ---: | ---: |
| 1 | 0.080 ms | 0.181 ms | 0.579 ms | 1807 |
| 2 | 0.058 ms | 0.126 ms | 0.374 ms | 1808 |

The endpoint reports 48000 Hz, a 4800-frame (100 ms) buffer, and an `IAudioClock`
frequency of 384000 units per second, so a position reading has 2.6 µs of resolution.
The position advances continuously between polls rather than in mix-period steps; the
median advance equals the polling interval.

## What it means

A clock built from `IAudioClock::GetPosition` and QPC is quiet enough for judgement.
Its short-term error, under 0.2 ms at the 95th percentile, is two orders of magnitude
below the tightest judgement half-window (25 ms at judge E). The fitted rate stays
within tens of ppm of QPC, so a three-minute song accumulates only a few milliseconds
of drift, and re-anchoring on every reading keeps even that from accumulating.

`IAudioClient::GetStreamLatency` returned 0.0 ms here, confirming the point above: the
API does not report the distance to the speaker, and a client must not pretend it does.

## What a client still needs

- A clock thread that polls, so the game thread's `audio_at` queries never wait on the
  device; the spike polls and queries on one thread because nothing else is running.
- Device changes: rebuild the stream on the new endpoint and continue from the position
  being heard, with the clock's discontinuity rule doing the switch.
- Exclusive mode and low-latency periods, if shared mode's 100 ms buffer proves too slow
  for input latency.
