import XCTest

/// U11 through the real screens: after the trial a new object is refused without losing the sentence, the options are
/// one tap away and never in the way, and the app goes on working. The debug launch argument gives an ended trial in
/// memory, so that the other tests of the simulator keep theirs.
final class TrialEndedFlowTests: XCTestCase {
    private let timeout: TimeInterval = 20

    func testANewObjectAfterTheTrialExplainsAndOffersWithoutBlocking() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US", "-uiTestTrialEnded"]
        app.launch()

        // Letters only: a digit could be read as a quantity.
        let suffix = String((0 ..< 6).map { _ in "abcdefghjkmnpqrstuvwxyz".randomElement() ?? "a" })
        let sentence = "lamp\(suffix) @ shelf\(suffix)"
        let field = app.textFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 60), "home screen")
        field.tap()
        field.typeText("\(sentence)\n")

        let options = app.alerts.buttons["See the options"]
        XCTAssertTrue(options.waitForExistence(timeout: timeout), "the moment an object is added")
        options.tap()

        let restore = app.buttons["purchase_restore"]
        XCTAssertTrue(restore.waitForExistence(timeout: timeout), "the options")
        XCTAssertTrue(app.staticTexts["Your free trial has ended"].exists)
        app.buttons["purchase_later"].tap()

        XCTAssertTrue(field.waitForExistence(timeout: timeout), "back on the home screen")
        XCTAssertEqual(field.value as? String, sentence, "the sentence is still there")
    }
}
