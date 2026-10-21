---
commit: 36a3c69
---

# Windows audio clock spike

`ensomi_win_audio` is a spike, not a client. It measures one thing: how the position
WASAPI reports for a playing stream relates to the host's QPC clock. The playfield,
the UI and the chart generator are out of scope; the [client design](../platform-clients.md)
explains why this measurement comes first.

## What it does

It opens the default render endpoint in shared mode, plays a quiet 440 Hz tone, and
every 4 ms records a pair of readings: the host time from `QueryPerformanceCounter`
and the stream's played-frame position from `IAudioClock::GetPosition`. After the
run it fits audio time against host time and reports:

- the fitted rate, where 1.0 means the stream advances exactly as fast as QPC;
- the drift implied by the first and last samples;
- the residual of that fit, which is the clock's short-term error;
- the median position advance, which shows the position's granularity.

## Run it

```
cargo run --release -- 8
```

The argument is the length in seconds; the default is six.

## What it measured

Windows 11, the default render endpoint of this development machine, three eight-second
runs in October 2026. The first and last 0.5 s of samples are discarded, so the spans
below are about 7.35 s.

| Run | Fitted rate | Drift | Residual rms | p95 | max |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 1.000022288 | −20.6 ppm | 0.145 ms | 0.217 ms | 0.791 ms |
| 2 | 0.999979967 | +34.0 ppm | 0.144 ms | 0.203 ms | 0.474 ms |
| 3 | 0.999991818 | +20.8 ppm | 0.141 ms | 0.217 ms | 0.515 ms |

The endpoint reports 48000 Hz, a 4800-frame (100 ms) buffer, and an `IAudioClock`
frequency of 384000 units per second, so a position reading has 2.6 µs of resolution.
The position advances continuously between polls rather than in mix-period steps; the
median advance equals the polling interval.

## What it means

A clock built from `IAudioClock::GetPosition` and QPC is quiet enough for judgement.
Its short-term error, about 0.15 ms rms and well under 1 ms at the tail, is two orders
of magnitude below the tightest judgement half-window (25 ms at judge E). The fitted
rate is within 35 ppm of QPC, so a three-minute song accumulates a few milliseconds of
drift — small, but enough that the clock should re-anchor on each new position reading
rather than integrate from a single one.

What the measurement does not settle is the constant offset between the position and
what the player hears: `IAudioClient::GetStreamLatency` returned 0.0 ms here, so the
output latency is not obtained from it. That offset is what the app's offset calibration
already exists to absorb, and a client should treat it as calibration data rather than
as a value the API supplies.

## What a client still needs

- A re-anchoring clock: fit the rate over recent readings, extrapolate to the asked host
  time, and re-fit as readings arrive.
- Output latency: try `IAudioClient3` and the endpoint's reported latency, and fall back
  to the calibration the design already specifies.
- Device changes: rebuild the stream on the new endpoint and continue from the position
  being heard.
- Exclusive mode and low-latency periods, if shared mode's 100 ms buffer proves too slow
  for input latency.
