import XCTest

/// I6 (lot 8): Xcode's accessibility audit (labels, contrast, hit regions, Dynamic Type, clipped text, traits) on
/// the main screens of the real app, as the user reaches them: home, settings (every scrolled screen of it since lot
/// 12), places, search.
final class AccessibilityAuditTests: XCTestCase {
    /// Every issue fails the test, except two kinds that are not errors, logged with their element:
    /// - « contrast nearly passed », the audit's warning tier (the system tint and secondary text);
    /// - Dynamic Type « partially » supported by a system control (a SwiftUI button: scaled, capped by the system in
    ///   the bars, where it offers the Large Content Viewer); « unsupported » stays an error;
    /// - « text clipped » on the system search field of `.searchable` (its placeholder, drawn by UIKit, out of the
    ///   app's hands even for two words).
    @available(iOS 17.0, *)
    private func audit(_ app: XCUIApplication) throws {
        try app.performAccessibilityAudit { issue in
            let element = issue.element.map { found in
                "\(found.elementType.rawValue) «\(found.label)» id=\(found.identifier)"
            } ?? "none"
            let warning = issue.compactDescription.localizedCaseInsensitiveContains("nearly passed")
            let partial = issue.auditType == .dynamicType &&
                issue.compactDescription.localizedCaseInsensitiveContains("partially") &&
                issue.element?.elementType == .button
            let systemSearch = issue.auditType == .textClipped && issue.element?.elementType == .searchField
            let ignored = warning || partial || systemSearch
            let tag = ignored ? " (not an error)" : ""
            print("AUDIT ISSUE\(tag): \(issue.compactDescription) — element: \(element)")
            return ignored
        }
    }

    /// Lot 12: the audit only sees what is on screen; the settings run over several screens (Vie privée, Photos,
    /// Achat, Sauvegarde chiffrée at the bottom), each audited after a swipe, then scrolled back to the top.
    @available(iOS 17.0, *)
    private func auditScrolled(_ app: XCUIApplication) throws {
        let list = app.collectionViews.firstMatch
        guard list.waitForExistence(timeout: 10) else { return }
        for _ in 0 ..< Self.settingsScreens {
            list.swipeUp()
            try audit(app)
        }
        for _ in 0 ..< Self.settingsScreens {
            list.swipeDown()
        }
    }

    private static let settingsScreens = 8

    override func setUp() {
        // Every audit issue is reported, not only the first one.
        continueAfterFailure = true
    }

    func testTheMainScreensPassTheAccessibilityAudit() throws {
        guard #available(iOS 17.0, *) else {
            throw XCTSkip("performAccessibilityAudit needs iOS 17")
        }
        let app = XCUIApplication()
        app.launch()

        XCTAssertTrue(app.textFields.firstMatch.waitForExistence(timeout: 60), "home screen")
        try audit(app)

        for screen in ["open_settings", "open_places", "open_search"] {
            let link = app.buttons[screen]
            XCTAssertTrue(link.waitForExistence(timeout: 10), screen)
            link.tap()
            XCTAssertTrue(app.navigationBars.buttons.firstMatch.waitForExistence(timeout: 10), screen)
            try audit(app)
            if screen == "open_settings" {
                try auditScrolled(app)
            }
            app.navigationBars.buttons.element(boundBy: 0).tap()
            XCTAssertTrue(app.textFields.firstMatch.waitForExistence(timeout: 10), "back home from \(screen)")
        }
    }
}
