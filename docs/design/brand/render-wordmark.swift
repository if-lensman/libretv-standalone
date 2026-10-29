import AppKit
import CoreText

let output = URL(fileURLWithPath:CommandLine.arguments[1])
func outlines(_ text:String, size:CGFloat, name:String) -> CGPath {
 let font=CTFontCreateWithName(name as CFString,size,nil)
 let attr=NSAttributedString(string:text,attributes:[NSAttributedString.Key(kCTFontAttributeName as String):font])
 let line=CTLineCreateWithAttributedString(attr)
 let result=CGMutablePath()
 for run in CTLineGetGlyphRuns(line) as! [CTRun] {
  let count=CTRunGetGlyphCount(run)
  var glyphs=[CGGlyph](repeating:0,count:count),positions=[CGPoint](repeating:.zero,count:count)
  CTRunGetGlyphs(run,CFRange(location:0,length:0),&glyphs)
  CTRunGetPositions(run,CFRange(location:0,length:0),&positions)
  let rf=(CTRunGetAttributes(run) as NSDictionary)[kCTFontAttributeName] as! CTFont
  for i in 0..<count {if let p=CTFontCreatePathForGlyph(rf,glyphs[i],nil) {result.addPath(p,transform:CGAffineTransform(translationX:positions[i].x,y:positions[i].y))}}
 }
 return result
}
func n(_ x:CGFloat)->String {String(format:"%.2f",Double(x))}
func svgPath(_ p:CGPath)->String {
 var s=""
 p.applyWithBlock { ptr in let e=ptr.pointee;let v=e.points
  switch e.type {
  case .moveToPoint:s+="M\(n(v[0].x)),\(n(v[0].y))"
  case .addLineToPoint:s+="L\(n(v[0].x)),\(n(v[0].y))"
  case .addQuadCurveToPoint:s+="Q\(n(v[0].x)),\(n(v[0].y)) \(n(v[1].x)),\(n(v[1].y))"
  case .addCurveToPoint:s+="C\(n(v[0].x)),\(n(v[0].y)) \(n(v[1].x)),\(n(v[1].y)) \(n(v[2].x)),\(n(v[2].y))"
  case .closeSubpath:s+="Z"
  @unknown default:break
  }
 }
 return s
}
let letters=outlines("片刻",size:250,name:"PingFangSC-Semibold")
let box=letters.boundingBoxOfPath
let slogan=outlines("留点片刻，看点喜欢的",size:30,name:"PingFangSC-Regular")
let sb=slogan.boundingBoxOfPath
let defs="""
<defs><linearGradient id="ink" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#FF93BF"/><stop offset="0.55" stop-color="#F15B9B"/><stop offset="1" stop-color="#E93C83"/></linearGradient></defs>
"""
// Slight forward lean, tight spacing, and two short horizon strokes echo a passing moment.
let mark="""
<g transform="translate(54 280) skewX(-7) scale(1 -1)"><path fill="url(#ink)" d="\(svgPath(letters))"/></g>
<path d="M66 315H164 M181 315H212" stroke="#F15B9B" stroke-width="7" stroke-linecap="round"/>
"""
let standalone="""
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 620 355" role="img" aria-label="片刻矢量字标"><title>片刻</title>\(defs)\(mark)</svg>
"""
try standalone.write(to:output.appendingPathComponent("pianke-wordmark.svg"),atomically:true,encoding:.utf8)
let card="""
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1440 900" role="img" aria-label="片刻，留点片刻，看点喜欢的"><title>片刻 · 留点片刻，看点喜欢的</title>\(defs)
<rect width="1440" height="900" rx="32" fill="#101723"/>
<path d="M104 90H1336" stroke="#293449"/>
<g transform="translate(410 140)">\(mark)</g>
<g transform="translate(\(n((1440-sb.width)/2-sb.minX)) 578) scale(1 -1)"><path fill="#D7DFEB" d="\(svgPath(slogan))"/></g>
<path d="M104 780H1336" stroke="#293449"/>
</svg>
"""
try card.write(to:output.appendingPathComponent("pianke-brand-card.svg"),atomically:true,encoding:.utf8)
print("Exported two SVGs with outlined glyphs, no font dependency. Glyph bounds: \(box)")
let bitmap=NSBitmapImageRep(bitmapDataPlanes:nil,pixelsWide:1440,pixelsHigh:900,bitsPerSample:8,samplesPerPixel:4,hasAlpha:true,isPlanar:false,colorSpaceName:.deviceRGB,bytesPerRow:0,bitsPerPixel:0)!
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current=NSGraphicsContext(bitmapImageRep:bitmap)
func color(_ h:UInt32)->NSColor {NSColor(srgbRed:CGFloat((h>>16)&255)/255,green:CGFloat((h>>8)&255)/255,blue:CGFloat(h&255)/255,alpha:1)}
color(0x101723).setFill();NSBezierPath(rect:NSRect(x:0,y:0,width:1440,height:900)).fill()
color(0x293449).setStroke()
let lines=NSBezierPath();lines.move(to:NSPoint(x:104,y:810));lines.line(to:NSPoint(x:1336,y:810));lines.move(to:NSPoint(x:104,y:120));lines.line(to:NSPoint(x:1336,y:120));lines.stroke()
let cg=NSGraphicsContext.current!.cgContext
cg.saveGState();cg.concatenate(CGAffineTransform(a:1,b:0,c:0.12278,d:1,tx:464,ty:480));cg.addPath(letters);cg.clip()
let colors=[color(0xFF93BF).cgColor,color(0xF15B9B).cgColor,color(0xE93C83).cgColor] as CFArray
let gradient=CGGradient(colorsSpace:CGColorSpaceCreateDeviceRGB(),colors:colors,locations:[0,0.55,1])!
cg.drawLinearGradient(gradient,start:CGPoint(x:0,y:240),end:CGPoint(x:520,y:0),options:[.drawsBeforeStartLocation,.drawsAfterEndLocation]);cg.restoreGState()
color(0xF15B9B).setStroke();let ticks=NSBezierPath();ticks.lineWidth=7;ticks.lineCapStyle = .round;ticks.move(to:NSPoint(x:476,y:445));ticks.line(to:NSPoint(x:574,y:445));ticks.move(to:NSPoint(x:591,y:445));ticks.line(to:NSPoint(x:622,y:445));ticks.stroke()
cg.saveGState();cg.translateBy(x:(1440-sb.width)/2-sb.minX,y:322);cg.addPath(slogan);cg.setFillColor(color(0xD7DFEB).cgColor);cg.fillPath();cg.restoreGState()
NSGraphicsContext.restoreGraphicsState()
try bitmap.representation(using:.png,properties:[:])!.write(to:output.appendingPathComponent("pianke-brand-card.png"))
if CommandLine.arguments.count > 2 {
 var t=CGAffineTransform(a:1,b:0,c:0.12278,d:-1,tx:54,ty:280)
 let flat=letters.copy(using:&t)!
 let xml="""
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="124dp" android:height="71dp" android:viewportWidth="620" android:viewportHeight="355">
<path android:fillColor="#F15B9B" android:pathData="\(svgPath(flat))"/>
<path android:strokeColor="#F15B9B" android:strokeWidth="7" android:strokeLineCap="round" android:pathData="M66,315H164 M181,315H212"/>
</vector>
"""
 try xml.write(toFile:CommandLine.arguments[2],atomically:true,encoding:.utf8)
}
