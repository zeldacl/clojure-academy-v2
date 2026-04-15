(ns cn.li.forge1201.client.ability-client-state)

(defonce client-activated-overlay (atom nil))

(defn set-client-activated! [v] (reset! client-activated-overlay v))

(defn clear-client-activated! [] (reset! client-activated-overlay nil))
