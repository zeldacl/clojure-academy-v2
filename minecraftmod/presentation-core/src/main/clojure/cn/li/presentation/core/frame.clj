(ns cn.li.presentation.core.frame
  (:import [cn.li.mcmod.runtime FramePacket RenderCommand$Mesh RenderPass RenderStage]))

(def dirty
  {:structure 1 :measure 2 :layout 4 :paint 8 :transform 16 :semantics 32})

(def stages (vec (RenderStage/values)))

(defn packet [frame-id passes]
  (FramePacket. frame-id (mapv #(RenderPass. (first %) (vec (second %))) passes)))

(defn append-mesh-payload
  "Append immutable geometry data for a version-owned mesh callback."
  [^FramePacket packet stage payload]
  (FramePacket. (.frameId packet)
                (conj (vec (.passes packet))
                      (RenderPass. stage [(RenderCommand$Mesh. -1 0 1 payload)]))))
