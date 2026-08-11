(ns kototama.native.executor
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kotoba.kir.admission :as admission]
            [kotoba.artifact.core :as artifact]
            [kotoba.artifact.runtime-identity :as runtime-identity]
            [kotoba.verifier.signing :as signing]
            [kotoba.kir.target :as target-profile])
  (:import [java.nio ByteBuffer]
           [java.nio.file Files LinkOption Path Paths]
           [java.lang ProcessHandle]
           [java.nio.charset CodingErrorAction StandardCharsets]
           [java.nio.file.attribute FileAttribute]
           [java.io ByteArrayOutputStream]
           [java.security MessageDigest]
           [java.util.concurrent TimeUnit]))

(def loader-source-sha256 runtime-identity/loader-source-sha256)
(def windows-loader-source-sha256 runtime-identity/windows-loader-source-sha256)

(defn- windows-host? [] (= :windows (let [os (str/lower-case (System/getProperty "os.name"))]
                                      (when (str/includes? os "win") :windows))))

(defn- raw-sha256 [bytes]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") bytes)]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest))))

(defn- file-sha256 [file]
  (raw-sha256 (Files/readAllBytes (.toPath ^java.io.File file))))

(defn- resolve-executable [name]
  (when-not (and (string? name) (seq name) (not (str/includes? name "/"))
                 (not (str/includes? name "\\")))
    (throw (ex-info "invalid toolchain executable name" {:phase :execute})))
  (let [path (or (System/getenv "PATH") "")
        suffixes (if (windows-host?) ["" ".exe" ".cmd" ".bat"] [""])
        candidates (for [entry (str/split path
                                          (re-pattern (java.util.regex.Pattern/quote
                                                       java.io.File/pathSeparator)) -1)
                         suffix suffixes]
                     (.resolve (if (empty? entry)
                                 (.toAbsolutePath (Paths/get "." (make-array String 0)))
                                 (.toAbsolutePath (Paths/get entry (make-array String 0))))
                               (str name suffix)))
        candidate (first (filter #(and (Files/isRegularFile % (make-array LinkOption 0))
                                       (Files/isExecutable %))
                                 candidates))]
    (when-not candidate
      (throw (ex-info "required toolchain executable was not found"
                      {:phase :execute})))
    (.toRealPath ^Path candidate (make-array LinkOption 0))))

(defn- host-target []
  (let [os (str/lower-case (System/getProperty "os.name"))
        arch (str/lower-case (System/getProperty "os.arch"))]
    (when-not (or (str/includes? os "linux") (str/includes? os "mac")
                  (str/includes? os "win"))
      (throw (ex-info "native execution is unsupported on this OS"
                      {:phase :execute :os os})))
    (cond
      (contains? #{"amd64" "x86_64"} arch) :x86_64-kotoba-v1
      (contains? #{"aarch64" "arm64"} arch) :aarch64-kotoba-v1
      :else (throw (ex-info "native execution is unsupported on this architecture"
                            {:phase :execute :arch arch})))))

(defn- host-os []
  (let [os (str/lower-case (System/getProperty "os.name"))]
    (cond
      (str/includes? os "linux") :linux
      (str/includes? os "mac") :macos
      (str/includes? os "win") :windows
      :else nil)))

(defn- explicit-host-target []
  (let [backend (host-target)
        os (host-os)]
    (case [backend os]
      [:x86_64-kotoba-v1 :linux] :x86_64-linux-kotoba-v1
      [:x86_64-kotoba-v1 :macos] :x86_64-macos-kotoba-v1
      [:x86_64-kotoba-v1 :windows] :x86_64-windows-kotoba-v1
      [:aarch64-kotoba-v1 :linux] :aarch64-linux-kotoba-v1
      [:aarch64-kotoba-v1 :macos] :aarch64-macos-kotoba-v1
      [:aarch64-kotoba-v1 :windows] :aarch64-windows-kotoba-v1
      (throw (ex-info "native host has no explicit target profile" {:phase :execute})))))

(defn- deterministic-linker-flags []
  (case (host-os)
    :macos []
    :windows ["-fuse-ld=lld" "-Wl,/timestamp:0,/Brepro"]
    ["-Wl,--build-id=none"]))

(def ^:private max-process-output-bytes (* 1024 1024))

(defn- terminate-process-tree! [^Process process]
  (let [handle (.toHandle process)]
    (with-open [descendants (.descendants handle)]
      (doseq [child (reverse (iterator-seq (.iterator descendants)))]
        (.destroyForcibly ^ProcessHandle child)))
    (.destroyForcibly handle)))

(defn- read-bounded [stream limit overflow!]
  (with-open [input stream
              output (ByteArrayOutputStream.)]
    (let [buffer (byte-array 8192)]
      (loop [total 0]
        (let [read (.read input buffer)]
          (cond
            (neg? read) (.toString output "UTF-8")
            (> (+ total read) limit)
            (do (overflow!) (.toString output "UTF-8"))
            :else
            (do (.write output buffer 0 read)
                (recur (+ total read)))))))))

(defn- run-process [command env {:keys [timeout-ms output-limit]
                                 :or {timeout-ms 5000
                                      output-limit max-process-output-bytes}}]
  (let [builder (ProcessBuilder. ^java.util.List (mapv str command))
        process-env (.environment builder)
        _ (.clear process-env)
        _ (.putAll process-env env)
        process (.start builder)
        stdout (atom nil)
        stderr (atom nil)
        output-exceeded? (atom false)
        overflow! #(when (compare-and-set! output-exceeded? false true)
                     (terminate-process-tree! process))
        stdout-reader (doto (Thread. #(reset! stdout
                                               (read-bounded (.getInputStream process)
                                                             output-limit overflow!)))
                        (.setDaemon true) (.start))
        stderr-reader (doto (Thread. #(reset! stderr
                                               (read-bounded (.getErrorStream process)
                                                             output-limit overflow!)))
                        (.setDaemon true) (.start))
        completed? (.waitFor process timeout-ms TimeUnit/MILLISECONDS)
        _ (when-not completed? (terminate-process-tree! process))
        _ (.waitFor process)
        exit (.exitValue process)]
    (.join stdout-reader)
    (.join stderr-reader)
    {:exit exit :stdout @stdout :stderr @stderr
     :timed-out? (not completed?) :output-exceeded? @output-exceeded?}))

