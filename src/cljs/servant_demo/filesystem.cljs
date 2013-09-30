(ns servant-demo.filesystem
  (:require 
            [cljs.core.async :refer [chan close! timeout put!]]
            [servant.core :as servant]
            [servant.worker :as worker])
  (:require-macros [cljs.core.async.macros :as m :refer [go]]
                   [servant.macros :refer [defservantfn]]))


(defn append-to-file [fs filename blob]
  (let [append-channel (chan)]
    (.getFile (.-root fs) filename (js-obj "create" true)
      (fn [file-entry] 
        (.createWriter file-entry 
          (fn [file-writer]
            (def fw file-writer)
            (.seek file-writer (.-length file-writer) )
            (aset file-writer "onwriteend" (fn [] (go (>! append-channel true))))
            (.write file-writer blob)))))
    append-channel))

(defn load-fs []
  (let [fs-channel (chan)]
    (.webkitRequestFileSystem js/window (.-TEMPORARY js/window) (* 1024 1024) #(go (>! fs-channel %) ) )
    fs-channel))

(defn delete-file [fs filename]
  (let [delete-channel (chan)]
    (.getFile (.-root fs) filename (js-obj "create" true)
      (fn [file-entry]
        (.remove file-entry
          (fn []
            (go (>! delete-channel true)))
          (fn []
            (go (>! delete-channel false))))))
    delete-channel))


(defn write-blobs-to-file 
  "Returns a url-channel for the file"
  [filename blobs]
  (let [url-channel (chan)]
    (go
      (let [fs (<! (load-fs))]
        (<! (delete-file fs filename))
        (doseq [blob blobs]
          (<! (append-to-file fs filename blob)))
        (>! url-channel (str "filesystem:" js/location.origin "/temporary/" filename))))
    url-channel))

