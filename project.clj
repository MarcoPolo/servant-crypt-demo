(defproject servant-demo "0.1.0-SNAPSHOT"
  :description "Demo for servant library"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :plugins [[lein-cljsbuild "0.3.3"]]
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/clojurescript "0.0-1859"] 
                 [org.clojure/core.async "0.1.222.0-83d0c2-alpha"]
                 [servant "0.1.0-SNAPSHOT"] ]
  :cljsbuild
              {:builds
               [{:id "servant-demo"
                 :source-paths ["src/cljs/servant-demo"]
                 :compiler {:optimizations :simple
                            :pretty-print false
                            :output-to "main.js" }}]})
