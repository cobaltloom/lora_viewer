import SwiftUI
import PhotosUI

/// Lets the user photograph (or pick a photo of) the club's whiteboard
/// roster, auto-fills a name for each board position via OCR, and saves the
/// confirmed/edited names as nicknames after the user reviews them.
struct BoardScanView: View {
    @ObservedObject var viewModel: GliderTrackerViewModel
    @EnvironmentObject private var nicknameStore: NicknameStore
    @Environment(\.dismiss) private var dismiss

    @State private var photoItem: PhotosPickerItem?
    @State private var draftNames: [Int: String] = [:]
    @State private var isRecognizing = false
    @State private var errorMessage: String?

    private let indices = Array(1...15)

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    PhotosPicker("ボードの写真を選ぶ", selection: $photoItem, matching: .images)
                    if isRecognizing {
                        HStack {
                            ProgressView()
                            Text("文字を読み取っています…")
                        }
                    }
                } footer: {
                    Text("ホワイトボードの写真を選ぶと、番号ごとの名前を自動で読み取って下の欄に入力します。手書き文字は読み間違えることがあるので、保存前に内容を確認・修正してください。")
                }

                Section("番号ごとの名前") {
                    ForEach(indices, id: \.self) { index in
                        HStack {
                            Text("\(index).")
                                .frame(width: 32, alignment: .leading)
                                .foregroundStyle(.secondary)
                            TextField("名前", text: bindingForIndex(index))
                        }
                    }
                }
            }
            .navigationTitle("名簿から一括登録")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("キャンセル") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") { applyAndDismiss() }
                }
            }
            .onChange(of: photoItem) { _, newItem in
                Task { await loadAndRecognize(newItem) }
            }
            .alert("エラー", isPresented: Binding(
                get: { errorMessage != nil },
                set: { if !$0 { errorMessage = nil } }
            )) {
                Button("OK") { errorMessage = nil }
            } message: {
                Text(errorMessage ?? "")
            }
        }
    }

    private func bindingForIndex(_ index: Int) -> Binding<String> {
        Binding(
            get: { draftNames[index] ?? "" },
            set: { draftNames[index] = $0 }
        )
    }

    private func loadAndRecognize(_ item: PhotosPickerItem?) async {
        guard let item else { return }
        isRecognizing = true
        defer { isRecognizing = false }
        do {
            guard let data = try await item.loadTransferable(type: Data.self),
                  let image = UIImage(data: data) else {
                errorMessage = "写真を読み込めませんでした。"
                return
            }
            let recognized = try await Task.detached(priority: .userInitiated) {
                try BoardOCR.recognizeNames(in: image)
            }.value
            for (index, name) in recognized {
                draftNames[index] = name
            }
        } catch {
            errorMessage = "文字の読み取りに失敗しました: \(error.localizedDescription)"
        }
    }

    private func applyAndDismiss() {
        guard let imeiMaster = viewModel.config?.imeiMaster else {
            errorMessage = "メンバー情報がまだ読み込まれていません。少し待ってから再度お試しください。"
            return
        }
        for (index, name) in draftNames {
            let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty, let imei = imeiMaster[String(index)] else { continue }
            nicknameStore.setNickname(trimmed, forIMEI: imei)
        }
        dismiss()
    }
}
