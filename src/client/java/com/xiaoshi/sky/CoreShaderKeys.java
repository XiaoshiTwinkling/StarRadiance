package com.xiaoshi.sky;

/**
 * Core shader program keys for 1.21.4 and newer.
 *
 * <p>1.21.1 registers our core shaders through Fabric's {@code CoreShaderRegistrationCallback} and
 * hands the compiled {@code ShaderProgram} to each renderer. That callback no longer exists in
 * 1.21.4: core shaders are instead identified by a {@code ShaderProgramKey} and resolved on demand,
 * so the renderers ask for the program through these keys. On 1.21.1 this class is empty.
 */
public final class CoreShaderKeys {
	//? if >=1.21.4 {
	/*public static final net.minecraft.client.gl.ShaderProgramKey MOON = key("moon",
		net.minecraft.client.render.VertexFormats.POSITION_TEXTURE);
	public static final net.minecraft.client.gl.ShaderProgramKey SUN = key("sun",
		net.minecraft.client.render.VertexFormats.POSITION_TEXTURE);
	public static final net.minecraft.client.gl.ShaderProgramKey PANEL_BLUR = key("panel_blur",
		net.minecraft.client.render.VertexFormats.POSITION_TEXTURE_COLOR);

	private static net.minecraft.client.gl.ShaderProgramKey key(String path,
			net.minecraft.client.render.VertexFormat format) {
		return new net.minecraft.client.gl.ShaderProgramKey(
			net.minecraft.util.Identifier.of("starradiance", "core/" + path), format,
			net.minecraft.client.gl.Defines.EMPTY);
	}
	*///?}

	private CoreShaderKeys() {
	}
}
