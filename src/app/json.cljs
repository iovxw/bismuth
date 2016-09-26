(ns app.json)

(defn- do-for-every-key [m func]
  (if (and (map? m) func)
    (into
     {}
     (for [[k v] m]
       [(func k) (do-for-every-key v func)]))
    m))

(defn read-str [string & {:keys [key-fn value-fn]
                          :or {key-fn nil value-fn nil}}]
  (do-for-every-key (js->clj (js/JSON.parse string nil)) key-fn))

(defn write-str [data & {:keys [space key-fn value-fn]
                         :or {space nil key-fn nil value-fn nil}}]
  (js/JSON.stringify (clj->js (do-for-every-key data key-fn)) value-fn space))
