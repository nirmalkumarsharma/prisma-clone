import Photos
import PrismaKit
import SwiftUI

@MainActor
final class EditorModel: ObservableObject {
    /// Longest side we filter at; keeps each filter to a few seconds on a phone.
    static let maxSide = 1200

    @Published private(set) var original: UIImage?
    @Published private(set) var styled: UIImage?
    @Published private(set) var thumbnails: [String: UIImage] = [:]
    @Published private(set) var busy = false
    @Published private(set) var seed = 1
    @Published var intensity = 1.0
    @Published var subject = ""
    @Published var message: String?
    @Published var filterId = Prisma.filters[0].id {
        didSet { if filterId != oldValue { render() } }
    }

    var prompt: String { (try? Prisma.buildPrompt(filterId, subject: subject)) ?? "" }

    private var source: PixelImage?
    private var renderTask: Task<Void, Never>?
    private var thumbTask: Task<Void, Never>?

    init() {
        useSample()
    }

    func useSample() {
        setSource(SampleScene.render(width: 800, height: 600))
    }

    func load(_ item: PhotosPickerItemData) async {
        busy = true
        let maxSide = Self.maxSide
        let pixels = await Task.detached(priority: .userInitiated) { () -> PixelImage? in
            guard let image = UIImage(data: item.data) else { return nil }
            return PixelBridge.pixels(from: image, maxSide: maxSide)
        }.value
        guard let pixels else {
            busy = false
            message = "Couldn't open that image"
            return
        }
        setSource(pixels)
    }

    func commitIntensity() { render() }

    func reroll() {
        seed = seed % 9973 + 1
        render()
        renderThumbnails()
    }

    private func setSource(_ pixels: PixelImage) {
        source = pixels
        original = PixelBridge.image(from: pixels)
        styled = nil
        thumbnails = [:]
        render()
        renderThumbnails()
    }

    /// A newer request cancels the stale one; its result is dropped before reaching the UI.
    private func render() {
        guard let src = source else { return }
        renderTask?.cancel()
        busy = true
        let id = filterId, t = intensity, s = seed
        renderTask = Task {
            let out = await Task.detached(priority: .userInitiated) {
                try? Prisma.apply(id, to: src, intensity: t, seed: s)
            }.value
            guard !Task.isCancelled, let out else { return }
            styled = PixelBridge.image(from: out)
            busy = false
        }
    }

    private func renderThumbnails() {
        guard let src = source else { return }
        thumbTask?.cancel()
        let s = seed
        thumbTask = Task {
            let small = await Task.detached(priority: .utility) { PixelBridge.squareThumbnail(src, side: 160) }.value
            for f in Prisma.filters {
                let out = await Task.detached(priority: .utility) { try? Prisma.apply(f.id, to: small, seed: s) }.value
                guard !Task.isCancelled, let out else { return }
                thumbnails[f.id] = PixelBridge.image(from: out)
            }
        }
    }

    func saveToPhotos() async {
        guard let image = styled else { return }
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else {
            message = "Allow Photos access in Settings to save"
            return
        }
        do {
            try await PHPhotoLibrary.shared().performChanges {
                _ = PHAssetChangeRequest.creationRequestForAsset(from: image)
            }
            message = "Saved to Photos"
        } catch {
            message = "Couldn't save: \(error.localizedDescription)"
        }
    }

    func copyPrompt() {
        UIPasteboard.general.string = prompt
        message = "Prompt copied"
    }
}

/// Raw bytes of a picked photo (decoded off the main thread).
struct PhotosPickerItemData: Sendable {
    let data: Data
}
