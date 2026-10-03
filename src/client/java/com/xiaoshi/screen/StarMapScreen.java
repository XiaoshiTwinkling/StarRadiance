package com.xiaoshi.screen;

import com.xiaoshi.astro.PlanetPosition;
import com.xiaoshi.hud.EntryZoom;
import com.xiaoshi.hud.OrbitDiagram;
import com.xiaoshi.hud.PlanetView;
import com.xiaoshi.hud.SkyDebugHud;
import com.xiaoshi.sky.Celestial;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * The full-screen star map: a non-pausing screen (the world keeps running behind it, like chat) so
 * the mouse is free to explore the solar system and to turn the globes.
 *
 * <p>The left button only ever drags: it pans the orbit chart, and in the globe view it turns the
 * body. Right-clicking a planet zooms into a procedural globe of it, right-clicking again (or Esc)
 * goes back to the chart; the wheel zooms in both views.
 *
 * <p>K or Esc closes it (Esc first leaves the globe view), Shift switches between the opaque chart
 * and the see-through mode that also shows the translated data block.
 */
public class StarMapScreen extends Screen {
	private static final double ZOOM_SECONDS = 0.35;
	/** Wide enough to fit Eris' 68 AU orbit, tight enough to inspect the inner planets. */
	private static final double MIN_CHART_ZOOM = 0.01;
	private static final double MAX_CHART_ZOOM = 12.0;
	private static final double MIN_GLOBE_ZOOM = 0.6;
	private static final double MAX_GLOBE_ZOOM = 3.0;
	private static final double DRAG_DEGREES_PER_PIXEL = 0.35;

	private final Screen parent;
	private boolean transparent;
	private boolean shiftDown;
	/** Body index currently shown as a globe, or -1 while the orbit chart is showing. */
	private int zoomedBody = -1;
	private double progress;
	private long lastNanos;

	/** Screen circles of the bodies drawn by the last chart frame: {x, y, radius, planetIndex}. */
	private double[][] hits = new double[0][];
	/** Where the globe grows out of, captured from the chart. */
	private double originX = -100.0;
	private double originY = -100.0;
	private double originRadius = 12.0;

	private double chartZoom = 1.0;
	private double chartPanX;
	private double chartPanY;
	private double globeYaw;
	private double globePitch;
	private double globeZoom = 1.0;
	/** Where the current press started, so a drag is not mistaken for a click. */
	private double pressX;
	private double pressY;
	private boolean pressDragged;
	private final EntryZoom entry = new EntryZoom();

