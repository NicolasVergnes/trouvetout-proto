import Foundation
import Shared
import UIKit

/// A question the card must ask before anything is written (B7 place, A14 duplicate, movement target).
enum PendingDecision {
    case place(proposal: CaptureProposal, resolution: PlaceResolution)
    /// `answers` holds the choices given so far, one per asked target.
    case item(
        proposal: CaptureProposal,
        placeChoice: PlaceChoice?,
        index: Int,
        target: ItemTarget,
        answers: [ItemChoice]
    )
}

/// What was just recorded, shown for three seconds (B6); tapping it puts the sentence back in the field.
struct ConfirmationCard {
    let intent: Intent
    let itemNames: [String]
    let placeText: String?
    let person: String?
    let rawInput: String
    /// Exactly what this capture wrote: « Défaire » reverts these and nothing else.
    let events: [Event]
    /// How the sentence came in (A4), also the source of the inverse events of « Défaire ».
    let source: EventSource
    let token: Int
    /// The photo this capture attached: « Modifier » gives it back with the sentence.
    var photo: AttachedPhoto?
}

/// A photo attached by a capture, kept in memory with its card.
struct AttachedPhoto {
    let data: Data
    let shown: CapturePhoto
}

/// Why the microphone is not listening; shown under the field, typing always works (B2, H9).
enum VoiceNotice {
    case notOnDevice
    case noRecognizer
    case noMatch
    case permissionDenied
    case failed
}

/// B10 « je range » : the place or container every sentence without a place of its own goes into.
struct BatchTarget {
    let ref: LocationRef
    let label: String
}

/// Why a photo of the capture could not be used (B8, A10).
enum PhotoNotice {
    case tooLarge
    case unsupported
    case severalItems
    case notSaved
}

/// The photo taken with the sentence, not stored before the sentence is recorded (B8).
struct CapturePhoto {
    let preview: UIImage
    /// Up to three names proposed by the text+vision model, in the UI language; empty on a text-only phone.
    var names: [NameSuggestion] = []
    var naming = false
}
