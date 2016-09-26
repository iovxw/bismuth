(ns app.utils)

(defn get-user-home []
  (or js/process.env.HOME js/process.env.HOMEPATH js/process.env.USERPROFILE))

(defn parse-int [s]
  (js/parseInt s))

(defn query-selector [q]
  (.querySelector js/document q))

(defn query-selecor-all [q]
  (.querySelectorAll js/document q))
