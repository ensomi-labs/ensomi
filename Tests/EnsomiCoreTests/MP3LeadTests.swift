import AVFoundation
import XCTest
@testable import EnsomiCore

final class MP3LeadTests: XCTestCase {
    /// The release engine's header classes, and the lead of each: an iTunes gapless comment leaves a file without one.
    func testHeaderClassesAreTheReleaseEnginesAndGiveTheirLeads() {
        let cases: [(MP3Fixture.Variant, MP3Lead.Header, Int)] = [
            (.untagged, .noHeader, 529),
            (.xing, .xing, 529),
            (.lameDelay, .lame(encoder: "LAME3.100", delay: 576), 0),
            (.lameZero, .lame(encoder: "Lavf58.29", delay: 0), 529),
            (.vbri, .vbri, 0),
            (.id3Untagged, .noHeader, 529),
            (.id3FooterLameDelay, .lame(encoder: "LAME3.100", delay: 576), 0),
            (.iTunSMPB, .noHeader, 0)
        ]
        for layout in [MP3Fixture.Layout.mpeg1, .mpeg2] {
            for (variant, header, lead) in cases {
                let bytes = Data(MP3Fixture.bytes(layout, variant))
                XCTAssertEqual(MP3Lead.header(of: bytes), header, "\(layout.name), \(variant)")
                XCTAssertEqual(MP3Lead.leadFrames(of: bytes), lead, "\(layout.name), \(variant)")
            }
        }
    }

    /// The Xing header follows 32 bytes of side information in a stereo MPEG-1 frame, and a sync whose next frame does
    /// not sync is passed over. Bytes with no two frames in a row are no MP3 the lead knows.
    func testHeaderOffsetFollowsTheChannelModeAndNeedsTwoFramesInARow() {
        let stereo: [UInt8] = [0xFF, 0xFB, 0x90, 0x04]
        var info = stereo + [UInt8](repeating: 0, count: 32) + Array("Info".utf8) + MP3Fixture.bigEndian(0, 4)
        info += [UInt8](repeating: 0, count: 417 - info.count)
        let frames = info + (stereo + [UInt8](repeating: 0, count: 413)) + (stereo + [UInt8](repeating: 0, count: 413))
        XCTAssertEqual(MP3Lead.header(of: Data(frames)), .xing)

        let falseSync: [UInt8] = [0xFF, 0xFB, 0x90, 0xC4, 1, 2, 3]
        XCTAssertEqual(MP3Lead.header(of: Data(falseSync + frames)), .xing)
        XCTAssertEqual(MP3Lead.header(of: Data(info)), .noSync)
        XCTAssertEqual(MP3Lead.header(of: Data((0..<10_000).map { UInt8(truncatingIfNeeded: $0 * 7) })), .noSync)
        XCTAssertEqual(MP3Lead.leadFrames(for: .noSync), 0)
    }

    /// The generation PCM of an MP3 without a LAME delay is AVFoundation's decode 529 frames later, with the lead as
    /// silence and AVFoundation's length; with a LAME delay it is AVFoundation's decode.
    func testDecodeStartsAnUntaggedMP3529FramesLaterAndKeepsItsLength() throws {
        for layout in [MP3Fixture.Layout.mpeg1, .mpeg2] {
            let untagged = try MP3Fixture.write(layout, .untagged)
            defer { try? FileManager.default.removeItem(at: untagged) }
            let decoded = try MP3Fixture.avFoundationSamples(untagged)
            let pcm = try OnDeviceAudioDecoder.decode(untagged)

            XCTAssertEqual(pcm.sampleRate, layout.sampleRate, layout.name)
            XCTAssertEqual(pcm.samples.count, decoded.count, layout.name)
            XCTAssertEqual(pcm.samples, [Float](repeating: 0, count: 529) + decoded.dropLast(529), layout.name)
            XCTAssertNotNil(MP3Fixture.onset(decoded), layout.name)

            let tagged = try MP3Fixture.write(layout, .lameDelay)
            defer { try? FileManager.default.removeItem(at: tagged) }
            XCTAssertEqual(try OnDeviceAudioDecoder.decode(tagged).samples, try MP3Fixture.avFoundationSamples(tagged), layout.name)
        }
    }