(defn- toolchain-environment [^Path compiler-path]
  (let [system-root (System/getenv "SystemRoot")
        injection-vars #{"CFLAGS" "CPPFLAGS" "LDFLAGS" "CL" "_CL_" "LINK"
                         "CPATH" "C_INCLUDE_PATH" "CPLUS_INCLUDE_PATH"
                         "OBJC_INCLUDE_PATH" "LIBRARY_PATH" "COMPILER_PATH"
                         "GCC_EXEC_PREFIX"}
        host-env (when (windows-host?)
                   (into {}
                         (remove (fn [[name _]]
                                   (contains? injection-vars (str/upper-case name))))
                         (System/getenv)))
        windows-vars (when (windows-host?)
                       (into {}
                             (keep (fn [name]
                                     (when-let [value (System/getenv name)]
                                       [name value])))
                             ["INCLUDE" "LIB" "LIBPATH"
                              "ProgramFiles" "ProgramFiles(x86)" "ProgramW6432"
                              "VCINSTALLDIR" "VCToolsInstallDir"
                              "WindowsSdkDir" "WindowsSDKVersion"
                              "UniversalCRTSdkDir" "UCRTVersion"
                              "TEMP" "TMP"]))]
    (cond->
     (merge host-env
      {"PATH" (if (windows-host?)
                (str (.getParent compiler-path) java.io.File/pathSeparator
                     system-root "\\System32" java.io.File/pathSeparator
                     (System/getenv "PATH"))
                (str (.getParent compiler-path) java.io.File/pathSeparator
                     "/usr/bin" java.io.File/pathSeparator "/bin"))
       "LANG" "C"
       "LC_ALL" "C"
       "TZ" "UTC"
       "SOURCE_DATE_EPOCH" "0"
       "ZERO_AR_DATE" "1"}
      windows-vars)
      system-root (assoc "SystemRoot" system-root))))

(defn- resolve-reported-tool [reported env]
  (try
    (let [lines (str/split-lines reported)
          value (str/trim reported)]
      (when-not (and (= 1 (count lines)) (seq value) (<= (count value) 4096))
        (throw (ex-info "compiler reported a malformed tool path" {:phase :execute})))
      (let [candidate (Paths/get value (make-array String 0))
            resolved (cond
                       (.isAbsolute candidate) candidate
                       (not (str/includes? value "/"))
                       (some (fn [entry]
                               (let [path (.resolve
                                           (.toAbsolutePath
                                            (Paths/get entry (make-array String 0)))
                                           value)]
                                 (when (and (Files/isRegularFile path (make-array LinkOption 0))
                                            (Files/isExecutable path)) path)))
                             (str/split (get env "PATH" "")
                                        (re-pattern (java.util.regex.Pattern/quote
                                                     java.io.File/pathSeparator)) -1))
                       :else nil)]
        (when-not (and resolved
                       (Files/isRegularFile resolved (make-array LinkOption 0))
                       (Files/isExecutable resolved))
          (throw (ex-info "compiler-reported tool is not an executable file"
                          {:phase :execute})))
        (.toRealPath ^Path resolved (make-array LinkOption 0))))
    (catch clojure.lang.ExceptionInfo error (throw error))
    (catch Exception error
      (throw (ex-info "compiler reported a malformed tool path"
                      {:phase :execute} error)))))

(defn- compiler-tool [compiler-path name env]
  (let [query (run-process [(str compiler-path) (str "-print-prog-name=" name)]
                           env {:timeout-ms 5000 :output-limit 4096})]
    (when-not (and (zero? (:exit query))
                   (not (:timed-out? query))
                   (not (:output-exceeded? query))
                   (empty? (:stderr query)))
      (throw (ex-info "native compiler tool query failed" {:phase :execute})))
    (resolve-reported-tool (:stdout query) env)))

(defn- compiler-resource-directory [compiler-path env]
  (let [query (run-process [(str compiler-path) "-print-file-name=include"]
                           env {:timeout-ms 5000 :output-limit 4096})
        value (str/trim (:stdout query))]
    (when-not (and (zero? (:exit query))
                   (not (:timed-out? query))
                   (not (:output-exceeded? query))
                   (empty? (:stderr query))
                   (= 1 (count (str/split-lines (:stdout query))))
                   (seq value) (<= (count value) 4096))
      (throw (ex-info "native compiler resource query failed" {:phase :execute})))
    (try
      (let [path (Paths/get value (make-array String 0))]
        (when-not (and (.isAbsolute path)
                       (Files/isDirectory path (make-array LinkOption 0)))
          (throw (ex-info "compiler resource directory is not absolute"
                          {:phase :execute})))
        (.toRealPath path (make-array LinkOption 0)))
      (catch clojure.lang.ExceptionInfo error (throw error))
      (catch Exception error
        (throw (ex-info "compiler reported a malformed resource directory"
                        {:phase :execute} error))))))

