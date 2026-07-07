(ns cn.li.ac.block.wind-gen.gui-reactive
  "Reactive Wind Generator GUI — migration template for block GUIs.
   Demonstrates: XML page load → signal binding → open via bridge.
   Ac module: only depends on mcmod. Screen host accessed via bridge."
  (:require [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.core :as ui]
            [cn.li.mcmod.ui.signal :as sig]
            [cn.li.mcmod.ui.anim :as anim]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.ac.gui.tech-ui-reactive :as tech-ui]
            [cn.li.ac.config.modid :as modid]))

(defn create-runtime [container]
  (let [r (rt/create-runtime)
        ;; Load page layout from XML
        page (tech-ui/create-rework-page "guis/rework/page_windbase.xml")
        ;; Create signals for dynamic data
        energy-sig (sig/signal-d 0.0)
        status-sig (sig/signal-o "IDLE")
        gen-speed-sig (sig/signal-o "0.00IF/T")]
    ;; Store for external update
    (rt/put-user-signal! r :energy energy-sig)
    (rt/put-user-signal! r :status status-sig)
    (rt/put-user-signal! r :gen-speed gen-speed-sig)
    r))

(defn update-signals!
  "Per-frame: read container atoms, sset signals."
  [r container]
  (let [safe-val #(some-> % deref)]
    (sig/sset-d! (rt/user-signal r :energy)
                 (double (or (safe-val (:energy container)) 0.0)))
    (when-let [s (rt/user-signal r :status)]
      (sig/sset-o! s (or (safe-val (:status container)) "IDLE")))
    (when-let [gs (rt/user-signal r :gen-speed)]
      (sig/sset-o! gs (format "%.2fIF/T"
                              (double (or (safe-val (:gen-speed container)) 0.0)))))))

(defn open!
  "Open wind gen screen via bridge. Container screen path TBD."
  [r]
  (bridge/open-reactive-screen! r "Wind Generator"))
