import AVFoundation
import Foundation

/// The frames at an MP3's start that osu!'s decoder keeps and AVFoundation trims: the lead that puts AVFoundation's
/// decode on the time charts are made and played on.
///
/// The release engine (ensomi-model's `audio_rows/decoder.py`) puts every song on osu!'s clock, BASS's, the one R2 was
/// fitted to. BASS trims an MP3's encoder delay and the 529-frame Layer III decoder delay only when a LAME extension
/// gives a non-zero encoder delay, and trims nothing otherwise. AVFoundation trims the decoder delay from every MP3,
/// and an encoder delay a LAME extension or an iTunes gapless comment gives. Without either, AVFoundation's first
/// frame is therefore 529 frames into the song. The header classes are the release engine's, found the same way.
public enum MP3Lead {
    /// The Layer III decoder delay, in frames at the file's own rate, for MPEG-1, 2 and 2.5 alike.
    public static let decoderDelayFrames = 529
    /// How far past an ID3v2 tag the first frame is looked for, as the release engine looks.
    static let scanBytes = 65_536

    /// What the first Layer III frame after an ID3v2 tag carries, in the release engine's classes.
    public enum Header: Equatable, Sendable {
        /// A Xing or Info header with a LAME extension: its encoder string and encoder delay field.
        case lame(encoder: String, delay: Int)
        /// A Xing or Info header without one.
        case xing
        /// A VBRI header.
        case vbri
        /// Neither header.
        case noHeader
        /// No Layer III frame followed by another in the bytes searched.
        case noSync
    }

    /// The frames AVFoundation trims that BASS keeps, for a file with `header`. VBRI files are left as AVFoundation
    /// decodes them: it starts them as files without a header, but what the release engine does has not been measured.
    public static func leadFrames(for header: Header) -> Int {
        switch header {
        case .lame(_, let delay): delay > 0 ? 0 : decoderDelayFrames
        case .xing, .noHeader: decoderDelayFrames
        case .vbri, .noSync: 0
        }
    }

    /// The lead of an MP3 from its bytes. AVFoundation also takes the priming of an iTunes gapless comment (`iTunSMPB`
    /// in the ID3v2 tag) as an encoder delay. The release engine's classes do not look at the comment and what its
    /// decoder does with one has not been measured, so a file with one is left as AVFoundation decodes it.
    public static func leadFrames(of data: Data) -> Int {
        hasITunesGaplessComment(data) ? 0 : leadFrames(for: header(of: data))
    }

    /// The lead of the file `file` reads, in its frames: 0 unless the file holds MPEG Layer III.
    public static func leadFrames(of file: AVAudioFile) -> AVAudioFramePosition {
        guard file.fileFormat.streamDescription.pointee.mFormatID == kAudioFormatMPEGLayer3,
              let bytes = try? Data(contentsOf: file.url, options: .mappedIfSafe)
        else {
            return 0
        }
        return AVAudioFramePosition(leadFrames(of: bytes))
    }

