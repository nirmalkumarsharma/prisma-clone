import PrismaKit
import UIKit

/// Converts between UIKit images and the engine's RGBA buffers.
enum PixelBridge {
    private static let sRGB = CGColorSpace(name: CGColorSpace.sRGB)!

    private static var rendererFormat: UIGraphicsImageRendererFormat {
        let f = UIGraphicsImageRendererFormat()
        f.scale = 1
        f.opaque = true
        f.preferredRange = .standard // 8-bit sRGB, even on wide-color devices
        return f
    }

    /// Draws `image` upright (EXIF orientation applied), scaled to fit `maxSide` pixels.
    static func pixels(from image: UIImage, maxSide: Int) -> PixelImage {
        let pw = image.size.width * image.scale, ph = image.size.height * image.scale
        let k = min(1, CGFloat(maxSide) / max(pw, ph))
        let w = max(1, Int((pw * k).rounded())), h = max(1, Int((ph * k).rounded()))
        let upright = UIGraphicsImageRenderer(size: CGSize(width: w, height: h), format: rendererFormat).image { _ in
            image.draw(in: CGRect(x: 0, y: 0, width: w, height: h))
        }
        return pixels(from: upright.cgImage!, width: w, height: h)
    }

    static func pixels(from cg: CGImage, width w: Int, height h: Int) -> PixelImage {
        var data = [UInt8](repeating: 255, count: w * h * 4)
        data.withUnsafeMutableBytes { buf in
            let ctx = CGContext(
                data: buf.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                space: sRGB, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        }
        for i in stride(from: 3, to: data.count, by: 4) { data[i] = 255 } // the engine expects opaque pixels
        return PixelImage(width: w, height: h, rgba: data)
    }

    static func image(from px: PixelImage) -> UIImage {
        let provider = CGDataProvider(data: Data(px.rgba) as CFData)!
        let cg = CGImage(
            width: px.width, height: px.height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: px.width * 4,
            space: sRGB, bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
            provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)!
        return UIImage(cgImage: cg)
    }

    /// Square center-crop thumbnail for the filter strip.
    static func squareThumbnail(_ px: PixelImage, side: Int) -> PixelImage {
        let s = min(px.width, px.height)
        let crop = image(from: px).cgImage!
            .cropping(to: CGRect(x: (px.width - s) / 2, y: (px.height - s) / 2, width: s, height: s))!
        let scaled = UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: rendererFormat).image { _ in
            UIImage(cgImage: crop).draw(in: CGRect(x: 0, y: 0, width: side, height: side))
        }
        return pixels(from: scaled.cgImage!, width: side, height: side)
    }
}
