(ns cn.li.mcmod.runtime.presentation-bridge
  "Version-neutral bridge between Presentation and the active mcmod UI host.

   Presentation owns declarative UI state, while mcmod owns this forwarding
   seam.  Loader/Minecraft adapters are installed behind the host map and are
   deliberately invisible to Presentation." 
  (:import [cn.li.mcmod.runtime UiEditCommand]))

(def host-kinds #{:hud :world-ui :screen})

(defn host-descriptor [id kind width height input-policy]
  (when-not (contains? host-kinds kind)
    (throw (ex-info "unknown presentation host" {:kind kind})))
  {:id id :kind kind :width width :height height :input-policy input-policy})

(defn snapshot [revision values]
  {:revision revision :values values})

(defn action-codec [encode decode max-bytes]
  {:encode (fn [action payload]
             (let [bytes (encode action payload)]
               (when (> (count bytes) max-bytes)
                 (throw (ex-info "presentation action exceeds size limit" {:size (count bytes)})))
               bytes))
   :decode decode
   :max-bytes max-bytes})

(defn validate-action [allowed-actions action]
  (when-not (contains? allowed-actions action)
    (throw (ex-info "presentation action rejected" {:action action})))
  action)

;; The host is installed by the mcmod-facing runtime bootstrap.  Keeping one
;; small map of functions makes the boundary explicit and testable without
;; exposing any Minecraft/Forge classes to Presentation.
(def required-host-operations
  #{:mount! :sync! :dispatch-input! :apply-edit! :undo-edit! :redo-edit!
    :reset-edits! :begin-frame! :extract-stage! :unmount!})

(defonce ^:private presentation-host* (atom nil))

(defn install-host! [host]
  (let [missing (->> required-host-operations
                     (remove #(fn? (get host %)))
                     set)]
    (when (seq missing)
      (throw (ex-info "presentation host is missing operations" {:missing missing})))
    (reset! presentation-host* host)
    host))

(defn clear-host-for-test! []
  (reset! presentation-host* nil)
  nil)

(defn host []
  (or @presentation-host*
      (throw (ex-info "presentation host is not installed" {}))))

(defn- require-host-op [operation]
  (get (host) operation))

(defn mount! [spec]
  ((require-host-op :mount!) spec))

(defn sync! [mount-id model-revision model]
  ((require-host-op :sync!) mount-id model-revision model))

(defn dispatch-input! [mount-id input]
  ((require-host-op :dispatch-input!) mount-id input))

(defn apply-edit! [mount-id command]
  (when-not (instance? UiEditCommand command)
    (throw (ex-info "presentation edit must use UiEditCommand" {:value (type command)})))
  ((require-host-op :apply-edit!) mount-id command))

(defn undo-edit! [mount-id]
  ((require-host-op :undo-edit!) mount-id))

(defn redo-edit! [mount-id]
  ((require-host-op :redo-edit!) mount-id))

(defn reset-edits! [mount-id]
  ((require-host-op :reset-edits!) mount-id))

(defn begin-frame! [mount-id frame-context]
  ((require-host-op :begin-frame!) mount-id frame-context))

(defn extract-stage! [mount-id]
  ((require-host-op :extract-stage!) mount-id))

(defn unmount! [mount-id]
  ((require-host-op :unmount!) mount-id))
