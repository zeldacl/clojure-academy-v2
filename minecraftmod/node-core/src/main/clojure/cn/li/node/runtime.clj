(ns cn.li.node.runtime
  "The single sanctioned call boundary into a primitive's :impl. Filters the
   resolved node data down to exactly the component's declared :inputs
   before calling :impl, so an implementation physically cannot read a
   field it never declared -- this is what makes descriptor/implementation
   drift structurally impossible instead of merely disciplined (see
   NODE_LANGUAGE.md section 2, and the 8 vfx-core components that had
   already drifted apart under the old convention-only approach)."
  (:require [cn.li.node.descriptor :as registry]))

(defn invoke-primitive!
  "Call `component-id`'s :impl with only its declared :inputs fields
   (already-resolved values, not raw EDN) plus `ctx`. Returns whatever
   :impl returns (an outputs map, or nil/side-effect for primitives with no
   declared :outputs)."
  [component-id resolved-fields ctx]
  (let [d (registry/descriptor component-id)]
    (when-not d
      (throw (ex-info "unknown component" {:component component-id :reason :unknown-component})))
    (when-not (= :primitive (:layer d))
      (throw (ex-info "invoke-primitive! called on a non-primitive component"
                       {:component component-id :layer (:layer d) :reason :not-a-primitive})))
    (let [declared (set (keys (:inputs d)))
          filtered (select-keys resolved-fields declared)]
      ((:impl d) filtered ctx))))
