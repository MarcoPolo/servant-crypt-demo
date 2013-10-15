(defproject servant-demo "0.1.0-SNAPSHOT"
  :description "Demo for servant library"
  :source-paths ["src/cljs"]
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :plugins [[lein-cljsbuild "0.3.3"]]
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/clojurescript "0.0-1909"] 
                 [org.clojure/core.async "0.1.242.0-44b1e3-alpha"]
                 [servant "0.1.2"] ]
  :cljsbuild
              {:builds
               [{:id "servant_demo"
                 :source-paths ["src/cljs/servant_demo"]
                 :compiler {:optimizations :whitespace
                            :pretty-print false
                            :externs ["sjcl.js"]
                            :output-to "main.js" 
                            :source-map "main.js.map"}}]})
