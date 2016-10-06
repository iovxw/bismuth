(ns app.macros)

(defmacro <!? [channel]
  (let [r (gensym 'r)
        err (gensym 'err)]
    `(let [~r (cljs.core.async/<! ~channel)
           ~err (:error ~r)]
       (if ~err
         (throw ~err)
         (:result ~r)))))

(defmacro take!?
  ([port fn1] `(take!? ~port ~fn1 true))
  ([port fn1 on-caller?]
   `(cljs.core.async/take! ~port
                           #(~fn1 (:result %) (:error %))
                           ~on-caller?)))

(defmacro go-let [& body]
  `(cljs.core.async.macros/go
     (let ~@body)))

(defmacro go-try [& body]
  `(cljs.core.async.macros/go
     (try ~@body)))
