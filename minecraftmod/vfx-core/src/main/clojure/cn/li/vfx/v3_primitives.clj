(ns cn.li.vfx.v3-primitives
  "v3 registration for vfx-core's vocabulary (R3, mossy-wren plan;
   NODE_LANGUAGE.md section 9).

   Architectural decision made (see the plan's R3 section for the full
   reasoning): a vfx graph keeps :input/:state as first-class value scopes
   -- they are closer to a shader's uniform/varying inputs than an
   injected environment table like combat's :tunables/:costs -- so
   structural nodes stay native cn.li.vfx.vm/sample-node! defmethods
   (:vfx/let/:curve/:branch/:repeat) instead of node-core's :local/:bind
   execution model. They are STILL registered here, though, because
   cn.li.node.composite/expand's structural walk needs a real descriptor
   (specifically its :children shape) for every node type it recurses
   through, including ones a composite's body merely contains without
   being itself a composite call -- without this, expanding a composite
   whose body uses :vfx/repeat fails with :unknown-component before ever
   reaching vfx's own execution. Their :impl is never actually invoked
   this way (cn.li.node.runtime/invoke-primitive! is for leaf primitives
   whose children live in :inputs, not :children) -- it throws loudly if
   misused rather than silently doing the wrong thing.

   Five render/presentation leaves (:vfx/line, :vfx/quad, :vfx/camera,
   :vfx/audio-one-shot, :vfx/audio-loop) ARE real invoke-primitive!
   targets: :impl is a pure data-shaping function that returns one op map,
   reusing cn.li.vfx.ops's tested construction logic for the two geometry
   primitives rather than a second copy of it.

   ADDITIVE ONLY at this revision: the v2 registry (cn.li.vfx.components)
   and vm.clj's own defmethods are untouched and still serve every
   existing effect document; this is a parallel v3 vocabulary registered
   on node-core for composite expansion and schema export, not a
   replacement of vfx-core's execution model."
  (:require [cn.li.node.descriptor :as node]
            [cn.li.vfx.ops :as ops]))

(defn- structural-impl [id]
  (fn [& _]
    (throw (ex-info (str id " is a native vfx sample-node! structural primitive, not an invoke-primitive! target -- see this namespace's docstring")
                    {:component id}))))

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
    :impl (fn [inputs _ctx] {:op (assoc inputs :kind :audio-loop)})})
  (node/register-primitive!
   {:id :vfx/let :revision 1 :category :flow
    :doc "Bind computed values into :input for :child (native sample-node! structural primitive)."
    :inputs {:bindings {:type :map}}
    :children {:child {:kind :single}}
    :effects #{:mutate}
    :impl (structural-impl :vfx/let)})
  (node/register-primitive!
   {:id :vfx/curve :revision 1 :category :flow
    :doc "Sample keyframes into :input under :as for :child (native sample-node! structural primitive)."
    :inputs {:curve {:type [:list-of :any]} :progress {:type :double} :as {:type :keyword}}
    :children {:child {:kind :single}}
    :effects #{:mutate}
    :impl (structural-impl :vfx/curve)})
  (node/register-primitive!
   {:id :vfx/branch :revision 1 :category :flow
    :doc "Run :then when :when is true, otherwise :else (native sample-node! structural primitive)."
    :inputs {:when {:type :boolean}}
    :children {:then {:kind :single} :else {:kind :single}}
    :effects #{:mutate}
    :impl (structural-impl :vfx/branch)})
  (node/register-primitive!
   {:id :vfx/repeat :revision 1 :category :flow
    :doc "Run :body :count times, binding :index-as (native sample-node! structural primitive)."
    :inputs {:count {:type :long} :index-as {:type :keyword}}
    :children {:body {:kind :single}}
    :effects #{:mutate}
    :impl (structural-impl :vfx/repeat)})
  (node/register-primitive!
   {:id :vfx/group :revision 1 :category :flow
    :doc "Render every one of :nodes, every tick -- the plain always-on fan-out (native sample-node! structural primitive)."
    :children {:nodes {:kind :seq}}
    :effects #{:mutate}
    :impl (structural-impl :vfx/group)}))
