(ns cn.li.mc1201.lifecycle.phase-contract
  "Shared lifecycle phase naming/description contract for platform loaders.")

(def ^:private phase-descriptions
  {:platform-init "platform bootstrap + init-from-java"
   :runtime-activation "activate runtime/config foundations"
   :resource-init "initialize blockstate/resource definitions"
   :content-registration "register content"
   :mod-bus-setup "wire deferred registers and lifecycle listeners"
   :common-setup "run common setup side effects"
   :event-wiring "register loader events"})

(defn description
  [phase-id fallback]
  (or (get phase-descriptions phase-id) fallback))

(defn phase
  ([id f]
   (phase id nil f))
  ([id desc f]
   {:id id
    :desc (description id desc)
    :fn f}))
