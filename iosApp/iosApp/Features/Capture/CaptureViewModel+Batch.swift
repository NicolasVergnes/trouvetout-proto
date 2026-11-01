import Shared

// MARK: - « Je range » mode (B10)

extension CaptureViewModel {
    /// The next sentences go to [ref] without naming it, until the mode is stopped.
    func startBatch(_ ref: LocationRef) {
        let label: String? =
            if let place = ref as? LocationRefInPlace {
                places.first { $0.id == place.placeId }?.name
            } else if let box = ref as? LocationRefInContainer {
                containers.first { $0.id == box.containerId }?.label
            } else {
                nil
            }
        guard let label else { return }
        batch = BatchTarget(ref: ref, label: label)
    }

    func stopBatch() {
        batch = nil
    }
}
