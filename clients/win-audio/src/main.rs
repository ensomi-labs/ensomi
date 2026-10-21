//! A spike that measures the WASAPI render clock: how the device's played-frame
//! position relates to the host's QPC clock. It plays a quiet 440 Hz tone in
//! shared mode, samples `IAudioClock::GetPosition` every few milliseconds, and
//! reports drift, quantization and jitter.
//!
//! Run `cargo run --release -- 6` for a six-second run.

use std::time::Duration;

use windows::core::Result;
use windows::Win32::Media::Audio::{
    eConsole, eRender, IAudioClient, IAudioClock, IAudioRenderClient, IMMDeviceEnumerator,
    MMDeviceEnumerator, AUDCLNT_SHAREMODE_SHARED,
};
use windows::Win32::System::Com::{CoCreateInstance, CoInitializeEx, CLSCTX_ALL, COINIT_MULTITHREADED};
use windows::Win32::System::Performance::{QueryPerformanceCounter, QueryPerformanceFrequency};

const TONE_HZ: f64 = 440.0;
const TONE_AMPLITUDE: f32 = 0.1;

fn main() -> Result<()> {
    let seconds: f64 = std::env::args()
        .nth(1)
        .and_then(|value| value.parse().ok())
        .unwrap_or(6.0);

    run(seconds)
}

fn now_qpc(frequency: i64) -> f64 {
    unsafe {
        let mut counter = 0i64;
        QueryPerformanceCounter(&mut counter).ok();
        counter as f64 / frequency as f64
    }
}

fn run(seconds: f64) -> Result<()> {
  unsafe {
    CoInitializeEx(None, COINIT_MULTITHREADED).ok()?;

    let enumerator: IMMDeviceEnumerator = CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL)?;
    let device = enumerator.GetDefaultAudioEndpoint(eRender, eConsole)?;
    let client: IAudioClient = device.Activate(CLSCTX_ALL, None)?;

    let mix = client.GetMixFormat()?;
    // WAVEFORMATEX is packed, so read it as a value before touching its fields.
    let format: windows::Win32::Media::Audio::WAVEFORMATEX = std::ptr::read_unaligned(mix);
    let channels = format.nChannels as usize;
    let rate = format.nSamplesPerSec;
    let bits = format.wBitsPerSample;
    if bits != 32 {
        println!(
            "the mix format is {} bits per sample, not float32; nothing was played",
            bits
        );
        return Ok(());
    }

    client.Initialize(AUDCLNT_SHAREMODE_SHARED, 0, 1_000_000, 0, mix, None)?;
    let buffer_frames = client.GetBufferSize()?;
    let stream_latency = client.GetStreamLatency()?;
    let render: IAudioRenderClient = client.GetService()?;
    let clock: IAudioClock = client.GetService()?;
    let clock_frequency = clock.GetFrequency()?;

    let mut phase = 0.0f64;
    let step = std::f64::consts::TAU * TONE_HZ / rate as f64;

    fill(&render, buffer_frames, channels, &mut phase, step)?;
    client.Start()?;

    let mut qpf = 0i64;
    QueryPerformanceFrequency(&mut qpf).ok();
    let start = now_qpc(qpf);

    let mut samples: Vec<(f64, f64)> = Vec::new();
    loop {
        let host = now_qpc(qpf) - start;
        if host > seconds {
            break;
        }

        let padding = client.GetCurrentPadding().unwrap_or(0);
        let available = buffer_frames.saturating_sub(padding);
        if available > 0 {
            fill(&render, available, channels, &mut phase, step)?;
        }

        let mut position = 0u64;
        let mut position_qpc = 0u64;
        clock.GetPosition(&mut position, Some(&mut position_qpc))?;
        samples.push((host, position as f64 / clock_frequency as f64));

        std::thread::sleep(Duration::from_millis(4));
    }

    client.Stop()?;

    report(&samples, seconds, buffer_frames, rate, stream_latency, clock_frequency);
    Ok(())
  }
}

