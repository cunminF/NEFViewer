import Foundation

enum RatingStore {
    /// 把项目内所有已评分照片的评分写成 XMP sidecar（与 NEF 同名同目录）。
    /// 返回导出的文件数。
    @discardableResult
    static func exportXMP(project: Project) throws -> Int {
        var count = 0
        for photo in project.photos where photo.rating > 0 {
            let nefURL = photo.absoluteURL(under: project.rootURL)
            let xmpURL = nefURL.deletingPathExtension().appendingPathExtension("xmp")
            try xmpContent(rating: photo.rating).write(to: xmpURL, atomically: true, encoding: .utf8)
            count += 1
        }
        return count
    }

    static func xmpContent(rating: Int) -> String {
        """
        <?xpacket begin="\u{FEFF}" id="W5M0MpCehiHzreSzNTczkc9d"?>
        <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="NEF Viewer">
         <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
          <rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/">
           <xmp:Rating>\(rating)</xmp:Rating>
          </rdf:Description>
         </rdf:RDF>
        </x:xmpmeta>
        <?xpacket end="w"?>
        """
    }
}
