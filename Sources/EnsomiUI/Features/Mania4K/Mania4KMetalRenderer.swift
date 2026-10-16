import Foundation
import Metal
import MetalKit

enum Mania4KMetalError: Error {
    case unavailable
}

/// Draws `Mania4KQuad`s as instanced rounded rectangles: one draw call per frame, in the order given.
///
/// The shader is compiled from source when the renderer is made, so building the app does not need the Metal
/// toolchain, which Xcode installs as a separate download.
@MainActor
final class Mania4KMetalRenderer {
    static let quadCapacity = 2_048
    static let framesInFlight = 3

    private let queue: any MTLCommandQueue
    private let pipeline: any MTLRenderPipelineState
    private let buffers: [any MTLBuffer]
    private var nextBuffer = 0
    private let inFlight = DispatchSemaphore(value: Mania4KMetalRenderer.framesInFlight)

    init(device: any MTLDevice, pixelFormat: MTLPixelFormat) throws {
        let library = try device.makeLibrary(source: Self.shaderSource, options: nil)
        let descriptor = MTLRenderPipelineDescriptor()
        descriptor.vertexFunction = library.makeFunction(name: "quadVertex")
        descriptor.fragmentFunction = library.makeFunction(name: "quadFragment")
        let attachment = descriptor.colorAttachments[0]!
        attachment.pixelFormat = pixelFormat
        // Premultiplied alpha. An additive quad outputs alpha 0, which leaves the destination and adds its colour.
        attachment.isBlendingEnabled = true
        attachment.sourceRGBBlendFactor = .one
        attachment.destinationRGBBlendFactor = .oneMinusSourceAlpha
        attachment.sourceAlphaBlendFactor = .one
        attachment.destinationAlphaBlendFactor = .oneMinusSourceAlpha

        let length = Self.quadCapacity * MemoryLayout<Mania4KQuad>.stride
        let buffers = (0..<Self.framesInFlight).compactMap { _ in
            device.makeBuffer(length: length, options: .storageModeShared)
        }
        guard let queue = device.makeCommandQueue(), buffers.count == Self.framesInFlight else {
            throw Mania4KMetalError.unavailable
        }

        self.queue = queue
        self.pipeline = try device.makeRenderPipelineState(descriptor: descriptor)
        self.buffers = buffers
    }

    /// Encodes and commits one frame; returns it so a caller can wait for the GPU. A frame is dropped when the GPU is
    /// still busy with the previous three.
    @discardableResult
    func draw(
        _ quads: [Mania4KQuad],
        viewportSize: CGSize,
        pass: MTLRenderPassDescriptor,
        drawable: (any MTLDrawable)?
    ) -> (any MTLCommandBuffer)? {
        draw(quads, viewportSize: viewportSize) { (pass, drawable) }
    }

    /// Encodes and commits one frame into the render pass and drawable `target` returns; returns the frame. A frame is
    /// dropped when the GPU is still busy with the previous three, and `target` is called only for a frame that is not:
    /// an `MTKView` takes a drawable when its render pass is read, so a dropped frame takes none. A frame whose target
    /// is nil is dropped too.
    @discardableResult
    func draw(
        _ quads: [Mania4KQuad],
        viewportSize: CGSize,
        target: () -> (pass: MTLRenderPassDescriptor, drawable: (any MTLDrawable)?)?
    ) -> (any MTLCommandBuffer)? {
        guard inFlight.wait(timeout: .now()) == .success else {
            return nil
        }

        guard let destination = target(),
              let commandBuffer = queue.makeCommandBuffer(),
              let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: destination.pass)
        else {
            inFlight.signal()
            return nil
        }

        let count = min(quads.count, Self.quadCapacity)
        let buffer = buffers[nextBuffer]
        nextBuffer = (nextBuffer + 1) % Self.framesInFlight
        if count > 0 {
            quads.withUnsafeBytes { source in
                buffer.contents().copyMemory(from: source.baseAddress!, byteCount: count * MemoryLayout<Mania4KQuad>.stride)
            }
        }

