import PhotosUI
import Shared
import SwiftUI

/// Two buttons: pick from the library (system picker, no permission) or take a photo (camera, asked at first use).
struct AddPhotoButtons: View {
    let enabled: Bool
    let onPicked: (Data) -> Void

    @State private var selection: PhotosPickerItem?
    @State private var cameraShown = false

    var body: some View {
        HStack {
            PhotosPicker(selection: $selection, matching: .images) {
                Text("photo_pick")
            }
            .disabled(!enabled)
            .onChange(of: selection) { item in
                guard let item else { return }
                Task {
                    if let data = try? await item.loadTransferable(type: Data.self) {
                        onPicked(data)
                    }
                    selection = nil
                }
            }
            if UIImagePickerController.isSourceTypeAvailable(.camera) {
                Button("photo_take") { cameraShown = true }
                    .disabled(!enabled)
            }
        }
        // Inside a list row, each button must take its own taps.
        .buttonStyle(.borderless)
        .sheet(isPresented: $cameraShown) {
            CameraPicker { data in
                cameraShown = false
                if let data {
                    onPicked(data)
                }
            }
            .ignoresSafeArea()
        }
    }
}

/// The system camera UI; the JPEG goes straight to the core, nothing is written to the photo library.
struct CameraPicker: UIViewControllerRepresentable {
    let onFinish: (Data?) -> Void

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(onFinish: onFinish)
    }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let onFinish: (Data?) -> Void

        init(onFinish: @escaping (Data?) -> Void) {
            self.onFinish = onFinish
        }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
        ) {
            let image = info[.originalImage] as? UIImage
            onFinish(image?.jpegData(compressionQuality: 0.9))
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            onFinish(nil)
        }
    }
}

/// Thumbnails of the attached photos, each with a remove action.
struct PhotoStrip: View {
    let photos: [Photo]
    let enabled: Bool
    let pathOf: (String) -> String
    let onDetach: (String) -> Void

    var body: some View {
        if !photos.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(photos, id: \.id) { photo in
                        VStack {
                            Thumbnail(path: pathOf(photo.thumbName))
                                .frame(width: 96, height: 96)
                                .clipShape(RoundedRectangle(cornerRadius: 8))
                            Button("photo_detach") { onDetach(photo.id) }
                                .buttonStyle(.borderless)
                                .disabled(!enabled)
                        }
                    }
                }
            }
        }
    }
}

/// An image from the private container (`UIImage(contentsOfFile:)`), a placeholder when it is gone.
struct Thumbnail: View {
    let path: String

    var body: some View {
        if let image = UIImage(contentsOfFile: path) {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .accessibilityLabel("photo_description")
        } else {
            Color.secondary.opacity(0.2)
        }
    }
}
