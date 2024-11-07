package net.irisshaders.iris.pipeline.transform.transformer;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import net.irisshaders.iris.gl.blending.AlphaTests;
import net.irisshaders.iris.gl.shader.ShaderType;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.parameter.VanillaParameters;

public class VanillaTransformer {
	public static void transform(
		ASTParser t,
		TranslationUnit tree,
		Root root,
		VanillaParameters parameters) {
		// this happens before common to make sure the renaming of attributes is done on
		// attribute inserted by this
		if (parameters.inputs.hasOverlay()) {
			EntityPatcher.patchOverlayColor(t, tree, root, parameters);
			EntityPatcher.patchEntityId(t, tree, root, parameters);
		} else if (parameters.inputs.isText()) {
			EntityPatcher.patchEntityId(t, tree, root, parameters);
		}

		CommonTransformer.transform(t, tree, root, parameters, false);

		if (parameters.type.glShaderType == ShaderType.VERTEX) {
			if (parameters.injectAmbientOcclusion) {
				tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS, "float iris_ambientOcclusion = 1.0f;");
				tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS, "bool iris_Shade = true;");
			}

			// Alias of gl_MultiTexCoord1 on 1.15+ for OptiFine
			// See https://github.com/IrisShaders/Iris/issues/1149
			root.rename("gl_MultiTexCoord2", "gl_MultiTexCoord1");

