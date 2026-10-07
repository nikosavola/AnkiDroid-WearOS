//! FSRS-6 scheduler math for offline interval previews.
//!
//! A faithful port of fsrs-rs `model_v6.rs`: 21 weights, a learnable forgetting-curve decay in
//! `w[20]`, and the same-day `w[19]` term FSRS-5 lacked. Plain `f64` with no external deps, so the
//! watch `.so` stays small. AnkiDroid still owns the real schedule and reconciles every grade on
//! write-back.

#![expect(
    clippy::suboptimal_flops,
    reason = "transcribed from fsrs-rs; kept readable, no mul_add rewrites"
)]

const S_MIN: f64 = 0.001;
const S_MAX: f64 = 36500.0;
const D_MIN: f64 = 1.0;
const D_MAX: f64 = 10.0;

/// Fill a deck's raw weight list into FSRS-6's 21, or `None` for a length that maps to no known
/// generation (an empty list means FSRS is off). Mirrors fsrs-rs: 17- and 19-weight presets are
/// upgraded to 21.
pub fn fill_parameters(raw: &[f64]) -> Option<Vec<f64>> {
    match raw.len() {
        21 => Some(raw.to_vec()),
        19 => {
            let mut p = raw.to_vec();
            p.extend_from_slice(&[0.0, 0.5]);
            Some(p)
        }
        17 => {
            let mut p = raw.to_vec();
            p[4] += p[5] * 2.0;
            p[5] = (p[5] * 3.0).ln_1p() / 3.0;
            p[6] += 0.5;
            p.extend_from_slice(&[0.0, 0.0, 0.0, 0.5]);
            Some(p)
        }
        _ => None,
    }
}

/// Probability of recall after `t` days at stability `s` (FSRS-6 forgetting curve).
fn power_forgetting_curve(w: &[f64], t: f64, s: f64) -> f64 {
    let s = s.max(S_MIN);
    let decay = -w[20];
    let factor = 0.9_f64.powf(1.0 / decay) - 1.0;
    let t = t.max(0.0).round();
    (1.0 + factor * t / s).powf(decay)
}

/// Interval in days that brings recall probability down to `desired_retention`.
pub fn next_interval(w: &[f64], s: f64, desired_retention: f64) -> f64 {
    let s = s.max(S_MIN);
    let dr = desired_retention.clamp(0.0001, 0.9999);
    let decay = -w[20];
    let factor = 0.9_f64.powf(1.0 / decay) - 1.0;
    (s / factor * (dr.powf(1.0 / decay) - 1.0)).clamp(0.0, S_MAX)
}

fn stability_after_success(w: &[f64], s: f64, r: f64, d: f64, rating: i32) -> f64 {
    let hard_penalty = if rating == 2 { w[15] } else { 1.0 };
    let easy_bonus = if rating == 4 { w[16] } else { 1.0 };
    let inner = w[8].exp()
        * (11.0 - d)
        * s.powf(-w[9])
        * ((1.0 - r) * w[10]).exp_m1()
        * hard_penalty
        * easy_bonus;
    (s * (inner + 1.0)).clamp(S_MIN, S_MAX)
}

fn stability_after_failure(w: &[f64], s: f64, r: f64, d: f64) -> f64 {
    let new_s_min = s / (w[17] * w[18]).exp();
    let new_s = w[11] * d.powf(-w[12]) * ((s + 1.0).powf(w[13]) - 1.0) * ((1.0 - r) * w[14]).exp();
    new_s.min(new_s_min).clamp(S_MIN, S_MAX)
}

/// Initial difficulty for a rating, unclamped (the caller clamps).
fn init_difficulty(w: &[f64], rating: i32) -> f64 {
    w[4] - (w[5] * f64::from(rating - 1)).exp() + 1.0
}

fn next_difficulty(w: &[f64], d: f64, rating: i32) -> f64 {
    let delta = -w[6] * f64::from(rating - 3);
    let new_d = d + (10.0 - d) / 9.0 * delta;
    (w[7] * init_difficulty(w, 4) + (1.0 - w[7]) * new_d).clamp(D_MIN, D_MAX)
}

/// The card's (stability, difficulty) after answering with `rating` (1 = Again, 4 = Easy),
/// `delta_t` days after the last review.
pub fn next_state(w: &[f64], s: f64, d: f64, delta_t: f64, rating: i32) -> (f64, f64) {
    let rating = rating.clamp(1, 4);
    let delta_t = delta_t.max(0.0).round();
    let s = s.clamp(S_MIN, S_MAX);
    let d = d.clamp(D_MIN, D_MAX);
    let r = power_forgetting_curve(w, delta_t, s);

    let stability = if delta_t == 0.0 {
        let sinc = (w[17] * (f64::from(rating) - 3.0 + w[18])).exp() * s.powf(-w[19]);
        (s * if rating >= 2 { sinc.max(1.0) } else { sinc }).clamp(S_MIN, S_MAX)
    } else if rating == 1 {
        stability_after_failure(w, s, r, d)
    } else {
        stability_after_success(w, s, r, d, rating)
    };

    (stability, next_difficulty(w, d, rating))
}

/// FSRS's (stability, difficulty) for a card's first answer with `rating`.
pub fn initial_state(w: &[f64], rating: i32) -> (f64, f64) {
    let rating = rating.clamp(1, 4);
    (w[(rating - 1) as usize].clamp(S_MIN, S_MAX), init_difficulty(w, rating).clamp(D_MIN, D_MAX))
}

