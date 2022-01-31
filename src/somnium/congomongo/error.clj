; Copyright (c) 2009-2012 Andrew Boekhoff, Sean Corfield

; Permission is hereby granted, free of charge, to any person obtaining a copy
; of this software and associated documentation files (the "Software"), to deal
; in the Software without restriction, including without limitation the rights
; to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
; copies of the Software, and to permit persons to whom the Software is
; furnished to do so, subject to the following conditions:

; The above copyright notice and this permission notice shall be included in
; all copies or substantial portions of the Software.

; THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
; IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
; FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
; AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
; LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
; OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
; THE SOFTWARE.

(ns
 ^{:author "Jeff Sapp"}
 somnium.congomongo.error
  (:require [somnium.congomongo.config :refer [*mongo-config*]])
  (:import [com.mongodb DB]))

(defn get-last-error
  "Gets the error (if there is one) from the previous operation"
  []
  (let [e (into {} (.getLastError ^DB (:db *mongo-config*)))]
    (when (e "err") e)))

(defn get-previous-error
  "Returns the last error that occurred"
  []
  (let [e (into {} (.getPreviousError ^DB (:db *mongo-config*)))]
    (when (e "err") e)))

(defn reset-error!
  "Resets the error memory for the database"
  []
  (into {} (.resetError ^DB (:db *mongo-config*))))

(defn force-error!
  "This method forces an error"
  []
  (into {} (.forceError ^DB (:db *mongo-config*))))