	public StarMapScreen(Screen parent) {
		super(Text.translatable("screen.starradiance.map.title"));
		this.parent = parent;
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	protected void init() {
		lastNanos = System.nanoTime();
	}

	@Override
	public void tick() {
		// Leaving the world (or a disconnect) closes the map instead of leaving an empty screen.
		if (this.client != null && this.client.world == null) {
			close();
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		// Deliberately no super.render(): no background panel, no widgets, the world stays visible.
		MinecraftClient client = this.client;
		if (client == null || client.world == null || client.player == null) {
			return;
		}
		updateZoom();
		double latitude = Celestial.latitudeOf(client.player.getZ());
		Celestial.SkyState state = Celestial.compute(client.world.getTimeOfDay(), latitude);

		double chartOpacity = 1.0 - progress;
		if (chartOpacity > 0.01) {
			// The chart eases in from further away; the interface itself does not move.
			double viewZoom = chartZoom * entry.factor();
			double[][] bodies = OrbitDiagram.render(context, client, state, 0, 0, this.width, this.height,
				transparent, chartOpacity, viewZoom, chartPanX, chartPanY, zoomedBody);
			hits = bodies != null ? bodies : new double[0][];
			if (zoomedBody >= 0) {
				captureOrigin(zoomedBody);
			}
			if (zoomedBody < 0 && chartOpacity > 0.5) {
				highlightHover(context, client, mouseX, mouseY);
			}
		}
		double globeOpacity = progress;
		if (globeOpacity > 0.01 && zoomedBody >= 0) {
			PlanetView.render(context, client, state, zoomedBody, globeOpacity, originX, originY,
				originRadius, globeYaw, globePitch, globeZoom);
		}
		SkyDebugHud.renderHint(context, client, transparent, zoomedBody >= 0, this.width, this.height);
		if (transparent) {
			SkyDebugHud.renderTextBlock(context, client, state, latitude);
		}
	}

	/** Rings the body under the cursor and shows its name, so the click target is obvious. */
	private void highlightHover(DrawContext context, MinecraftClient client, int mouseX, int mouseY) {
		int body = bodyAt(mouseX, mouseY);
		if (body < 0) {
			return;
		}
		double[] hit = hitOf(body);
		if (hit == null) {
			return;
		}
		OrbitDiagram.ring(context, hit[0], hit[1], hit[2] + 3.0, 0xA0FFFFFF);
		String name = Text.translatable("hud.starradiance.planet." + PlanetPosition.KEYS[body]).getString();
		context.drawText(client.textRenderer, name, (int) Math.round(hit[0]) + 12,
			(int) Math.round(hit[1]) - 4, 0xFFFFFF, true);
		context.drawText(client.textRenderer, Text.translatable("hud.starradiance.map.click"),
			(int) Math.round(hit[0]) + 12, (int) Math.round(hit[1]) + 7, 0xA8A8A8, true);
	}

	private void captureOrigin(int body) {
		double[] hit = hitOf(body);
		if (hit != null) {
			originX = hit[0];
			originY = hit[1];
			originRadius = hit[2];
		}
	}

	/** The body whose disc contains the cursor; the smallest such disc wins. */
	private int bodyAt(double mouseX, double mouseY) {
		int found = -1;
		double smallest = Double.MAX_VALUE;
		for (double[] hit : hits) {
			if (hit == null) {
				continue;
			}
			double dx = mouseX - hit[0];
			double dy = mouseY - hit[1];
			if (dx * dx + dy * dy <= hit[2] * hit[2] && hit[2] < smallest) {
				smallest = hit[2];
				found = (int) hit[3];
			}
		}
		return found;
	}

	private double[] hitOf(int body) {
		for (double[] hit : hits) {
			if (hit != null && (int) hit[3] == body) {
				return hit;
			}
		}
		return null;
	}

	private void updateZoom() {
		long now = System.nanoTime();
		double elapsed = Math.min(0.2, (now - lastNanos) / 1.0E9);
		lastNanos = now;
		double target = zoomedBody >= 0 ? 1.0 : 0.0;
		double step = elapsed / ZOOM_SECONDS;
		if (progress < target) {
			progress = Math.min(target, progress + step);
		} else if (progress > target) {
			progress = Math.max(target, progress - step);
		}
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT && button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
			return super.mouseClicked(mouseX, mouseY, button);
		}
		// Only remember the press: dragging either pans the chart or turns the globe, and the
		// action itself happens on release so a drag never counts as a click.
		entry.cancel();
		pressX = mouseX;
		pressY = mouseY;
		pressDragged = false;
		return true;
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		boolean wasClick = !pressDragged;
		pressDragged = false;
		if (!wasClick || button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
			// The left button only ever drags (pan the chart, turn the globe).
			return true;
		}
		if (zoomedBody >= 0) {
			// A plain right click leaves the globe view and eases back to the chart.
			zoomedBody = -1;
			return true;
		}
		int body = bodyAt(mouseX, mouseY);
		if (body >= 0) {
			captureOrigin(body);
			zoomedBody = body;
			globeYaw = 0.0;
			globePitch = 0.0;
			globeZoom = 1.0;
		}
		return true;
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT && button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
			return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
		}
		if (Math.hypot(mouseX - pressX, mouseY - pressY) > 3.0) {
			pressDragged = true;
		}
		entry.cancel();
		if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			// Right-dragging is only there to cancel the click that would otherwise open a body.
			return true;
		}
		if (zoomedBody >= 0) {
			globeYaw -= deltaX * DRAG_DEGREES_PER_PIXEL;
			globePitch = Math.max(-85.0, Math.min(85.0,
				globePitch - deltaY * DRAG_DEGREES_PER_PIXEL));
		} else {
			chartPanX += deltaX;
			chartPanY += deltaY;
		}
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount,
			double verticalAmount) {
		if (zoomedBody >= 0) {
			globeZoom = clamp(globeZoom * Math.pow(1.25, verticalAmount), MIN_GLOBE_ZOOM, MAX_GLOBE_ZOOM);
		} else {
			entry.cancel();
			chartZoom = clamp(chartZoom * Math.pow(1.25, verticalAmount), MIN_CHART_ZOOM, MAX_CHART_ZOOM);
		}
		return true;
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == GLFW.GLFW_KEY_LEFT_SHIFT || keyCode == GLFW.GLFW_KEY_RIGHT_SHIFT) {
			if (!shiftDown) {
				shiftDown = true;
				transparent = !transparent;
			}
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_R) {
			entry.cancel();
			chartZoom = 1.0;
			chartPanX = 0.0;
			chartPanY = 0.0;
			globeYaw = 0.0;
			globePitch = 0.0;
			globeZoom = 1.0;
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_K || keyCode == GLFW.GLFW_KEY_ESCAPE) {
			if (zoomedBody >= 0) {
				zoomedBody = -1;
			} else {
				close();
			}
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
		if (keyCode == GLFW.GLFW_KEY_LEFT_SHIFT || keyCode == GLFW.GLFW_KEY_RIGHT_SHIFT) {
			shiftDown = false;
			return true;
		}
		return super.keyReleased(keyCode, scanCode, modifiers);
	}

	@Override
	public void close() {
		if (this.client != null) {
			this.client.setScreen(this.parent);
		}
	}
}
