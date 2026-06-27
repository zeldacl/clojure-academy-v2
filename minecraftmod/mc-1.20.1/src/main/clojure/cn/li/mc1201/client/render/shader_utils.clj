(ns cn.li.mc1201.client.render.shader-utils
  "CLIENT-ONLY shared shader resolution utility.

  Resolves ShaderInstance objects from the Forge ModShaders registry
  using reflection to avoid compile-time dependency on the forge module.
  Used by both the draw-ops screen host and the CGUI renderer."
  (:import [net.minecraft.client.renderer ShaderInstance]))

(defn resolve-shader
  "Resolve a ShaderInstance from the Forge ModShaders registry by name.
  Uses reflection to avoid compile-time dependency on forge module.

  Supported shader names:
    :skill-progbar — radial progress ring shader
    :mono          — grayscale/monochrome shader
    :alpha-discard — alpha-threshold discard shader (for depth masking)"
  [shader-name]
  (try
    (let [mod-shaders-class (Class/forName "cn.li.forge1201.client.render.ModShaders")]
      (case shader-name
        :skill-progbar (some-> (.getDeclaredMethod mod-shaders-class "getSkillProgbarShader" (into-array Class []))
                               (.invoke nil (into-array Object [])))
        :mono (some-> (.getDeclaredMethod mod-shaders-class "getMonoShader" (into-array Class []))
                      (.invoke nil (into-array Object [])))
        :alpha-discard (some-> (.getDeclaredMethod mod-shaders-class "getAlphaDiscardShader" (into-array Class []))
                               (.invoke nil (into-array Object [])))
        nil))
    (catch Exception _
      nil)))
