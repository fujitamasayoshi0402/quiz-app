// macOS のログインキーチェーン。秘密は stdin / stdout のパイプだけで渡し、引数やログに出さない。
import Foundation
import Security

struct Request: Decodable {
  let operation: String
  let profile: String
  let value: String?
}

func finish(_ status: OSStatus, _ value: String? = nil) -> Never {
  var response: [String: Any] = ["status": Int(status)]
  if let value = value { response["value"] = value }
  let data = try! JSONSerialization.data(withJSONObject: response)
  FileHandle.standardOutput.write(data)
  exit(0)
}

do {
  let request = try JSONDecoder().decode(Request.self, from: FileHandle.standardInput.readDataToEndOfFile())
  guard request.profile.range(of: "^[a-z0-9][a-z0-9-]{0,63}$", options: .regularExpression) != nil else {
    finish(errSecParam)
  }
  // プロファイルをまたいで検索しない。iCloud 同期や全アプリへの許可は設定しない。
  let query: [String: Any] = [
    kSecClass as String: kSecClassGenericPassword,
    kSecAttrService as String: "quiz-app.content-auth.v1",
    kSecAttrAccount as String: request.profile,
  ]
  switch request.operation {
  case "read":
    var search = query
    search[kSecReturnData as String] = true
    search[kSecMatchLimit as String] = kSecMatchLimitOne
    var found: CFTypeRef?
    let status = SecItemCopyMatching(search as CFDictionary, &found)
    guard status == errSecSuccess, let data = found as? Data,
      let value = String(data: data, encoding: .utf8) else { finish(status) }
    finish(status, value)
  case "write":
    guard let value = request.value else { finish(errSecParam) }
    let data = Data(value.utf8)
    let status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
    if status == errSecItemNotFound {
      var item = query
      item[kSecValueData as String] = data
      finish(SecItemAdd(item as CFDictionary, nil))
    }
    finish(status)
  case "delete":
    finish(SecItemDelete(query as CFDictionary))
  default:
    finish(errSecParam)
  }
} catch {
  // デコードエラーの本文には入力が含まれうるため出さない。
  finish(errSecParam)
}
