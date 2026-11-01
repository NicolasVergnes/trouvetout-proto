import XCTest

/// I6 (lot 8): Xcode's accessibility audit (labels, contrast, hit regions, Dynamic Type, clipped text, traits) on
/// the main screens of the real app, as the user reaches them: home, settings, places, search.
final class AccessibilityAuditTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    func testTheMainScreensPassTheAccessibilityAudit() throws {
        guard #available(iOS 17.0, *) else {
            throw XCTSkip("performAccessibilityAudit needs iOS 17")
        }
        let app = XCUIApplication()
        app.launch()

        XCTAssertTrue(app.textFields.firstMatch.waitForExistence(timeout: 60), "home screen")
        try app.performAccessibilityAudit()

        for screen in ["open_settings", "open_places", "open_search"] {
            let link = app.buttons[screen]
            XCTAssertTrue(link.waitForExistence(timeout: 10), screen)
            link.tap()
            XCTAssertTrue(app.navigationBars.buttons.firstMatch.waitForExistence(timeout: 10), screen)
            try app.performAccessibilityAudit()
            app.navigationBars.buttons.element(boundBy: 0).tap()
            XCTAssertTrue(app.textFields.firstMatch.waitForExistence(timeout: 10), "back home from \(screen)")
        }
    }
}
