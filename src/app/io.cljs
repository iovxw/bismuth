(ns app.io
  (:require-macros [cljs.core.async.macros :refer [go]]
                   [app.macros :refer [<!-with-err go-try]])
  (:require [cljs.core.async :refer [<! >! put! take! chan close!]]))

(def fs (js/require "fs"))

(defn file-exist? [path]
  (let [result-chan (chan)]
    (.stat fs path
           #(if %
              (if (= (.-code %) "ENOENT")
                (put! result-chan {:result false})
                (put! result-chan {:error %}))
              (put! result-chan {:result true})))
    result-chan))

(defn write-file [file data & {:keys [encoding mode flag]
                               :or {encoding "utf8" mode 0666 flag "w"}}]
  (let [error-chan (chan)]
    (.writeFile fs file data encoding mode flag
                #(if % (put! error-chan {:error %}) (close! error-chan)))
    error-chan))

(defn read-file [file & {:keys [encoding flag]
                         :or {encoding nil flag "r"}}]
  (let [result-chan (chan)]
    (.readFile fs file encoding flag
               #(if %1
                  (put! result-chan {:error %1})
                  (put! result-chan {:result %2})))
    result-chan))

(defn read-dir [path & {:keys [encoding]
                        :or {encoding "utf8"}}]
  (let [result-chan (chan)]
    (.readdir fs path encoding
              #(if %1
                 (put! result-chan {:error %1})
                 (put! result-chan {:result (js->clj %2)})))
    result-chan))

(defn unlink [path]
  (let [error-chan (chan)]
    (.unlink fs path #(if % (put! error-chan {:error %1}) (close! error-chan)))
    error-chan))

(defn mkdir
  ([path] (mkdir path 0777))
  ([path mode]
   (let [error-chan (chan)]
     (.mkdir fs path mode
             #(if (and % (not= (.-code %) "EEXIST"))
                (put! error-chan {:error %})
                (close! error-chan)))
     error-chan)))
