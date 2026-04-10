(ns cn.li.ac.content.blocks.wireless
    "Content entrypoint for wireless blocks.

    Loads all wireless block namespaces. Each namespace self-registers its
    capabilities via declare-capability! and its tiles via deftile at load time.
    The wireless system (cn.li.ac.wireless.*) has no compile-time dependency on
    these namespaces; blocks interact with the wireless system purely through
    the acapi Java interfaces."
    (:require [cn.li.ac.wireless.shared.message-registry :as msg-reg]))

(defn- load-wireless-blocks! []
  (doseq [ns-sym '[cn.li.ac.block.wireless-matrix.block
                   cn.li.ac.block.wireless-matrix.gui
                   cn.li.ac.block.wireless-node.block
                   cn.li.ac.block.wireless-node.gui
                   cn.li.ac.block.solar-gen.block
                   cn.li.ac.block.solar-gen.gui]]
    (require ns-sym)))

(defn- init-wireless-block-definitions! []
    (doseq [init-sym '[cn.li.ac.block.wireless-matrix.block/init-wireless-matrix!
                                        cn.li.ac.block.wireless-matrix.gui/init-wireless-matrix-gui!
                                        cn.li.ac.block.wireless-node.block/init-wireless-nodes!
                                        cn.li.ac.block.wireless-node.gui/init!
                                        cn.li.ac.block.solar-gen.block/init-solar-gen!
                                        cn.li.ac.block.solar-gen.gui/init-solar-gui!]]
        (when-let [init-fn (requiring-resolve init-sym)]
            (init-fn))))

(defonce ^:private wireless-blocks-installed? (atom false))

(defn init-wireless-blocks!
    []
    (when (compare-and-set! wireless-blocks-installed? false true)
        (load-wireless-blocks!)
        (init-wireless-block-definitions!)
        (msg-reg/register-all!)))
