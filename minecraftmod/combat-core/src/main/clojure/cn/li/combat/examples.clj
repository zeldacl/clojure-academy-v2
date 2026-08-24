(ns cn.li.combat.examples
  "Small final-engine graph fixtures used by coverage and documentation.")

(def final-combat-examples
  [{:id :example-hit
    :activation :instant
    :program {:component :flow/sequence
              :steps [{:component :entity/damage
                       :args {:target {:ref [:input :target]}
                              :amount 1.0}}]}}
   {:id :example-persistent
    :activation :session
    :program {:component :flow/phases
              :start {:component :flow/finish :outcome :started}
              :pulse {:component :flow/finish :outcome :pulsed}}}])
