(ns servant.demo
  (:require 
            [cljs.core.async :refer [chan close! timeout put!]]
            [servant.core :as servant]
            [servant.worker :as worker])
  (:require-macros [cljs.core.async.macros :as m :refer [go]]
                   [servant.macros :refer [defservantfn]]) )

(def worker-count 2)
(def worker-script "/main.js") ;; This is whatever the name of this script will be

(defn get-first-32-bits [ab] 
  (let [d (js/DataView. ab)]
    (.toString (.getUint32 d 0) 16)))

;; Here we define servant functions
;; They are just plain ol' functions (in fact there is defn behind the macro)
;; but they automatically register themselves so the webworker becomes aware of them
;; These need to be pure functions, or very bad things will happen
(defservantfn get-first-4bytes-as-str [arraybuffer]
  ;; You can make calls to other functions too!
  (get-first-32-bits arraybuffer))

(defservantfn some-random-fn [a b]
  (+ a b))
              
(defservantfn another-fn-definition [a b]
  (str a b))

(defn window-load []
  (set! *print-fn* #(.log js/console %))


  ;; We keep a channel of servants, this lets us know
  ;; who is available for work
  (def servant-channel (servant/spawn-servants worker-count worker-script))
 
  ;; We pass in the channel of available workers, it will use the first available
  ;; worker. We also supply a function that defines how we send the message 
  ;; (this can be a standard copy all args and result, or transfer context of arraybuffer).
  ;; We supply the function we created earlier
  ;; And finally the arguments
  ;; It returns a channel that will contain the result
  (def channel-1 (servant/servant-thread servant-channel servant/standard-message some-random-fn 5 6))

  ;; Create an arraybuffer to demo transferring array buffers
  (def ab (js/ArrayBuffer. 10))
  (def d (js/DataView. ab))
  (.setUint32 d 0 0xdeadbeef)

  ;; This is a bit more interesting
  ;; our message-fn is an arraybuffer that will setup a standard (non transfer of context) reply 
  ;; (since we expect a string back).
  ;; But we need to massage the arguments a bit before putting it in
  ;; Notice how are arguments are the first item in the vector, and the arraybuffers are the second item.
  ;; This is similar to how you would do it natively in javascript.
  ;; Transferring context is extremely important for passing around giant blobs, 
  ;; since you don't have to pay for the copy
  (def channel-2 (servant/servant-thread servant-channel servant/array-buffer-message-standard-reply get-first-4bytes-as-str
                                       [ab] [ab]))

  (go 
    (println 
      "The value from the first call is "
      ;; We block on the value from the first servant
      (<! channel-1))
    (println 
      "The value from the second call is "
      (<! channel-2))
    ;; That's enough fun for now, but I need to get back to my yacht so servants, finish yourselves.
    (println "Killing webworkers")
    ;; Here you have the ability to specify how many workers to kill off.
    (servant/kill-servants servant-channel worker-count))

  ;; Notice how you don't even have to think about setting up a whole new context, dealing with 
  ;; individual web workers, or handling messages! 
  
)

;; This is important!
;; Because we are using the same script for the webworker and the client, 
;; We have to make sure the webworker doesn't try doing anything it shouldn't/coudn't
;; be doing
(if (servant/webworker?)
  (worker/bootstrap)
  (set! (.-onload js/window) window-load))
