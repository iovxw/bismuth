(ns app.macros)

(defmacro <!-with-err [channel]
  (let [r (gensym 'r)
        err (gensym 'err)]
    `(let [~r (cljs.core.async/<! ~channel)
           ~err (:error ~r)]
       (if ~err
         (throw ~err)
         (:result ~r)))))

(defmacro go-let [& body]
  `(cljs.core.async.macros/go
     (let ~@body)))


(defmacro go-try [& body]
  `(cljs.core.async.macros/go
     (try ~@body)))
