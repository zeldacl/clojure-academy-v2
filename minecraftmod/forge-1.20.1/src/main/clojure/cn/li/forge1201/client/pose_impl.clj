(ns cn.li.forge1201.client.pose-impl
  "CLIENT-ONLY: Client-side pose stack and vertex consumer implementation for Forge 1.20.1.

  This namespace wraps com.mojang.blaze3d.vertex.PoseStack and VertexConsumer
  and must only be loaded on the client side via side-checked requiring-resolve."
  (:import [com.mojang.blaze3d.vertex PoseStack VertexConsumer]
           [org.joml Matrix3f Matrix4f]))

(defn rotate-y
  "Rotate pose stack around Y axis.

  Args:
    pose-stack: PoseStack
    angle: float - Rotation angle in degrees

  Returns:
    nil"
  [^PoseStack pose-stack angle]
  (.mulPose pose-stack (.rotationDegrees com.mojang.math.Axis/YP (float angle))))

(defn rotate-x
  "Rotate pose stack around X axis."
  [^PoseStack pose-stack angle]
  (.mulPose pose-stack (.rotationDegrees com.mojang.math.Axis/XP (float angle))))

(defn rotate-z
  "Rotate pose stack around Z axis.

  Args:
    pose-stack: PoseStack
    angle: float - Rotation angle in degrees

  Returns:
    nil"
  [^PoseStack pose-stack angle]
  (.mulPose pose-stack (.rotationDegrees com.mojang.math.Axis/ZP (float angle))))

(defn push-pose
  "Push a new pose onto the stack.

  Args:
    pose-stack: PoseStack

  Returns:
    nil"
  [^PoseStack pose-stack]
  (.pushPose pose-stack))

(defn pop-pose
  "Pop the current pose from the stack.

  Args:
    pose-stack: PoseStack

  Returns:
    nil"
  [^PoseStack pose-stack]
  (.popPose pose-stack))

(defn translate
  "Translate the pose stack.

  Args:
    pose-stack: PoseStack
    x: double
    y: double
    z: double

  Returns:
    nil"
  [^PoseStack pose-stack x y z]
  (.translate pose-stack (double x) (double y) (double z)))

(defn scale
  "Scale the pose stack.

  Args:
    pose-stack: PoseStack
    x: float
    y: float
    z: float

  Returns:
    nil"
  [^PoseStack pose-stack x y z]
  (.scale pose-stack (float x) (float y) (float z)))

(defn get-pose-matrix
  "Get the current pose matrix from the stack.

  Args:
    pose-stack: PoseStack

  Returns:
    Matrix4f - The current transformation matrix"
  [^PoseStack pose-stack]
  (.pose (.last pose-stack)))

(defn submit-vertex
  "Submit a vertex using the current pose entry's position and normal matrices.

  Normals must be transformed when the pose includes scale (e.g. OBJ BER); use
  `VertexConsumer.normal(Matrix3f, nx, ny, nz)` with `(.normal (.last pose-stack))`."
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
