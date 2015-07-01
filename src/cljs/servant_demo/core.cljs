;; Note: Do not let the size of this file shock you,
;; It's big because of all the crap you have to do to get a File Object
;; into blobs into arraybuffers into blobs again into the filesystem api
;; so that you can create a download link. (And then again for decrypting)
(ns servant-demo.demo
  (:require
            [cljs.core.async :refer [chan close! timeout put! take!]]
            [cljs.reader :as reader]
            [servant.core :as servant]
            [servant-demo.filesystem :as filesystem]
            [servant.worker :as worker])
  (:require-macros [cljs.core.async.macros :as m :refer [go]]
                   [servant-demo.macros :refer [<!-and-apply go->]]
                   [servant.macros :refer [defservantfn]]) )

;; How many webworks would you like today?
(def worker-count 4)
(def worker-script "/main.js") ;; This is whatever the name of this script will be
(def chunk-size 200000)


(defn concat-arraybuffer [ab1 ab2]
  (let [ab1-length (aget ab1 "byteLength")
        ab2-length (aget ab2 "byteLength")
        new-length (+  ab1-length ab2-length)
        new-ab (js/ArrayBuffer. new-length)
        d1 (js/DataView. ab1)
        d2 (js/DataView. ab2)
        new-d (js/DataView. new-ab) ]
    (doseq [x (range 0 ab1-length)]
      (.setUint8 new-d x (.getUint8 d1 x)))
    (doseq [x (range 0 ab2-length) :let [new-d-index (+ x ab1-length)]]
      (.setUint8 new-d new-d-index (.getUint8 d2 x)))
    new-ab))

(defn create-padding-arraybuffer [padding-count filler]
  (let [new-ab (js/ArrayBuffer. padding-count)
        d (js/DataView. new-ab)]
    (doseq [x (range padding-count)]
      (.setUint8 d x filler))
    new-ab))

(defn pad-arraybuffer [arraybuffer]
  (let [remainder (mod (aget arraybuffer "byteLength") 16)]
    (if (= 0 remainder)
      (concat-arraybuffer arraybuffer (create-padding-arraybuffer 16 remainder))
      (concat-arraybuffer arraybuffer
        (concat-arraybuffer
          (create-padding-arraybuffer (- 16 remainder) 0)
          (create-padding-arraybuffer 16 remainder))))))

(defn remove-padding [arraybuffer]
  (let [arraybuffer-size (aget arraybuffer "byteLength")
        d (js/DataView. arraybuffer)
        padding (.getUint8 d (dec arraybuffer-size))]

    (.slice arraybuffer 0 (- arraybuffer-size (+ 16 padding)))))

;;These are the underlying functions for cryptography
(defn encrypt-arraybuffer [password iv arraybuffer]
  (let [ aes (js/sjcl.cipher.aes. password)
         arraybuffer (pad-arraybuffer arraybuffer)]
    (.encrypt js/sjcl.arrayBuffer.ccm
       aes arraybuffer iv)))

(defn decrypt-arraybuffer [password iv tag arraybuffer]
  (let [ aes (js/sjcl.cipher.aes. password)
         decrypted-buffer (.decrypt js/sjcl.arrayBuffer.ccm
                            aes arraybuffer iv tag)]
    (remove-padding decrypted-buffer)))

;; Here we put out servantfn wrapper around the raw crypto fns
;; Notice how we return a vector of two items:
;;  [ result [arrayBuffer1 arrayBuffer2] ]
;; We do this because we are planning to send arraybuffer messages
;; Which means we save time by transffering context
(defservantfn servant-encrypt [password iv arraybuffer]
  (let [cipherObj (encrypt-arraybuffer password iv arraybuffer)]
    [cipherObj [(aget cipherObj "ciphertext_buffer")]]))

(defservantfn servant-decrypt [password iv tag arraybuffer]
  (let [decrypted-arraybuffer (decrypt-arraybuffer password iv tag arraybuffer)]
    [decrypted-arraybuffer [decrypted-arraybuffer]]))

