import SwiftUI
import WidgetKit

/// Capture widgets (B5): home screen and lock screen. They only open the capture — no object, place or count is
/// ever read or shown (H4), so the extension does not link the core and touches no data.
@main
struct CaptureWidgetBundle: WidgetBundle {
    var body: some Widget {
        CaptureWidget()
    }
}

struct CaptureWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "CaptureWidget", provider: CaptureProvider()) { _ in
            CaptureWidgetView()
                .widgetBackground()
        }
        .configurationDisplayName("widget_name")
        .description("widget_description")
        .supportedFamilies([.systemSmall, .systemMedium, .accessoryCircular, .accessoryRectangular])
    }
}

struct CaptureEntry: TimelineEntry {
    let date: Date
}

/// One entry, never refreshed (N6): the buttons do not change.
struct CaptureProvider: TimelineProvider {
    func placeholder(in _: Context) -> CaptureEntry {
        CaptureEntry(date: Date())
    }

    func getSnapshot(in _: Context, completion: @escaping (CaptureEntry) -> Void) {
        completion(CaptureEntry(date: Date()))
    }

    func getTimeline(in _: Context, completion: @escaping (Timeline<CaptureEntry>) -> Void) {
        completion(Timeline(entries: [CaptureEntry(date: Date())], policy: .never))
    }
}

/// `trouvetout-proto://capture?mode=…`, the scheme the app declares (ADR-010: neutral prototype name).
enum CaptureLink {
    static let voice = url("voice")
    static let text = url("text")

    private static func url(_ mode: String) -> URL? {
        var components = URLComponents()
        components.scheme = "trouvetout-proto"
        components.host = "capture"
        components.queryItems = [URLQueryItem(name: "mode", value: mode)]
        return components.url
    }
}

struct CaptureWidgetView: View {
    @Environment(\.widgetFamily) private var family

    var body: some View {
        switch family {
        case .accessoryCircular:
            ZStack {
                AccessoryWidgetBackground()
                Image(systemName: "mic.fill")
                    .font(.title2)
            }
            .accessibilityLabel("widget_voice")
            .widgetURL(CaptureLink.voice)
        case .accessoryRectangular:
            HStack {
                Image(systemName: "mic.fill")
                Text("widget_short")
                    .font(.headline)
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel("widget_voice")
            .widgetURL(CaptureLink.voice)
        case .systemMedium:
            HStack(spacing: 12) {
                if let voice = CaptureLink.voice {
                    Link(destination: voice) { tile(symbol: "mic.fill", title: "widget_voice_button") }
                }
                if let text = CaptureLink.text {
                    Link(destination: text) { tile(symbol: "keyboard", title: "widget_text_button") }
                }
            }
        default:
            tile(symbol: "mic.fill", title: "widget_voice_button")
                .widgetURL(CaptureLink.voice)
        }
    }

    private func tile(symbol: String, title: LocalizedStringKey) -> some View {
        VStack(spacing: 8) {
            Image(systemName: symbol)
                .font(.largeTitle)
                .accessibilityHidden(true)
            Text(title)
                .font(.headline)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .combine)
    }
}

private extension View {
    /// iOS 17 asks widgets for a container background; iOS 16 draws its own.
    @ViewBuilder
    func widgetBackground() -> some View {
        if #available(iOS 17.0, *) {
            containerBackground(for: .widget) { Color(.systemBackground) }
        } else {
            self
        }
    }
}
