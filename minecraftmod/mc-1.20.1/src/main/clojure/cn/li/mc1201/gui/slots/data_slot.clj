(ns cn.li.mc1201.gui.slots.data-slot
  "Atom-backed Minecraft DataSlot implementations."
  (:require [cn.li.mcmod.gui.container.data-slot-codec :as codec])
  (:import [net.minecraft.world.inventory DataSlot]))

(defn create-atom-backed-data-slot
  "Create a DataSlot whose get/set encode/decode a container atom."
  [atom-ref field-codec]
  (when-not (instance? clojure.lang.Atom atom-ref)
    (throw (ex-info "DataSlot requires atom ref" {:atom-ref atom-ref})))
  (let [encode (:encode field-codec)
        decode (:decode field-codec)]
    (proxy [DataSlot] []
      (get [_]
        (codec/clamp-int (encode @atom-ref)))
      (set [_ v]
        (reset! atom-ref (decode (int v)))))))

(defn materialize-data-slots!
  "Attach atom-backed DataSlots to container under :data-slots (vector, stable order)."
  [clj-container field-specs]
  (let [slots (mapv (fn [{:keys [container-key codec]}]
                      (when-let [atom-ref (get clj-container container-key)]
                        (create-atom-backed-data-slot atom-ref codec)))
                    field-specs)]
    (assoc clj-container :data-slots slots :data-slot-specs field-specs)))

(defn register-data-slots-on-menu!
  [^cn.li.mc1201.gui.CMenuBridge menu clj-container]
  (when-let [slots (:data-slots clj-container)]
    (doseq [^DataSlot slot slots]
      (when slot
        (.addDataSlotPublic menu slot)))))
