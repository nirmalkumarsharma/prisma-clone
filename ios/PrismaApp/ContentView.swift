import PhotosUI
import PrismaKit
import SwiftUI

enum Theme {
    static let bg = Color(red: 0x0E / 255, green: 0x0D / 255, blue: 0x12 / 255)
    static let panel = Color(red: 0x1A / 255, green: 0x18 / 255, blue: 0x20 / 255)
    static let line = Color(red: 0x2C / 255, green: 0x29 / 255, blue: 0x34 / 255)
    static let text = Color(red: 0xF1 / 255, green: 0xEE / 255, blue: 0xE8 / 255)
    static let muted = Color(red: 0x9A / 255, green: 0x95 / 255, blue: 0xA6 / 255)
    static let accent = Color(red: 0xF5 / 255, green: 0xC8 / 255, blue: 0x5A / 255)
    static let accentInk = Color(red: 0x1A / 255, green: 0x14 / 255, blue: 0x08 / 255)
}

struct ContentView: View {
    @StateObject private var model = EditorModel()
    @State private var pickerItem: PhotosPickerItem?
    @State private var comparing = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                header
                stage
                intensityRow
                actionRow
                filterStrip
                promptBuilder
            }
            .padding(16)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(Theme.bg.ignoresSafeArea())
        .foregroundStyle(Theme.text)
        .tint(Theme.accent)
        .preferredColorScheme(.dark)
        .task(id: pickerItem) {
            guard let item = pickerItem else { return }
            if let data = try? await item.loadTransferable(type: Data.self) {
                await model.load(PhotosPickerItemData(data: data))
            } else {
                model.message = "Couldn't open that image"
            }
        }
        .overlay(alignment: .bottom) { toast }
    }

    private var header: some View {
        HStack {
            (Text("Prisma").bold() + Text(" clone").foregroundColor(Theme.accent))
                .font(.title2)
            Spacer()
            Button("Sample") { model.useSample() }
                .buttonStyle(PillButtonStyle(filled: false))
            PhotosPicker(selection: $pickerItem, matching: .images) {
                Text("Photo")
            }
            .buttonStyle(PillButtonStyle(filled: true))
        }
    }

    /// The main image. Press and hold to see the original.
    private var stage: some View {
        let shown = comparing ? model.original : (model.styled ?? model.original)
        let ratio = model.original.map { $0.size.width / $0.size.height } ?? 4 / 3
        return ZStack {
            Theme.panel
            if let shown {
                Image(uiImage: shown).resizable().scaledToFit()
            }
            if model.busy {
                Theme.bg.opacity(0.5)
                VStack(spacing: 8) {
                    ProgressView().tint(Theme.accent)
                    Text("Painting…").font(.subheadline.weight(.semibold))
                }
            }
            if comparing {
                Text("Original")
                    .font(.caption)
                    .padding(.horizontal, 8).padding(.vertical, 2)
                    .background(Theme.bg.opacity(0.7), in: RoundedRectangle(cornerRadius: 6))
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                    .padding(10)
            }
        }
        .aspectRatio(ratio, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 14))
        // A small maximumDistance lets the surrounding ScrollView still scroll.
        .onLongPressGesture(minimumDuration: .infinity, maximumDistance: 10, perform: {}, onPressingChanged: { comparing = $0 })
        .accessibilityElement()
        .accessibilityLabel("Styled image")
        .accessibilityHint("Press and hold to compare with the original")
    }

    private var intensityRow: some View {
        HStack {
            Text("Intensity \(Int((model.intensity * 100).rounded()))%")
                .monospacedDigit()
                .frame(width: 120, alignment: .leading)
            Slider(value: $model.intensity, in: 0.4...1) { editing in
                if !editing { model.commitIntensity() }
            }
        }
    }

    private var actionRow: some View {
        HStack(spacing: 8) {
            Button("Re-roll") { model.reroll() }
                .buttonStyle(PillButtonStyle(filled: false, expand: true))
            if let styled = model.styled {
                ShareLink(
                    item: Image(uiImage: styled),
                    preview: SharePreview("Prisma \(model.filterId) artwork", image: Image(uiImage: styled))
                ) { Text("Share") }
                .buttonStyle(PillButtonStyle(filled: false, expand: true))
            } else {
                Button("Share") {}.disabled(true)
                    .buttonStyle(PillButtonStyle(filled: false, expand: true))
            }
            Button("Save") { Task { await model.saveToPhotos() } }
                .disabled(model.styled == nil)
                .buttonStyle(PillButtonStyle(filled: true, expand: true))
        }
    }

    private var filterStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ForEach(Prisma.filters) { f in
                    Button { model.filterId = f.id } label: {
                        FilterCard(filter: f, thumbnail: model.thumbnails[f.id], selected: f.id == model.filterId)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(f.name) filter")
                    .accessibilityAddTraits(f.id == model.filterId ? .isSelected : [])
                }
            }
        }
    }

    private var promptBuilder: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Prompt builder").font(.headline)
            Text("Describe a subject to get a text-to-image prompt in the selected style.")
                .font(.footnote).foregroundStyle(Theme.muted)
            TextField("e.g. a lighthouse on a cliff during a storm", text: $model.subject)
                .textFieldStyle(.plain)
                .padding(10)
                .background(Theme.bg, in: RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(Theme.line))
                .submitLabel(.done)
            Text(model.prompt)
                .font(.callout)
                .textSelection(.enabled)
            Button("Copy prompt") { model.copyPrompt() }
                .buttonStyle(PillButtonStyle(filled: false))
        }
        .padding(14)
        .background(Theme.panel, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.line))
    }

    @ViewBuilder private var toast: some View {
        if let message = model.message {
            Text(message)
                .font(.subheadline.weight(.semibold))
                .padding(.horizontal, 16).padding(.vertical, 10)
                .background(Theme.panel, in: Capsule())
                .overlay(Capsule().stroke(Theme.line))
                .padding(.bottom, 24)
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .task(id: message) {
                    try? await Task.sleep(nanoseconds: 2_000_000_000)
                    withAnimation { model.message = nil }
                }
        }
    }
}

struct FilterCard: View {
    let filter: StyleFilter
    let thumbnail: UIImage?
    let selected: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            ZStack {
                Theme.line
                if let thumbnail {
                    Image(uiImage: thumbnail).resizable().scaledToFill()
                } else {
                    ProgressView()
                }
            }
            .frame(width: 124, height: 124)
            .clipped()
            Text(filter.name).font(.subheadline.weight(.semibold)).padding(.horizontal, 8).padding(.top, 4)
            Text(filter.tagline)
                .font(.caption2).foregroundStyle(Theme.muted)
                .lineLimit(2, reservesSpace: true)
                .padding(.horizontal, 8).padding(.bottom, 8)
        }
        .frame(width: 124)
        .background(Theme.panel)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(selected ? Theme.accent : .clear, lineWidth: 2))
    }
}

struct PillButtonStyle: ButtonStyle {
    var filled: Bool
    var expand = false
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .padding(.horizontal, 14).padding(.vertical, 8)
            .frame(maxWidth: expand ? .infinity : nil)
            .foregroundStyle(filled ? Theme.accentInk : Theme.text)
            .background(filled ? Theme.accent : .clear, in: Capsule())
            .overlay(Capsule().stroke(filled ? Theme.accent : Theme.line))
            .opacity(isEnabled ? (configuration.isPressed ? 0.75 : 1) : 0.4)
    }
}
