(defproject servant-demo "0.1.0-SNAPSHOT"
  :description "Demo for servant library"
  :source-paths ["src/cljs"]
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :plugins [[lein-cljsbuild "1.1.2"]]
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.7.228"]
                 [org.clojure/core.async "0.2.374"]
                 [servant "0.1.5"]]
  :cljsbuild
              {:builds
               [{:id "servant_demo"
                 :source-paths ["src/cljs/servant_demo"]
                 :compiler {:optimizations :whitespace
                            :pretty-print false
                            :externs ["sjcl.js"]
                            :output-to "main.js"}}]})
