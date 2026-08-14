(ns cn.li.mcmod.gui.presentation-menu-bridge
  "Menu/Slot boundary for Presentation Runtime.

   The Minecraft menu remains authoritative. This bridge exposes immutable
   slot anchors, visibility and typed snapshot/delta data only; it never
   mirrors focus, animation or layout state into the server model.")

(defn- validate-anchor [anchor]
  (when-not (and (integer? (:slot-index anchor))
                 (number? (:x anchor))
                 (number? (:y anchor))
                 (number? (:width anchor))
                 (number? (:height anchor)))
    (throw (ex-info "invalid slot anchor" {:anchor anchor})))
  (select-keys anchor [:slot-index :x :y :width :height :visible?]))

(defn create [menu-id slot-anchors allowed-actions]
  {:menu-id menu-id
   :slot-anchors (mapv validate-anchor slot-anchors)
   :allowed-actions (set allowed-actions)
   :visible? (atom true)
   :snapshot (atom {:revision 0 :values {}})})

(defn set-visible! [bridge visible?]
  (reset! (:visible? bridge) (boolean visible?))
  nil)

(defn update-snapshot! [bridge revision values]
  (when-not (and (integer? revision) (map? values))
    (throw (ex-info "snapshot must be typed revision + map values"
                    {:revision revision :values values})))
  (reset! (:snapshot bridge) {:revision revision :values values})
  nil)

(defn snapshot [bridge]
  (assoc @(:snapshot bridge) :visible? @(:visible? bridge)
         :slot-anchors (:slot-anchors bridge)))

(defn dispatch-action [bridge action payload dispatch!]
  (when-not (contains? (:allowed-actions bridge) action)
    (throw (ex-info "menu action rejected" {:action action :menu-id (:menu-id bridge)})))
  (dispatch! action payload)
  :accepted)
