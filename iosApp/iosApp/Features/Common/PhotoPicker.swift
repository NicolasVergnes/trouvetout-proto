import AVFoundation
import PhotosUI
import Shared
import SwiftUI

/// Two buttons: pick from the library (system picker, no permission) or take a photo (camera, asked at first use
/// after an explanation, H9; refused: the way to the Settings). Every photo button of the app is this one.
struct AddPhotoButtons: View {
    let enabled: Bool
    let onPicked: (Data) -> Void

    @State private var selection: PhotosPickerItem?
    @State private var cameraShown = false
    @State private var cameraAlert: CameraAlert?

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
                Button("photo_take") { takePhoto() }
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
        .alert(cameraAlertTitle, isPresented: cameraAlertShown, presenting: cameraAlert) { alert in
            switch alert {
            case .rationale:
                Button("action_continue") { askCamera() }
            case .denied:
                Button("action_phone_settings") {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                }
            }
            Button("action_cancel", role: .cancel) {}
        } message: { alert in
            Text(LocalizedStringKey(alert == .rationale ? "camera_rationale_text" : "camera_denied_text"))
        }
    }

    private var cameraAlertShown: Binding<Bool> {
        Binding(
            get: { cameraAlert != nil },
            set: { shown in
                if !shown {
                    cameraAlert = nil
                }
            }
        )
    }

    private var cameraAlertTitle: LocalizedStringKey {
        cameraAlert == .denied ? "camera_denied_title" : "camera_rationale_title"
    }

    private func takePhoto() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: cameraShown = true
        case .notDetermined: cameraAlert = .rationale
        default: cameraAlert = .denied
        }
    }

    private func askCamera() {
        Task { @MainActor in
            if await AVCaptureDevice.requestAccess(for: .video) {
                cameraShown = true
            } else {
                cameraAlert = .denied
            }
        }
    }
}

/// The two camera messages (H9): the explanation before the system prompt, the way to the Settings after a refusal.
enum CameraAlert {
    case rationale
    case denied
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
    let photos: [Photo_]
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
