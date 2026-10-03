package com.xiaoshi.hud;

import net.minecraft.client.gui.DrawContext;

/**
 * The short zoom-in played when one of the celestial screens opens: the whole interface grows from
 * {@value #START_SCALE}x to full size over {@value #DURATION_SECONDS}s with an ease-out curve.
 *
 * <p>Scaling is done through the draw matrix, so every renderer inside the screen (the orbit chart,
 * the globe view, the star wheel, all labels) is carried along by a single push/pop and no renderer
 * needs to know about the animation.
 */
public final class EnterAnimation {
	private static final double DURATION_SECONDS = 0.35;
	private static final double START_SCALE = 0.82;

	private long startNanos = System.nanoTime();

	/** Restarts the animation, e.g. when the same screen is reused instead of rebuilt. */
	public void restart() {
		startNanos = System.nanoTime();
	}

	/** Eased 0..1 progress of the animation. */
	public double progress() {
		double t = (System.nanoTime() - startNanos) / 1.0E9 / DURATION_SECONDS;
		t = Math.max(0.0, Math.min(1.0, t));
		return 1.0 - Math.pow(1.0 - t, 3.0);
	}

	/** Current scale factor, 0.82 at the start and exactly 1 once finished. */
	public double scale() {
		return START_SCALE + (1.0 - START_SCALE) * progress();
	}

	/** Scales the draw matrix about the centre of the window until the animation finishes. */
	public void push(DrawContext context, int width, int height) {
		double scale = scale();
		context.getMatrices().push();
		if (scale < 0.999) {
			context.getMatrices().translate(width / 2.0, height / 2.0, 0.0);
			context.getMatrices().scale((float) scale, (float) scale, 1.0F);
			context.getMatrices().translate(-width / 2.0, -height / 2.0, 0.0);
		}
	}

	public void pop(DrawContext context) {
		context.getMatrices().pop();
	}
}
