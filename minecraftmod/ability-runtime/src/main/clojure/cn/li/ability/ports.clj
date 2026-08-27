(ns cn.li.ability.ports
  "Small host seams shared by AC/BC/CC. Ports are data maps; implementations
   live in mcmod/platform adapters and are invoked only by the runtime tick.")

(def required-server-ports #{:world-query :world-command :send-vfx :send-result})
(def required-client-ports #{:enqueue-packet :render-frame})

(defn validate-server-ports [ports]
  (let [missing (seq (remove #(ifn? (get ports %)) required-server-ports))]
    (when missing
      (throw (ex-info "server runtime ports are incomplete" {:missing missing})))
    ports))

(defn validate-client-ports [ports]
  (let [missing (seq (remove #(ifn? (get ports %)) required-client-ports))]
    (when missing
      (throw (ex-info "client runtime ports are incomplete" {:missing missing})))
    ports))
