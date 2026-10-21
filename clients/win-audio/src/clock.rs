//! A clock that turns sampled `(host time, device position)` pairs into the audio
//! time heard at any host time.
//!
//! The device reports where it is in the stream; it does not report how far behind
//! the speakers are, and that distance differs between people, headphones and
//! rooms. So this clock stays on the device's own timebase: the offset between it
//! and what a person hears is calibration data the app keeps per output device,
//! never a constant chosen here.

use std::collections::VecDeque;

/// Samples kept for the rate fit. At a poll every 4 ms this spans about 1.6 s,
/// long enough to average out reading noise and short enough to follow a device
/// whose rate wanders.
const DEFAULT_WINDOW: usize = 400;

/// How far a reading may sit from the prediction before the clock treats the
/// stream as restarted (a new device, a seek, or a discontinuity in the driver's
/// position report).
const DISCONTINUITY_SECONDS: f64 = 0.05;

/// Rates outside this band are reading noise or a broken fit, not a device.
const RATE_LIMIT: f64 = 200e-6;

#[derive(Clone, Debug)]
pub struct AudioClock {
    window: VecDeque<(f64, f64)>,
    sum_host: f64,
    sum_audio: f64,
    sum_host_squared: f64,
    sum_host_audio: f64,
    rate: f64,
    last: Option<(f64, f64)>,
    /// The largest value `audio_at` has returned, so time never runs backwards
    /// inside a game even if a reading arrives late.
    reported: f64,
}

impl Default for AudioClock {
    fn default() -> Self {
        Self::new(DEFAULT_WINDOW)
    }
}

impl AudioClock {
    pub fn new(window: usize) -> Self {
        Self {
            window: VecDeque::with_capacity(window.max(1)),
            sum_host: 0.0,
            sum_audio: 0.0,
            sum_host_squared: 0.0,
            sum_host_audio: 0.0,
            rate: 1.0,
            last: None,
            reported: f64::NEG_INFINITY,
        }
    }

    /// Adds a reading: the device's position, in seconds, at a host time, in
    /// seconds. Both are on any consistent epoch; only their differences matter.
    pub fn push(&mut self, host: f64, position: f64) {
        if let Some((last_host, last_position)) = self.last {
            let predicted = last_position + self.rate * (host - last_host);
            if (position - predicted).abs() > DISCONTINUITY_SECONDS {
                self.reset(host, position);
                return;
            }
        }

        self.window.push_back((host, position));
        self.sum_host += host;
        self.sum_audio += position;
        self.sum_host_squared += host * host;
        self.sum_host_audio += host * position;

        while self.window.len() > self.window.capacity() {
            if let Some((old_host, old_position)) = self.window.pop_front() {
                self.sum_host -= old_host;
                self.sum_audio -= old_position;
                self.sum_host_squared -= old_host * old_host;
                self.sum_host_audio -= old_host * old_position;
            }
        }

        self.rate = self.fitted_rate();
        self.last = Some((host, position));
    }

    /// The audio time that is being heard at `host`, extrapolated from the last
    /// reading at the fitted rate, and never earlier than a previous answer.
    pub fn audio_at(&mut self, host: f64) -> f64 {
        let Some((last_host, last_position)) = self.last else {
            return f64::NEG_INFINITY;
        };

        let predicted = last_position + self.rate * (host - last_host);
        self.reported = self.reported.max(predicted);
        self.reported
    }

    /// The fitted rate: 1.0 means the device advances exactly with the host clock.
    pub fn rate(&self) -> f64 {
        self.rate
    }

    pub fn samples(&self) -> usize {
        self.window.len()
    }

    pub fn last_position(&self) -> Option<f64> {
        self.last.map(|(_, position)| position)
    }

    fn reset(&mut self, host: f64, position: f64) {
        self.window.clear();
        self.sum_host = 0.0;
        self.sum_audio = 0.0;
        self.sum_host_squared = 0.0;
        self.sum_host_audio = 0.0;
        self.rate = 1.0;
        self.window.push_back((host, position));
        self.last = Some((host, position));
        self.reported = position;
    }

    fn fitted_rate(&self) -> f64 {
        let count = self.window.len() as f64;
        if count < 3.0 {
            return 1.0;
        }

        let denominator = count * self.sum_host_squared - self.sum_host * self.sum_host;
        if denominator.abs() < f64::EPSILON {
            return 1.0;
        }

        let slope = (count * self.sum_host_audio - self.sum_host * self.sum_audio) / denominator;
        if !slope.is_finite() {
            return 1.0;
        }

        slope.clamp(1.0 - RATE_LIMIT, 1.0 + RATE_LIMIT)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn feed(clock: &mut AudioClock, seconds: f64, step: f64, position: impl Fn(f64) -> f64) {
        let mut host = 0.0;
        while host <= seconds {
            clock.push(host, position(host));
            host += step;
        }
    }

    #[test]
    fn a_steady_stream_fits_its_rate() {
        let mut clock = AudioClock::new(200);
        feed(&mut clock, 2.0, 0.004, |host| host);

        assert!((clock.rate() - 1.0).abs() < 1e-9, "rate {}", clock.rate());
        assert!((clock.audio_at(2.5) - 2.5).abs() < 1e-9);
    }

    #[test]
    fn a_faster_device_is_measured() {
        let mut clock = AudioClock::new(200);
        feed(&mut clock, 2.0, 0.004, |host| host * 1.000_05);

        assert!((clock.rate() - 1.000_05).abs() < 1e-7, "rate {}", clock.rate());
    }

    #[test]
    fn reading_noise_does_not_dominate_the_answer() {
        let mut clock = AudioClock::new(400);
        let mut noise = 0x2545_f491_4f6c_dd1du64;
        let mut jitter = || {
            noise ^= noise << 13;
            noise ^= noise >> 7;
            noise ^= noise << 17;
            ((noise >> 11) as f64 / (1u64 << 53) as f64 - 0.5) * 2.0 * 0.0002
        };

        let mut host = 0.0;
        let mut worst: f64 = 0.0;
        while host <= 4.0 {
            let position = host + jitter();
            clock.push(host, position);
            if clock.samples() > 8 {
                let predicted = clock.audio_at(host);
                worst = worst.max((predicted - host).abs());
            }
            host += 0.004;
        }

        assert!(worst < 0.0005, "worst {worst}");
    }

    #[test]
    fn time_never_runs_backwards() {
        let mut clock = AudioClock::new(100);
        feed(&mut clock, 1.0, 0.004, |host| host);

        let forward = clock.audio_at(1.5);
        let backward = clock.audio_at(1.2);
        assert_eq!(forward, backward, "the clock moved back");
    }

    #[test]
    fn a_jump_restarts_the_fit() {
        let mut clock = AudioClock::new(100);
        feed(&mut clock, 1.0, 0.004, |host| host);

        clock.push(1.004, 1.5);
        assert_eq!(clock.samples(), 1);
        assert!((clock.audio_at(1.004) - 1.5).abs() < 1e-9);

        feed(&mut clock, 0.004, 0.004, |host| 1.5 + host);
        assert!(clock.samples() > 1);
        assert!((clock.rate() - 1.0).abs() < 1e-6);
    }
}
