import Shared
import SwiftUI

/// B8: a photo for the next sentence. Without one, the two photo buttons; with one, its preview, the names the model
/// proposes (a tap starts the sentence) or why there are none, and a button to drop it.
struct CapturePhotoRow: View {
    @ObservedObject var viewModel: CaptureViewModel
    /// A name was tapped: the field takes the focus so the user completes the sentence.
    var onNameUsed: () -> Void = {}

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let photo = viewModel.photo {
                HStack(alignment: .center, spacing: 12) {
                    Image(uiImage: photo.preview)
                        .resizable()
                        .scaledToFill()
                        .frame(width: 72, height: 72)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                        .accessibilityLabel("photo_description")
                    VStack(alignment: .leading, spacing: 4) {
                        names(photo)
                        Text("capture_photo_hint").font(.footnote).foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 0)
                    Button {
                        viewModel.removePhoto()
                    } label: {
                        Image(systemName: "xmark.circle.fill").frame(minWidth: 44, minHeight: 44)
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel("capture_photo_remove")
                }
            } else {
                AddPhotoButtons(enabled: !viewModel.busy && !viewModel.listening) { viewModel.addPhoto($0) }
            }
            if let notice = viewModel.photoNotice {
                Text(noticeKey(notice))
                    .font(.callout)
                    .foregroundStyle(.red)
                    .accessibilityAddTraits(.updatesFrequently)
            }
        }
    }

    @ViewBuilder
    private func names(_ photo: CapturePhoto) -> some View {
        if photo.naming {
            HStack(spacing: 8) {
                ProgressView()
                Text("capture_photo_naming").font(.callout)
            }
        } else if !photo.names.isEmpty {
            // On one line when they fit, one under the other otherwise (large text): the third name stays visible.
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) { nameButtons(photo) }
                VStack(alignment: .leading, spacing: 8) { nameButtons(photo) }
            }
        } else {
            Text(noNamesKey(viewModel.photoModel)).font(.callout)
        }
    }

    private func nameButtons(_ photo: CapturePhoto) -> some View {
        ForEach(photo.names, id: \.key) { suggestion in
            Button(suggestion.name) {
                viewModel.useName(suggestion.name)
                onNameUsed()
            }
            .buttonStyle(.bordered)
        }
    }

    /// Why no name is proposed: the build, the phone, the user's setting, or nothing close enough.
    private func noNamesKey(_ model: PhotoModelState?) -> LocalizedStringKey {
        guard let model, model.bundled else { return "capture_photo_no_names_build" }
        if !model.deviceCapable { return "capture_photo_no_names_memory" }
        if model.forceTextOnly { return "capture_photo_no_names_off" }
        return "capture_photo_no_names_found"
    }

    private func noticeKey(_ notice: PhotoNotice) -> LocalizedStringKey {
        switch notice {
        case .tooLarge: "photo_too_large"
        case .unsupported: "photo_unsupported"
        case .severalItems: "capture_photo_several"
        }
    }
}
