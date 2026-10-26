// Draws Healdan Helper's app icon: Healdan's mark (the docs site's favicon.svg) on macOS's rounded square, at each
// size an .iconset holds. package.sh runs it, and iconutil turns the .iconset into the bundle's AppIcon.icns.
//
//   swift make-icon.swift <mark.svg> <out.iconset>
//
// The proportions are Apple's icon grid on a 1024-point canvas: the square is 824 points with continuous corners
// (drawn as a superellipse) and a soft shadow below, so macOS shows it as it is rather than inside a grey square.
import AppKit

let args = CommandLine.arguments
guard args.count == 3 else {
    FileHandle.standardError.write(Data("usage: swift make-icon.swift <mark.svg> <out.iconset>\n".utf8))
    exit(2)
}
guard let mark = NSImage(contentsOf: URL(fileURLWithPath: args[1])), mark.isValid else {
    FileHandle.standardError.write(Data("make-icon.swift: can't read the mark \(args[1])\n".utf8))
    exit(1)
}
let iconset = URL(fileURLWithPath: args[2])
try FileManager.default.createDirectory(at: iconset, withIntermediateDirectories: true)
let sRGB = CGColorSpace(name: CGColorSpace.sRGB)!

/// The rounded square, centred on the canvas: a superellipse of exponent 5, which is within a point of Apple's shape.
let square: CGPath = {
    let path = CGMutablePath()
    let steps = 720
    for step in 0..<steps {
        let t = Double(step) / Double(steps) * 2 * .pi
        let point = CGPoint(x: 512 + 412 * copysign(pow(abs(cos(t)), 0.4), cos(t)),
                            y: 512 + 412 * copysign(pow(abs(sin(t)), 0.4), sin(t)))
        if step == 0 { path.move(to: point) } else { path.addLine(to: point) }
    }
    path.closeSubpath()
    return path
}()

func colour(_ hex: Int) -> CGColor {
    CGColor(srgbRed: CGFloat(hex >> 16 & 0xff) / 255, green: CGFloat(hex >> 8 & 0xff) / 255,
            blue: CGFloat(hex & 0xff) / 255, alpha: 1)
}

func draw(pixels: Int, to file: URL) {
    let scale = CGFloat(pixels) / 1024
    let context = CGContext(data: nil, width: pixels, height: pixels, bitsPerComponent: 8, bytesPerRow: 0, space: sRGB,
                            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    context.scaleBy(x: scale, y: scale)

    // The shadow's offset and blur are in pixels, whatever the scale.
    context.saveGState()
    context.setShadow(offset: CGSize(width: 0, height: -10 * scale), blur: 20 * scale,
                      color: CGColor(gray: 0, alpha: 0.3))
    context.addPath(square)
    context.setFillColor(colour(0xffffff))
    context.fillPath()
    context.restoreGState()

    context.saveGState()
    context.addPath(square)
    context.clip()
    let wash = CGGradient(colorsSpace: sRGB, colors: [colour(0xffffff), colour(0xe4ecf5)] as CFArray,
                          locations: [0, 1])!
    context.drawLinearGradient(wash, start: CGPoint(x: 512, y: 924), end: CGPoint(x: 512, y: 100), options: [])
    context.restoreGState()

    // favicon.svg is 260 points square with the shield centred in it, 250 points tall: drawn 580 points square, the
    // shield is about 560 tall, two thirds of the square's height.
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(cgContext: context, flipped: false)
    mark.draw(in: NSRect(x: 512 - 290, y: 512 - 290, width: 580, height: 580))
    NSGraphicsContext.restoreGraphicsState()

    let destination = CGImageDestinationCreateWithURL(file as CFURL, "public.png" as CFString, 1, nil)!
    CGImageDestinationAddImage(destination, context.makeImage()!, nil)
    guard CGImageDestinationFinalize(destination) else {
        FileHandle.standardError.write(Data("make-icon.swift: can't write \(file.path)\n".utf8))
        exit(1)
    }
}

for points in [16, 32, 128, 256, 512] {
    draw(pixels: points, to: iconset.appendingPathComponent("icon_\(points)x\(points).png"))
    draw(pixels: points * 2, to: iconset.appendingPathComponent("icon_\(points)x\(points)@2x.png"))
}