;; This function is interesting, because we have a whole bunch of arraybuffers, and our servant-fn
;; We map through them creating a lazy seq of channels that will hold the value of the encrypted
;; arraybuffers. We loop through each one in sequence and add them to a ciphertexts seq
(defn encrypt-arraybuffers
  "Given a seq of arraybuffers lets encrypt them all"
  [arraybuffers encrypt-servant-fn password iv]
  (let [ servant-channels (map #(encrypt-servant-fn [password iv %] [%]) arraybuffers)
         ciphertexts-channel (chan)]
    (go
      (loop [servant-channels servant-channels
             ciphertexts []]
        (if (seq servant-channels)
          (recur (rest servant-channels)
                 (conj ciphertexts (<! (first servant-channels))))
          (>! ciphertexts-channel ciphertexts))))
    ciphertexts-channel))

;; Here we do the same thing, but we need to include tags, since we are using CCM encryption
(defn decrypt-arraybuffers
  "Given a seq of arraybuffers lets decrypt them all"
  [arraybuffers decrypt-servant-fn tags password iv]
  (let [ servant-channels (map #(decrypt-servant-fn [password iv %1 %2 ] [%2]) tags arraybuffers)
         plaintexts-channel (chan) ]
    (go
      (loop [servant-channels servant-channels
             plaintexts []]
        (if (seq servant-channels)
          (recur (rest servant-channels)
                 (conj plaintexts (<! (first servant-channels))))
          (>! plaintexts-channel plaintexts))))
    plaintexts-channel))


;; Boring functions that deal with the hoops I need to jump through

(defn split-file-into-blobs [file]
  (let [[_ chunk-sizes _ _] (read-tags-password-iv)
        blobs (map (fn [[start end]] (.slice file start end))
                   (partition 2 1 (reductions + (cons 0 chunk-sizes))))]
    (js* "debugger")
    (go blobs)))

(defn split-plaintext-into-blobs [file]
  (let [blob-channel (chan)]
    (go
      (>! blob-channel
        (map
          #(.slice file % (+ chunk-size %))
          (range 0 (aget file "size") chunk-size))))
    blob-channel))


(defn get-arraybuffers-from-blobs [blobs]
  ;; When we got the file blobs, we need to get their respective arraybuffers
  (let [file-reader (js/FileReader.)
        arraybuffer-channel (chan)
        all-arraybuffers-chan (chan)]
    (set! (.-onload file-reader)
          #(go
             (>! arraybuffer-channel (aget file-reader "result"))))
    (go
      (loop [blobs blobs arraybuffers []]
        (if (seq blobs)
          (do
            (.readAsArrayBuffer file-reader (first blobs))
            (recur (rest blobs)
                   (conj arraybuffers (<! arraybuffer-channel))))
          (>! all-arraybuffers-chan arraybuffers))))
    all-arraybuffers-chan))

(defn read-file-from-dom [dom-id]
  (let [file-channel (chan)]
    (set! (.-onchange (.getElementById js/document dom-id))
          #(go (>! file-channel (aget (.-files (.-target %)) "0"))))
    file-channel))

(defn write-ciphertexts
  "Writes the ciphertexts to a file, returns the tags"
  [ciphertexts]
  (let [blobs (map #(js/Blob. (array (aget % "ciphertext_buffer"))) ciphertexts)
        tags (js->clj (map #(aget % "tag") ciphertexts))
        chunk-sizes (map #(aget (aget % "ciphertext_buffer") "byteLength") ciphertexts)]
    (go
      (aset (.getElementById js/document "downloadLink") "href"  (<! (filesystem/write-blobs-to-file "encrypted.blob" blobs))))
    [tags chunk-sizes]))

(defn write-plaintexts
  "Writes the ciphertexts to a file, returns the tags"
  [plaintexts]
  (let [ blobs (map #(js/Blob. (array %)) plaintexts)]
    (println "Writing plaintext to the file!")
    (go
      (aset (.getElementById js/document "downloadLink") "href"  (<! (filesystem/write-blobs-to-file "pt.blob" blobs))))))


;; To set the passcode in the little box
(defn save-tags-password-iv [[tags chunk-sizes] password iv]
  (set!
    (.-value (.getElementById js/document "password"))
    (js/btoa (pr-str (js->clj [tags chunk-sizes password iv])))))

;; To Read the passcode from the box
(defn read-tags-password-iv []
  (reader/read-string
    (js/atob
      (.-value (.getElementById js/document "password")))))



;;Workflow defining the steps to do all the encryption
(defn encrypt-workflow [encrypt-servant-fn password iv]
  (let [done-chan (chan)]
    ;; To encrypt the files
    (go->
      (read-file-from-dom "filePicker")
      (<!-and-apply split-plaintext-into-blobs)
      (<!-and-apply get-arraybuffers-from-blobs)
      ;; This is where we make the servant call. We pass a partial function so
      ;; this function doesn't have to worry about the servant-channel
      (<!-and-apply encrypt-arraybuffers [encrypt-servant-fn password iv])
      (<!-and-apply write-ciphertexts)
      (save-tags-password-iv password iv)
      ((fn [_] (go (>! done-chan true)))))
    done-chan))

(defn decrypt-workflow [decrypt-servant-fn]
  (let [done-chan (chan)]
    ;; To decrypt the files
    (go->
      (read-file-from-dom "fileDecrypt")
      (<!-and-apply split-file-into-blobs)
      (<!-and-apply get-arraybuffers-from-blobs)
      ;; This is where we make the servant call. We pass a partial function so
      ;; this function doesn't have to worry about the servant-channel
      (#(go
         (let [ arraybuffers (<! %)
                [tags chunk-sizes password iv] (read-tags-password-iv)
                ;; Uncomment to not use webworkers
                #_#_ plaintexts (doall (map (fn [t a] (decrypt-arraybuffer (clj->js password) (clj->js iv) (clj->js t) a)) tags arraybuffers))
                plaintexts (<! (decrypt-arraybuffers arraybuffers decrypt-servant-fn tags password iv)) ]
           (write-plaintexts plaintexts))))
      ((fn [_] (go (>! done-chan true)))))
    done-chan))

(defn window-load []
  (set! *print-fn* #(.log js/console %))

  ;; Create some random passwords
  (def password (.randomWords (.-random js/sjcl) 8))
  (def iv (.randomWords (.-random js/sjcl) 2))

  ;; We keep a channel of servants, this lets us know who is available for work
  (def servant-channel (servant/spawn-servants worker-count worker-script))

  ;; Write some partials so we don't have to worry about the type of message and servant channels
  ;; Also so it looks identical to the defservantfn we made earlier
  (def encrypt-servant-fn (partial servant/servant-thread servant-channel servant/array-buffer-message servant-encrypt))
  (def decrypt-servant-fn (partial servant/servant-thread servant-channel servant/array-buffer-message servant-decrypt))


  ;; Set up the page!
  (go
    (loop []
      (<! (encrypt-workflow encrypt-servant-fn password iv))
      (recur)))

  (go
    (loop []
      (<! (decrypt-workflow decrypt-servant-fn))
      (recur)))


)

(if (servant/webworker?)
  (worker/bootstrap)
  (set! (.-onload js/window) window-load))

;;The webworker needs to load sjcl.js
(when (servant/webworker?)
  (.importScripts js/self "sjcl.js"))
