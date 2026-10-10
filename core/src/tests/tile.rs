//! Tile formatting.

use crate::*;

#[test]
fn test_format_queue_eta() {
    // Empty queue.
    assert_eq!(format_queue_eta(0, None), "All caught up 🎉");
    assert_eq!(format_queue_eta(-3, Some(5000)), "All caught up 🎉");

    // No observed pace: flat 10s/card fallback.
    assert_eq!(format_queue_eta(3, None), "~30s to finish");
    assert_eq!(format_queue_eta(6, None), "~1 min to finish");
    // A zero/negative average is ignored in favour of the fallback.
    assert_eq!(format_queue_eta(3, Some(0)), "~30s to finish");

    // Real pace: 20 cards at 8s each = 160s -> rounds up to 3 min.
    assert_eq!(format_queue_eta(20, Some(8000)), "~3 min to finish");
    // 4 cards at 12.5s each = 50s.
    assert_eq!(format_queue_eta(4, Some(12500)), "~50s to finish");
    // 59s stays in seconds; 60s rolls over to a minute; 61s rounds up to two.
    assert_eq!(format_queue_eta(59, Some(1000)), "~59s to finish");
    assert_eq!(format_queue_eta(60, Some(1000)), "~1 min to finish");
    assert_eq!(format_queue_eta(61, Some(1000)), "~2 min to finish");
}
