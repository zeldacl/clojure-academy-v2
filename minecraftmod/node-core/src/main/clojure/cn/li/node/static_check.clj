(ns cn.li.node.static-check
  "Check statically resolvable values without dispatching queries or effects.
   Mutable registers and world-query results remain unknown. A known nil is
   distinct from unknown, so it cannot silently cross a numeric boundary."
  (:require [cn.li.node.ops :as ops]
            [cn.li.node.types :as types]))

(defn problems
  ([ir] (problems ir nil))
  ([ir input]
   (let [instrs (mapcat :instrs (:blocks ir))
         writers (group-by :dst (filter :dst instrs))
         cache (atom {})]
     (letfn [(resolve-value [reg seen]
               (when-not (contains? seen reg)
                 (if (contains? @cache reg)
                   (get @cache reg)
                   (let [[kind bank slot] reg
                         definitions (get writers reg)
                         instr (when (= 1 (count definitions)) (first definitions))
                         seen (conj seen reg)
                         source #(resolve-value % seen)
                         result
                         (if (= :const kind)
                           {:value (get-in ir [:constants bank slot])}
                           (case (:op instr)
                             :cap
                             (when input
                               {:value (get-in input [:capabilities (:key instr)])})
                             :tun
                             (when input
                               {:value (get-in input [:tunables (:key instr)])})
                             :copy (source (:src instr))
                             :convert (source (:src instr))
                             :get (when-let [v (source (:src instr))]
                                    {:value (get (:value v) (:key instr))})
                             :map-lit (let [xs (into {} (map (fn [[k r]] [k (source r)])) (:args instr))]
                                        (when (every? some? (vals xs))
                                          {:value (into {} (map (fn [[k v]] [k (:value v)])) xs)}))
                             :pure (let [xs (mapv source (:args instr))]
                                     (when (every? some? xs)
                                       (try {:value (ops/invoke (:fn instr) (mapv :value xs))}
                                            (catch Exception _ nil))))
                             nil))]
                     (swap! cache assoc reg result)
                     result))))
             (check-value [instr reg want]
               (when-let [known (resolve-value reg #{})]
                 (let [want (types/canonical-type want)
                       v (:value known)
                       valid? (cond
                                (= :any want) true
                                (types/numeric? want) (number? v)
                                (= :vec3 want) (or (types/payload-conforms? :vec3 v)
                                                  (and (map? v)
                                                       (every? #(number? (get v %)) [:x :y :z])))
                                :else (types/payload-conforms? want v))]
                   (when-not valid?
                     {:code :invalid-static-value :severity :error
                      :nid (:nid instr) :want want
                      :message (str "node " (:nid instr) " expects " want
                                    ", statically resolved value is " (pr-str v))}))))]
       (vec
        (mapcat
         (fn [instr]
           (concat
            (when (and input (contains? #{:cap :tun} (:op instr)))
              (let [section (if (= :cap (:op instr)) :capabilities :tunables)]
                (when-not (contains? (get input section) (:key instr))
                  [{:code :missing-input :severity :error :nid (:nid instr)
                    :message (str "missing " section " input " (:key instr))}])))
            (keep identity
                  (cond
                    (= :convert (:op instr))
                    [(check-value instr (:src instr) (:to instr))]

                    (= :pure (:op instr))
                    (map #(check-value instr %1 %2) (:args instr)
                         (:params (ops/signature (:fn instr))))

                    (contains? #{:query :action} (:op instr))
                    (map (fn [[k reg]]
                           (check-value instr reg (get (:arg-types instr) k :any)))
                         (:args instr))

                    (contains? #{:cap :tun :get :copy} (:op instr))
                    [(check-value instr (:dst instr)
                                  (or (:type instr)
                                      (case (second (:dst instr))
                                        :doubles :double :longs :long :any)))]

                    :else []))))
         instrs))))))