fn fill(
    render: &IAudioRenderClient,
    frames: u32,
    channels: usize,
    phase: &mut f64,
    step: f64,
) -> Result<()> {
  unsafe {
    let data = render.GetBuffer(frames)?;
    let values = std::slice::from_raw_parts_mut(data as *mut f32, frames as usize * channels);
    for frame in 0..frames as usize {
        let value = (*phase).sin() as f32 * TONE_AMPLITUDE;
        *phase += step;
        if *phase > std::f64::consts::TAU {
            *phase -= std::f64::consts::TAU;
        }
        for channel in 0..channels {
            values[frame * channels + channel] = value;
        }
    }
    render.ReleaseBuffer(frames, 0)
  }
}

fn report(
    samples: &[(f64, f64)],
    seconds: f64,
    buffer_frames: u32,
    rate: u32,
    stream_latency: i64,
    clock_frequency: u64,
) {
    if samples.len() < 4 {
        println!("only {} samples were taken; nothing is reported", samples.len());
        return;
    }

    // Discard the start, where the device has not begun to play, and the end,
    // where the last submitted buffer may not have been consumed.
    let end = samples[samples.len() - 1].0;
    let samples: Vec<(f64, f64)> = samples
        .iter()
        .copied()
        .filter(|(host, _)| *host > 0.5 && *host < end - 0.15)
        .collect();
    if samples.len() < 4 {
        println!("too few samples after trimming; nothing is reported");
        return;
    }

    let first = samples[0];
    let last = samples[samples.len() - 1];
    let host_span = last.0 - first.0;
    let audio_span = last.1 - first.1;

    // Least squares slope of audio time against host time: 1.0 means the device
    // plays exactly as fast as the host clock.
    let mean_host = samples.iter().map(|s| s.0).sum::<f64>() / samples.len() as f64;
    let mean_audio = samples.iter().map(|s| s.1).sum::<f64>() / samples.len() as f64;
    let mut covariance = 0.0;
    let mut variance = 0.0;
    for (host, audio) in &samples {
        covariance += (host - mean_host) * (audio - mean_audio);
        variance += (host - mean_host).powi(2);
    }
    let rate_ratio = covariance / variance;

    let mut residuals: Vec<f64> = samples
        .iter()
        .map(|(host, audio)| (audio - mean_audio) - rate_ratio * (host - mean_host))
        .collect();
    residuals.sort_by(|a, b| a.partial_cmp(b).unwrap());
    let residual_mean_sq =
        residuals.iter().map(|value| value * value).sum::<f64>() / residuals.len() as f64;

    // Position advances in mix-period steps: measure them.
    let mut steps: Vec<f64> = Vec::new();
    for pair in samples.windows(2) {
        let delta = (pair[1].1 - pair[0].1) * 1000.0;
        if delta > 0.0001 {
            steps.push(delta);
        }
    }
    steps.sort_by(|a, b| a.partial_cmp(b).unwrap());
    let step_median = if steps.is_empty() {
        0.0
    } else {
        steps[steps.len() / 2]
    };

    println!("device mix format: {rate} Hz, {buffer_frames} buffer frames");
    println!("reported stream latency: {:.1} ms", stream_latency as f64 / 10_000.0);
    println!("clock frequency: {clock_frequency} frames/s");
    println!(
        "samples: {} over {:.2} s (target {:.2} s)",
        samples.len(),
        host_span,
        seconds
    );
    println!();
    println!("audio span   {audio_span:.6} s");
    println!("host span    {host_span:.6} s");
    println!(
        "drift        {:+.1} ppm",
        (audio_span - host_span) / host_span * 1e6
    );
    println!("rate         {rate_ratio:.9} (1.0 = locked to QPC)");
    println!(
        "residual     {:.3} ms rms, p95 {:.3} ms, max {:.3} ms",
        residual_mean_sq.sqrt() * 1000.0,
        residuals[(residuals.len() as f64 * 0.95) as usize] * 1000.0,
        residuals[residuals.len() - 1] * 1000.0
    );
    println!(
        "position step {:.3} ms median over {} advances",
        step_median,
        steps.len()
    );
}

