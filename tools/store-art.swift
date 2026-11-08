// Generates Wiz PDF Master store art with AppKit (no extra tools needed).
//   swift tools/store-art.swift icons <outDir>
//   swift tools/store-art.swift frame <raw.png> <out.png> "<Caption line 1>" "<Caption line 2>"
import AppKit
import Foundation

let yellow = NSColor(srgbRed: 1.0, green: 0.784, blue: 0.0, alpha: 1)
let ink = NSColor(srgbRed: 0.067, green: 0.067, blue: 0.078, alpha: 1)
let bg = NSColor(srgbRed: 0.043, green: 0.043, blue: 0.051, alpha: 1)
let muted = NSColor(srgbRed: 0.62, green: 0.62, blue: 0.66, alpha: 1)

func canvas(_ w: Int, _ h: Int, _ draw: (CGFloat, CGFloat) -> Void) -> NSBitmapImageRep {
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: w, pixelsHigh: h, bitsPerSample: 8,
                               samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                               colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    rep.size = NSSize(width: w, height: h)
    NSGraphicsContext.saveGraphicsState()
    let ctx = NSGraphicsContext(bitmapImageRep: rep)!
    ctx.imageInterpolation = .high
    NSGraphicsContext.current = ctx
    // Flip so y grows downward, like Android / the vector icon.
    let t = NSAffineTransform(); t.translateX(by: 0, yBy: CGFloat(h)); t.scaleX(by: 1, yBy: -1); t.concat()
    draw(CGFloat(w), CGFloat(h))
    NSGraphicsContext.restoreGraphicsState()
    return rep
}

func save(_ rep: NSBitmapImageRep, _ path: String, opaque: Bool = true) {
    var r = rep
    if opaque {
        // Play wants icons and screenshots without alpha: flatten onto the image itself.
        let w = rep.pixelsWide, h = rep.pixelsHigh
        let cg = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                           space: CGColorSpace(name: CGColorSpace.sRGB)!,
                           bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
        cg.setFillColor(bg.cgColor); cg.fill(CGRect(x: 0, y: 0, width: w, height: h))
        cg.draw(rep.cgImage!, in: CGRect(x: 0, y: 0, width: w, height: h))
        r = NSBitmapImageRep(cgImage: cg.makeImage()!)
    }
    try! r.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: path))
    print("wrote", path, "\(r.pixelsWide)x\(r.pixelsHigh)")
}

func rect(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat) -> NSRect { NSRect(x: x, y: y, width: w, height: h) }

/// The launcher artwork from res/drawable/ic_launcher_foreground.xml, in its 108-unit space.
func drawMark(origin: CGPoint, scale s: CGFloat, paper: NSColor = yellow) {
    func p(_ x: CGFloat, _ y: CGFloat) -> NSPoint { NSPoint(x: origin.x + x * s, y: origin.y + y * s) }
    func poly(_ pts: [(CGFloat, CGFloat)], _ c: NSColor) {
        let b = NSBezierPath(); b.move(to: p(pts[0].0, pts[0].1)); pts.dropFirst().forEach { b.line(to: p($0.0, $0.1)) }
        b.close(); c.setFill(); b.fill()
    }
    // Sheet with folded corner
    let sheet = NSBezierPath()
    sheet.move(to: p(39, 30)); sheet.line(to: p(62, 30)); sheet.line(to: p(72, 40)); sheet.line(to: p(72, 76))
    sheet.curve(to: p(70, 78), controlPoint1: p(72, 77.3), controlPoint2: p(71.3, 78))
    sheet.line(to: p(39, 78))
    sheet.curve(to: p(37, 76), controlPoint1: p(37.7, 78), controlPoint2: p(37, 77.3))
    sheet.line(to: p(37, 32))
    sheet.curve(to: p(39, 30), controlPoint1: p(37, 30.7), controlPoint2: p(37.7, 30))
    sheet.close(); ink.setFill(); sheet.fill()
    poly([(62, 30), (62, 40), (72, 40)], paper)
    poly([(43, 47), (64, 47), (64, 50), (43, 50)], paper)
    poly([(43, 54), (66, 54), (66, 57), (43, 57)], paper)
    poly([(43, 61), (58, 61), (58, 64), (43, 64)], paper)
    poly([(27, 27), (36, 27), (36, 31), (31, 31), (31, 36), (27, 36)], ink)
    poly([(81, 27), (72, 27), (72, 31), (77, 31), (77, 36), (81, 36)], ink)
    poly([(27, 81), (36, 81), (36, 77), (31, 77), (31, 72), (27, 72)], ink)
    poly([(81, 81), (72, 81), (72, 77), (77, 77), (77, 72), (81, 72)], ink)
    poly([(24, 68), (84, 68), (84, 70.5), (24, 70.5)], ink.withAlphaComponent(0.91))
}

/// Square app tile: yellow, mark scaled so the 72-unit visible area of the adaptive icon fills it.
func drawTile(_ r: NSRect, corner: CGFloat) {
    NSGraphicsContext.saveGraphicsState()
    let clip = NSBezierPath(roundedRect: r, xRadius: corner, yRadius: corner); clip.addClip()
    yellow.setFill(); r.fill()
    let s = r.width / 72
    drawMark(origin: CGPoint(x: r.minX - 18 * s, y: r.minY - 18 * s), scale: s)
    NSGraphicsContext.restoreGraphicsState()
}

