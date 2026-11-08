import SwiftUI
import UIKit

extension Color {
    /// Error and warning text (I6, lot 12): the system red reads at about 3.6:1 on white, under the 4.5:1 that
    /// footnote and callout text need. This red is ≥ 7:1 on the light backgrounds and ≥ 6:1 on the dark ones.
    static let errorText = Color(
        UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(red: 1.0, green: 0.45, blue: 0.42, alpha: 1)
                : UIColor(red: 0.69, green: 0.0, blue: 0.13, alpha: 1)
        }
    )
}
