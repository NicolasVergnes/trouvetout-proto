import CLiteRTLM
import Foundation
import LiteRTLM
import Shared

/// EmbeddingGemma 2 bundled with the app (ADR-021), run by LiteRT-LM on the CPU and read in place from the app
/// bundle. Nothing is downloaded; without the resource (a build without the model) the search stays on keywords.
final class LiteRtTextModel: CallbackTextModelSource {
    private let url: URL?
    private let identity: String?

    init(bundle: Bundle = .main) {
        url = bundle.url(forResource: "embeddinggemma-2-text-270m", withExtension: "litertlm")
        // Name and checksum prefix from the bundled manifest, as on Android: another model means new vectors.
        identity = url == nil ? nil : LiteRtTextModel.manifestIdentity(bundle)
        super.init()
    }

    override var modelId: String? {
        identity
    }

    /// `id@sha256-prefix` of the text model in `models/manifest.json`, or nil when the manifest is missing.
    private static func manifestIdentity(_ bundle: Bundle) -> String? {
        guard let manifestURL = bundle.url(forResource: "manifest", withExtension: "json"),
              let data = try? Data(contentsOf: manifestURL),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let models = root["models"] as? [[String: Any]],
              let text = models.first(where: { $0["variant"] as? String == "TEXT_ONLY" }),
              let id = text["id"] as? String,
              let sha = text["sha256"] as? String
        else {
            return nil
        }
        return "\(id)@\(sha.prefix(12))"
    }

    /// Every iPhone that runs iOS 16 has a 64-bit ARM processor, which the runtime supports.
    override var isSupported: Bool {
        true
    }

    override func loadModel(completion: @escaping (CallbackTextEmbedder?, String?) -> Void) {
        guard let url else {
            completion(nil, "no model in this build")
            return
        }
        Task.detached(priority: .userInitiated) {
            do {
                let cache = try FileManager.default.url(
                    for: .cachesDirectory,
                    in: .userDomainMask,
                    appropriateFor: nil,
                    create: true
                ).appendingPathComponent("litertlm", isDirectory: true)
                try FileManager.default.createDirectory(at: cache, withIntermediateDirectories: true)
                // The native library logs its inputs at some levels (rule 3: no business data in logs, debug included).
                litert_lm_set_min_log_level(kLiteRtLmLogSeveritySilent)
                let engine = EmbeddingEngine(
                    config: EmbeddingEngineConfig(
                        modelPath: url.path,
                        backend: .cpu(),
                        cacheDir: cache.path,
                        // The core clips texts well below this: the runtime rejects, never truncates, a longer input.
                        maxInputLength: 512
                    )
                )
                try await engine.initialize()
                completion(LiteRtTextEmbedder(engine: engine), nil)
            } catch {
                // The type only: an error description could quote a path.
                completion(nil, String(describing: type(of: error)))
            }
        }
    }
}

/// The loaded engine behind the core's interface; the core runs one computation at a time.
final class LiteRtTextEmbedder: CallbackTextEmbedder {
    private var engine: EmbeddingEngine?

    init(engine: EmbeddingEngine) {
        self.engine = engine
        super.init()
    }

    override func embedTexts(texts: [String], dim: Int32, completion: @escaping ([Data]?, String?) -> Void) {
        guard let engine else {
            completion(nil, "closed")
            return
        }
        Task.detached(priority: .userInitiated) {
            do {
                let options = LiteRTLM.EmbeddingOptions(normalize: true, outputSize: Int(dim))
                let responses = try await engine.computeEmbeddingBatch(
                    contentsBatch: texts.map { [LiteRTLM.Content.text($0)] },
                    options: options
                )
                let vectors = responses.map { response in
                    response.embedding.withUnsafeBufferPointer { Data(buffer: $0) }
                }
                completion(vectors, nil)
            } catch {
                completion(nil, String(describing: type(of: error)))
            }
        }
    }

    /// The engine frees its native memory when the last reference goes (an actor's `deinit`).
    override func close() {
        engine = nil
    }
}
