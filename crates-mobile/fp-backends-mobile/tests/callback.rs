use std::sync::atomic::Ordering;

use fp_backends::Renderer;
use fp_backends_mobile::callback::CallbackCore;
use fp_backends_mobile::stream_params::AAUDIO_CALLBACK_RESULT_CONTINUE;

/// Plays back fixed samples, repeating 0.0 once exhausted (the desktop tests'
/// `Samples` renderer): deterministic, so fills are comparable exactly.
struct Samples(Vec<f32>, usize);

impl Renderer for Samples {
    fn render(&mut self, out: &mut [f32], _channels: usize) {
        for s in out {
            *s = self.0.get(self.1).copied().unwrap_or(0.0);
            self.1 += 1;
        }
    }
}

/// A renderer whose samples climb a quarter step, so successive fills read
/// distinct, exactly representable values.
fn ramp(start: f32, len: usize) -> Samples {
    Samples((0..len).map(|i| start + i as f32 * 0.25).collect(), 0)
}

#[test]
fn a_pushed_renderers_samples_arrive_in_fills_output() {
    let (mut producer, mut core) = CallbackCore::new();
    assert!(
        producer.push(Box::new(ramp(1.0, 8))).is_ok(),
        "the one-slot handoff ring starts empty"
    );
    let mut out = vec![0.0f32; 4 * 2];
    let result = core.fill(&mut out, 2);
    assert_eq!(result, AAUDIO_CALLBACK_RESULT_CONTINUE);
    assert_eq!(
        out,
        vec![1.0, 1.25, 1.5, 1.75, 2.0, 2.25, 2.5, 2.75],
        "the renderer's samples must fill the interleaved output in order"
    );
}

#[test]
fn before_the_handoff_fill_writes_silence_and_continues() {
    let (_producer, mut core) = CallbackCore::new();
    // Non-zero garbage: only the callback may blank it out.
    let mut out = vec![7.7f32; 4 * 2];
    let result = core.fill(&mut out, 2);
    assert_eq!(result, AAUDIO_CALLBACK_RESULT_CONTINUE);
    assert!(
        out.iter().all(|s| *s == 0.0),
        "without a renderer the output must be silent, got {out:?}"
    );
}

#[test]
fn the_renderer_is_delivered_exactly_once() {
    let (mut producer, mut core) = CallbackCore::new();
    // The stream exists before the renderer arrives: the first fill is
    // silent, and the push happens after it ("stream exists, renderer
    // arrives" order).
    let mut out = vec![1.0f32; 2 * 2];
    assert_eq!(core.fill(&mut out, 2), AAUDIO_CALLBACK_RESULT_CONTINUE);
    assert!(out.iter().all(|s| *s == 0.0), "pre-handoff: {out:?}");
    assert!(
        producer.push(Box::new(ramp(0.0, 32))).is_ok(),
        "the slot is free before the handoff"
    );
    core.fill(&mut out, 2);
    assert_eq!(out, vec![0.0, 0.25, 0.5, 0.75], "first rendered block");
    // Subsequent fills keep rendering through the same renderer.
    core.fill(&mut out, 2);
    assert_eq!(out, vec![1.0, 1.25, 1.5, 1.75], "second rendered block");
    // The handoff is one-shot: a renderer pushed after the first one was
    // picked up never replaces it (the slot freed by the pop stays unused).
    assert!(
        producer.push(Box::new(Samples(vec![-9.0; 32], 0))).is_ok(),
        "the slot freed by the pop accepts a late push"
    );
    core.fill(&mut out, 2);
    assert_eq!(
        out,
        vec![2.0, 2.25, 2.5, 2.75],
        "the late push must be ignored; the first renderer stays in use"
    );
}

#[test]
fn called_frames_tracks_the_largest_block_seen() {
    let (_producer, mut core) = CallbackCore::new();
    let called = core.called_frames();
    assert_eq!(called.load(Ordering::Relaxed), 0);
    let mut small = vec![0.0f32; 128 * 2];
    core.fill(&mut small, 2);
    assert_eq!(called.load(Ordering::Relaxed), 128);
    let mut large = vec![0.0f32; 512 * 2];
    core.fill(&mut large, 2);
    assert_eq!(called.load(Ordering::Relaxed), 512);
    // A smaller block afterwards never shrinks the record.
    core.fill(&mut small, 2);
    assert_eq!(called.load(Ordering::Relaxed), 512);
}