    /// The same audio frames behind each header decode where osu!'s decoder puts them, which the release engine's PCM
    /// follows: as the file without a header, except that a non-zero LAME delay starts them that delay plus 529
    /// frames earlier.
    func testEveryHeaderClassDecodesOnTheReleaseEnginesTime() throws {
        let advances: [(MP3Fixture.Variant, Int)] = [
            (.xing, 0), (.lameZero, 0), (.lameDelay, 576 + 529), (.id3Untagged, 0), (.id3FooterLameDelay, 576 + 529)
        ]
        for layout in [MP3Fixture.Layout.mpeg1, .mpeg2] {
            let untagged = try decode(layout, .untagged)
            let untaggedOnset = try XCTUnwrap(MP3Fixture.onset(untagged), layout.name)
            for (variant, advance) in advances {
                let pcm = try decode(layout, variant)
                let pcmOnset = try XCTUnwrap(MP3Fixture.onset(pcm), "\(layout.name), \(variant)")
                XCTAssertEqual(pcmOnset + advance, untaggedOnset, "\(layout.name), \(variant)")
                let difference = zip(pcm[pcmOnset...], untagged[untaggedOnset...]).map { abs($0 - $1) }.max()
                XCTAssertLessThanOrEqual(try XCTUnwrap(difference), 1e-6, "\(layout.name), \(variant)")
            }
        }
    }

    /// AVFoundation takes an iTunes gapless comment's priming as an encoder delay, starting the frames 576 later than
    /// without it, which the release engine's classes do not see; the generation PCM is AVFoundation's decode.
    func testAnITunesGaplessCommentLeavesTheDecodeAsAVFoundationsIs() throws {
        for layout in [MP3Fixture.Layout.mpeg1, .mpeg2] {
            let untaggedURL = try MP3Fixture.write(layout, .untagged)
            defer { try? FileManager.default.removeItem(at: untaggedURL) }
            let url = try MP3Fixture.write(layout, .iTunSMPB)
            defer { try? FileManager.default.removeItem(at: url) }
            let untagged = try MP3Fixture.avFoundationSamples(untaggedURL)
            let decoded = try MP3Fixture.avFoundationSamples(url)

            XCTAssertEqual(
                try XCTUnwrap(MP3Fixture.onset(decoded)) + 576, try XCTUnwrap(MP3Fixture.onset(untagged)), layout.name
            )
            XCTAssertEqual(try OnDeviceAudioDecoder.decode(url).samples, decoded, layout.name)
        }
    }

    private func decode(_ layout: MP3Fixture.Layout, _ variant: MP3Fixture.Variant) throws -> [Float] {
        let url = try MP3Fixture.write(layout, variant)
        defer { try? FileManager.default.removeItem(at: url) }
        return try OnDeviceAudioDecoder.decode(url).samples
    }
}

/// Tiny MPEG Layer III files built bit by bit, the same audio frames behind each header class. Audio frames are mono and
/// use no bit reservoir. Two granules code each of their 576 spectral lines as ±1 or 0 with Huffman table 1; the other
/// granules are silent. Each decodes to silence but for two bursts, whose position shows where a decoder starts.
enum MP3Fixture {
    struct Layout: Sendable {
        let name: String
        let header: [UInt8]
        let frameBytes: Int
        let sideInfoBytes: Int
        let granules: Int
        let sampleRate: Double
        let frames: Int
        /// The burst granules, as (frame, granule) pairs.
        let bursts: [[Int]]

        /// 128 kbps.
        static let mpeg1 = Layout(
            name: "MPEG-1 44.1 kHz", header: [0xFF, 0xFB, 0x90, 0xC4], frameBytes: 417, sideInfoBytes: 17, granules: 2,
            sampleRate: 44_100, frames: 24, bursts: [[8, 0], [15, 1]]
        )
        /// 64 kbps.
        static let mpeg2 = Layout(
            name: "MPEG-2 22.05 kHz", header: [0xFF, 0xF3, 0x80, 0xC4], frameBytes: 208, sideInfoBytes: 9, granules: 1,
            sampleRate: 22_050, frames: 40, bursts: [[14, 0], [27, 0]]
        )
    }

    enum Variant {
        case untagged
        /// A Xing header without a LAME extension.
        case xing
        /// An Info header with a LAME extension: encoder delay 576, padding 1100.
        case lameDelay
        /// An Info header with an extension from FFmpeg's muxer that gives no delay.
        case lameZero
        case vbri
        /// An ID3v2.4 tag in front of the untagged frames.
        case id3Untagged
        /// An ID3v2.4 tag with a footer in front of `lameDelay`.
        case id3FooterLameDelay
        /// An ID3v2.3 tag whose COMM frame carries iTunes' `iTunSMPB` (priming 576, padding 1100) in front of the
        /// untagged frames.
        case iTunSMPB
    }