#[cfg(test)]
#[expect(
    clippy::unreadable_literal,
    clippy::float_cmp,
    reason = "reference values copied verbatim from py-fsrs; rounded day counts compare exactly"
)]
mod tests {
    use super::*;

    /// Stock FSRS-6 default weights, as py-fsrs 6 ships them.
    const W: [f64; 21] = [
        0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666, 0.796,
        1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
    ];

    /// FSRS-5 (19-weight) preset, upgraded to 21 by `fill_parameters`.
    const W19: [f64; 19] = [
        0.40255, 1.18385, 3.173, 15.69105, 7.1949, 0.5345, 1.4604, 0.0046, 1.54575, 0.1192,
        1.01925, 1.9395, 0.11, 0.29605, 2.2698, 0.2315, 2.9898, 0.51655, 0.6621,
    ];

    fn close(actual: f64, expected: f64) {
        assert!((actual - expected).abs() < 1e-4, "got {actual}, want {expected}");
    }

    #[test]
    fn test_fill_parameters_accepts_supported_lengths() {
        assert!(fill_parameters(&[]).is_none(), "no weights means FSRS is off");
        assert!(fill_parameters(&[1.0, 2.0]).is_none(), "an unknown length has no mapping");

        let twenty_one = fill_parameters(&W).expect("21 weights pass through");
        assert_eq!(twenty_one, W.to_vec());

        let from19 = fill_parameters(&W19).expect("19 weights upgrade");
        assert_eq!(from19.len(), 21);
        close(from19[19], 0.0);
        close(from19[20], 0.5);

        // 17: p[4] uses the old p[5], then p[5] is replaced, and p[6] is bumped by 0.5.
        let mut raw17 = [0.0; 17];
        raw17[4] = 10.0;
        raw17[5] = 2.0;
        raw17[6] = 1.0;
        let from17 = fill_parameters(&raw17).expect("17 weights upgrade");
        assert_eq!(from17.len(), 21);
        close(from17[4], 14.0);
        close(from17[5], 7.0_f64.ln() / 3.0);
        close(from17[6], 1.5);
        assert_eq!(&from17[17..], &[0.0, 0.0, 0.0, 0.5]);
    }

    #[test]
    fn test_initial_state_matches_reference() {
        close(initial_state(&W, 1).0, 0.212);
        close(initial_state(&W, 1).1, 6.4133);
        close(initial_state(&W, 2).0, 1.2931);
        close(initial_state(&W, 2).1, 5.112171);
        close(initial_state(&W, 3).0, 2.3065);
        close(initial_state(&W, 3).1, 2.118104);
        close(initial_state(&W, 4).0, 8.2956);
        close(initial_state(&W, 4).1, 1.0);
    }

    #[test]
    fn test_next_state_review_after_ten_days() {
        // S=10, D=5, elapsed 10d, R=0.9.
        let (s1, d1) = next_state(&W, 10.0, 5.0, 10.0, 1);
        close(s1, 1.391987);
        close(d1, 8.341762);

        let (s2, d2) = next_state(&W, 10.0, 5.0, 10.0, 2);
        close(s2, 23.246875);
        close(d2, 6.665995);
        assert_eq!(next_interval(&W, s2, 0.9).round(), 23.0);

        let (s3, d3) = next_state(&W, 10.0, 5.0, 10.0, 3);
        close(s3, 32.026729);
        close(d3, 4.990228);
        assert_eq!(next_interval(&W, s3, 0.9).round(), 32.0);

        let (s4, d4) = next_state(&W, 10.0, 5.0, 10.0, 4);
        close(s4, 51.253862);
        close(d4, 3.314461);
        assert_eq!(next_interval(&W, s4, 0.9).round(), 51.0);
    }

    #[test]
    fn test_next_state_review_after_thirteen_days() {
        // Elapsed 13d, R=0.880987.
        let (s2, _) = next_state(&W, 10.0, 5.0, 13.0, 2);
        close(s2, 25.886961);
        assert_eq!(next_interval(&W, s2, 0.9).round(), 26.0);

        let (s3, _) = next_state(&W, 10.0, 5.0, 13.0, 3);
        close(s3, 36.416629);
        assert_eq!(next_interval(&W, s3, 0.9).round(), 36.0);

        let (s4, _) = next_state(&W, 10.0, 5.0, 13.0, 4);
        close(s4, 59.475705);
        assert_eq!(next_interval(&W, s4, 0.9).round(), 59.0);
    }

    #[test]
    fn test_next_state_same_day() {
        // No elapsed time: the w[19] same-day term applies.
        close(next_state(&W, 10.0, 5.0, 0.0, 1).0, 3.051249);
        close(next_state(&W, 10.0, 5.0, 0.0, 2).0, 10.0);
        close(next_state(&W, 10.0, 5.0, 0.0, 3).0, 10.0);
        close(next_state(&W, 10.0, 5.0, 0.0, 4).0, 15.534308);
    }

    #[test]
    fn test_next_interval_tracks_desired_retention() {
        assert_eq!(next_interval(&W, 10.0, 0.9).round(), 10.0);
        assert_eq!(next_interval(&W, 10.0, 0.85).round(), 19.0);
        assert_eq!(next_interval(&W, 10.0, 0.95).round(), 4.0);
    }

    #[test]
    fn test_filled_fsrs5_weights_match_reference() {
        // S=10, D=5, elapsed 10d, Good.
        let w = fill_parameters(&W19).expect("19 weights upgrade");
        let (s, d) = next_state(&w, 10.0, 5.0, 10.0, 3);
        close(s, 32.954264);
        close(d, 4.991833);
        assert_eq!(next_interval(&w, s, 0.9).round(), 33.0);
    }
}
