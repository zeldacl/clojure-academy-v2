(ns cn.li.presentation.core.host
  "Public map-shaped host API for Presentation Runtime." 
  (:require [cn.li.presentation.core.runtime :as runtime]))

(defn create-runtime []
  (runtime/create-runtime))

(defn api [runtime]
  {:mount-view!
   (fn [spec] (runtime/mount! runtime spec))
   :present-view!
   (fn [mount state] (runtime/present! runtime mount state))
   :update-view!
   (fn [mount f & args] (apply runtime/update-view! runtime mount f args))
   :update-host!
   (fn [mount geometry] (runtime/update-host! runtime mount geometry))
   :dispatch-input!
   (fn [mount event] (runtime/dispatch! runtime mount event))
   :extract-stage!
   (fn [stage frame-context] (runtime/extract-stage! runtime stage frame-context))
   :semantics!
   (fn [mount] (runtime/semantics runtime mount))
   :unmount!
   (fn [mount] (runtime/unmount! runtime mount))
   :unmount-all!
   (fn [] (runtime/unmount-all! runtime))
   :invalidate-render-resources!
   (fn [] (runtime/invalidate-render-resources! runtime))})
