import Foundation
import FoundationModels
import Darwin

@main
struct FeatherWandAFM {
    static func main() async {
        guard let command = CommandLine.arguments.dropFirst().first else {
            fputs("Usage: feather-wand-afm status|respond\n", stderr)
            exit(EXIT_FAILURE)
        }

        switch command {
        case "status":
            writeLine(statusObject())
        case "respond":
            await respond()
        default:
            fputs("Usage: feather-wand-afm status|respond\n", stderr)
            exit(EXIT_FAILURE)
        }
    }

    private static func availabilityReason() -> String? {
        switch SystemLanguageModel.default.availability {
        case .available:
            return nil
        case .unavailable(let reason):
            switch reason {
            case .deviceNotEligible:
                return "deviceNotEligible"
            case .appleIntelligenceNotEnabled:
                return "appleIntelligenceNotEnabled"
            case .modelNotReady:
                return "modelNotReady"
            @unknown default:
                return "unknown"
            }
        }
    }

    private static func statusObject() -> [String: Any] {
        if let reason = availabilityReason() {
            return ["available": false, "reason": reason]
        }
        return ["available": true]
    }

    private static func respond() async {
        guard let input = try? JSONSerialization.jsonObject(with: FileHandle.standardInput.readDataToEndOfFile()),
              let request = input as? [String: Any],
              let instructions = request["instructions"] as? String,
              let prompt = request["prompt"] as? String else {
            fail("The request must include string instructions and prompt fields.", code: "other")
        }

        if let reason = availabilityReason() {
            fail("Apple on-device model is unavailable (\(reason)). It requires macOS 26 or later, Apple silicon, and Apple Intelligence enabled in System Settings.", code: "unavailable")
        }

        let temperature = (request["temperature"] as? NSNumber)?.doubleValue
        let maxTokens = (request["maxTokens"] as? NSNumber)?.intValue
        let options = GenerationOptions(temperature: temperature, maximumResponseTokens: maxTokens)
        let session = LanguageModelSession(instructions: instructions)
        var previous = ""

        do {
            for try await snapshot in session.streamResponse(to: prompt, options: options) {
                let content = snapshot.content
                guard content.hasPrefix(previous) else {
                    continue
                }
                let delta = String(content.dropFirst(previous.count))
                previous = content
                if !delta.isEmpty {
                    writeLine(["delta": delta])
                }
            }
            writeLine(["done": true])
        } catch let error as LanguageModelSession.GenerationError {
            let (code, message) = map(error)
            fail(message, code: code)
        } catch {
            fail(error.localizedDescription, code: "other")
        }
    }

    private static func map(_ error: LanguageModelSession.GenerationError) -> (String, String) {
        switch error {
        case .exceededContextWindowSize:
            return ("context_exceeded", "The conversation exceeded the on-device model's context window.")
        case .guardrailViolation:
            return ("guardrail", "Apple's safety filter blocked this response.")
        case .unsupportedLanguageOrLocale:
            return ("unsupported_language", "The on-device model does not support this language or locale.")
        case .assetsUnavailable:
            return ("unavailable", "Apple Intelligence model assets are unavailable. Check Apple Intelligence in System Settings.")
        case .rateLimited:
            return ("rate_limited", "Apple's on-device model is temporarily rate limited. Try again shortly.")
        default:
            return ("other", error.localizedDescription)
        }
    }

    private static func fail(_ message: String, code: String) -> Never {
        writeLine(["error": message, "code": code])
        exit(EXIT_FAILURE)
    }

    private static func writeLine(_ object: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: object, options: [.fragmentsAllowed, .sortedKeys]) else {
            fputs("{\"error\":\"Could not encode helper response.\",\"code\":\"other\"}\n", stdout)
            fflush(stdout)
            return
        }
        FileHandle.standardOutput.write(data)
        FileHandle.standardOutput.write(Data([0x0A]))
        fflush(stdout)
    }
}
