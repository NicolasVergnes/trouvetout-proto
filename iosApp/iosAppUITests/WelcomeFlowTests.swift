import XCTest

/// I10 through the real screens: after a total wipe (the way a user starts afresh), the first-steps card is on the home
/// screen, never in the way of the field; it leads to the place sets, and once dismissed it stays gone, relaunch
/// included.
final class WelcomeFlowTests: XCTestCase {
    private let timeout: TimeInterval = 20

    func testTheFirstStepsCardLeadsToThePlacesAndGoesAwayForGood() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        let field = app.textFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 60), "home screen")

        wipe(app)

        let dismiss = app.buttons["welcome_dismiss"]
        XCTAssertTrue(dismiss.waitForExistence(timeout: timeout), "the card on a fresh journal")
        XCTAssertTrue(field.isHittable, "the field stays usable under the card")

        app.buttons["See the place sets"].tap()
        XCTAssertTrue(app.navigationBars["Places"].waitForExistence(timeout: timeout), "the place sets")
        app.navigationBars.buttons.element(boundBy: 0).tap()

        XCTAssertTrue(dismiss.waitForExistence(timeout: timeout), "still there until dismissed")
        dismiss.tap()
        XCTAssertTrue(waitForDisappearance(dismiss), "gone once dismissed")

        app.terminate()
        app.launch()
        XCTAssertTrue(app.textFields.firstMatch.waitForExistence(timeout: 60), "home screen after a relaunch")
        XCTAssertFalse(app.buttons["welcome_dismiss"].exists, "still gone after a relaunch")
    }

    /// Settings → Erase all data → Erase, then back home.
    private func wipe(_ app: XCUIApplication) {
        app.buttons["open_settings"].tap()
        let erase = app.buttons["Erase all data"]
        XCTAssertTrue(erase.waitForExistence(timeout: timeout), "the wipe button")
        erase.tap()
        let confirm = app.buttons["Erase"]
        XCTAssertTrue(confirm.waitForExistence(timeout: timeout), "the confirmation")
        confirm.tap()
        // The settings say it is done; close that, then go home.
        let done = app.alerts.firstMatch
        if done.waitForExistence(timeout: timeout) {
            done.buttons.firstMatch.tap()
        }
        let back = app.navigationBars.buttons.element(boundBy: 0)
        if back.waitForExistence(timeout: 5), !app.textFields.firstMatch.exists {
            back.tap()
        }
        XCTAssertTrue(app.textFields.firstMatch.waitForExistence(timeout: 60), "home screen after the wipe")
    }

    private func waitForDisappearance(_ element: XCUIElement) -> Bool {
        let gone = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: element)
        return XCTWaiter().wait(for: [gone], timeout: timeout) == .completed
    }
}
