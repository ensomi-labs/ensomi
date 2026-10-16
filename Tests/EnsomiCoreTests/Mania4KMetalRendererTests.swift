import Metal
import XCTest
@testable import EnsomiUI

@MainActor
final class Mania4KMetalRendererTests: XCTestCase {
    func testQuadsBlendOverAndAddToWhatIsBelowAndHonourTheirClip() throws {
        guard let device = MTLCreateSystemDefaultDevice() else {
            throw XCTSkip("No Metal device")
        }

        let renderer = try Mania4KMetalRenderer(device: device, pixelFormat: .bgra8Unorm)
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: 64, height: 64, mipmapped: false)
        descriptor.usage = .renderTarget
        descriptor.storageMode = .shared
        let texture = try XCTUnwrap(device.makeTexture(descriptor: descriptor))
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = texture
        pass.colorAttachments[0].loadAction = .clear
        pass.colorAttachments[0].clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 0)
        pass.colorAttachments[0].storeAction = .store
        let everywhere = SIMD4<Float>(0, 0, 64, 64)

        let quads = [
            Mania4KQuad(rect: SIMD4(0, 0, 32, 32), color: SIMD4(1, 0, 0, 1), clip: everywhere),
            Mania4KQuad(rect: SIMD4(16, 16, 32, 32), color: SIMD4(0, 0, 1, 0.5), clip: everywhere),
            Mania4KQuad(rect: SIMD4(50, 50, 10, 10), color: SIMD4(0, 0.5, 0, 1), clip: everywhere, additive: true),
            Mania4KQuad(rect: SIMD4(40, 0, 20, 20), color: SIMD4(1, 1, 1, 1), clip: SIMD4(0, 0, 50, 64))
        ]
        let commandBuffer = try XCTUnwrap(renderer.draw(quads, viewportSize: CGSize(width: 64, height: 64), pass: pass, drawable: nil))
        commandBuffer.waitUntilCompleted()

        func pixel(_ x: Int, _ y: Int) -> [UInt8] {
            var bgra = [UInt8](repeating: 0, count: 4)
            texture.getBytes(&bgra, bytesPerRow: 4 * 64, from: MTLRegionMake2D(x, y, 1, 1), mipmapLevel: 0)
            return bgra
        }
        func assertPixel(_ x: Int, _ y: Int, blue: Int, green: Int, red: Int, alpha: Int, line: UInt = #line) {
            let bgra = pixel(x, y).map(Int.init)
            XCTAssertEqual(bgra[0], blue, accuracy: 2, "blue at \(x),\(y)", line: line)
            XCTAssertEqual(bgra[1], green, accuracy: 2, "green at \(x),\(y)", line: line)
            XCTAssertEqual(bgra[2], red, accuracy: 2, "red at \(x),\(y)", line: line)
            XCTAssertEqual(bgra[3], alpha, accuracy: 2, "alpha at \(x),\(y)", line: line)
        }

        assertPixel(8, 8, blue: 0, green: 0, red: 255, alpha: 255)
        assertPixel(24, 24, blue: 128, green: 0, red: 128, alpha: 255)
        assertPixel(40, 40, blue: 128, green: 0, red: 0, alpha: 128)
        assertPixel(55, 55, blue: 0, green: 128, red: 0, alpha: 0)
        assertPixel(45, 10, blue: 255, green: 255, red: 255, alpha: 255)
        assertPixel(55, 10, blue: 0, green: 0, red: 0, alpha: 0)
    }

    /// The playfield's target is its view's render pass, and reading that takes a drawable: a frame dropped because
    /// three are in flight must not ask for it, and a frame without a target frees its place.
    func testTheTargetIsAskedForOnlyWhenAFrameIsFree() throws {
        guard let device = MTLCreateSystemDefaultDevice() else {
            throw XCTSkip("No Metal device")
        }

        let renderer = try Mania4KMetalRenderer(device: device, pixelFormat: .bgra8Unorm)
        var targetsAsked = 0
        // Each target starts the next frame before it answers, so the fourth frame starts while three are in flight.
        func drawFrame(_ depth: Int) {
            renderer.draw([], viewportSize: CGSize(width: 64, height: 64)) {
                targetsAsked += 1
                if depth <= Mania4KMetalRenderer.framesInFlight {
                    drawFrame(depth + 1)
                }
                return nil
            }
        }

        drawFrame(1)
        XCTAssertEqual(targetsAsked, Mania4KMetalRenderer.framesInFlight)
        drawFrame(1)
        XCTAssertEqual(targetsAsked, 2 * Mania4KMetalRenderer.framesInFlight)
    }
}