(defn- directory-manifest-sha256 [^Path root]
  (with-open [stream (Files/walk root (make-array java.nio.file.FileVisitOption 0))]
    (let [iterator (.iterator stream)
          paths (loop [out []]
                  (if (.hasNext iterator)
                    (do
                      (when (>= (count out) 20000)
                        (throw (ex-info "compiler resource entry count exceeds limit"
                                        {:phase :execute :limit 20000})))
                      (recur (conj out (.next iterator))))
                    out))]
      (doseq [path paths]
        (when (Files/isSymbolicLink path)
          (throw (ex-info "compiler resource directory contains a symlink"
                          {:phase :execute})))
        (when-not (or (Files/isDirectory path (make-array LinkOption 0))
                      (Files/isRegularFile path (make-array LinkOption 0)))
          (throw (ex-info "compiler resource directory contains a special file"
                          {:phase :execute}))))
      (let [files (vec (filter #(Files/isRegularFile % (make-array LinkOption 0)) paths))
            _ (when (> (count files) 10000)
                (throw (ex-info "compiler resource file count exceeds limit"
                                {:phase :execute :limit 10000})))
            total (reduce + 0 (map #(Files/size ^Path %) files))
            _ (when (> total (* 64 1024 1024))
                (throw (ex-info "compiler resource bytes exceed limit"
                                {:phase :execute :limit (* 64 1024 1024)})))
            entries (mapv (fn [^Path path]
                            (let [relative (str/replace (str (.relativize root path)) "\\" "/")
                                  size (Files/size path)]
                              (when (> (count relative) 4096)
                                (throw (ex-info "compiler resource path exceeds limit"
                                                {:phase :execute :limit 4096})))
                              [relative size (file-sha256 (.toFile path))]))
                          (sort-by #(str (.relativize root ^Path %)) files))]
        (artifact/sha256 {:format :kotoba.directory-manifest/v1
                          :files entries :total-bytes total})))))

(defn- parse-dependency-file [text]
  (when-not (and (string? text) (<= (count text) (* 1024 1024))
                 (not (str/includes? text (str \u0000))))
    (throw (ex-info "compiler dependency file exceeds syntax limits"
                    {:phase :execute})))
  (let [colon (loop [index 0]
                (if (>= index (dec (count text)))
                  -1
                  (if (and (= \: (.charAt ^String text index))
                           (Character/isWhitespace (.charAt ^String text (inc index))))
                    index
                    (recur (inc index)))))]
    (when (neg? colon)
      (throw (ex-info "compiler dependency file has no target separator"
                      {:phase :execute})))
    (loop [index (inc colon) token (StringBuilder.) out []]
      (when (> (count out) 10000)
        (throw (ex-info "compiler dependency count exceeds limit"
                        {:phase :execute :limit 10000})))
      (if (>= index (count text))
        (cond-> out (pos? (.length token)) (conj (str token)))
        (let [ch (.charAt ^String text index)]
          (cond
            (= ch \\)
            (if (>= (inc index) (count text))
              (throw (ex-info "compiler dependency file ends in an escape"
                              {:phase :execute}))
              (let [next-ch (.charAt ^String text (inc index))]
                (cond
                  (= next-ch \newline)
                  (recur (+ index 2) token out)

                  (and (= next-ch \return)
                       (< (+ index 2) (count text))
                       (= \newline (.charAt ^String text (+ index 2))))
                  (recur (+ index 3) token out)

                  (or (Character/isWhitespace next-ch)
                      (contains? #{\# \: \\} next-ch))
                  (do (.append token next-ch)
                      (when (> (.length token) 4096)
                        (throw (ex-info "compiler dependency path exceeds limit"
                                        {:phase :execute :limit 4096})))
                      (recur (+ index 2) token out))

                  :else
                  ;; Clang emits native Windows separators without escaping
                  ;; them.  A backslash before an ordinary path character is
                  ;; therefore data, not Make escape syntax.
                  (do (.append token ch)
                      (recur (inc index) token out)))))

            (Character/isWhitespace ch)
            (if (zero? (.length token))
              (recur (inc index) token out)
              (recur (inc index) (StringBuilder.) (conj out (str token))))

            :else
            (do (.append token ch)
                (when (> (.length token) 4096)
                  (throw (ex-info "compiler dependency path exceeds limit"
                                  {:phase :execute :limit 4096})))
                (recur (inc index) token out))))))))

(defn- dependency-manifest-sha256 [dependencies]
  (let [paths (mapv (fn [value]
                      (try
                        (let [path (Paths/get value (make-array String 0))
                              absolute (if (.isAbsolute path) path (.toAbsolutePath path))]
                          (when-not (Files/isRegularFile absolute (make-array LinkOption 0))
                            (throw (ex-info "compiler dependency is not a regular file"
                                            {:phase :execute})))
                          (.toRealPath absolute (make-array LinkOption 0)))
                        (catch clojure.lang.ExceptionInfo error (throw error))
                        (catch Exception error
                          (throw (ex-info "compiler dependency path is malformed"
                                          {:phase :execute} error)))))
                    (distinct dependencies))
        _ (when (> (count paths) 10000)
            (throw (ex-info "compiler dependency count exceeds limit"
                            {:phase :execute :limit 10000})))
        total (reduce + 0 (map #(Files/size ^Path %) paths))
        _ (when (> total (* 64 1024 1024))
            (throw (ex-info "compiler dependency bytes exceed limit"
                            {:phase :execute :limit (* 64 1024 1024)})))
        entries (mapv (fn [^Path path]
                        [(str path) (Files/size path) (file-sha256 (.toFile path))])
                      (sort-by str paths))]
    (artifact/sha256 {:format :kotoba.dependency-manifest/v1
                      :files entries :total-bytes total})))

(defn- delete-tree! [file]
  (when (.exists ^java.io.File file)
    (doseq [child (reverse (file-seq file))] (io/delete-file child true))))

(defn- allowed-capabilities [policy]
  (->> (:allow policy #{})
       (keep (fn [effect]
               (when (and (vector? effect) (= :cap/call (first effect))
                          (integer? (second effect)))
                 (second effect))))
       sort
       (str/join ",")))

(defn- loader-source-file
  "Locate the reviewed loader source.

  It used to be read as `tools/<name>` relative to the process working
  directory, which is only correct when that directory happens to be the
  compiler repository that owns the file. Every other host -- a library with a
  native decision, a service, a test suite in another repo -- got
  `NoSuchFileException: tools/kexe_loader.c` and no way to say where it is.

  So the directory is now nameable, by `:loader-source-dir` or by
  `KOTOBA_LOADER_SOURCE_DIR`, and `tools/` under the working directory stays
  the last candidate so existing callers are unaffected. Naming a directory
  cannot smuggle in a different loader: `build-runtime!` still refuses any
  source whose digest is not the reviewed one for this target profile."
  [windows? source-dir]
  (let [file-name (if windows? "kexe_loader_windows.c" "kexe_loader.c")
        directories (remove str/blank?
                            [source-dir
                             (System/getenv "KOTOBA_LOADER_SOURCE_DIR")
                             "tools"])
        candidates (map #(io/file % file-name) directories)]
    (or (first (filter #(.isFile ^java.io.File %) candidates))
        (throw (ex-info "reviewed native loader source was not found"
                        {:phase :execute :file file-name
                         :searched (mapv #(.getPath ^java.io.File %) candidates)
                         :name-it :loader-source-dir})))))

(defn- build-runtime! [directory source-dir]
  (let [windows? (= :windows (host-os))
        loader (io/file directory (if windows? "kexe-loader.exe" "kexe-loader"))
        dependency-file (io/file directory "loader.d")
        dependency-object (io/file directory "loader-deps.o")
        loader-source (loader-source-file windows? source-dir)
        expected-source-sha (runtime-identity/loader-source-for-profile
                             (target-profile/profile (explicit-host-target)))
        compiler-path (resolve-executable (if windows? "clang" "cc"))
        compiler-file (.toFile compiler-path)
        compiler-binary-sha (file-sha256 compiler-file)
        toolchain-env (toolchain-environment compiler-path)
        assembler-path (if windows? compiler-path
                            (compiler-tool compiler-path "as" toolchain-env))
        linker-path (if windows? (resolve-executable "lld-link")
                          (compiler-tool compiler-path "ld" toolchain-env))
        resource-path (compiler-resource-directory compiler-path toolchain-env)
        assembler-sha (file-sha256 (.toFile assembler-path))
        linker-sha (file-sha256 (.toFile linker-path))
        resource-sha (directory-manifest-sha256 resource-path)
        actual-source-sha (file-sha256 loader-source)]
    (when-not (= expected-source-sha actual-source-sha)
      (throw (ex-info "native loader source identity mismatch"
                      {:phase :execute :expected expected-source-sha
                       :actual actual-source-sha})))
    (let [compiler (run-process [(str compiler-path) "--version"] toolchain-env
                                {:timeout-ms 5000})
          _ (when-not (and (zero? (:exit compiler))
                           (not (:timed-out? compiler))
                           (not (:output-exceeded? compiler)))
              (throw (ex-info "native C compiler identity query failed"
                              {:phase :execute :stderr (:stderr compiler)})))
          compiler-text (str (:stdout compiler) (:stderr compiler))
          common-flags [(str compiler-path) "-std=c11" "-O2" "-Wall" "-Wextra" "-Werror"]
          dependency-command (vec (concat common-flags
                                          ["-MD" "-MF" (.getPath dependency-file)
                                           "-c" (.getPath loader-source)
                                           "-o" (.getPath dependency-object)]))
          dependency-build (run-process dependency-command toolchain-env
                                        {:timeout-ms 30000})
          _ (when-not (and (zero? (:exit dependency-build))
                           (not (:timed-out? dependency-build))
                           (not (:output-exceeded? dependency-build)))
              (throw (ex-info "native compiler dependency scan failed"
                              {:phase :execute :stderr (:stderr dependency-build)})))
          _ (when (> (.length dependency-file) (* 1024 1024))
              (throw (ex-info "compiler dependency file exceeds byte limit"
                              {:phase :execute :limit (* 1024 1024)})))
          dependencies (parse-dependency-file (slurp dependency-file))
          dependency-sha (dependency-manifest-sha256 dependencies)
          build-command (fn []
                          (vec (concat
                                common-flags
                                (deterministic-linker-flags)
                                [(.getPath loader-source) "-o" (.getPath loader)]
                                (when windows? ["-ladvapi32" "-luserenv" "-lws2_32"
                                                "-lfwpuclnt" "-lrpcrt4"]))))
          build (run-process (build-command) toolchain-env {:timeout-ms 30000})
          first-loader-sha (when (zero? (:exit build)) (file-sha256 loader))
          reproduced-build (run-process (build-command) toolchain-env {:timeout-ms 30000})]
      (when-not (and (zero? (:exit build)) (zero? (:exit reproduced-build))
                     (not-any? #(or (:timed-out? %) (:output-exceeded? %))
                               [build reproduced-build]))
        (throw (ex-info "native loader build failed"
                        {:phase :execute :stderr (str (:stderr build)
                                                     (:stderr reproduced-build))})))
      (let [loader-sha (file-sha256 loader)]
        (when-not (= compiler-binary-sha (file-sha256 compiler-file))
          (throw (ex-info "native C compiler changed during measurement"
                          {:phase :execute})))
        (when-not (and (= assembler-sha (file-sha256 (.toFile assembler-path)))
                       (= linker-sha (file-sha256 (.toFile linker-path))))
          (throw (ex-info "native assembler or linker changed during measurement"
                          {:phase :execute})))
        (when-not (= resource-sha (directory-manifest-sha256 resource-path))
          (throw (ex-info "compiler resource directory changed during measurement"
                          {:phase :execute})))
        (when-not (= dependency-sha (dependency-manifest-sha256 dependencies))
          (throw (ex-info "compiler dependency closure changed during measurement"
                          {:phase :execute})))
        (when-not (= first-loader-sha loader-sha)
          (throw (ex-info "native loader build is not reproducible"
                          {:phase :execute :first first-loader-sha
                           :second loader-sha})))
        {:loader loader
         :runtime {:format :kotoba.native-runtime/v6
                   :target-profile (target-profile/profile (explicit-host-target))
                   :loader-source-sha256 expected-source-sha
                   :loader-binary-sha256 loader-sha
                   :compiler-binary-sha256 compiler-binary-sha
                   :compiler-version-sha256
                   (raw-sha256 (.getBytes compiler-text StandardCharsets/UTF_8))
                   :assembler-binary-sha256 assembler-sha
                   :linker-binary-sha256 linker-sha
                   :compiler-resource-sha256 resource-sha
                   :system-header-closure-sha256 dependency-sha}}))))

(defn measure-runtime
  "Build the reviewed loader twice and return its identity and exact bytes.

  `:loader-source-dir` says where the reviewed source is; without it, the
  `KOTOBA_LOADER_SOURCE_DIR` environment variable and then `tools/` under the
  working directory are tried, in that order."
  ([] (measure-runtime {}))
  ([{:keys [loader-source-dir]}]
  ;; Refuse unsupported hosts here, before invoking a host toolchain.
  (host-target)
  (let [directory (.toFile (Files/createTempDirectory
                            "kotoba-measure-" (make-array FileAttribute 0)))]
    (try
      (let [{:keys [loader runtime]} (build-runtime! directory loader-source-dir)]
        {:runtime runtime :loader-bytes (Files/readAllBytes (.toPath ^java.io.File loader))})
      (finally (delete-tree! directory))))))

(defn- trap-value [stderr]
  (when-let [[_ value] (re-find #"(?m)^KEXE_TRAP (\{.*\})$" stderr)]
    (edn/read-string value)))

(defn- loader-failure-class [stderr]
  (when-let [[_ stage win32]
             (re-find #"(?m)^kexe-loader-windows: ([A-Za-z0-9_ ()-]+)(?:: win32=([0-9]+))?$"
                      (or stderr ""))]
    (str stage (when win32 (str "/win32=" win32)))))

(defn- scalar-record-type? [type]
  (and (vector? type) (= 3 (count type)) (= :record (first type))
       (keyword? (second type))
       (let [fields (nth type 2)]
         (and (vector? fields) (<= 1 (count fields) 128)
              (every? #(and (vector? %) (= 2 (count %))
                            (keyword? (first %))
                            (contains? #{:i64 :bool} (second %)))
                      fields)
              (= (count fields) (count (distinct (map first fields))))))))

(defn- record-field-count [type]
  (when (scalar-record-type? type) (count (nth type 2))))

(defn- scalar-variant-type? [type]
  (and (vector? type) (= 3 (count type)) (= :variant (first type))
       (keyword? (second type)) (some? (namespace (second type)))
       (let [cases (nth type 2)]
         (and (vector? cases) (<= 1 (count cases) 32)
              (every? #(and (vector? %) (= 2 (count %))
                            (keyword? (first %))
                            (contains? #{:i64 :bool} (second %)))
                      cases)
              (= (count cases) (count (distinct (map first cases))))))))

(defn- variant-result-profile [type]
  (when (scalar-variant-type? type)
    (let [cases (nth type 2)
          bool-mask (reduce (fn [mask [index [_ payload-type]]]
                              (if (= :bool payload-type)
                                (bit-or mask (bit-shift-left 1 index))
                                mask))
                            0 (map-indexed vector cases))]
      (str "variant:" (count cases) ":" bool-mask))))

(defn- runtime-environment
  ([host-os-value] (runtime-environment host-os-value :i64))
  ([host-os-value result-type]
   (cond->
    (if (= :windows host-os-value)
      (if-let [system-root (or (System/getenv "SystemRoot")
                               (System/getenv "WINDIR"))]
        (if-let [local-app-data (System/getenv "LOCALAPPDATA")]
          {"KEXE_STRUCTURED_REPORT" "1"
           "SystemRoot" system-root
           ;; CreateProcessW uses this to materialize the per-profile
           ;; AppContainer environment. Do not inherit the rest of the user's
           ;; environment (PATH, credentials, or arbitrary injection knobs).
           "LOCALAPPDATA" local-app-data}
          (throw (ex-info "Windows AppContainer execution requires LOCALAPPDATA"
                          {:phase :execute})))
        (throw (ex-info "Windows native execution requires SystemRoot"
                        {:phase :execute})))
      {"KEXE_STRUCTURED_REPORT" "1"})
     (= :string result-type) (assoc "KEXE_RESULT_TYPE" "string")
     (contains? #{:option-i64 :result-i64} result-type)
     (assoc "KEXE_RESULT_TYPE" (name result-type))
     (scalar-record-type? result-type)
     (assoc "KEXE_RESULT_TYPE" (str "record:" (record-field-count result-type)))
     (scalar-variant-type? result-type)
     (assoc "KEXE_RESULT_TYPE" (variant-result-profile result-type)))))

(def ^:private hex-digits "0123456789abcdef")

(defn- bytes->hex [bytes]
  (let [out (StringBuilder. (* 2 (alength ^bytes bytes)))]
    (doseq [byte bytes]
      (let [value (bit-and (int byte) 0xff)]
        (.append out (.charAt hex-digits (unsigned-bit-shift-right value 4)))
        (.append out (.charAt hex-digits (bit-and value 0x0f)))))
    (str out)))

(defn- string-argument-token [entry index value]
  (when-not (string? value)
    (throw (ex-info "execution input does not match entry arguments (entry arity)"
                    {:phase :execute :entry entry :index index :expected :string})))
  (let [bytes (.getBytes ^String value StandardCharsets/UTF_8)]
    (when (> (alength bytes) 65536)
      (throw (ex-info "execution string input exceeds native host arena"
                      {:phase :execute :entry entry :index index
                       :limit-bytes 65536})))
    (str "s:" (bytes->hex bytes))))

(defn- scalar-host-word [entry index field-name type value]
  (case type
    :i64 (if (and (integer? value) (<= Long/MIN_VALUE value Long/MAX_VALUE))
           value
           (throw (ex-info "execution input does not match record field"
                           {:phase :execute :entry entry :index index
                            :field field-name :expected :i64})))
    :bool (if (boolean? value)
            (if value 1 0)
            (throw (ex-info "execution input does not match record field"
                            {:phase :execute :entry entry :index index
                             :field field-name :expected :bool})))))

(defn- record-argument-token [entry index type value]
  (let [fields (nth type 2)
        names (mapv first fields)]
    (when-not (and (map? value) (= (set names) (set (keys value))))
      (throw (ex-info "execution input does not match record fields"
                      {:phase :execute :entry entry :index index
                       :expected names})))
    (str "r:"
         (str/join ","
                   (map (fn [[field-name field-type]]
                          (scalar-host-word entry index field-name field-type
                                            (get value field-name)))
                        fields)))))

(defn- signed-i64? [value]
  (and (integer? value) (<= Long/MIN_VALUE value Long/MAX_VALUE)))

(defn- tagged-i64-argument-token [entry index type value]
  (let [valid? (if (= type :option-i64)
                 (and (vector? value)
                      (or (= [false] value)
                          (and (= 2 (count value)) (true? (first value))
                               (signed-i64? (second value)))))
                 (and (vector? value) (= 2 (count value))
                      (boolean? (first value))
                      (signed-i64? (second value))))]
    (when-not valid?
      (throw (ex-info "execution input does not match tagged i64 value"
                      {:phase :execute :entry entry :index index
                       :expected type})))
    (case type
      :option-i64 (if (false? (first value))
                    "o:none"
                    (str "o:some:" (second value)))
      :result-i64 (str (if (true? (first value)) "e:ok:" "e:err:")
                       (second value)))))

(defn- variant-argument-token [entry index type value]
  (let [cases (nth type 2)
        valid-shape? (and (vector? value) (= 3 (count value))
                          (= type (first value)) (keyword? (second value)))
        ordinal (when valid-shape?
                  (first (keep-indexed (fn [position [case-name _]]
                                         (when (= case-name (second value)) position))
                                       cases)))
        payload-type (when (some? ordinal) (second (nth cases ordinal)))
        payload (when valid-shape? (nth value 2))
        valid-payload? (case payload-type
                         :i64 (signed-i64? payload)
                         :bool (boolean? payload)
                         false)]
    (when-not (and valid-shape? (some? ordinal) valid-payload?)
      (throw (ex-info "execution input does not match scalar variant"
                      {:phase :execute :entry entry :index index
                       :expected type})))
    (str "v:" (count cases) ":" ordinal ":"
         (if (= :bool payload-type) "b" "i") ":"
         (if (= :bool payload-type) (if payload 1 0) payload))))

(defn- decode-utf8-hex [value]
  (when (and (string? value) (even? (count value))
             (boolean (re-matches #"[0-9a-f]*" value)))
    (try
      (let [bytes (byte-array (quot (count value) 2))]
        (dotimes [index (alength bytes)]
          (aset-byte bytes index
                     (unchecked-byte
                      (Integer/parseInt (.substring ^String value
                                                    (* 2 index) (+ (* 2 index) 2))
                                        16))))
        (let [decoder (doto (.newDecoder StandardCharsets/UTF_8)
                        (.onMalformedInput CodingErrorAction/REPORT)
                        (.onUnmappableCharacter CodingErrorAction/REPORT))]
          (str (.decode decoder (ByteBuffer/wrap bytes)))))
      (catch Exception _ nil))))

(defn- valid-supervisor-report? [report exit]
  (let [status (:status report)
        string-result? (and (= status :ok) (= :string (:result-type report)))
        record-result? (and (= status :ok) (= :record (:result-type report)))
        tagged-result? (and (= status :ok)
                            (contains? #{:option-i64 :result-i64}
                                       (:result-type report)))
        variant-result? (and (= status :ok) (= :variant (:result-type report)))
        expected-keys (case status
                        :ok (cond
                              string-result?
                              #{:status :result :result-type :result-utf8-hex :fuel :heap}
                              record-result?
                              #{:status :result :result-type :result-words :fuel :heap}
                              tagged-result?
                              #{:status :result :result-type :result-tag :result-word
                                :fuel :heap}
                              variant-result?
                              #{:status :result :result-type :result-ordinal :result-word
                                :fuel :heap}
                              :else #{:status :result :fuel :heap})
                        :trap #{:status :exit :fuel :heap}
                        nil)
        fuel (:fuel report)
        heap (:heap report)]
    (and (map? report)
         (= expected-keys (set (keys report)))
         (= (zero? exit) (= status :ok))
         (= #{:initial :remaining} (set (keys fuel)))
         (= 512 (:initial fuel))
         (integer? (:remaining fuel)) (<= 0 (:remaining fuel) 512)
         (= #{:capacity :used} (set (keys heap)))
         (= 4096 (:capacity heap))
         (integer? (:used heap)) (<= 0 (:used heap) 4096)
         (or (not= status :trap) (= exit (:exit report)))
         (or (not= status :ok) (integer? (:result report)))
         (or (not string-result?)
             (some? (decode-utf8-hex (:result-utf8-hex report))))
         (or (not record-result?)
             (and (vector? (:result-words report))
                  (<= 1 (count (:result-words report)) 128)
                  (every? #(and (integer? %)
                                (<= Long/MIN_VALUE % Long/MAX_VALUE))
                          (:result-words report))))
         (or (not tagged-result?)
             (and (boolean? (:result-tag report))
                  (signed-i64? (:result-word report))
                  (or (not= :option-i64 (:result-type report))
                      (:result-tag report)
                      (zero? (:result-word report)))))
         (or (not variant-result?)
             (and (integer? (:result-ordinal report))
                  (<= 0 (:result-ordinal report) 31)
                  (signed-i64? (:result-word report)))))))

(defn- entry-contract
  "Return the selected export's typed function boundary.

  `artifact :exports` owns machine offsets and arities; the sealed KIR program
  owns value types.  The entry-bearing `:signature` is not sufficient for a
  library because it is nil there, and it describes only `main` when present."
  [artifact entry]
  (let [matches (filter #(= entry (:name %)) (get-in artifact [:program :functions]))
        function (first matches)]
    (when-not (= 1 (count matches))
      (throw (ex-info "selected native export has no unique typed function"
                      {:phase :execute :entry entry})))
    {:param-types (or (:param-types function)
                      (vec (repeat (count (:params function)) :i64)))
     :result (:result function)}))

(defn- marshal-entry-arguments
  "Validate host values against the selected export and lower them to loader
  words.  A Kotoba `:bool` is a real host boolean at the public boundary and a
  0/1 word only inside native code; accepting integer 0/1 here would erase the
  type distinction that the reference, Wasm and restricted-ESM hosts preserve."
  [entry param-types args]
  (when-not (and (vector? args)
                 (= (count param-types) (count args))
                 (<= (count args) 5))
    (throw (ex-info "execution input does not match entry arguments (entry arity)"
                    {:phase :execute :entry entry
                     :arity (count param-types)})))
  (let [marshalled
        (mapv (fn [index type value]
                (cond
                  (= :i64 type)
                  (if (and (integer? value)
                           (<= Long/MIN_VALUE value Long/MAX_VALUE))
                    value
                    (throw (ex-info "execution input does not match entry arguments (entry arity)"
                                    {:phase :execute :entry entry :index index
                                     :expected :i64})))
                  (= :bool type)
                  (if (boolean? value)
                    (if value 1 0)
                    (throw (ex-info "execution input does not match entry arguments (entry arity)"
                                    {:phase :execute :entry entry :index index
                                     :expected :bool})))
                  (= :string type) (string-argument-token entry index value)
                  (contains? #{:option-i64 :result-i64} type)
                  (tagged-i64-argument-token entry index type value)
                  (scalar-record-type? type) (record-argument-token entry index type value)
                  (scalar-variant-type? type) (variant-argument-token entry index type value)
                  :else
                  (throw (ex-info "native execution host does not support entry parameter type"
                                  {:phase :execute :entry entry :index index :type type}))))
              (range) param-types args)
        string-bytes (reduce + 0
                             (map (fn [type token]
                                    (if (= :string type)
                                      (quot (- (count token) 2) 2)
                                      0))
                                  param-types marshalled))
        pair-cells (reduce + 0
                           (map #(cond (scalar-record-type? %) (record-field-count %)
                                       (scalar-variant-type? %) 1
                                       :else 0)
                                param-types))]
    (when (> string-bytes 65536)
      (throw (ex-info "execution string inputs exceed native host arena"
                      {:phase :execute :entry entry :bytes string-bytes
                       :limit-bytes 65536})))
    (when (> pair-cells 4096)
      (throw (ex-info "execution aggregate inputs exceed native pair arena"
                      {:phase :execute :entry entry :cells pair-cells
                       :limit-cells 4096})))
    marshalled))

(defn- admit-entry-result! [entry result-type]
  ;; Handles never cross the process boundary. The measured loader copies a
  ;; selected string, scalar record, option-i64, or result-i64 into typed
  ;; report fields before exit;
  ;; every other aggregate remains closed until it has the same ownership and
  ;; validation story.
  (when-not (or (contains? #{:i64 :bool :string :option-i64 :result-i64}
                           result-type)
                (scalar-record-type? result-type)
                (scalar-variant-type? result-type))
    (throw (ex-info "native execution host does not support entry result type"
                    {:phase :execute :entry entry :type result-type}))))

(defn- host-result [result-type report]
  (cond
    (= :bool result-type)
    (case (:result report)
      0 false
      1 true
      (throw (ex-info "native bool result is not 0/1"
                      {:phase :execute :word (:result report)})))
    (= :string result-type)
    (or (decode-utf8-hex (:result-utf8-hex report))
        (throw (ex-info "native string result is not canonical UTF-8 hex"
                        {:phase :execute})))
    (= :option-i64 result-type)
    (if (:result-tag report) [true (:result-word report)] [false])
    (= :result-i64 result-type)
    [(:result-tag report) (:result-word report)]
    (scalar-variant-type? result-type)
    (let [ordinal (:result-ordinal report)
          cases (nth result-type 2)]
      (when-not (and (integer? ordinal) (<= 0 ordinal) (< ordinal (count cases)))
        (throw (ex-info "native variant result ordinal is outside descriptor"
                        {:phase :execute :ordinal ordinal :case-count (count cases)})))
      (let [[case-name payload-type] (nth cases ordinal)
            word (:result-word report)
            payload (case payload-type
                      :i64 word
                      :bool (if (contains? #{0 1} word)
                              (= 1 word)
                              (throw (ex-info "native variant bool payload is not 0/1"
                                              {:phase :execute :case case-name
                                               :word word}))))]
        [result-type case-name payload]))
    (scalar-record-type? result-type)
    (let [fields (nth result-type 2)
          words (:result-words report)]
      (when-not (= (count fields) (count words))
        (throw (ex-info "native record result field count mismatch"
                        {:phase :execute :expected (count fields)
                         :actual (count words)})))
      (into {}
            (map (fn [[field-name field-type] word]
                   [field-name
                    (case field-type
                      :i64 word
                      :bool (if (contains? #{0 1} word)
                              (= 1 word)
                              (throw (ex-info "native record bool field is not 0/1"
                                              {:phase :execute :field field-name
                                               :word word}))))])
                 fields words)))
    :else (:result report)))

(defn- admit-validity!
  "Re-check the part of trust that can change while a session is open.

  `prepare` verifies the signature and the artifact once. Those facts are
  time-invariant: the same bytes carry the same signature no matter when they
  are read, and the staged copy under the session directory is the one that
  runs. Expiry, not-before, and the two revocation lists are NOT time-
  invariant, so every invocation re-checks them against its own `now` against
  the trust the session was opened with. Amortizing THESE is the change that
  would actually weaken the boundary, so it is not amortized."
  [{:keys [statement trust]} now]
  (let [{:keys [signer not-before expires artifact-sha256]} statement]
    (when (contains? (set (:revoked-signers trust)) signer)
      (throw (ex-info "signer is revoked" {:phase :trust :signer signer})))
    (when (contains? (set (:revoked-artifacts trust)) artifact-sha256)
      (throw (ex-info "artifact is revoked"
                      {:phase :trust :artifact artifact-sha256})))
    (when (< now not-before)
      (throw (ex-info "signature is not yet valid"
                      {:phase :trust :not-before not-before :now now})))
    (when (>= now expires)
      (throw (ex-info "signature is expired"
                      {:phase :trust :expires expires :now now})))))

(defn prepare
  "Verify a signed native artifact once and stage its measured execution
  environment. Returns a session usable for many `invoke` calls.

  WHY THIS EXISTS

  `execute` verifies on every call, and verification is not cheap: the
  signature check is small, but `verify-artifact!` re-validates the whole
  sealed artifact, which for a 19k-word module measured 1.7-3.6 s on an M4
  while the program itself runs in microseconds and the loader process costs
  about 2 ms over a bare spawn. A host that calls a Kotoba decision more than
  once therefore paid three orders of magnitude more for re-reading the
  artifact than for running it, which is why callers reached for the
  interpreter instead and native stayed a conformance target rather than an
  execution path.

  WHAT IS AMORTIZED, AND WHAT IS NOT

  Amortized (time-invariant, and the staged bytes cannot change underneath a
  session because the session owns them):
    - Ed25519 signature and statement/artifact agreement
    - `verify-artifact!` structural verification of the sealed artifact
    - loader binary measurement against the runtime identity
    - host/artifact/runtime target-profile agreement
    - writing the measured loader and `program.bin` into the session directory

  Not amortized -- `invoke` re-checks each of these per call:
    - expiry, not-before, revoked signers, revoked artifacts (`admit-validity!`)
    - capability admission of the artifact's effects against THAT call's policy
    - argument marshalling and result-type admission

  The session directory holds only the measured loader and the verified code;
  `close!` removes it. A session is not serializable and must not outlive the
  process that opened it."
  [envelope trust {:keys [now runtime loader-path]}]
  (let [{artifact :artifact signer :signer} (signing/verify envelope trust now)
        host-backend (host-target)
        host-os-value (host-os)
        artifact-backend (target-profile/backend (:target artifact))
        artifact-profile (:target-profile artifact)
        runtime-profile (:target-profile runtime)]
    (when-not (and (= host-backend artifact-backend)
                   (contains? #{:unspecified host-os-value} (:os artifact-profile)))
      (throw (ex-info "artifact target does not match execution host"
                      {:phase :execute :artifact-target (:target artifact)
                       :host-target host-backend})))
    (when-not (= (target-profile/profile (explicit-host-target)) runtime-profile)
      (throw (ex-info "runtime target profile does not match execution host"
                      {:phase :runtime-identity})))
    (when-not (and (= (:execution artifact-profile) (:execution runtime-profile))
                   (= (:isa artifact-profile) (:isa runtime-profile))
                   (= (:abi artifact-profile) (:abi runtime-profile))
                   (contains? #{:unspecified (:os runtime-profile)} (:os artifact-profile))
                   (or (= :kotoba-supervisor-v1 (:runtime artifact-profile))
                       (= (:runtime artifact-profile) (:runtime runtime-profile))))
      (throw (ex-info "artifact and runtime target profiles do not match"
                      {:phase :runtime-identity})))
    (runtime-identity/admit! runtime trust)
    (when-not (and (string? loader-path) (seq loader-path))
      (throw (ex-info "native execution requires a measured loader"
                      {:phase :execute})))
    (let [loader-source (io/file loader-path)
          loader-bytes (when (.isFile loader-source)
                         (Files/readAllBytes (.toPath loader-source)))]
      (when-not (and loader-bytes (.canExecute loader-source)
                     (= (:loader-binary-sha256 runtime) (raw-sha256 loader-bytes)))
        (throw (ex-info "measured native loader does not match runtime identity"
                        {:phase :runtime-identity})))
      (let [directory (.toFile (Files/createTempDirectory
                                "kotoba-native-" (make-array FileAttribute 0)))
            code-file (io/file directory "program.bin")
            loader (io/file directory (if (= :windows host-os-value)
                                        "kexe-loader.exe" "kexe-loader"))]
        (try
          (with-open [out (io/output-stream loader)]
            (.write out ^bytes loader-bytes))
          (when-not (or (= :windows host-os-value) (.setExecutable loader true true))
            (throw (ex-info "cannot make measured loader executable"
                            {:phase :execute})))
          (with-open [out (io/output-stream code-file)]
            (.write out ^bytes (byte-array (map unchecked-byte (:code artifact)))))
          {:format :kotoba.native-session/v1
           :artifact artifact
           :signer signer
           :trust trust
           :statement (:statement envelope)
           :runtime runtime
           :target (:target artifact)
           :host-backend host-backend
           :host-os host-os-value
           :directory directory
           :code-file code-file
           :loader loader}
          (catch Throwable failure
            (delete-tree! directory)
            (throw failure)))))))

(defn close!
  "Remove a session's staged loader and code. Idempotent."
  [session]
  (when-let [directory (:directory session)]
    (delete-tree! directory))
  nil)

(defn invoke
  "Execute one export of a prepared session. Returns measured supervisor
  evidence in the same shape `execute` returns."
  [session policy input {:keys [now entry]}]
  (let [{:keys [artifact runtime host-backend host-os code-file loader]} session
        entry (or entry 'main)
        export (get (:exports artifact) entry)
        args (:args input)]
    (when-not (= :kotoba.native-session/v1 (:format session))
      (throw (ex-info "unknown native session" {:phase :execute})))
    (admit-validity! session now)
    (admission/check {:effects (:effects artifact)} policy)
    (when-not export
      (throw (ex-info "unknown native entry" {:phase :execute :entry entry})))
    (let [{:keys [param-types result]} (entry-contract artifact entry)]
      (when-not (map? input)
        (throw (ex-info "execution input does not match entry arguments (entry arity)"
                        {:phase :execute :entry entry :arity (:arity export)})))
      (when-not (= (:arity export) (count param-types))
        (throw (ex-info "native export arity disagrees with sealed KIR"
                        {:phase :execute :entry entry :export-arity (:arity export)
                         :kir-arity (count param-types)})))
      (let [args (marshal-entry-arguments entry param-types args)]
        (admit-entry-result! entry result)
        (when-not (and (.isFile ^java.io.File code-file)
                       (.isFile ^java.io.File loader))
          (throw (ex-info "native session is closed" {:phase :execute})))
        (let [isa (if (= host-backend :x86_64-kotoba-v1) "x86_64" "aarch64")
              allow (let [ids (allowed-capabilities policy)] (if (empty? ids) "-" ids))
              command (into [(.getPath ^java.io.File loader) (.getPath ^java.io.File code-file)
                             (str (:offset export)) (str (:arity export)) isa allow]
                            (map str args))
              started-at (quot (System/currentTimeMillis) 1000)
              process (run-process command (runtime-environment host-os result)
                                   {:timeout-ms (if (= :windows host-os) 60000 5000)
                                    :output-limit (if (= :string result) 160000 65536)})
              finished-at (quot (System/currentTimeMillis) 1000)
              report (edn/read-string (str/trim (:stdout process)))
              trap (trap-value (:stderr process))
              status (:status report)
              _ (when-not (valid-supervisor-report? report (:exit process))
                  (throw (ex-info (str "malformed native supervisor evidence"
                                       " (exit=" (:exit process)
                                       ", timed-out=" (:timed-out? process)
                                       ", output-exceeded=" (:output-exceeded? process)
                                       ", report-status=" status
                                       ", loader-failure="
                                       (loader-failure-class (:stderr process)) ")")
                                  {:phase :execute :exit (:exit process)
                                   :stdout (:stdout process) :stderr (:stderr process)})))
              ;; Box a `:bool` entry's result at this boundary. `:bool` is a
              ;; plain 0/1 word inside a module -- in the interpreter, in a
              ;; wasm module, and in these backends' own setcc/cset sequences
              ;; -- but the value that LEAVES a target is a host boolean
              ;; (`kotoba-kir` 38d1bd0, 2026-07-31): wasm emits an export
              ;; wrapper that boxes one, the restricted-ESM emitter returns
              ;; one, and the reference interpreter boxes one so the shared
              ;; corpora agree on the same value for a predicate. Native was
              ;; the last target still handing back the word.
              ;;
              ;; The supervisor's own report is untouched and still carries the
              ;; integer -- `valid-supervisor-report?` continues to require
              ;; that, and `:report` is returned verbatim below. Only
              ;; `:evidence :result`, the value a caller reads as the program's
              ;; answer, is boxed.
              evidence (cond-> {:status status :runtime runtime}
                         (= status :ok)
                         (assoc :result (host-result result report))
                         trap (assoc :trap trap))]
          {:artifact artifact :signer (:signer session) :target (:target artifact)
           :entry entry :input input :evidence evidence :report report
           :started-at started-at :finished-at finished-at})))))

(defn execute
  "Verify and execute a signed native artifact. Returns measured supervisor
  evidence.

  This is `prepare` + one `invoke` + `close!`. A caller that runs one entry
  once should keep using it. A caller that runs many should hold the session,
  because what this composition pays for is verifying the artifact again for
  every call.

  One ordering note for callers reading error phases: capability admission now
  runs after the host/runtime target checks rather than before them, because
  the target checks belong to opening the session and admission belongs to the
  call. Both still refuse; only which refusal is reported first can differ."
  [envelope trust policy input opts]
  (let [session (prepare envelope trust opts)]
    (try
      (invoke session policy input opts)
      (finally (close! session)))))
