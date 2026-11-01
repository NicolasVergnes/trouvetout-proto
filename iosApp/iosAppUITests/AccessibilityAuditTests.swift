import XCTest

/// I6 (lot 8): Xcode's accessibility audit (labels, contrast, hit regions, Dynamic Type, clipped text, traits) on
/// the main screens of the real app, as the user reaches them: home, settings, places, search.
final class AccessibilityAuditTests: XCTestCase {
    /// Every issue fails the test, except two kinds that are not errors, logged with their element:
    /// - « contrast nearly passed », the audit's warning tier (the system tint and secondary text);
    /// - Dynamic Type of a navigation bar button: iOS caps its size itself and offers the Large Content Viewer.
    @available(iOS 17.0, *)
    private func audit(_ app: XCUIApplication) throws {
        try app.performAccessibilityAudit { issue in
            let element = issue.element.map { found in
                "\(found.elementType.rawValue) «\(found.label)» id=\(found.identifier)"
            } ?? "none"
            let warning = issue.compactDescription.localizedCaseInsensitiveContains("nearly passed")
            let barButton = issue.auditType == .dynamicType &&
                ["open_settings", "open_places", "open_search"].contains(issue.element?.identifier ?? "")
            let ignored = warning || barButton
            let tag = ignored ? " (not an error)" : ""
            print("AUDIT ISSUE\(tag): \(issue.compactDescription) — element: \(element)")
            return ignored
        }
    }

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
            app.navigationBars.buttons.element(boundBy: 0).tap()
            XCTAssertTrue(app.textFields.firstMatch.waitForExistence(timeout: 10), "back home from \(screen)")
        }
    }
}
