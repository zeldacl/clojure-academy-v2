(ns cn.li.forge1201.client.pose-impl
  "CLIENT-ONLY: Client-side pose stack and vertex consumer implementation for Forge 1.20.1.

  This namespace wraps com.mojang.blaze3d.vertex.PoseStack and VertexConsumer
  and must only be loaded on the client side via side-checked requiring-resolve."
  (:import [com.mojang.blaze3d.vertex PoseStack VertexConsumer]
           [org.joml Matrix4f]))

(defn rotate-y
  "Rotate pose stack around Y axis.

  Args:
    pose-stack: PoseStack
    angle: float - Rotation angle in degrees

  Returns:
    nil"
  [^PoseStack pose-stack angle]
  (.mulPose pose-stack (.rotationDegrees com.mojang.math.Axis/YP (float angle))))

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
  "Submit a vertex to the vertex consumer.

  Args:
    vc: VertexConsumer
    matrix: Matrix4f - Transformation matrix
    x, y, z: float - Position
    r, g, b, a: float - Color (0.0-1.0)
    u, v: float - Texture coordinates
    overlay: int - Overlay coordinates
    uv2: int - Light map coordinates
    nx, ny, nz: float - Normal vector

  Returns:
    nil"
  [^VertexConsumer vc ^Matrix4f matrix x y z r g b a u v overlay uv2 nx ny nz]
  (-> vc
      (.vertex matrix (float x) (float y) (float z))
      (.color (float r) (float g) (float b) (float a))
      (.uv (float u) (float v))
      (.overlayCoords (int overlay))
      (.uv2 (int uv2))
      (.normal (float nx) (float ny) (float nz))
      (.endVertex)))
