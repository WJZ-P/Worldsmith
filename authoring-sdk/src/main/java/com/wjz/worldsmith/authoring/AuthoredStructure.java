package com.wjz.worldsmith.authoring;

import com.wjz.worldsmith.core.draw.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Frozen geometry with declarative metadata. No executable objects enter the sidecar. */
public record AuthoredStructure(DrawStructure drawing, Map<String,Object> semantics, Map<String,Box> components) {
    public static final String VERSION="authoring-1";
    public AuthoredStructure {
        Objects.requireNonNull(drawing);
        semantics=freezeMap(semantics);components=Collections.unmodifiableMap(new TreeMap<>(components));
        if(components.size()>128)throw new IllegalArgumentException("At most 128 named components");
        for(var e:components.entrySet())if(!e.getKey().matches("[a-zA-Z0-9_./-]{1,128}")||!drawing.bounds().contains(e.getValue().min())||!drawing.bounds().contains(e.getValue().max()))
            throw new IllegalArgumentException("Component outside drawing: "+e.getKey());
    }
    public byte[] sidecar() {
        var boxes=new TreeMap<String,Object>();components.forEach((k,v)->boxes.put(k,box(v)));
        byte[] result=json(Map.of("version",1,"semantics",semantics,"components",boxes)).getBytes(StandardCharsets.UTF_8);
        if(result.length>1024*1024)throw new IllegalArgumentException("Authored metadata exceeds 1 MiB");
        return result;
    }
    static Map<String,Object> point(Vec3i p){return Map.of("x",p.x(),"y",p.y(),"z",p.z());}
    static Map<String,Object> box(Box b){return Map.of("from",point(b.min()),"to",point(b.max()));}
    private static Map<String,Object> freezeMap(Map<String,?> input) {
        var result=new TreeMap<String,Object>();input.forEach((k,v)->result.put(Objects.requireNonNull(k),freeze(v)));return Collections.unmodifiableMap(result);
    }
    private static Object freeze(Object v) {
        if(v instanceof Map<?,?> m) {var result=new TreeMap<String,Object>();m.forEach((k,value)->result.put((String)k,freeze(value)));return Collections.unmodifiableMap(result);}
        if(v instanceof List<?> l)return l.stream().map(AuthoredStructure::freeze).toList();
        if(v instanceof String||v instanceof Boolean||v instanceof Integer||v instanceof Long)return v;
        if(v instanceof Double d&&Double.isFinite(d))return d;
        throw new IllegalArgumentException("Metadata must contain JSON values, not "+(v==null?"null":v.getClass()));
    }
    private static String json(Object v) {
        if(v instanceof Map<?,?> m)return "{"+String.join(",",m.entrySet().stream().sorted(Comparator.comparing(e->(String)e.getKey())).map(e->quote((String)e.getKey())+":"+json(e.getValue())).toList())+"}";
        if(v instanceof List<?> l)return "["+String.join(",",l.stream().map(AuthoredStructure::json).toList())+"]";
        return v instanceof String s?quote(s):v.toString();
    }
    private static String quote(String s) {
        var b=new StringBuilder("\"");
        for(char c:s.toCharArray())switch(c){case '"'->b.append("\\\"");case '\\'->b.append("\\\\");case '\n'->b.append("\\n");case '\r'->b.append("\\r");case '\t'->b.append("\\t");default->{if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}}
        return b.append('"').toString();
    }
}
