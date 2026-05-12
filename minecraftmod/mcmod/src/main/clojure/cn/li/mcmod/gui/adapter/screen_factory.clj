(ns cn.li.mcmod.gui.adapter.screen-factory)

(defonce screen-factories
  (atom {}))

(defn register-screen-factory!
  [screen-fn-kw screen-fn]
  (swap! screen-factories assoc (keyword screen-fn-kw) screen-fn)
  nil)

(defn get-screen-factory-fn
  [screen-fn-kw]
  (if-let [f (get @screen-factories (keyword screen-fn-kw))]
    f
    (throw (ex-info "Screen factory not registered"
                    {:screen-fn-kw screen-fn-kw}))))