    /// The lead of the audio file at `url` in milliseconds, 0 when AVFoundation cannot read it.
    public static func leadMilliseconds(ofAudioAt url: URL) -> Double {
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }
        guard let file = try? AVAudioFile(forReading: url) else { return 0 }
        return Double(leadFrames(of: file)) / file.processingFormat.sampleRate * 1_000
    }

    /// The header class of an MP3 from its bytes: past one ID3v2 tag (and its footer), the first Layer III frame whose
    /// next frame also syncs, within `scanBytes`. A Xing or Info header sits after the frame's side information, whose
    /// size follows from the MPEG version and channel mode; a LAME extension follows its fields, and its encoder string
    /// starts with four printable characters.
    public static func header(of data: Data) -> Header {
        let b = [UInt8](data.dropFirst(id3v2Bytes(data)).prefix(scanBytes))
        for j in 0..<max(0, b.count - 4) {
            guard let first = frame(in: b, at: j), frame(in: b, at: j + first.length) != nil else { continue }
            if matches(b, at: j + 36, "VBRI") { return .vbri }
            let x = j + 4 + first.sideInfoBytes
            guard matches(b, at: x, "Xing") || matches(b, at: x, "Info") else { return .noHeader }
            // The flags say which of the frame count, byte count, table of contents and quality fields follow. A header
            // or extension cut short by the end of the bytes counts as one without an extension.
            guard x + 8 <= b.count else { return .xing }
            let flags = b[(x + 4)..<(x + 8)].reduce(0) { $0 << 8 | Int($1) }
            var p = x + 8
            for (flag, size) in [(1, 4), (2, 4), (4, 100), (8, 4)] where flags & flag != 0 {
                p += size
            }
            guard p + 23 <= b.count, b[p..<(p + 4)].allSatisfy({ (32..<127).contains($0) }) else { return .xing }
            let encoder = String(b[p..<(p + 9)].map { Character(Unicode.Scalar($0)) })
                .trimmingCharacters(in: CharacterSet(charactersIn: "\0 "))
            return .lame(encoder: encoder, delay: Int(b[p + 21]) << 4 | Int(b[p + 22]) >> 4)
        }
        return .noSync
    }

    /// Whether the ID3v2 tag at the start of `data` holds an `iTunSMPB` comment.
    static func hasITunesGaplessComment(_ data: Data) -> Bool {
        data.prefix(id3v2Bytes(data)).range(of: Data("iTunSMPB".utf8)) != nil
    }

    /// The bytes an ID3v2 tag at the start of `data` takes with its header and footer, or 0.
    private static func id3v2Bytes(_ data: Data) -> Int {
        let head = [UInt8](data.prefix(10))
        guard head.count == 10, head[0] == 0x49, head[1] == 0x44, head[2] == 0x33 else { return 0 }
        let size = head[6...9].reduce(0) { $0 << 7 | Int($1 & 0x7F) }
        return 10 + size + (head[5] & 0x10 != 0 ? 10 : 0)
    }

    private struct Frame {
        let length: Int
        let sideInfoBytes: Int
    }

    private static let bitratesV1 = [0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320]
    private static let bitratesV2 = [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160]
    /// By the header's version bits: 3 is MPEG-1, 2 MPEG-2 and 0 MPEG-2.5.
    private static let sampleRates = [3: [44_100, 48_000, 32_000], 2: [22_050, 24_000, 16_000], 0: [11_025, 12_000, 8_000]]

    /// The Layer III frame whose header starts at `j`, or nil.
    private static func frame(in b: [UInt8], at j: Int) -> Frame? {
        guard j + 4 <= b.count, b[j] == 0xFF, b[j + 1] & 0xE0 == 0xE0 else { return nil }
        let version = Int(b[j + 1] >> 3) & 3, layer = Int(b[j + 1] >> 1) & 3
        let bitrateIndex = Int(b[j + 2] >> 4), rateIndex = Int(b[j + 2] >> 2) & 3
        let padding = Int(b[j + 2] >> 1) & 1, mono = b[j + 3] >> 6 == 3
        guard version != 1, layer == 1, bitrateIndex != 0, bitrateIndex != 15, rateIndex != 3,
              let sampleRate = sampleRates[version]?[rateIndex]
        else {
            return nil
        }
        let mpeg1 = version == 3
        let bitrate = (mpeg1 ? bitratesV1 : bitratesV2)[bitrateIndex] * 1_000
        return Frame(
            length: (mpeg1 ? 144 : 72) * bitrate / sampleRate + padding,
            sideInfoBytes: mpeg1 ? (mono ? 17 : 32) : (mono ? 9 : 17)
        )
    }

    private static func matches(_ b: [UInt8], at offset: Int, _ tag: String) -> Bool {
        let tag = Array(tag.utf8)
        return offset + tag.count <= b.count && Array(b[offset..<(offset + tag.count)]) == tag
    }
}
