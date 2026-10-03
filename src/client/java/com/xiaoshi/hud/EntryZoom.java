package com.xiaoshi.hud;

/**
 * The camera-style zoom played when one of the celestial screens opens: the view starts pulled back
 * and eases forward into its normal framing over {@value #DURATION_SECONDS}s.
 *
 * <p>This scales the <em>view</em> (the star wheel's or orbit chart's own zoom), not the interface:
 * the HUD, panel and text stay the size they are while the sky itself grows into place. Any input
 * from the player cancels the animation so it never fights with the wheel or a drag.
 */
public final class EntryZoom {
	private static final double DURATION_SECONDS = 0.55;
	/** How far back the view starts: 0.45 = the sky is less than half its final size. */
	private static final double START_FACTOR = 0.45;

	private final long startNanos = System.nanoTime();
	private boolean active = true;

	/** Stops the animation immediately, leaving the view at its normal framing. */
	public void cancel() {
		active = false;
	}

	public boolean active() {
		return active;
	}

	/** Multiplier for the view zoom: {@value #START_FACTOR} at the start, exactly 1 once done. */
	public double factor() {
		if (!active) {
			return 1.0;
		}
		double t = (System.nanoTime() - startNanos) / 1.0E9 / DURATION_SECONDS;
		if (t >= 1.0) {
			active = false;
			return 1.0;
		}
		double eased = 1.0 - Math.pow(1.0 - Math.max(0.0, t), 3.0);
		return START_FACTOR + (1.0 - START_FACTOR) * eased;
	}
}
