(set-env!
 :source-paths    #{"src"}
 :resource-paths  #{"resources"}
 :dependencies '[[adzerk/boot-cljs        "1.7.228-1" :scope "test"]
                 [adzerk/boot-cljs-repl   "0.3.2"     :scope "test"]
                 [com.cemerick/piggieback "0.2.1"     :scope "test"]
                 [weasel                  "0.7.0"     :scope "test"]
                 [org.clojure/tools.nrepl "0.2.12"    :scope "test"]
                 [adzerk/boot-reload      "0.4.12"    :scope "test"]

                 [org.clojure/clojurescript "1.9.225"]
                 [org.clojure/core.async "0.2.385"]
                 [reagent "0.6.0-rc"]])

(require
 '[adzerk.boot-cljs      :refer [cljs]]
 '[adzerk.boot-cljs-repl :refer [cljs-repl start-repl]]
 '[adzerk.boot-reload    :refer [reload]])

(deftask build []
  (comp (cljs :ids #{"main" "renderer"}
              :optimizations :simple)
        (target)))

(deftask run []
  (comp (watch)
        (cljs-repl :ids #{"renderer"})
        (reload    :ids #{"renderer"}
                   :on-jsload 'app.renderer/init
                   :ws-host "localhost")
        (cljs      :ids #{"renderer"})
        ;; path.resolve(".") which is used in CLJS's node shim
        ;; returns the directory `electron` was invoked in and
        ;; not the directory our main.js file is in.
        ;; Because of this we need to override the compilers `:asset-path option`
        ;; See http://dev.clojure.org/jira/browse/CLJS-1444 for details.
        (cljs      :ids #{"main"}
                   :compiler-options {:asset-path "target/main.out"
                                      :closure-defines {'app.main/dev? true}})
        (target)))
