(ns cn.li.mc1201.client.render.pose
  "CLIENT-ONLY shared pose stack and vertex consumer helpers for Minecraft 1.20.1."
  (:import [com.mojang.blaze3d.vertex PoseStack VertexConsumer]
           [org.joml Matrix3f Matrix4f]))

(defn rotate-y
  [^PoseStack pose-stack angle]
  (.mulPose pose-stack (.rotationDegrees com.mojang.math.Axis/YP (float angle))))

(defn rotate-x
  [^PoseStack pose-stack angle]
  (.mulPose pose-stack (.rotationDegrees com.mojang.math.Axis/XP (float angle))))

(defn rotate-z
  [^PoseStack pose-stack angle]
  (.mulPose pose-stack (.rotationDegrees com.mojang.math.Axis/ZP (float angle))))

(defn push-pose
  [^PoseStack pose-stack]
  (.pushPose pose-stack))

(defn pop-pose
  [^PoseStack pose-stack]
  (.popPose pose-stack))

(defn translate
  [^PoseStack pose-stack x y z]
  (.translate pose-stack (double x) (double y) (double z)))

(defn scale
  [^PoseStack pose-stack x y z]
  (.scale pose-stack (float x) (float y) (float z)))

(defn get-pose-matrix
  [^PoseStack pose-stack]
  (.pose (.last pose-stack)))

(defn submit-vertex
  "Submit one vertex using PoseStack's current pose + normal matrices."
  [^VertexConsumer vc ^PoseStack pose-stack x y z r g b a u v overlay uv2 nx ny nz]
  (let [entry (.last pose-stack)]
    (-> vc
        (.vertex ^Matrix4f (.pose entry) (float x) (float y) (float z))
        (.color (float r) (float g) (float b) (float a))
        (.uv (float u) (float v))
        (.overlayCoords (int overlay))
        (.uv2 (int uv2))
        (.normal ^Matrix3f (.normal entry) (float nx) (float ny) (float nz))
        (.endVertex))))
