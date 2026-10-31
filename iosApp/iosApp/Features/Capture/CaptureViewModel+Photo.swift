import Foundation
import Shared
import UIKit

// MARK: - Photo with the sentence (B8)

extension CaptureViewModel {
    /// A photo for the next sentence: previewed, named by the model when it can (B8), stored only once recorded.
    func addPhoto(_ data: Data) {
        namingTask?.cancel()
        guard data.count <= Int(PhotoStore.companion.MAX_SOURCE_BYTES) else {
            photoNotice = .tooLarge
            return
        }
        // UIImage applies the orientation; the preview stays in memory, never written.
        guard let image = UIImage(data: data), image.size.width > 0 else {
            photoNotice = .unsupported
            return
        }
        let side = CGSize(width: 320, height: 320 * image.size.height / image.size.width)
        guard let preview = image.preparingThumbnail(of: side) else {
            photoNotice = .unsupported
            return
        }
        photoData = data
        photoNotice = nil
        let naming = photoModel?.imagesActive == true
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
        photoData = nil
        photo = nil
        photoNotice = nil
    }

    /// A proposed name starts the sentence; the user completes the place and sends.
    func useName(_ name: String) {
        let current = input.trimmingCharacters(in: .whitespaces)
        typed(current.isEmpty ? "\(name) @ " : "\(name) \(current)")
    }

    /// The photo goes to the object the core names (B8); its events join the card so that « Défaire » removes it
    /// too. Kept for the next sentence when the outcome names several objects.
    func attachPhoto(to recorded: CaptureOutcomeRecorded) async throws -> [Event] {
        guard let data = photoData else { return [] }
        guard let itemId = recorded.photoItemId else {
            photoNotice = .severalItems
            return []
        }
        photoData = nil
        photo = nil
        let result = try await container.shared.photoAttacher.attach(
            ownerType: .item,
            ownerId: itemId,
            source: BytesKt.bytesOf(data: data),
            eventSource: .photo
        )
        if let done = result as? CommandResultDone {
            return done.events
        }
        photoNotice = (result as? CommandResultRejected)?.reason == .imageTooLarge ? .tooLarge : .unsupported
        return []
    }
}