			if (parameters.inputs.hasTex()) {
				root.replaceReferenceExpressions(t, "gl_MultiTexCoord0",
					"vec4(irisInt_UV0, 0.0, 1.0)");
				tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
					"in vec2 irisInt_UV0;");
			} else {
				root.replaceReferenceExpressions(t, "gl_MultiTexCoord0",
					"vec4(0.5, 0.5, 0.0, 1.0)");
			}

			if (parameters.inputs.isIE()) {
				root.replaceReferenceExpressions(t, "gl_MultiTexCoord1",
					"vec4(irisInt_LightUV, 0.0, 1.0)");

				tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
					"uniform ivec2 irisInt_LightUV;");
			} else if (parameters.inputs.hasLight()) {
				root.replaceReferenceExpressions(t, "gl_MultiTexCoord1",
					"vec4(irisInt_UV2, 0.0, 1.0)");

				tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
					"in ivec2 irisInt_UV2;");
			} else {
				root.replaceReferenceExpressions(t, "gl_MultiTexCoord1",
					"vec4(240.0, 240.0, 0.0, 1.0)");
			}

			CommonTransformer.patchMultiTexCoord3(t, tree, root, parameters);

			// gl_MultiTexCoord0 and gl_MultiTexCoord1 are the only valid inputs (with
			// gl_MultiTexCoord2 and gl_MultiTexCoord3 as aliases), other texture
			// coordinates are not valid inputs.
			CommonTransformer.replaceGlMultiTexCoordBounded(t, root, 4, 7);
		}

		tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
			"uniform vec4 irisInt_ColorModulator;");

		if (parameters.inputs.hasColor() && parameters.type == PatchShaderType.VERTEX) {
			// TODO: Handle the fragment / geometry shader here
			if (parameters.alpha == AlphaTests.VERTEX_ALPHA) {
				root.replaceReferenceExpressions(t, "gl_Color",
					"vec4((irisInt_Color * irisInt_ColorModulator).rgb, irisInt_ColorModulator.a)");
			} else {
				root.replaceReferenceExpressions(t, "gl_Color",
					"(irisInt_Color * irisInt_ColorModulator)");
			}

			if (parameters.type.glShaderType == ShaderType.VERTEX) {
				tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
					"in vec4 irisInt_Color;");
			}
		} else if (parameters.inputs.isGlint()) {
			tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"uniform float irisInt_GlintAlpha;");
			// irisInt_ColorModulator should be applied regardless of the alpha test state.
			root.replaceReferenceExpressions(t, "gl_Color", "vec4(irisInt_ColorModulator.rgb, irisInt_ColorModulator.a * irisInt_GlintAlpha)");
		} else {
			// irisInt_ColorModulator should be applied regardless of the alpha test state.
			root.rename("gl_Color", "irisInt_ColorModulator");
		}

		if (parameters.type.glShaderType == ShaderType.VERTEX) {
			if (parameters.inputs.hasNormal()) {
				if (!parameters.inputs.isNewLines()) {
					root.rename("gl_Normal", "irisInt_Normal");
				} else {
					root.replaceReferenceExpressions(t, "gl_Normal",
						"vec3(0.0, 0.0, 1.0)");
				}

				tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
					"in vec3 irisInt_Normal;");
			} else {
				root.replaceReferenceExpressions(t, "gl_Normal",
					"vec3(0.0, 0.0, 1.0)");
			}
		}

		tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
			"uniform mat4 irisInt_LightmapTextureMatrix;",
			"uniform mat4 irisInt_TextureMat;",
			"uniform mat4 irisInt_ModelViewMat;");

		// TODO: More solid way to handle texture matrices
		root.replaceExpressionMatches(t, CommonTransformer.glTextureMatrix0, "irisInt_TextureMat");
		root.replaceExpressionMatches(t, CommonTransformer.glTextureMatrix1, "irisInt_LightmapTextureMatrix");

		// TODO: Should probably add the normal matrix as a proper uniform that's
		// computed on the CPU-side of things
		root.replaceReferenceExpressions(t, "gl_NormalMatrix",
			"irisInt_NormalMat");

		root.replaceReferenceExpressions(t, "gl_ModelViewMatrixInverse",
			"irisInt_ModelViewMatInverse");

		root.replaceReferenceExpressions(t, "gl_ProjectionMatrixInverse",
			"irisInt_ProjMatInverse");
		tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS, "uniform mat3 irisInt_NormalMat;");
		tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS, "uniform mat4 irisInt_ProjMatInverse;");
		tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS, "uniform mat4 irisInt_ModelViewMatInverse;");

		if (parameters.type.glShaderType == ShaderType.VERTEX) {
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"in vec3 irisInt_Position;");
			if (root.identifierIndex.has("ftransform")) {
				tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_FUNCTIONS,
					"vec4 ftransform() { return gl_ModelViewProjectionMatrix * gl_Vertex; }");
			}

			if (parameters.inputs.isNewLines()) {
				root.replaceReferenceExpressions(t, "gl_Vertex",
					"vec4(irisInt_Position + irisInt_vertex_offset, 1.0)");

				// Create our own main function to wrap the existing main function, so that we
				// can do our line shenanigans.
				// TRANSFORM: this is fine since the AttributeTransformer has a different name
				// in the vertex shader
				root.rename("main", "irisMain");
				tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
					"vec3 irisInt_vertex_offset = vec3(0.0);");
				tree.parseAndInjectNodes(t, ASTInjectionPoint.END,
					"uniform vec2 irisInt_ScreenSize;",
					"uniform float irisInt_LineWidth;",
					"void irisInt_widen_lines(vec4 linePosStart, vec4 linePosEnd) {" +
						"vec3 ndc1 = linePosStart.xyz / linePosStart.w;" +
						"vec3 ndc2 = linePosEnd.xyz / linePosEnd.w;" +
						"vec2 lineScreenDirection = normalize((ndc2.xy - ndc1.xy) * irisInt_ScreenSize);" +
						"vec2 lineOffset = vec2(-lineScreenDirection.y, lineScreenDirection.x) * irisInt_LineWidth / irisInt_ScreenSize;"
						+
						"if (lineOffset.x < 0.0) {" +
						"    lineOffset *= -1.0;" +
						"}" +
						"if (gl_VertexID % 2 == 0) {" +
						"    gl_Position = vec4((ndc1 + vec3(lineOffset, 0.0)) * linePosStart.w, linePosStart.w);" +
						"} else {" +
						"    gl_Position = vec4((ndc1 - vec3(lineOffset, 0.0)) * linePosStart.w, linePosStart.w);" +
						"}}",
					"void main() {" +
						"irisInt_vertex_offset = irisInt_Normal;" +
						"irisMain();" +
						"vec4 linePosEnd = gl_Position;" +
						"gl_Position = vec4(0.0);" +
						"irisInt_vertex_offset = vec3(0.0);" +
						"irisMain();" +
						"vec4 linePosStart = gl_Position;" +
						"irisInt_widen_lines(linePosStart, linePosEnd);}");
			} else {
				root.replaceReferenceExpressions(t, "gl_Vertex", "vec4(irisInt_Position, 1.0)");
			}
		}

		// TODO: All of the transformed variants of the input matrices, preferably
		// computed on the CPU side...
		root.replaceReferenceExpressions(t, "gl_ModelViewProjectionMatrix",
			"(gl_ProjectionMatrix * gl_ModelViewMatrix)");

		if (parameters.hasChunkOffset) {
			boolean doInjection = root.replaceReferenceExpressionsReport(t, "gl_ModelViewMatrix",
				"(irisInt_ModelViewMat * _irisInt_internal_translate(irisInt_ChunkOffset))");
			if (doInjection) {
				tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_FUNCTIONS,
					"uniform vec3 irisInt_ChunkOffset;",
					"mat4 _irisInt_internal_translate(vec3 offset) {" +
						"return mat4(1.0, 0.0, 0.0, 0.0," +
						"0.0, 1.0, 0.0, 0.0," +
						"0.0, 0.0, 1.0, 0.0," +
						"offset.x, offset.y, offset.z, 1.0); }");
			}
		} else if (parameters.inputs.isNewLines()) {
			tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
				"const float irisInt_VIEW_SHRINK = 1.0 - (1.0 / 256.0);",
				"const mat4 irisInt_VIEW_SCALE = mat4(" +
					"irisInt_VIEW_SHRINK, 0.0, 0.0, 0.0," +
					"0.0, irisInt_VIEW_SHRINK, 0.0, 0.0," +
					"0.0, 0.0, irisInt_VIEW_SHRINK, 0.0," +
					"0.0, 0.0, 0.0, 1.0);");
			root.replaceReferenceExpressions(t, "gl_ModelViewMatrix",
				"(irisInt_VIEW_SCALE * irisInt_ModelViewMat)");
		} else {
			root.rename("gl_ModelViewMatrix", "irisInt_ModelViewMat");
		}

		root.rename("gl_ProjectionMatrix", "irisInt_ProjMat");
		tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
			"uniform mat4 irisInt_ProjMat;");
	}
}
