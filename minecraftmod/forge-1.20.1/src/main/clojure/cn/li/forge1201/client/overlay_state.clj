(ns cn.li.forge1201.client.overlay-state)

(defonce client-activated-overlay (atom nil))

(defn set-client-activated! [v] (reset! client-activated-overlay v))

(defn clear-client-activated! [] (reset! client-activated-overlay nil))