    static func write(_ layout: Layout, _ variant: Variant) throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathExtension("mp3")
        try Data(bytes(layout, variant)).write(to: url)
        return url
    }

    static func bytes(_ layout: Layout, _ variant: Variant) -> [UInt8] {
        let frames = audio(layout)
        let title = textFrame("TIT2", "Fixture")
        switch variant {
        case .untagged: return frames
        case .xing: return infoFrame(layout, tag: "Xing", encoder: nil) + frames
        case .lameDelay: return infoFrame(layout, tag: "Info") + frames
        case .lameZero: return infoFrame(layout, tag: "Info", encoder: "Lavf58.29", delay: 0, padding: 0) + frames
        case .vbri: return vbriFrame(layout) + frames
        case .id3Untagged: return id3(title) + frames
        case .id3FooterLameDelay: return id3(title, footer: true) + infoFrame(layout, tag: "Info") + frames
        case .iTunSMPB: return iTunSMPB(layout) + frames
        }
    }

    static func audio(_ layout: Layout) -> [UInt8] {
        (0..<layout.frames).flatMap { audioFrame(layout, $0) }
    }

    private static let pairs = 288
    private static let burstGain = 170

    private static func audioFrame(_ layout: Layout, _ index: Int) -> [UInt8] {
        var side = Bits(), main = Bits()
        let mpeg1 = layout.granules == 2
        side.write(0, mpeg1 ? 9 : 8) // main_data_begin: no bit reservoir
        side.write(0, mpeg1 ? 5 : 1) // private bits
        if mpeg1 { side.write(0, 4) } // scfsi
        for granule in 0..<layout.granules {
            let burst = layout.bursts.contains([index, granule])
            side.write(burst ? pairs * 3 : 0, 12) // part2_3_length: no scalefactor bits, 3 bits a pair
            side.write(burst ? pairs : 0, 9) // big_values
            side.write(burst ? burstGain : 0, 8) // global_gain
            side.write(0, mpeg1 ? 4 : 9) // scalefac_compress
            side.write(0, 1) // window_switching_flag
            for _ in 0..<3 { side.write(burst ? 1 : 0, 5) } // table_select
            side.write(7, 4) // region0_count
            side.write(7, 3) // region1_count
            if mpeg1 { side.write(0, 1) } // preflag
            side.write(0, 1) // scalefac_scale
            side.write(0, 1) // count1table_select
            if burst {
                for pair in 0..<pairs {
                    main.write(0b01, 2) // table 1's code for (1, 0)
                    main.write((((index * 31 + granule * 7 + pair) * 2_654_435_761) >> 7) & 1, 1) // its sign
                }
            }
        }
        precondition(side.count == layout.sideInfoBytes * 8)
        let frame = layout.header + side.bytes + main.bytes
        precondition(frame.count <= layout.frameBytes)
        return frame + [UInt8](repeating: 0, count: layout.frameBytes - frame.count)
    }

    /// An Info or Xing frame with every Xing field for `layout`'s frames, then a LAME extension when `encoder` is given.
    private static func infoFrame(
        _ layout: Layout, tag: String, encoder: String? = "LAME3.100", delay: Int = 576, padding: Int = 1_100
    ) -> [UInt8] {
        var frame = layout.header + [UInt8](repeating: 0, count: layout.frameBytes - 4)
        let total = layout.frameBytes * (layout.frames + 1)
        var xing = Array(tag.utf8) + bigEndian(15, 4) + bigEndian(layout.frames, 4) + bigEndian(total, 4)
        xing += (0..<100).map { UInt8($0 * 256 / 100) }
        xing += bigEndian(0, 4)
        let x = 4 + layout.sideInfoBytes
        frame.replaceSubrange(x..<(x + xing.count), with: xing)
        guard let encoder else { return frame }

        let p = x + xing.count
        var ext = [UInt8](repeating: 0, count: 36)
        ext.replaceSubrange(0..<encoder.utf8.count, with: Array(encoder.utf8))
        ext[9] = 0x01 // tag revision 0, CBR
        ext[20] = 128
        ext[21] = UInt8(delay >> 4)
        ext[22] = UInt8((delay & 0xF) << 4 | padding >> 8)
        ext[23] = UInt8(padding & 0xFF)
        ext.replaceSubrange(28..<32, with: bigEndian(total, 4))
        ext.replaceSubrange(32..<34, with: bigEndian(crc16(audio(layout)), 2))
        frame.replaceSubrange(p..<(p + 36), with: ext)
        frame.replaceSubrange((p + 34)..<(p + 36), with: bigEndian(crc16(Array(frame[0..<(p + 34)])), 2))
        return frame
    }

    private static func vbriFrame(_ layout: Layout) -> [UInt8] {
        var frame = layout.header + [UInt8](repeating: 0, count: layout.frameBytes - 4)
        var vbri = Array("VBRI".utf8) + bigEndian(1, 2) + bigEndian(0, 2) + bigEndian(75, 2)
        vbri += bigEndian(layout.frameBytes * (layout.frames + 1), 4) + bigEndian(layout.frames, 4)
        vbri += [UInt8](repeating: 0, count: 8)
        frame.replaceSubrange(36..<(36 + vbri.count), with: vbri)
        return frame
    }

    private static func id3(_ frames: [UInt8], version: UInt8 = 4, footer: Bool = false) -> [UInt8] {
        let flags: UInt8 = footer ? 0x10 : 0
        let fields: [UInt8] = [version, 0, flags] + syncsafe(frames.count)
        var tag = Array("ID3".utf8) + fields + frames
        if footer { tag += Array("3DI".utf8) + fields }
        return tag
    }

    /// An ID3v2.4 text frame in ISO-8859-1.
    private static func textFrame(_ id: String, _ text: String) -> [UInt8] {
        let payload: [UInt8] = [0] + Array(text.utf8)
        let flags: [UInt8] = [0, 0]
        return Array(id.utf8) + syncsafe(payload.count) + flags + payload
    }

    private static func iTunSMPB(_ layout: Layout, priming: Int = 576, padding: Int = 1_100) -> [UInt8] {
        let valid = layout.frames * 576 * layout.granules - priming - padding
        let values = [hex(0, 8), hex(priming, 8), hex(padding, 8), hex(valid, 16)] + Array(repeating: hex(0, 8), count: 8)
        let text = values.map { " " + $0 }.joined()
        // ISO-8859-1, English, the description and its terminator, then the text.
        var payload: [UInt8] = [0]
        payload += Array("eng".utf8)
        payload += Array("iTunSMPB".utf8)
        payload.append(0)
        payload += Array(text.utf8)
        let flags: [UInt8] = [0, 0]
        let comment = Array("COMM".utf8) + bigEndian(payload.count, 4) + flags + payload
        return id3(comment, version: 3)
    }

    /// The first frame clearly above silence.
    static func onset(_ samples: [Float]) -> Int? {
        samples.firstIndex { abs($0) > 1e-5 }
    }

    /// The file's first channel as AVFoundation decodes it.
    static func avFoundationSamples(_ url: URL) throws -> [Float] {
        let file = try AVAudioFile(forReading: url)
        let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: AVAudioFrameCount(file.length)))
        try file.read(into: buffer)
        return Array(UnsafeBufferPointer(start: try XCTUnwrap(buffer.floatChannelData)[0], count: Int(buffer.frameLength)))
    }

    static func bigEndian(_ value: Int, _ count: Int) -> [UInt8] {
        (0..<count).reversed().map { UInt8(truncatingIfNeeded: value >> ($0 * 8)) }
    }

    private static func syncsafe(_ value: Int) -> [UInt8] {
        [21, 14, 7, 0].map { UInt8((value >> $0) & 0x7F) }
    }

    private static func hex(_ value: Int, _ digits: Int) -> String {
        let text = String(value, radix: 16, uppercase: true)
        return String(repeating: "0", count: max(0, digits - text.count)) + text
    }

    /// CRC-16/ARC, the LAME extension's checksum.
    private static func crc16(_ bytes: [UInt8]) -> Int {
        var crc = 0
        for byte in bytes {
            crc ^= Int(byte)
            for _ in 0..<8 { crc = crc & 1 != 0 ? crc >> 1 ^ 0xA001 : crc >> 1 }
        }
        return crc
    }

    private struct Bits {
        private(set) var bytes: [UInt8] = []
        private(set) var count = 0

        mutating func write(_ value: Int, _ width: Int) {
            for shift in stride(from: width - 1, through: 0, by: -1) {
                if count % 8 == 0 { bytes.append(0) }
                if (value >> shift) & 1 == 1 { bytes[bytes.count - 1] |= UInt8(0x80 >> (count % 8)) }
                count += 1
            }
        }
    }
}
