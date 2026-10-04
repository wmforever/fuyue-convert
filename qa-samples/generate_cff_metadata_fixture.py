#!/usr/bin/env python3
"""Build tiny original CID-CFF fonts for metadata tests; no third-party glyphs."""
import base64,io,json,pathlib
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.t2CharStringPen import T2CharStringPen
from fontTools.cffLib import FDArrayIndex,FontDict,FDSelect

def program(weight,angle,family):
    builder=FontBuilder(1000,isTTF=False)
    names=['.notdef','cid00001'];builder.setupGlyphOrder(names)
    glyphs={}
    for name in names:
        pen=T2CharStringPen(600,None)
        if name!='.notdef':
            pen.moveTo((80,0));pen.lineTo((300,700));pen.lineTo((520,0));pen.closePath()
        glyphs[name]=pen.getCharString()
    info={'FullName':'QA Metadata Face','Weight':weight,'ItalicAngle':angle}
    if family:info['FamilyName']='QA Embedded CJK'
    builder.setupCFF('QAMetadataFace',info,glyphs,{})
    cff=builder.font['CFF '].cff;top=cff.topDictIndex[0]
    top.ROS=('Adobe','Identity',0);top.CIDCount=2
    fd=FontDict();fd.Private=top.Private;fd.FontName='QAMetadataFace'
    top.FDArray=FDArrayIndex();top.FDArray.append(fd)
    top.FDSelect=FDSelect(format=3);top.FDSelect.gidArray=[0,0]
    top.CharStrings.fdArray=top.FDArray;top.CharStrings.fdSelect=top.FDSelect
    del top.Private
    out=io.BytesIO();cff.compile(out,builder.font)
    return base64.b64encode(out.getvalue()).decode()

def main():
    data={name:program(*args) for name,args in {'regular':('Regular',0,True),'boldItalic':('Bold',-12,True),'missingFamily':('Regular',0,False)}.items()}
    path=pathlib.Path(__file__).resolve().parents[1]/'task-service/src/test/resources/fonts/qa-cid-cff.json'
    path.write_text(json.dumps(data,indent=2)+'\n');print(path)
if __name__=='__main__':main()
