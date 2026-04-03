(ns cn.li.ac.block.cat-engine.schema
  "Cat Engine state schema")

(def cat-engine-schema
  "Schema for cat engine block"
  [{:key :linked-node-x
    :nbt-key "LinkedNodeX"
    :type :int
    :default 0
    :persist? true}

   {:key :linked-node-y
    :nbt-key "LinkedNodeY"
    :type :int
    :default 0
    :persist? true}

   {:key :linked-node-z
    :nbt-key "LinkedNodeZ"
    :type :int
    :default 0
    :persist? true}

   {:key :has-link
    :nbt-key "HasLink"
    :type :boolean
    :default false
    :persist? true}

   {:key :search-cooldown
    :nbt-key "SearchCooldown"
    :type :int
    :default 0
    :persist? true}

   {:key :link-attempts
    :nbt-key "LinkAttempts"
    :type :int
    :default 0
    :persist? true}

   {:key :update-ticker
    :type :int
    :default 0
    :persist? false}])
