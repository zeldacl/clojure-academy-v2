(ns cn.li.mc1201.client.gl-ops
  "Direct LWJGL GL11 ops for mcmod render util injection."
  (:import [org.lwjgl.opengl GL11]))

(defn ops-map
  []
  {:push-matrix #(GL11/glPushMatrix)
   :pop-matrix #(GL11/glPopMatrix)
   :translate #(GL11/glTranslated (double %1) (double %2) (double %3))
   :rotate #(GL11/glRotated (double %1) (double %2) (double %3) (double %4))
   :scale #(GL11/glScaled (double %1) (double %2) (double %3))
   :begin-triangles #(GL11/glBegin GL11/GL_TRIANGLES)
   :end #(GL11/glEnd)
   :normal #(GL11/glNormal3f (float %1) (float %2) (float %3))
   :tex-coord #(GL11/glTexCoord2f (float %1) (float %2))
   :vertex #(GL11/glVertex3f (float %1) (float %2) (float %3))})
