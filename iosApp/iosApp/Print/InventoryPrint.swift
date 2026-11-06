import Shared
import SwiftUI
import UIKit

/// C16: a place or a container printed as text through the system print sheet (which also saves a PDF). The text is
/// laid out by `UISimpleTextPrintFormatter`, which paginates at the printer's page size; no WebKit.
enum InventoryPrint {
    @MainActor
    static func present(_ inventory: Inventory) {
        let info = UIPrintInfo(dictionary: nil)
        info.outputType = .general
        // Named after the app, never after the place: the Print Center shows the job's name.
        info.jobName = "TrouveTout"
        let controller = UIPrintInteractionController.shared
        controller.printInfo = info
        controller.printFormatter = UISimpleTextPrintFormatter(attributedText: text(inventory))
        // The controller is a singleton: the text does not stay in memory once the sheet is gone.
        controller.present(animated: true) { finished, _, _ in
            finished.printFormatter = nil
        }
    }

    /// The document: title, path, date, then one paragraph per line, indented by depth.
    static func text(_ inventory: Inventory) -> NSAttributedString {
        let out = NSMutableAttributedString()
        func add(_ string: String, _ font: UIFont, depth: Int = 0) {
            let paragraph = NSMutableParagraphStyle()
            paragraph.firstLineHeadIndent = CGFloat(depth) * indent
            paragraph.headIndent = CGFloat(depth) * indent
            paragraph.paragraphSpacing = 4
            let attributes: [NSAttributedString.Key: Any] = [.font: font, .paragraphStyle: paragraph]
            out.append(NSAttributedString(string: string + "\n", attributes: attributes))
        }
        add(inventory.title, .boldSystemFont(ofSize: 18))
        if !inventory.path.isEmpty {
            add(inventory.path.joined(separator: " › "), .systemFont(ofSize: 9))
        }
        let today = Date().formatted(date: .long, time: .omitted)
        add(String(format: NSLocalizedString("print_printed_on", comment: ""), today), .systemFont(ofSize: 9))
        if inventory.lines.isEmpty {
            add(NSLocalizedString("print_empty", comment: ""), .systemFont(ofSize: 11))
        }
        for line in inventory.lines {
            add(lineText(line), font(line.kind), depth: Int(line.depth))
        }
        if inventory.hiddenCount > 0 {
            let hidden = String(format: NSLocalizedString("print_hidden", comment: ""), Int(inventory.hiddenCount))
            add(hidden, .systemFont(ofSize: 9))
        }
        return out
    }

    /// The same text rendered as PDF pages of A4 size (tests and a check of the pagination).
    static func pdf(_ inventory: Inventory) -> Data {
        let page = CGRect(x: 0, y: 0, width: 595, height: 842)
        let renderer = UIPrintPageRenderer()
        renderer.addPrintFormatter(UISimpleTextPrintFormatter(attributedText: text(inventory)), startingAtPageAt: 0)
        renderer.setValue(NSValue(cgRect: page), forKey: "paperRect")
        renderer.setValue(NSValue(cgRect: page.insetBy(dx: 36, dy: 36)), forKey: "printableRect")
        let data = NSMutableData()
        UIGraphicsBeginPDFContextToData(data, page, nil)
        for index in 0 ..< max(renderer.numberOfPages, 1) {
            UIGraphicsBeginPDFPage()
            renderer.drawPage(at: index, in: UIGraphicsGetPDFContextBounds())
        }
        UIGraphicsEndPDFContext()
        return data as Data
    }

    private static let indent: CGFloat = 18

    private static func font(_ kind: InventoryLine.Kind) -> UIFont {
        switch kind {
        case .place: .boldSystemFont(ofSize: 11)
        case .container: UIFont.systemFont(ofSize: 11).withTraits(.traitBold, .traitItalic)
        default: .systemFont(ofSize: 11)
        }
    }

    private static func lineText(_ line: InventoryLine) -> String {
        var parts = [line.label]
        if let quantity = line.quantity?.doubleValue {
            let number = formatQuantity(quantity)
            parts.append(line.unit.map { "\(number) \($0)" } ?? "× \(number)")
        }
        switch line.status {
        case .taken: parts.append(NSLocalizedString("print_taken", comment: ""))
        case .lent: parts.append(String(format: NSLocalizedString("print_lent", comment: ""), line.lentTo ?? ""))
        default: break
        }
        return parts.joined(separator: " · ")
    }
}

private extension UIFont {
    func withTraits(_ traits: UIFontDescriptor.SymbolicTraits...) -> UIFont {
        guard let descriptor = fontDescriptor.withSymbolicTraits(UIFontDescriptor.SymbolicTraits(traits)) else {
            return self
        }
        return UIFont(descriptor: descriptor, size: pointSize)
    }
}

/// The « Imprimer » button of a place or a container; sensitive items are printed only when shown (H4).
struct PrintInventoryButton: View {
    let load: () async -> Inventory?
    @State private var loading = false

    var body: some View {
        Button {
            loading = true
            Task {
                if let inventory = await load() {
                    InventoryPrint.present(inventory)
                }
                loading = false
            }
        } label: {
            Label("print_action", systemImage: "printer")
        }
        .disabled(loading)
    }
}
