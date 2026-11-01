import Foundation
import Shared
import UIKit

// MARK: - Photo with the sentence (B8)

extension CaptureViewModel {
    /// A photo for the next sentence: previewed, named by the model when it can (B8), stored only once recorded.
    func addPhoto(_ data: Data) {
        // The current photo (and its naming) stays until the new one is known to be usable.
        guard data.count <= Int(PhotoStore.companion.MAX_SOURCE_BYTES) else {
            photoNotice = .tooLarge
            return
        }
        Task { [weak self] in
            // Orientation applied, decoded subsampled off the main thread; the preview stays in memory, never written.
            let preview = await PhotoPreview.make(from: data)
            guard let self else { return }
            guard let preview else {
                photoNotice = .unsupported
                return
            }
            show(preview, of: data)
        }
    }

    private func show(_ preview: UIImage, of data: Data) {
        namingTask?.cancel()
        usedName = nil
        photoData = data
        photoNotice = nil
        // From the core, not from the watched flow, which may not have delivered its first value yet.
        let model = container.shared.modelSettings.state.value as? PhotoModelState
        let naming = model?.imagesActive == true
        photo = CapturePhoto(preview: preview, naming: naming)
        guard naming else { return }
        let lang = uiLang()
        namingTask = Task { [weak self] in
            guard let self else { return }
            let names = await (try? container.shared.search.suggestNames(
                photo: BytesKt.bytesOf(data: data),
                lang: lang,
                limit: 3
            )) ?? []
            guard !Task.isCancelled else { return }
            photo?.names = names
            photo?.naming = false
        }
    }

    func removePhoto() {
        namingTask?.cancel()
        usedName = nil
        photoData = nil
        photo = nil
        photoNotice = nil
    }

    /// A proposed name starts the sentence; the user completes the place and sends.
    func useName(_ name: String) {
        let current = input.trimmingCharacters(in: .whitespaces)
        var rest = current
        if let used = usedName, current.hasPrefix(used) {
            rest = String(current.dropFirst(used.count)).trimmingCharacters(in: .whitespaces)
        }
        usedName = name
        typed(rest.isEmpty || rest == "@" ? "\(name) @ " : "\(name) \(rest)")
    }

    /// The photo goes to the object the core names (B8); its events join the card so that « Défaire » removes it
    /// too. Kept for the next sentence when the outcome names several objects.
    func attachPhoto(to recorded: CaptureOutcomeRecorded) async -> [Event] {
        attachedPhoto = nil
        guard let data = photoData else { return [] }
        let shown = photo
        guard let itemId = recorded.photoItemId else {
            photoNotice = .severalItems
            return []
        }
        photoData = nil
        photo = nil
        photoNotice = nil
        usedName = nil
        namingTask?.cancel()
        // The sentence is recorded: a photo that cannot be stored must not hide its card nor its « Défaire ».
        let result: CommandResult
        do {
            result = try await container.shared.photoAttacher.attach(
                ownerType: .item,
                ownerId: itemId,
                source: BytesKt.bytesOf(data: data),
                eventSource: .photo
            )
        } catch {
            photoNotice = .notSaved
            return []
        }
        if let done = result as? CommandResultDone {
            if var shown {
                shown.naming = false
                attachedPhoto = AttachedPhoto(data: data, shown: shown)
            }
            return done.events
        }
        // The object is recorded; its size and format were checked when the photo was added.
        photoNotice = .notSaved
        return []
    }
}