        var viewport = SIMD2<Float>(Float(viewportSize.width), Float(viewportSize.height))
        encoder.setRenderPipelineState(pipeline)
        encoder.setVertexBuffer(buffer, offset: 0, index: 0)
        encoder.setVertexBytes(&viewport, length: MemoryLayout<SIMD2<Float>>.size, index: 1)
        encoder.setFragmentBuffer(buffer, offset: 0, index: 0)
        if count > 0 {
            encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4, instanceCount: count)
        }
        encoder.endEncoding()
        if let drawable = destination.drawable {
            commandBuffer.present(drawable)
        }
        let semaphore = inFlight
        commandBuffer.addCompletedHandler { _ in semaphore.signal() }
        commandBuffer.commit()
        return commandBuffer
    }

    private static let shaderSource = """
    #include <metal_stdlib>
    using namespace metal;

    struct Quad {
        float4 rect;
        float4 color;
        float4 shape;
        float4 clip;
        float4 flags;
    };

    struct VertexOut {
        float4 position [[position]];
        float2 local;
        float2 point;
        uint instance [[flat]];
    };

    vertex VertexOut quadVertex(
        uint vertexID [[vertex_id]],
        uint instanceID [[instance_id]],
        constant Quad *quads [[buffer(0)]],
        constant float2 &viewport [[buffer(1)]]
    ) {
        Quad quad = quads[instanceID];
        float2 halfSize = quad.rect.zw * 0.5;
        float sigma = quad.shape.z * 0.4;
        float pad = 1.0 + (quad.shape.w > 0.0 ? sigma * 3.0 : 0.0);
        float2 corner = float2((vertexID & 1) != 0 ? 1.0 : -1.0, (vertexID & 2) != 0 ? 1.0 : -1.0);
        float2 local = corner * (halfSize + pad);
        float2 point = quad.rect.xy + halfSize + local;

        VertexOut out;
        out.position = float4(point.x / viewport.x * 2.0 - 1.0, 1.0 - point.y / viewport.y * 2.0, 0.0, 1.0);
        out.local = local;
        out.point = point;
        out.instance = instanceID;
        return out;
    }

    float roundedBoxDistance(float2 p, float2 halfSize, float radius) {
        float r = min(radius, min(halfSize.x, halfSize.y));
        float2 q = abs(p) - halfSize + r;
        return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
    }

    fragment float4 quadFragment(VertexOut in [[stage_in]], constant Quad *quads [[buffer(0)]]) {
        Quad quad = quads[in.instance];
        if (in.point.x < quad.clip.x || in.point.y < quad.clip.y
            || in.point.x > quad.clip.x + quad.clip.z || in.point.y > quad.clip.y + quad.clip.w) {
            discard_fragment();
        }

        float d = roundedBoxDistance(in.local, quad.rect.zw * 0.5, quad.shape.x);
        float edge = max(fwidth(d), 1e-4);
        float coverage;
        if (quad.shape.y > 0.0) {
            coverage = 1.0 - smoothstep(quad.shape.y * 0.5 - edge * 0.5, quad.shape.y * 0.5 + edge * 0.5, abs(d));
        } else {
            coverage = 1.0 - smoothstep(-edge * 0.5, edge * 0.5, d);
        }

        float fill = quad.color.a * coverage;
        float glow = 0.0;
        if (quad.shape.w > 0.0) {
            float t = max(d, 0.0) / max(quad.shape.z * 0.4, 1e-3);
            glow = quad.shape.w * 0.5 * exp(-0.5 * t * t);
        }

        float alpha = fill + (1.0 - fill) * glow;
        if (quad.flags.y > 0.5) {
            float fromTop = clamp((in.local.y + quad.rect.w * 0.5) / max(quad.rect.w, 1e-3), 0.0, 1.0);
            alpha *= fromTop * fromTop;
        }
        return float4(quad.color.rgb * alpha, quad.flags.x > 0.5 ? 0.0 : alpha);
    }
    """
}
