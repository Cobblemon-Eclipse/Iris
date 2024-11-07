package net.irisshaders.iris.uniforms;

import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformType;

public class ExternallyManagedUniforms {
	private ExternallyManagedUniforms() {
		// no construction allowed
	}

	public static void addExternallyManagedUniforms(UniformHolder uniformHolder) {
		addMat4(uniformHolder, "irisInt_ModelViewMatrix");
		addMat4(uniformHolder, "u_ModelViewProjectionMatrix");
		addMat3(uniformHolder, "irisInt_NormalMatrix");
		// Exclusive to pre-1.19
		addFloat(uniformHolder, "darknessFactor");
		addFloat(uniformHolder, "darknessLightFactor");
	}

	public static void addExternallyManagedUniforms116(UniformHolder uniformHolder) {
		addExternallyManagedUniforms(uniformHolder);

		uniformHolder.externallyManagedUniform("u_ModelScale", UniformType.VEC3);
		uniformHolder.externallyManagedUniform("u_TextureScale", UniformType.VEC2);
	}

	public static void addExternallyManagedUniforms117(UniformHolder uniformHolder) {
		addExternallyManagedUniforms(uniformHolder);

		// Sodium
		addFloat(uniformHolder, "irisInt_FogStart");
		addFloat(uniformHolder, "irisInt_FogEnd");
		addVec4(uniformHolder, "irisInt_FogColor");
		addMat4(uniformHolder, "irisInt_ProjectionMatrix");
		addMat4(uniformHolder, "irisInt_ModelViewMatrix");
		addMat3(uniformHolder, "irisInt_NormalMatrix");
		addFloat(uniformHolder, "irisInt_TextureScale");
		addFloat(uniformHolder, "irisInt_GlintAlpha");
		addFloat(uniformHolder, "irisInt_ModelScale");
		addFloat(uniformHolder, "irisInt_ModelOffset");
		addVec3(uniformHolder, "irisInt_CameraTranslation");
		addVec3(uniformHolder, "u_RegionOffset");

		// Vanilla
		uniformHolder.externallyManagedUniform("irisInt_TextureMat", UniformType.MAT4);
		uniformHolder.externallyManagedUniform("irisInt_ModelViewMat", UniformType.MAT4);
		uniformHolder.externallyManagedUniform("irisInt_ProjMat", UniformType.MAT4);
		uniformHolder.externallyManagedUniform("irisInt_ChunkOffset", UniformType.VEC3);
		uniformHolder.externallyManagedUniform("irisInt_ColorModulator", UniformType.VEC4);
		uniformHolder.externallyManagedUniform("irisInt_NormalMat", UniformType.MAT3);
		uniformHolder.externallyManagedUniform("irisInt_FogStart", UniformType.FLOAT);
		uniformHolder.externallyManagedUniform("irisInt_FogEnd", UniformType.FLOAT);
		uniformHolder.externallyManagedUniform("irisInt_FogDensity", UniformType.FLOAT);
		uniformHolder.externallyManagedUniform("irisInt_LineWidth", UniformType.FLOAT);
		uniformHolder.externallyManagedUniform("irisInt_ScreenSize", UniformType.VEC2);
		uniformHolder.externallyManagedUniform("irisInt_FogColor", UniformType.VEC4);
	}

	private static void addMat3(UniformHolder uniformHolder, String name) {
		uniformHolder.externallyManagedUniform(name, UniformType.MAT3);
	}


	private static void addMat4(UniformHolder uniformHolder, String name) {
		uniformHolder.externallyManagedUniform(name, UniformType.MAT4);
	}

	private static void addVec3(UniformHolder uniformHolder, String name) {
		uniformHolder.externallyManagedUniform(name, UniformType.VEC3);
	}

	private static void addVec4(UniformHolder uniformHolder, String name) {
		uniformHolder.externallyManagedUniform(name, UniformType.VEC4);
	}

	private static void addFloat(UniformHolder uniformHolder, String name) {
		uniformHolder.externallyManagedUniform(name, UniformType.FLOAT);
	}
}