func text(_ str: String, _ x: CGFloat, _ y: CGFloat, size: CGFloat, weight: NSFont.Weight, color: NSColor,
          width: CGFloat = 4000, align: NSTextAlignment = .left, kern: CGFloat = 0) {
    let para = NSMutableParagraphStyle(); para.alignment = align; para.lineSpacing = size * 0.12
    let attrs: [NSAttributedString.Key: Any] = [
        .font: NSFont.systemFont(ofSize: size, weight: weight), .foregroundColor: color,
        .paragraphStyle: para, .kern: kern
    ]
    let a = NSAttributedString(string: str, attributes: attrs)
    // Text draws upright inside our flipped context only through a flipped graphics state.
    NSGraphicsContext.saveGraphicsState()
    let ctx = NSGraphicsContext(cgContext: NSGraphicsContext.current!.cgContext, flipped: true)
    NSGraphicsContext.current = ctx
    a.draw(with: NSRect(x: x, y: y, width: width, height: size * 6), options: [.usesLineFragmentOrigin])
    NSGraphicsContext.restoreGraphicsState()
}

func icons(_ out: String) {
    try? FileManager.default.createDirectory(atPath: out, withIntermediateDirectories: true)
    // Play Store icon: full-bleed square, Play applies its own rounded mask.
    save(canvas(512, 512) { w, h in drawTile(rect(0, 0, w, h), corner: 0) }, out + "/icon-512.png")

    // Logo: tile + wordmark on transparent, for websites / documents.
    save(canvas(1600, 480) { w, h in
        drawTile(rect(40, 40, 400, 400), corner: 92)
        text("Wiz PDF Master", 500, 110, size: 150, weight: .heavy, color: ink)
        text("SCAN · READ · SEARCH", 506, 300, size: 58, weight: .bold, color: NSColor(white: 0.25, alpha: 1), kern: 4)
    }, out + "/logo-1600x480.png", opaque: false)

    // Feature graphic 1024x500 (same composition as Wiz Dip Charts, in black & yellow).
    save(canvas(1024, 500) { w, h in
        bg.setFill(); rect(0, 0, w, h).fill()
        // Concentric "scan" rings
        for i in 0..<9 {
            let r = CGFloat(70 + i * 70)
            let ring = NSBezierPath(ovalIn: rect(760 - r, 250 - r, r * 2, r * 2))
            yellow.withAlphaComponent(0.10 - CGFloat(i) * 0.008).setStroke(); ring.lineWidth = 2; ring.stroke()
        }
        // Soft glow behind the tile
        let glow = NSGradient(colors: [yellow.withAlphaComponent(0.28), yellow.withAlphaComponent(0)])!
        glow.draw(fromCenter: NSPoint(x: 190, y: 250), radius: 0, toCenter: NSPoint(x: 190, y: 250), radius: 230, options: [])
        drawTile(rect(62, 122, 256, 256), corner: 58)
        text("Wiz PDF Master", 360, 120, size: 64, weight: .heavy, color: .white)
        text("SCAN · READ · SEARCH", 362, 210, size: 27, weight: .bold, color: yellow, kern: 2)
        text("Document scanner, PDF reader and text recognition", 362, 262, size: 23, weight: .regular, color: NSColor(white: 0.92, alpha: 1))
        text("100% offline  •  No ads  •  No account", 362, 298, size: 23, weight: .regular, color: NSColor(white: 0.92, alpha: 1))
        let pill = NSBezierPath(roundedRect: rect(362, 358, 230, 52), xRadius: 26, yRadius: 26)
        yellow.setFill(); pill.fill()
        text("BY WIZ LABS", 362, 368, size: 24, weight: .heavy, color: ink, width: 230, align: .center, kern: 1.5)
    }, out + "/feature-graphic-1024x500.png")
}

/// Marketing screenshot: caption on top, real app screenshot in a rounded "phone" below. 1080x1920 (9:16).
func frame(_ raw: String, _ out: String, _ line1: String, _ line2: String) {
    let shot = NSImage(contentsOfFile: raw)!
    let rep = canvas(1080, 1920) { w, h in
        bg.setFill(); rect(0, 0, w, h).fill()
        let glow = NSGradient(colors: [yellow.withAlphaComponent(0.22), yellow.withAlphaComponent(0)])!
        glow.draw(fromCenter: NSPoint(x: 540, y: 1250), radius: 0, toCenter: NSPoint(x: 540, y: 1250), radius: 760, options: [])
        text(line1, 60, 92, size: 74, weight: .heavy, color: .white, width: 960, align: .center)
        text(line2, 60, 196, size: 40, weight: .semibold, color: yellow, width: 960, align: .center)
        // Phone body
        let pw: CGFloat = 760, ph = pw * 2400 / 1080
        let px = (w - pw) / 2, py: CGFloat = 330
        let body = NSBezierPath(roundedRect: rect(px - 18, py - 18, pw + 36, ph + 36), xRadius: 78, yRadius: 78)
        NSColor(white: 0.16, alpha: 1).setFill(); body.fill()
        NSColor(white: 0.32, alpha: 1).setStroke(); body.lineWidth = 3; body.stroke()
        NSGraphicsContext.saveGraphicsState()
        NSBezierPath(roundedRect: rect(px, py, pw, ph), xRadius: 62, yRadius: 62).addClip()
        // Draw upright (our context is flipped).
        let t = NSAffineTransform(); t.translateX(by: 0, yBy: py * 2 + ph); t.scaleX(by: 1, yBy: -1); t.concat()
        shot.draw(in: rect(px, py, pw, ph))
        NSGraphicsContext.restoreGraphicsState()
    }
    save(rep, out)
}

let a = CommandLine.arguments
switch a[1] {
case "icons": icons(a[2])
case "frame": frame(a[2], a[3], a[4], a[5])
default: print("usage: icons <dir> | frame <raw> <out> <line1> <line2>")
}
