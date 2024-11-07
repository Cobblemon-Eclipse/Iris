package net.irisshaders.iris.pipeline.transform.transformer;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier.StorageType;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import io.github.douira.glsl_transformer.util.Type;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.parameter.VanillaParameters;

import static net.irisshaders.iris.pipeline.transform.transformer.CommonTransformer.addIfNotExists;

public class VanillaCoreTransformer {
	public static void transform(
		ASTParser t,
		TranslationUnit tree,
		Root root,
		VanillaParameters parameters) {

		if (parameters.inputs.hasOverlay()) {
			if (!parameters.inputs.isText()) {
				EntityPatcher.patchOverlayColor(t, tree, root, parameters);
			}
			EntityPatcher.patchEntityId(t, tree, root, parameters);
		}
		tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS, "float s_ambientOcclusion = 1.0f;");

		CommonTransformer.transform(t, tree, root, parameters, true);
		root.rename("alphaTestRef", "irisInt_currentAlphaTest");
		root.rename("modelViewMatrix", "irisInt_ModelViewMat");
		root.rename("gl_ModelViewMatrix", "irisInt_ModelViewMat");
		root.rename("modelViewMatrixInverse", "irisInt_ModelViewMatInverse");
		root.rename("gl_ModelViewMatrixInverse", "irisInt_ModelViewMatInverse");
		root.rename("projectionMatrix", "irisInt_ProjMat");
		root.rename("gl_ProjectionMatrix", "irisInt_ProjMat");
		root.rename("projectionMatrixInverse", "irisInt_ProjMatInverse");
		root.rename("gl_ProjectionMatrixInverse", "irisInt_ProjMatInverse");
		root.rename("textureMatrix", "irisInt_TextureMat");

		root.replaceExpressionMatches(t, CommonTransformer.glTextureMatrix0, "irisInt_TextureMat");
		root.replaceExpressionMatches(t, CommonTransformer.glTextureMatrix1,
			"mat4(vec4(0.00390625, 0.0, 0.0, 0.0), vec4(0.0, 0.00390625, 0.0, 0.0), vec4(0.0, 0.0, 0.00390625, 0.0), vec4(0.03125, 0.03125, 0.03125, 1.0))");
		root.replaceExpressionMatches(t, CommonTransformer.glTextureMatrix2,
			"mat4(vec4(0.00390625, 0.0, 0.0, 0.0), vec4(0.0, 0.00390625, 0.0, 0.0), vec4(0.0, 0.0, 0.00390625, 0.0), vec4(0.03125, 0.03125, 0.03125, 1.0))");
		addIfNotExists(root, t, tree, "irisInt_TextureMat", Type.F32MAT4X4, StorageType.UNIFORM);
		addIfNotExists(root, t, tree, "irisInt_ProjMat", Type.F32MAT4X4, StorageType.UNIFORM);
		addIfNotExists(root, t, tree, "irisInt_ProjMatInverse", Type.F32MAT4X4, StorageType.UNIFORM);
		addIfNotExists(root, t, tree, "irisInt_ModelViewMat", Type.F32MAT4X4, StorageType.UNIFORM);
		addIfNotExists(root, t, tree, "irisInt_ModelViewMatInverse", Type.F32MAT4X4, StorageType.UNIFORM);
		root.rename("normalMatrix", "irisInt_NormalMat");
		root.rename("gl_NormalMatrix", "irisInt_NormalMat");
		addIfNotExists(root, t, tree, "irisInt_NormalMat", Type.F32MAT3X3, StorageType.UNIFORM);
		root.rename("chunkOffset", "irisInt_ChunkOffset");
		addIfNotExists(root, t, tree, "irisInt_ChunkOffset", Type.F32VEC3, StorageType.UNIFORM);

		CommonTransformer.upgradeStorageQualifiers(t, tree, root, parameters);

		if (parameters.type == PatchShaderType.VERTEX) {
			root.replaceReferenceExpressions(t, "gl_Vertex", "vec4(irisInt_Position, 1.0)");
			root.rename("vaPosition", "irisInt_Position");
			if (parameters.inputs.hasColor()) {
				root.replaceReferenceExpressions(t, "vaColor", "irisInt_Color * irisInt_ColorModulator");
				root.replaceReferenceExpressions(t, "gl_Color", "irisInt_Color * irisInt_ColorModulator");
			} else {
				root.replaceReferenceExpressions(t, "vaColor", "irisInt_ColorModulator");
				root.replaceReferenceExpressions(t, "gl_Color", "irisInt_ColorModulator");
			}
			root.rename("vaNormal", "irisInt_Normal");
			root.rename("gl_Normal", "irisInt_Normal");
			root.rename("vaUV0", "irisInt_UV0");
			root.replaceReferenceExpressions(t, "gl_MultiTexCoord0", "vec4(irisInt_UV0, 0.0, 1.0)");
			if (parameters.inputs.hasLight()) {
				root.replaceReferenceExpressions(t, "gl_MultiTexCoord1", "vec4(irisInt_UV2, 0.0, 1.0)");
				root.replaceReferenceExpressions(t, "gl_MultiTexCoord2", "vec4(irisInt_UV2, 0.0, 1.0)");
				root.rename("vaUV2", "irisInt_UV2");
			} else {
				root.replaceReferenceExpressions(t, "gl_MultiTexCoord1", "vec4(240.0, 240.0, 0.0, 1.0)");
				root.replaceReferenceExpressions(t, "gl_MultiTexCoord2", "vec4(240.0, 240.0, 0.0, 1.0)");
				root.rename("vaUV2", "irisInt_UV2");
			}
			root.rename("vaUV1", "irisInt_UV1");

			addIfNotExists(root, t, tree, "irisInt_Color", Type.F32VEC4, StorageType.IN);
			addIfNotExists(root, t, tree, "irisInt_ColorModulator", Type.F32VEC4, StorageType.UNIFORM);
			addIfNotExists(root, t, tree, "irisInt_Position", Type.F32VEC3, StorageType.IN);
			addIfNotExists(root, t, tree, "irisInt_Normal", Type.F32VEC3, StorageType.IN);
			addIfNotExists(root, t, tree, "irisInt_UV0", Type.F32VEC2, StorageType.IN);
			addIfNotExists(root, t, tree, "irisInt_UV1", Type.F32VEC2, StorageType.IN);
			addIfNotExists(root, t, tree, "irisInt_UV2", Type.F32VEC2, StorageType.IN);
		}
	}
}
