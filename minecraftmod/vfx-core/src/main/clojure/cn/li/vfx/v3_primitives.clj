(ns cn.li.vfx.v3-primitives
  "v3 registration for vfx-core's genuinely orthogonal render primitives
   (R3, mossy-wren plan; NODE_LANGUAGE.md section 9): :vfx/line, :vfx/quad
   (world geometry), :vfx/camera, :vfx/audio-one-shot, :vfx/audio-loop
   (presentation, no geometry). :impl is a pure data-shaping function --
   given typed, already-resolved inputs, it returns one op map, reusing
   cn.li.vfx.ops's tested construction logic for the two geometry
   primitives rather than a second copy of it. A future vfx-core v3
   sampler collects every op one graph evaluation produces and wraps them
   into the batch shape each op's own :stage/:kind implies (:mesh/:ops for
   world geometry, a distinct primitive for :camera/:audio) -- that
   accumulation loop, and the structural primitives (:vfx/repeat/
   :vfx/transform/:vfx/let/:vfx/curve/:vfx/branch/:vfx/timeline) it would
   drive, are deliberately NOT written yet: they require first deciding
   whether a vfx v3 graph keeps :input/:state as first-class value scopes
   alongside node-core's :local (the v2 model every effect document
   already uses, and genuinely different from an injected-environment
   read like combat's tunables/costs -- :input/:state are closer to a
   shader's uniform/varying inputs than a document-level table) or forces
   everything through source nodes the way combat-core's :ability/*
   family does. That is a real architectural decision, not busywork, and
   deserves its own pass rather than being decided as a side effect of
   registering five leaf primitives -- see the mossy-wren plan's R3
   section for the open question recorded in full.

   ADDITIVE ONLY at this revision: the v2 registry (cn.li.vfx.components)
   and vm.clj's :vfx/line/:vfx/quad defmethods are untouched and still
   serve every existing effect document; this is a parallel v3 vocabulary
   registered on node-core, not a replacement."
  (:require [cn.li.node.descriptor :as node]
            [cn.li.vfx.ops :as ops]))

(defn install!
  "Register the v3 render primitives. Call once per registry lifetime,
   before node/freeze!."
  []
  (node/register-primitive!
   {:id :vfx/line :revision 1 :category :render
    :doc "A single line segment, the platform renderer's own primitive shape."
    :inputs {:from {:type :vec3} :to {:type :vec3} :color {:type :color}}
    :outputs {:op {:type :render-op}} :effects #{:emit}
    :impl (fn [inputs _ctx] {:op (ops/line-op inputs)})})
  (node/register-primitive!
   {:id :vfx/quad :revision 1 :category :render
    :doc "A single quad (p0/p1 at the start edge, p2/p3 at the end edge; u runs along the shape, v across its width)."
    :inputs {:p0 {:type :vec3} :p1 {:type :vec3} :p2 {:type :vec3} :p3 {:type :vec3}
             :u0 {:type :double :default 0.0} :u1 {:type :double :default 1.0}
             :v0 {:type :double :default 0.0} :v1 {:type :double :default 1.0}
             :color {:type :color} :texture {:type :string :default nil}}
    :outputs {:op {:type :render-op}} :effects #{:emit}
    :impl (fn [inputs _ctx] {:op (ops/quad-op inputs)})})
  (node/register-primitive!
   {:id :vfx/camera :revision 1 :category :presentation
    :doc "A camera contribution (FOV/shake/roll) for one presentation frame."
    :inputs {:operation {:type :keyword} :value {:type :any} :duration-ticks {:type :long :default 0}}
    :outputs {:op {:type :render-op}} :effects #{:emit}
    :impl (fn [inputs _ctx] {:op (assoc inputs :kind :camera)})})
  (node/register-primitive!
   {:id :vfx/audio-one-shot :revision 1 :category :presentation
    :doc "Play a sound once at a position."
    :inputs {:sound-id {:type :keyword} :position {:type :vec3}
             :volume {:type :double :default 1.0} :pitch {:type :double :default 1.0}}
    :outputs {:op {:type :render-op}} :effects #{:emit}
    :impl (fn [inputs _ctx] {:op (assoc inputs :kind :audio)})})
  (node/register-primitive!
   {:id :vfx/audio-loop :revision 1 :category :presentation
    :doc "Start/update a looping sound instance."
    :inputs {:sound-id {:type :keyword} :position {:type :vec3}
             :volume {:type :double :default 1.0} :pitch {:type :double :default 1.0}
             :instance-key {:type [:list-of :any]} :stop-on-destroy? {:type :boolean :default true}}
    :outputs {:op {:type :render-op}} :effects #{:emit}
    :impl (fn [inputs _ctx] {:op (assoc inputs :kind :audio-loop)})}))
