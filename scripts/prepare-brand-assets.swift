import AppKit
// Mechanical Android resource sizing from the approved full-square master artwork.
let source=NSImage(contentsOfFile:CommandLine.arguments[1])!
let root=URL(fileURLWithPath:CommandLine.arguments[2])
func save(_ path:String,_ width:Int,_ height:Int,_ mask:String="",_ banner:Bool=false)throws {
 let url=root.appendingPathComponent(path);try FileManager.default.createDirectory(at:url.deletingLastPathComponent(),withIntermediateDirectories:true)
 let bitmap=NSBitmapImageRep(bitmapDataPlanes:nil,pixelsWide:width,pixelsHigh:height,bitsPerSample:8,samplesPerPixel:4,hasAlpha:true,isPlanar:false,colorSpaceName:.deviceRGB,bytesPerRow:0,bitsPerPixel:0)!
 NSGraphicsContext.saveGraphicsState();NSGraphicsContext.current=NSGraphicsContext(bitmapImageRep:bitmap)
 let bounds=NSRect(x:0,y:0,width:width,height:height)
 if mask=="round" {NSBezierPath(ovalIn:bounds).addClip()}
 else if mask=="legacy" {NSBezierPath(roundedRect:bounds,xRadius:CGFloat(width)*0.22,yRadius:CGFloat(height)*0.22).addClip()}
 var crop=NSRect(origin:.zero,size:source.size)
 if banner {crop.size.height=source.size.width*CGFloat(height)/CGFloat(width);crop.origin.y=source.size.height*0.44-crop.height/2}
 source.draw(in:bounds,from:crop,operation:.sourceOver,fraction:1,respectFlipped:true,hints:[.interpolation:NSImageInterpolation.high])
 NSGraphicsContext.restoreGraphicsState();try bitmap.representation(using:.png,properties:[:])!.write(to:url)
}
try save("drawable-nodpi/pianke_art.png",512,512)
try save("drawable-nodpi/pianke_tv_banner.png",640,360,"",true)
for (density,size) in [("mdpi",48),("hdpi",72),("xhdpi",96),("xxhdpi",144),("xxxhdpi",192)] {
 try save("mipmap-"+density+"/ic_launcher.png",size,size,"legacy")
 try save("mipmap-"+density+"/ic_launcher_round.png",size,size,"round")
}
print("Generated UI artwork, TV banner and 10 legacy launcher density variants")
