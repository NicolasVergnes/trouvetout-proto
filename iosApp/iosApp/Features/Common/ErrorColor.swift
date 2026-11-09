import SwiftUI
import UIKit

extension Color {
    /// Error and warning text (I6, lot 12): the system red reads at about 3.6:1 on white, under the 4.5:1 that
    /// footnote and callout text need. This red is ≥ 6.5:1 on the light grouped backgrounds (7.3:1 on white) and
    /// ≥ 6:1 on the dark ones (5.2:1 on a tertiary cell): AA everywhere.
    static let errorText = Color(
        UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(red: 1.0, green: 0.45, blue: 0.42, alpha: 1)
                : UIColor(red: 0.69, green: 0.0, blue: 0.13, alpha: 1)
        }
    )

    /// Explanations under a setting (I6, lot 12): the system secondary label is about 3.5:1 on a grouped cell, under
    /// the 4.5:1 that footnote text needs. This grey is ≥ 6:1 on the light cells and ≥ 6.5:1 on the dark ones.
    /// Tint of the buttons of the first steps card (I6, lot 12): a bordered button draws the system blue on a grey fill
    /// (about 3.3:1). This blue is ≥ 5.5:1 on that fill in light mode and the light blue ≥ 6:1 in dark mode.
    static let strongTint = Color(
        UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(red: 0.45, green: 0.72, blue: 1.0, alpha: 1)
                : UIColor(red: 0.0, green: 0.33, blue: 0.72, alpha: 1)
        }
    )

    static let hintText = Color(
        UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(white: 0.68, alpha: 1)
                : UIColor(white: 0.36, alpha: 1)
        }
    )
}
