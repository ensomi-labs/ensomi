import AVFoundation
#if os(macOS)
import CoreAudio
#endif

/// The device the song plays to: on macOS the default output device, which `AVAudioEngine` plays to, and on iOS the
/// current route's first output. `id` is the device's UID, which stays the same when it connects again.
public struct Mania4KAudioOutputDevice: Equatable, Sendable {
    public let id: String
    public let name: String

    public init(id: String, name: String) {
        self.id = id
        self.name = name
    }

    #if os(macOS)
    public static func current() -> Mania4KAudioOutputDevice? {
        var deviceID = AudioObjectID(kAudioObjectUnknown)
        var size = UInt32(MemoryLayout<AudioObjectID>.size)
        var address = defaultOutputDeviceAddress
        guard AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size, &deviceID) == noErr,
              deviceID != kAudioObjectUnknown,
              let uid = stringProperty(kAudioDevicePropertyDeviceUID, of: deviceID),
              let name = stringProperty(kAudioObjectPropertyName, of: deviceID)
        else {
            return nil
        }

        return Mania4KAudioOutputDevice(id: uid, name: name)
    }

    /// Calls `onChange` on the main thread whenever the default output device changes, for the life of the app.
    public static func observeChanges(_ onChange: @escaping @MainActor @Sendable () -> Void) {
        var address = defaultOutputDeviceAddress
        AudioObjectAddPropertyListenerBlock(AudioObjectID(kAudioObjectSystemObject), &address, .main) { _, _ in
            MainActor.assumeIsolated(onChange)
        }
    }

    private static var defaultOutputDeviceAddress: AudioObjectPropertyAddress {
        AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDefaultOutputDevice,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
    }

    private static func stringProperty(_ selector: AudioObjectPropertySelector, of object: AudioObjectID) -> String? {
        var address = AudioObjectPropertyAddress(
            mSelector: selector,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        var value: Unmanaged<CFString>?
        var size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        let status = withUnsafeMutablePointer(to: &value) {
            AudioObjectGetPropertyData(object, &address, 0, nil, &size, $0)
        }
        guard status == noErr, let value else {
            return nil
        }

        return value.takeRetainedValue() as String
    }
    #else
    public static func current() -> Mania4KAudioOutputDevice? {
        AVAudioSession.sharedInstance().currentRoute.outputs.first.map {
            Mania4KAudioOutputDevice(id: $0.uid, name: $0.portName)
        }
    }

    /// Calls `onChange` on the main thread whenever the audio route changes, for the life of the app.
    public static func observeChanges(_ onChange: @escaping @MainActor @Sendable () -> Void) {
        _ = NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: nil,
            queue: .main
        ) { _ in
            MainActor.assumeIsolated(onChange)
        }
    }
    #endif
}
