(ns cn.li.mcbase.presentation.frame-dispatch
  "Opaque FramePacket dispatch. Base never inspects Render IR semantics.")

(defn submit! [backend frame-packet stage]
  ((:submit! backend) stage frame-packet)
  nil)

(defn reload! [backend generation]
  ((:reload! backend) generation)
  nil)
