import XCTest

/// E2, H9 through the real screens: a reminder set on the item sheet, then the explanation and the system question
/// about notifications, once the picker is gone. Needs a simulator where the app never asked (a fresh install in CI).
final class ReminderFlowTests: XCTestCase {
    private let timeout: TimeInterval = 20

    private func labelled(_ query: XCUIElementQuery, _ text: String) -> XCUIElement {
        query.matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    func testAReminderSetOnTheSheetAsksForNotificationsThenIsListed() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()

        // Letters only: a digit could be read as a quantity.
        let suffix = String((0 ..< 6).map { _ in "abcdefghjkmnpqrstuvwxyz".randomElement() ?? "a" })
        let item = "lamp\(suffix)"
        let place = "shelf\(suffix)"

        let field = app.textFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 60), "home screen")
        field.tap()
        field.typeText("\(item) @ \(place)\n")

        // The place is unknown: the question offers to create it (B7).
        let create = labelled(app.buttons, place)
        XCTAssertTrue(create.waitForExistence(timeout: timeout), "create the place")
        create.tap()

        let row = labelled(app.collectionViews.descendants(matching: .any), item)
        XCTAssertTrue(row.waitForExistence(timeout: timeout), "the object in the list")
        row.tap()

        let add = app.buttons["Add a reminder"]
        XCTAssertTrue(app.navigationBars.firstMatch.waitForExistence(timeout: timeout), "the item sheet")
        for _ in 0 ..< 10 where !add.isHittable {
            app.swipeUp()
        }
        XCTAssertTrue(add.isHittable, "the reminders section")
        add.tap()

        let save = app.navigationBars.buttons["Save"]
        XCTAssertTrue(save.waitForExistence(timeout: timeout), "the moment picker")
        save.tap()

        // Asked after the picker's dismissal: an alert asked during it would be lost.
        let goOn = app.alerts.buttons["Continue"]
        XCTAssertTrue(goOn.waitForExistence(timeout: timeout), "the explanation before the system question")
        goOn.tap()

        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let allow = springboard.alerts.buttons["Allow"]
        XCTAssertTrue(allow.waitForExistence(timeout: timeout), "the system question")
        allow.tap()

        XCTAssertTrue(
            labelled(app.buttons, "Remove").waitForExistence(timeout: timeout),
            "the reminder is listed on the sheet"
        )
    }
}
