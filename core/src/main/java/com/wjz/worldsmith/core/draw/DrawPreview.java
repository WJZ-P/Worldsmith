package com.wjz.worldsmith.core.draw;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/** Bounded CPU model preview, not game rendering, physical lighting or collision geometry. */
public final class DrawPreview {
	private DrawPreview() {}
	public static final List<String> VIEWS = List.of("isometric", "isometric_back", "front", "back", "left", "right", "top", "slice");
	public static final List<String> RENDER_MODES = List.of("material", "clay");
    static final int MAX_FACES = 250_000;
	private record Face(double[] x,double[] y,double depth,int colour) {}
	private record PreviewCell(DrawBlock block,int shape,List<DrawPreviewShapes.Part> parts) {}
	public record Marker(Vec3i position,int colour,String label) {}
    public static byte[] png(DrawStructure drawing,String view,Integer sliceY) throws IOException {
        return png(drawing,view,sliceY,drawing.bounds(),List.of());
    }
    public static byte[] png(DrawStructure drawing,String view,Integer sliceY,Box frame,List<Marker> markers) throws IOException {
        return png(drawing,view,sliceY,frame,markers,"material");
    }
    public static byte[] png(DrawStructure drawing,String view,Integer sliceY,Box frame,List<Marker> markers,String renderMode) throws IOException {
        return png(drawing,view,sliceY,frame,markers,renderMode,Map.of());
    }
    /** RGB overrides are verified average material colours, not texture sampling or a game-rendering claim. */
    public static byte[] png(DrawStructure drawing,String view,Integer sliceY,Box frame,List<Marker> markers,String renderMode,Map<String,Integer> colours) throws IOException {
        if(colours.size()>256 || colours.values().stream().anyMatch(c->c==null||c<0||c>0xffffff))throw new IllegalArgumentException("Invalid preview material colours");
		if(!VIEWS.contains(view))throw new IllegalArgumentException("Unknown preview view");
		if(!RENDER_MODES.contains(renderMode))throw new IllegalArgumentException("Unknown preview renderMode");
		if(view.equals("slice")&&(sliceY==null||sliceY<drawing.bounds().min().y()||sliceY>drawing.bounds().max().y()))throw new IllegalArgumentException("sliceY must be inside drawing bounds");
		var occupied=new HashMap<Vec3i,PreviewCell>();
        var shapeCache=new HashMap<DrawBlock,PreviewCell>();
        var drawn=new HashMap<Vec3i,DrawBlock>();
		for(var v:drawing.voxels())if(!v.block().state().isAir()&&(!view.equals("slice")||v.position().y()==sliceY))drawn.put(v.position(),v.block());
        int[][] sides={{0,-1},{1,0},{0,1},{-1,0}};
        for(var v:drawn.entrySet()) {
            var p=v.getKey();var block=v.getValue();
            PreviewCell cell;
            if(DrawPreviewShapes.joinsNeighbours(block.state())) {
                // Written without connections, as a window's panes are: join what the world would join.
                var joined=DrawPreviewShapes.joined(block,s->drawn.containsKey(new Vec3i(p.x()+sides[s][0],p.y(),p.z()+sides[s][1])));
                cell=new PreviewCell(block,0,DrawPreviewShapes.parts(joined));
            } else {
                cell=shapeCache.get(block);
                if(cell==null) {
                    // Thin blocks are drawn from their model boxes and hide no neighbouring face.
                    var parts=DrawPreviewShapes.parts(block);
                    cell=new PreviewCell(block,parts.isEmpty()?DrawPreviewShapes.mask(block):0,parts);
                    if(shapeCache.size()<1024)shapeCache.put(block,cell);
                }
            }
            occupied.put(p,cell);
        }
		double yaw=switch(view) {
            case "back" -> Math.PI;
            case "left" -> -Math.PI/2;
            case "right" -> Math.PI/2;
            case "isometric" -> Math.PI/4;
            case "isometric_back" -> Math.PI*5/4;
            default -> 0;
        };
		double elevation=(view.equals("slice")||view.equals("top"))?Math.PI/2:view.startsWith("isometric")?Math.PI/6:0;
		double[] right={Math.cos(yaw),0,Math.sin(yaw)}, down={Math.sin(yaw)*Math.sin(elevation),-Math.cos(elevation),-Math.cos(yaw)*Math.sin(elevation)}, near={Math.sin(yaw)*Math.cos(elevation),Math.sin(elevation),-Math.cos(yaw)*Math.cos(elevation)};
		var faces=new ArrayList<Face>();
		for(var entry:occupied.entrySet()) {
			var p=entry.getKey();double[] centre={p.x(),p.y(),p.z()};
			for(int axis=0;axis<3;axis++) {
				if(Math.abs(near[axis])<1e-8)continue;int sign=near[axis]>0?1:-1;
				long nx=(long)p.x()+(axis==0?sign:0),ny=(long)p.y()+(axis==1?sign:0),nz=(long)p.z()+(axis==2?sign:0);
                var neighbor=nx>=Integer.MIN_VALUE&&nx<=Integer.MAX_VALUE&&ny>=Integer.MIN_VALUE&&ny<=Integer.MAX_VALUE&&nz>=Integer.MIN_VALUE&&nz<=Integer.MAX_VALUE?
                    occupied.get(new Vec3i((int)nx,(int)ny,(int)nz)):null;
                int neighborShape=neighbor==null?0:neighbor.shape();
                int u=(axis+1)%3,v=(axis+2)%3;
                for(var surface:DrawPreviewShapes.surfaces(entry.getValue().shape(),axis,sign,neighborShape)) {
                    if(faces.size()>=MAX_FACES)throw new IllegalArgumentException("Isometric face budget exceeded; request front/back or a slice of this same drawing");
                    // Preserve half-grid collinear edge points. A full edge beside two short edges
                    // otherwise becomes a raster T-junction and can leak a one-pixel seam after scaling.
                    // This stays one face (4..8 vertices), with no face inflation or expanded coverage.
                    int perimeter=2*(surface.width()+surface.height());
                    double[] xs=new double[perimeter],ys=new double[perimeter];double depth=0;
                    for(int i=0;i<perimeter;i++) {
                        int du,dv;
                        if(i<surface.width()){du=i;dv=0;}
                        else if(i<surface.width()+surface.height()){du=surface.width();dv=i-surface.width();}
                        else if(i<2*surface.width()+surface.height()){du=2*surface.width()+surface.height()-i;dv=surface.height();}
                        else {du=0;dv=perimeter-i;}
                        double[] point=centre.clone();
                        point[axis]+=surface.plane()*.5-.5;
                        point[u]+=(surface.u()+du)*.5-.5;
                        point[v]+=(surface.v()+dv)*.5-.5;
                        xs[i]=dot(point,right);ys[i]=dot(point,down);depth+=dot(point,near)/perimeter;
                    }
                    var state=entry.getValue().block().state();
                    int baseColour=renderMode.equals("clay")?0xC3C7CC:colours.getOrDefault(state.id(),colour(state.id()));
                    // Fixed frame and actual partial-block top keep before/after height shading comparable.
                    if(renderMode.equals("clay")&&view.equals("top")) {
                        double surfaceY=p.y()+surface.plane()*.5-.5;
                        double height=Math.max(0,Math.min(1,(surfaceY-(double)frame.min().y())/frame.height()));
                        baseColour=shade(baseColour,.65+.35*height);
                    }
                    faces.add(new Face(xs,ys,depth,shade(baseColour,axis==1?1.0:axis==0?.77:.9)));
                }
			}
            var state=entry.getValue().block().state();
            int partColour=renderMode.equals("clay")?0xC3C7CC:colours.getOrDefault(state.id(),colour(state.id()));
            for(var part:entry.getValue().parts()) {
                double[] lo={part.x0()/16.0-.5,part.y0()/16.0-.5,part.z0()/16.0-.5},hi={part.x1()/16.0-.5,part.y1()/16.0-.5,part.z1()/16.0-.5};
                for(int axis=0;axis<3;axis++) {
                    if(Math.abs(near[axis])<1e-8)continue;
                    if(faces.size()>=MAX_FACES)throw new IllegalArgumentException("Isometric face budget exceeded; request front/back or a slice of this same drawing");
                    int u=(axis+1)%3,v=(axis+2)%3;
                    double[] xs=new double[4],ys=new double[4];double depth=0;
                    for(int i=0;i<4;i++) {
                        double[] point=centre.clone();
                        point[axis]+=near[axis]>0?hi[axis]:lo[axis];
                        point[u]+=(i==1||i==2)?hi[u]:lo[u];
                        point[v]+=(i>=2)?hi[v]:lo[v];
                        xs[i]=dot(point,right);ys[i]=dot(point,down);depth+=dot(point,near)/4;
                    }
                    faces.add(new Face(xs,ys,depth,shade(partColour,axis==1?1.0:axis==0?.77:.9)));
                }
            }
		}
		faces.sort(Comparator.comparingDouble(Face::depth).thenComparingDouble(f->f.x[0]).thenComparingDouble(f->f.y[0]));
        // Antialiasing each polygon separately blends shared edges with previously drawn background
        // or interior faces. Render exact adjoining coverage without AA, then filter the complete image.
        // Fixed 2x supersampling stays bounded and does not expand faces across real half-block gaps.
		var image=new BufferedImage(2560,2000,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();
        g.scale(2,2);
		g.setColor(new Color(0x18242D));g.fillRect(0,0,1280,1000);g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_OFF);
		if(!faces.isEmpty()) {
			double minX=Double.POSITIVE_INFINITY,maxX=-minX,minY=minX,maxY=-minX;
			for(double x:new double[]{frame.min().x()-.5,frame.max().x()+.5})for(double y:new double[]{frame.min().y()-.5,frame.max().y()+.5})for(double z:new double[]{frame.min().z()-.5,frame.max().z()+.5}) {
                double[] point={x,y,z};double px=dot(point,right),py=dot(point,down);
                minX=Math.min(minX,px);maxX=Math.max(maxX,px);minY=Math.min(minY,py);maxY=Math.max(maxY,py);
            }
			double scale=Math.min(1160/Math.max(1,maxX-minX),850/Math.max(1,maxY-minY));
            for(var face:faces) {
                var polygon=new java.awt.geom.Path2D.Double();
                for(int i=0;i<face.x.length;i++) {
                    double x=640+(face.x[i]-(minX+maxX)/2)*scale,y=485+(face.y[i]-(minY+maxY)/2)*scale;
                    if(i==0)polygon.moveTo(x,y);else polygon.lineTo(x,y);
                }
                polygon.closePath();g.setColor(new Color(face.colour));g.fill(polygon);
            }
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            int labels=0;g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,12));
            for(var marker:markers.stream().limit(4096).toList()) {
                var p=marker.position();double[] point={p.x(),p.y(),p.z()};
                if(view.equals("slice")&&p.y()!=sliceY)continue;
                int x=(int)Math.round(640+(dot(point,right)-(minX+maxX)/2)*scale),y=(int)Math.round(485+(dot(point,down)-(minY+maxY)/2)*scale);
                g.setColor(new Color(marker.colour()));g.fillOval(x-3,y-3,6,6);
                if(!marker.label().isEmpty()&&labels++<24)g.drawString(marker.label(),x+5,y-4);
            }
		}
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0xD6E3E8));g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,18));
		g.drawString("Worldsmith | "+view+" | "+renderMode+" | "+occupied.size()+" selected non-air cells",40,950);
		g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,14));g.drawString("Offline model; vanilla slabs, stairs, trapdoors, carpets and thin blocks shaped, other blocks as cubes. Not an in-game screenshot."+(renderMode.equals("clay")&&view.equals("top")?" Top shade indicates height.":""),40,978);g.dispose();
        var output=new BufferedImage(1280,1000,BufferedImage.TYPE_INT_RGB);var composite=output.createGraphics();
        composite.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        composite.drawImage(image,0,0,1280,1000,null);composite.dispose();
		var bytes=new ByteArrayOutputStream();ImageIO.write(output,"png",bytes);return bytes.toByteArray();
	}
	private static double dot(double[] a,double[] b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];}
	private static int shade(int rgb,double amount){return ((int)(((rgb>>16)&255)*amount)<<16)|((int)(((rgb>>8)&255)*amount)<<8)|(int)((rgb&255)*amount);}
	/**
	 * Light sources stay bright so a preview shows where the light is; other vanilla
	 * blocks use their measured texture average; the keyword guesses remain for
	 * tinted and modded blocks.
	 */
	private static int colour(String id) {
		if(id.contains("lantern")||id.contains("glowstone"))return 0xF5CE73;
		Integer measured=DrawPreviewColours.average(id);
		if(measured!=null)return measured;
		if(id.contains("oxidized")||id.contains("prismarine"))return 0x548F87;
		if(id.contains("quartz")||id.contains("calcite")||id.contains("white_"))return 0xE3DFCF;
		if(id.contains("deepslate")||id.contains("blackstone"))return 0x3B4651;
		if(id.contains("glass"))return id.contains("blue")?0x648CAE:0xAFD2D4;
		if(id.contains("leaves")||id.contains("moss")||id.contains("grass"))return 0x749553;
		if(id.contains("dark_oak")||id.contains("spruce"))return 0x715440;
		if(id.contains("planks")||id.contains("log"))return 0xB89566;
		if(id.contains("gold"))return 0xD6B54A;
		if(id.contains("red_"))return 0xA75C50;
		if(id.contains("stone")||id.contains("andesite"))return 0x9C9F99;
		return Color.HSBtoRGB(Math.floorMod(id.hashCode(),360)/360f,.25f,.7f)&0xffffff;
	}
}
