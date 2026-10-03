#!/usr/bin/env python3
"""Isolated OOXML rotation probes opened by actual Office; never application output.

Requires LibreOffice and PyMuPDF. This tests editable frame rotation only;
scan-media conservation is checked separately by the HTTP acceptance matrix.
"""
import argparse
import json
import math
from pathlib import Path
import subprocess
import zipfile
import fitz

W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
A = 'http://schemas.openxmlformats.org/drawingml/2006/main'
WP = 'http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing'
WPS = 'http://schemas.microsoft.com/office/word/2010/wordprocessingShape'
VALUE = 'Rotation probe 59028'
CONTENT = (f'<w:txbxContent xmlns:w="{W}"><w:p><w:pPr><w:spacing w:before="0" w:after="0"/>'
           '<w:jc w:val="left"/></w:pPr><w:r><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/>'
           f'<w:sz w:val="36"/><w:color w:val="000000"/></w:rPr><w:t>{VALUE}</w:t></w:r></w:p></w:txbxContent>')


def shape(kind, angle):
    if kind == 'vml':
        return (f'<w:pict><v:rect xmlns:v="urn:schemas-microsoft-com:vml" style="position:absolute;'
                f'margin-left:85.039pt;margin-top:113.386pt;width:283.465pt;height:56.693pt;rotation:{angle};'
                'mso-position-horizontal-relative:page;mso-position-vertical-relative:page" filled="f" stroked="f">'
                f'<v:textbox inset="0,0,0,0">{CONTENT}</v:textbox></v:rect></w:pict>')
    warp = '<a:prstTxWarp prst="textPlain"><a:avLst/></a:prstTxWarp>' if kind == 'wordart' else ''
    return (f'<w:drawing><wp:anchor xmlns:wp="{WP}" xmlns:a="{A}" xmlns:wps="{WPS}" distT="0" distB="0" '
            'distL="0" distR="0" simplePos="0" relativeHeight="2" behindDoc="0" locked="0" layoutInCell="1" '
            'allowOverlap="1"><wp:simplePos x="0" y="0"/><wp:positionH relativeFrom="page"><wp:posOffset>1080000</wp:posOffset>'
            '</wp:positionH><wp:positionV relativeFrom="page"><wp:posOffset>1440000</wp:posOffset></wp:positionV>'
            '<wp:extent cx="3600000" cy="720000"/><wp:effectExtent l="0" t="0" r="0" b="0"/><wp:wrapNone/>'
            f'<wp:docPr id="1" name="probe"/><wp:cNvGraphicFramePr/><a:graphic><a:graphicData uri="{WPS}"><wps:wsp>'
            f'<wps:cNvSpPr/><wps:spPr><a:xfrm rot="{angle*60000}"><a:off x="0" y="0"/><a:ext cx="3600000" cy="720000"/>'
            '</a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/><a:ln><a:noFill/></a:ln></wps:spPr>'
            f'<wps:txbx>{CONTENT}</wps:txbx><wps:bodyPr lIns="0" tIns="0" rIns="0" bIns="0" anchor="t" '
            f'fromWordArt="{int(kind=="wordart")}" upright="0">{warp}<a:noAutofit/></wps:bodyPr></wps:wsp>'
            '</a:graphicData></a:graphic></wp:anchor></w:drawing>')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--office', default='soffice')
    args = parser.parse_args(); out = args.out.resolve(); out.mkdir(parents=True, exist_ok=True)
    report = {'officeVersion': subprocess.check_output([args.office,'--version'],text=True).strip(),
              'pymupdf':fitz.VersionBind, 'sourceText': VALUE, 'cases':[]}
    for kind, angle in [('vml',0),('vml',6),('wps',6),('wps',-6),('wordart',6)]:
        name = f'{kind}-{angle:+d}'; source = out/(name+'.docx')
        document = (f'<w:document xmlns:w="{W}"><w:body><w:p><w:r>{shape(kind,angle)}</w:r></w:p>'
                    '<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="0" w:right="0" w:bottom="0" '
                    'w:left="0" w:header="0" w:footer="0"/></w:sectPr></w:body></w:document>')
        with zipfile.ZipFile(source,'w',zipfile.ZIP_DEFLATED) as z:
            z.writestr('[Content_Types].xml','<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
                       '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
                       '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
            z.writestr('_rels/.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                       '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
            z.writestr('word/document.xml',document)
        subprocess.run([args.office, '-env:UserInstallation='+(out/('profile-'+name)).as_uri(), '--headless',
                        '--convert-to','pdf','--outdir',str(out),str(source)],check=True,capture_output=True,timeout=45)
        with fitz.open(out/(name+'.pdf')) as pdf:
            page=pdf[0]; text=page.get_text(); directions=[]
            for block in page.get_text('dict')['blocks']:
                for line in block.get('lines',[]):
                    directions.append(round(math.degrees(math.atan2(line['dir'][1],line['dir'][0])),3))
            page.get_pixmap(matrix=fitz.Matrix(1.5,1.5)).save(out/(name+'.png'))
            report['cases'].append({'kind':kind,'requestedDegrees':angle,'pages':len(pdf),
                'text':text,'pdfBaselineDegrees':directions,'searchableTextMatches':text.strip()==VALUE,
                'drawingPaths':len(page.get_drawings())})
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))


if __name__=='__main__':main()
