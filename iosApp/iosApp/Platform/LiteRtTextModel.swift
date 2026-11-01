import CLiteRTLM
import Foundation
import LiteRTLM
import Shared

/// EmbeddingGemma 2 bundled with the app (ADR-021, ADR-022), run by LiteRT-LM on the CPU and read in place from the
/// app bundle: the text file, and on ≥ 6 GB iPhones the text+vision file, whose text weights are the same byte for
/// byte (one text identity). Nothing is downloaded; without the resources (a build without the models) the search
/// stays on keywords.
final class LiteRtTextModel: CallbackTextModelSource {
    private let textURL: URL?
    private let visionURL: URL?
    private let labelsURL: URL?
    private let textIdentity: String?
    private let visionIdentity: String?

    init(bundle: Bundle = .main) {
        textURL = bundle.url(forResource: "embeddinggemma-2-text-270m", withExtension: "litertlm")
        visionURL = bundle.url(forResource: "embeddinggemma-2-text-vision-440m", withExtension: "litertlm")
        labelsURL = bundle.url(forResource: "labels_fr_en", withExtension: "catalog")
        // Name and checksum prefix from the bundled manifest, as on Android: another model means new vectors.
        textIdentity = textURL == nil ? nil : LiteRtTextModel.manifestIdentity(bundle, variant: "TEXT_ONLY")
        visionIdentity = textURL == nil || visionURL == nil
            ? nil : LiteRtTextModel.manifestIdentity(bundle, variant: "OMNIMODAL")
        super.init()
    }

    override var modelId: String? {
        textIdentity
    }

    override var imageModelId: String? {
        visionIdentity
    }

    /// `id@sha256-prefix` of a model of `models/manifest.json`, or nil when the manifest is missing.
    private static func manifestIdentity(_ bundle: Bundle, variant: String) -> String? {
        guard let manifestURL = bundle.url(forResource: "manifest", withExtension: "json"),
              let data = try? Data(contentsOf: manifestURL),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let models = root["models"] as? [[String: Any]],
              let model = models.first(where: { $0["variant"] as? String == variant }),
              let id = model["id"] as? String,
              let sha = model["sha256"] as? String
        else {
            return nil
        }
        return "\(id)@\(sha.prefix(12))"
    }

    /// Every iPhone that runs iOS 16 has a 64-bit ARM processor, which the runtime supports.
    override var isSupported: Bool {
        true
    }

    override func labelCatalogData() -> Data? {
        labelsURL.flatMap { try? Data(contentsOf: $0) }
    }

    override func loadModel(withImages: Bool, completion: @escaping (CallbackTextEmbedder?, String?) -> Void) {
        guard let url = withImages ? visionURL : textURL else {
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
                // The core clips texts well below 512 tokens: the runtime rejects, never truncates, a longer input.
                if withImages {
                    let engine = EmbeddingEngine(
                        config: EmbeddingEngineConfig(
                            modelPath: url.path,
                            backend: .cpu(),
                            visionBackend: .cpu(),
                            cacheDir: cache.path,
                            maxInputLength: 512,
                            // 70 image tokens do as well as 140 on household photos at 2.4× the speed (ADR-022).
                            visionTokensPerImage: 70
                        )
                    )
                    try await engine.initialize()
                    completion(LiteRtImageEmbedder(engine: engine), nil)
                } else {
                    let engine = EmbeddingEngine(
                        config: EmbeddingEngineConfig(
                            modelPath: url.path,
                            backend: .cpu(),
                            cacheDir: cache.path,
                            maxInputLength: 512
                        )
                    )
                    try await engine.initialize()
                    completion(LiteRtTextEmbedder(engine: engine), nil)
                }
            } catch {
                // The type only: an error description could quote a path.
                completion(nil, String(describing: type(of: error)))
            }
        }
    }
}

/// One batch through the engine: one float32 vector per input, as `Data`; the failure is described by its type only.
private func computeVectors(
    _ engine: EmbeddingEngine,
    _ contents: [[LiteRTLM.Content]],
    dim: Int32,
    completion: @escaping ([Data]?, String?) -> Void
) {
    Task.detached(priority: .userInitiated) {
        do {
            let options = LiteRTLM.EmbeddingOptions(normalize: true, outputSize: Int(dim))
            let responses = try await engine.computeEmbeddingBatch(contentsBatch: contents, options: options)
            let vectors = responses.map { response in
                response.embedding.withUnsafeBufferPointer { Data(buffer: $0) }
            }
            completion(vectors, nil)
        } catch {
            completion(nil, String(describing: type(of: error)))
        }
    }
}

/// The loaded text engine behind the core's interface; the core runs one computation at a time.
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
        computeVectors(engine, texts.map { [LiteRTLM.Content.text($0)] }, dim: dim, completion: completion)
    }

    /// The engine frees its native memory when the last reference goes (an actor's `deinit`).
    override func close() {
        engine = nil
    }
}

/// The text+vision engine (B8, C10): photos (JPEG, already oriented, without metadata) land in the text space.
final class LiteRtImageEmbedder: CallbackImageEmbedder {
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
        computeVectors(engine, texts.map { [LiteRTLM.Content.text($0)] }, dim: dim, completion: completion)
    }

    override func embedJpegs(jpegs: [Data], dim: Int32, completion: @escaping ([Data]?, String?) -> Void) {
        guard let engine else {
            completion(nil, "closed")
            return
        }
        computeVectors(engine, jpegs.map { [LiteRTLM.Content.imageData($0)] }, dim: dim, completion: completion)
    }

    override func close() {
        engine = nil
    }
}
