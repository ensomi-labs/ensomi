import Compression
import Foundation

/// The files of an `.osz`, which is a zip archive, found through its central directory. Entries stored or deflated
/// can be read; osu! and the runtime's `exportOsz` write nothing else. Offsets and sizes come from the file, so every
/// read is checked against its length, and a file that does not hold together is `unreadableArchive`.
struct OszArchive {
    struct Entry {
        let name: String
        let method: UInt16
        let compressedSize: Int
        let size: Int
        let headerOffset: Int
    }

    let entries: [Entry]
    private let bytes: [UInt8]

    init(data: Data) throws {
        let bytes = [UInt8](data)
        // The end-of-central-directory record is the last 22 bytes, unless a comment of up to 65535 bytes follows it.
        let lastStart = bytes.count - 22
        guard lastStart >= 0,
              let end = stride(from: lastStart, through: max(0, lastStart - 65_535), by: -1)
                .first(where: { Self.uint32(bytes, $0) == 0x0605_4b50 })
        else {
            throw Mania4KChartInspectionError.unreadableArchive
        }

        var entries: [Entry] = []
        var offset = Int(Self.uint32(bytes, end + 16))
        for _ in 0..<Int(Self.uint16(bytes, end + 10)) {
            guard offset + 46 <= bytes.count, Self.uint32(bytes, offset) == 0x0201_4b50 else {
                throw Mania4KChartInspectionError.unreadableArchive
            }
            let nameLength = Int(Self.uint16(bytes, offset + 28))
            let nameEnd = offset + 46 + nameLength
            guard nameEnd <= bytes.count else {
                throw Mania4KChartInspectionError.unreadableArchive
            }
            entries.append(Entry(
                name: String(decoding: bytes[offset + 46 ..< nameEnd], as: UTF8.self),
                method: Self.uint16(bytes, offset + 10),
                compressedSize: Int(Self.uint32(bytes, offset + 20)),
                size: Int(Self.uint32(bytes, offset + 24)),
                headerOffset: Int(Self.uint32(bytes, offset + 42))
            ))
            offset = nameEnd + Int(Self.uint16(bytes, offset + 30)) + Int(Self.uint16(bytes, offset + 32))
        }
        self.entries = entries
        self.bytes = bytes
    }

    /// The entry's bytes, inflated if it was deflated.
    func contents(of entry: Entry) throws -> Data {
        let header = entry.headerOffset
        guard header + 30 <= bytes.count, Self.uint32(bytes, header) == 0x0403_4b50 else {
            throw Mania4KChartInspectionError.unreadableArchive
        }
        let start = header + 30 + Int(Self.uint16(bytes, header + 26)) + Int(Self.uint16(bytes, header + 28))
        guard start + entry.compressedSize <= bytes.count else {
            throw Mania4KChartInspectionError.unreadableArchive
        }
        let body = bytes[start ..< start + entry.compressedSize]

        switch entry.method {
        case 0:
            return Data(body)
        case 8 where entry.size == 0:
            return Data()
        case 8 where !body.isEmpty:
            // COMPRESSION_ZLIB reads raw deflate, the form zip stores.
            var inflated = Data(count: entry.size)
            let written = inflated.withUnsafeMutableBytes { destination in
                body.withUnsafeBufferPointer { source in
                    compression_decode_buffer(
                        destination.bindMemory(to: UInt8.self).baseAddress!, entry.size,
                        source.baseAddress!, source.count, nil, COMPRESSION_ZLIB
                    )
                }
            }
            guard written == entry.size else {
                throw Mania4KChartInspectionError.unreadableArchive
            }
            return inflated
        default:
            throw Mania4KChartInspectionError.unreadableArchive
        }
    }

    /// The entry named `name`, ignoring case as osu! does.
    func entry(named name: String) -> Entry? {
        entries.first { $0.name.caseInsensitiveCompare(name) == .orderedSame }
    }

    private static func uint16(_ bytes: [UInt8], _ offset: Int) -> UInt16 {
        UInt16(bytes[offset]) | UInt16(bytes[offset + 1]) << 8
    }

    private static func uint32(_ bytes: [UInt8], _ offset: Int) -> UInt32 {
        UInt32(bytes[offset]) | UInt32(bytes[offset + 1]) << 8 | UInt32(bytes[offset + 2]) << 16
            | UInt32(bytes[offset + 3]) << 24
    }
}
