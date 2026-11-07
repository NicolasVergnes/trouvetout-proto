import Shared
import SwiftUI
import UIKit

/// Texts and system links of the capture screen, kept out of the view (SwiftLint type body length).
enum CaptureTexts {
    static func whereText(_ view: ItemView) -> String {
        switch view.location.status {
        case .taken: NSLocalizedString("state_taken", comment: "")
        case .lent: String(format: NSLocalizedString("state_lent", comment: ""), view.location.lentTo ?? "")
        default:
            if view.path.isUnknown {
                NSLocalizedString("state_unknown_place", comment: "")
            } else {
                view.path.labels.joined(separator: " › ")
            }
        }
    }

    static func problemKey(_ problem: CaptureProblem) -> LocalizedStringKey {
        switch problem {
        case .nothingUnderstood: "capture_problem_nothing"
        case .placeUndecided: "capture_problem_place"
        case .itemNotFound: "capture_problem_item"
        case .personMissing: "capture_problem_person"
        case .inputTooLong: "capture_problem_too_long"
        case .dateMissing: "capture_problem_date_missing"
        case .datePast: "capture_problem_date_past"
        case .trialEnded: "capture_problem_trial_ended"
        default: "capture_problem_rejected"
        }
    }

    static func openAppSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }
}
