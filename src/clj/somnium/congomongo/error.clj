(ns
  #^{:author "Jeff Sapp"}
  somnium.congomongo.error
  (:use somnium.congomongo
        somnium.congomongo.config
        somnium.congomongo.coerce)
  (:import [com.mongodb DB]))

(defn get-last-error
  "Gets the error (if there is one) from the previous operation"
  []
  (let [e (into {} (.getLastError #^DB (:db @*mongo-config*)))]
     (when (e "err") e)))

(defn get-previous-error
  "Returns the last error that occurred"
  []
  (let [e (into {} (.getPreviousError #^DB (:db @*mongo-config*)))]
    (when (e "err") e)))

(defn reset-error!
  "Resets the error memory for the database"
  []
  (into {} (.resetError #^DB (:db @*mongo-config*))))

(defn force-error!
  "This method forces an error"
  []
  (into {} (.forceError #^DB (:db @*mongo-config*))))