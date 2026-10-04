package com.fuyue.formatconverter.task;

import org.apache.pdfbox.text.TextPosition;
import java.util.*;

/** Restore only encoded spaces lost by PDFBox's visual sort, for TXT parsing. */
final class PdfExplicitSpaces {
    private final int limit;
    private final Map<TextPosition, Gap> gaps = new IdentityHashMap<>();
    private final List<TextPosition> pending = new ArrayList<>();
    private TextPosition previous;
    private boolean disabled;

    PdfExplicitSpaces(int maxEntries) { limit = Math.min(Math.max(0, maxEntries), 4096); }
    void reset(boolean unsupportedPage) { gaps.clear();pending.clear();previous=null;disabled=unsupportedPage || limit==0; }
    void disable() { reset(true); }

    void record(TextPosition position) {
        if (disabled) return;
        String value=position.getUnicode();
        if (" ".equals(value)) {
            if (previous!=null && pending.size()<8) pending.add(position);
            else { pending.clear();previous=null; }
            return;
        }
        if (previous!=null && !pending.isEmpty() && eligible(previous,position,pending)) {
            if (gaps.size()==limit) { disable();return; }
            gaps.put(position,new Gap(previous,List.copyOf(pending)));
        }
        previous=position;pending.clear();
    }

    Text restore(List<TextPosition> emitted) {
        // Optional recovery must stay bounded even for unusually long native
        // text lines. Leave the legacy output unchanged beyond this budget.
        if (disabled || gaps.isEmpty() || emitted.size()>100_000) return Text.EMPTY;
        Map<TextPosition,Integer> indices=new IdentityHashMap<>();
        for (int i=0;i<emitted.size();i++) indices.put(emitted.get(i),i);
        Set<TextPosition> skip=Collections.newSetFromMap(new IdentityHashMap<>());
        Map<TextPosition,String> suffix=new IdentityHashMap<>();TextPosition last=null;
        for (TextPosition position:emitted) {
            if (position==null) continue;
            if (" ".equals(position.getUnicode())) continue;
            Gap gap=gaps.get(position);
            if (gap!=null && gap.before()==last) {
                // Correct order already present needs no intervention. Missing
                // or visually displaced source spaces are restored at their
                // original adjacent character boundary, never inferred by gap.
                int beforeIndex=indices.get(gap.before()),afterIndex=indices.get(position);
                boolean ordered=gap.spaces().stream().allMatch(s->{
                    Integer i=indices.get(s);return i!=null && i>beforeIndex && i<afterIndex;
                });
                if (!ordered) {
                    skip.addAll(gap.spaces());suffix.put(gap.before()," ".repeat(gap.spaces().size()));
                }
            }
            last=position;
        }
        return suffix.isEmpty()?Text.EMPTY:new Text(skip,suffix);
    }

    private boolean eligible(TextPosition before,TextPosition after,List<TextPosition> spaces) {
        if (!endpoint(before.getUnicode()) || !endpoint(after.getUnicode())
                || before.getDir()!=0 || after.getDir()!=0) return false;
        double y=before.getYDirAdj(),height=Math.max(before.getHeightDir(),after.getHeightDir());
        double x=before.getXDirAdj(),right=x+before.getWidthDirAdj(),next=after.getXDirAdj(),end=next+after.getWidthDirAdj();
        if (!Double.isFinite(x+right+next+end+y+height) || height<=0 || next<=x
                || Math.abs(after.getYDirAdj()-y)>.1d) return false;
        double width=spaces.stream().mapToDouble(TextPosition::getWidthDirAdj).sum();
        if (!Double.isFinite(width) || width<=0 || width>height*2d
                || next-right < -width || next-right > width*2d) return false;
        return spaces.stream().allMatch(s->s.getDir()==0 && Math.abs(s.getYDirAdj()-y)<=.1d
                && Float.isFinite(s.getXDirAdj()+s.getWidthDirAdj())
                && s.getWidthDirAdj()>0 && s.getXDirAdj()>=x && s.getXDirAdj()<=end);
    }

    private boolean endpoint(String text) {
        if (text==null || text.codePointCount(0,text.length())!=1) return false;
        int cp=text.codePointAt(0);
        return cp<128 && Character.isLetterOrDigit(cp) || Character.UnicodeScript.of(cp)==Character.UnicodeScript.HAN;
    }
    private record Gap(TextPosition before,List<TextPosition> spaces) { }
    record Text(Set<TextPosition> skip,Map<TextPosition,String> suffix) {
        static final Text EMPTY=new Text(Set.of(),Map.of());
        String value(List<TextPosition> positions) {
            StringBuilder text=new StringBuilder();
            for (TextPosition p:positions) {
                if (!skip.contains(p)) text.append(p.getUnicode());
                text.append(suffix.getOrDefault(p,""));
            }
            return text.toString();
        }
    }
}
