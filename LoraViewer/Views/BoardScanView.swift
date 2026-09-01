import SwiftUI
import PhotosUI

/// Lets the user pick a photo of the club's whiteboard roster, shows the
/// names OCR found on it as a copyable reference list, and saves whatever
/// the user types (or pastes) into each numbered slot as nicknames.
///
/// This deliberately does not try to auto-match a recognized name to a board
/// position — a tilted photo throws off simple top-to-bottom ordering enough
/// that an automatic guess was often wrong, and a confidently-wrong prefill
/// is worse than no prefill. Matching name to number is quick for a person
/// who knows the roster and unreliable for OCR position math, so that step
/// is left to the user.
struct BoardScanView: View {
    @ObservedObject var viewModel: GliderTrackerViewModel
    @EnvironmentObject private var nicknameStore: NicknameStore
    @Environment(\.dismiss) private var dismiss

    @State private var photoItem: PhotosPickerItem?
    @State private var recognizedCandidates: [String] = []
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
                    Text("写真から名前らしき文字を読み取り、下に参考として一覧表示します。番号との対応づけは自動では行っていないので、一覧の文字を長押ししてコピーし、該当する番号の欄に貼り付けてください。")
                }

                if !recognizedCandidates.isEmpty {
                    Section("読み取った文字(参考)") {
                        ForEach(recognizedCandidates, id: \.self) { text in
                            Text(text)
                                .textSelection(.enabled)
                        }
                    }
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
            recognizedCandidates = try await Task.detached(priority: .userInitiated) {
                try BoardOCR.recognizeNames(in: image)
            }.value
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
