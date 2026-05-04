(ns voxx.audio-utils
  "Audio format conversion utilities using ffmpeg."
  (:require [clojure.string :as str]
            [clojure.java.io :as io])
  (:import [java.io File]
           [java.nio.file Files]))

;; ---------------------------------------------------------------------------
;; Format maps
;; ---------------------------------------------------------------------------

(def ^:private audio-format-content-types
  {"mp3"  "audio/mpeg"
   "wav"  "audio/wav"
   "flac" "audio/flac"
   "opus" "audio/opus"
   "aac"  "audio/aac"
   "pcm"  "application/octet-stream"})

(def ^:private audio-format-extensions
  {"mp3"  ".mp3"
   "wav"  ".wav"
   "flac" ".flac"
   "opus" ".opus"
   "aac"  ".aac"
   "pcm"  ".pcm"})

(def ^:private mime-suffixes
  {"audio/wav"    ".wav"
   "audio/x-wav"  ".wav"
   "audio/mpeg"   ".mp3"
   "audio/mp3"    ".mp3"
   "audio/ogg"    ".ogg"
   "audio/webm"   ".webm"
   "audio/mp4"    ".m4a"
   "audio/x-m4a"  ".m4a"
   "audio/flac"   ".flac"
   "audio/aac"    ".aac"})

;; ---------------------------------------------------------------------------
;; Normalization
;; ---------------------------------------------------------------------------

(defn normalize-audio-format
  "Normalize an audio format string to canonical form."
  [fmt]
  (let [normalized (str/lower-case (str/trim (or fmt "mp3")))]
    (cond
      (contains? audio-format-content-types normalized) normalized
      (.startsWith ^String normalized "mp3")  "mp3"
      (.startsWith ^String normalized "pcm")  "pcm"
      (.startsWith ^String normalized "ulaw") "pcm"
      (= normalized "s16le")                  "pcm"
      :else "mp3")))

(defn normalize-voice-output-format
  "Normalize a voice output format string."
  [value]
  (if (str/blank? value)
    "mp3"
    (let [normalized (str/lower-case (str/trim value))]
      (cond
        (.startsWith ^String normalized "mp3")  "mp3"
        (.startsWith ^String normalized "pcm")  "pcm"
        (.startsWith ^String normalized "wav")  "wav"
        (.startsWith ^String normalized "flac") "flac"
        (.startsWith ^String normalized "opus") "opus"
        (.startsWith ^String normalized "aac")  "aac"
        :else (normalize-audio-format normalized)))))

(defn mime-for-audio-format
  "Get the MIME type for an audio format."
  [fmt]
  (get audio-format-content-types (normalize-audio-format fmt) "audio/mpeg"))

(defn audio-suffix-for-mime
  "Get the file extension for a MIME type."
  [mime]
  (let [normalized (str/lower-case (first (str/split (str/trim (or mime "")) #";")))]
    (get mime-suffixes normalized ".webm")))

;; ---------------------------------------------------------------------------
;; Audio conversion
;; ---------------------------------------------------------------------------

(defn convert-audio-bytes
  "Convert audio bytes between formats using ffmpeg.
   Optionally applies audio filters.
   Returns the original bytes if conversion fails or is not needed."
  [audio-bytes & {:keys [source-format target-format ffmpeg-bin audio-filters]
                  :or {audio-filters ""}}]
  (let [src-fmt (normalize-audio-format source-format)
        tgt-fmt (normalize-audio-format target-format)
        filters (str/trim (or audio-filters ""))]
    (if (and (= src-fmt tgt-fmt) (str/blank? filters))
      audio-bytes
      (if (str/blank? ffmpeg-bin)
        audio-bytes
        (let [src-ext (get audio-format-extensions src-fmt ".mp3")
              tgt-ext (get audio-format-extensions tgt-fmt ".mp3")
              temp-dir (Files/createTempDirectory "voxx_convert_" (into-array java.nio.file.attribute.FileAttribute []))
              src-path (.resolve temp-dir (str "input" src-ext))
              tgt-path (.resolve temp-dir (str "output" tgt-ext))]
          (try
            (Files/write src-path ^bytes audio-bytes (into-array java.nio.file.OpenOption []))
            (let [command (cond-> [ffmpeg-bin "-y" "-loglevel" "error"
                                   "-i" (str src-path)]
                            (not (str/blank? filters))
                            (conj "-af" filters)

                            (= tgt-fmt "pcm")
                            (into ["-f" "s16le" "-acodec" "pcm_s16le" "-ac" "1" "-ar" "24000"])

                            :always
                            (conj (str tgt-path)))
                  process (.start (ProcessBuilder. ^java.util.List command))
                  exit-code (.waitFor process)]
              (if (and (= exit-code 0) (.exists ^File (.toFile tgt-path)))
                (Files/readAllBytes tgt-path)
                audio-bytes))
            (catch Exception _ audio-bytes)
            (finally
              ;; Cleanup
              (try (Files/deleteIfExists src-path) (catch Exception _))
              (try (Files/deleteIfExists tgt-path) (catch Exception _))
              (try (Files/deleteIfExists temp-dir) (catch Exception _)))))))))
