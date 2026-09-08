package com.wjz.worldsmith.worker;

import com.wjz.worldsmith.core.draw.*;
import com.wjz.worldsmith.authoring.*;
import org.eclipse.jdt.internal.compiler.tool.EclipseCompiler;
import javax.tools.*;
import java.io.*;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Compiler/generator subprocess. The parent owns deadlines, cancellation and approval. */
public final class DrawWorkerMain {
	public static final String COMPILER_VERSION = "ecj-3.46.0";
	public static void main(String[] args) throws Exception {
		Path root = Path.of(args[1]).toAbsolutePath().normalize();
		var request = new Properties(); try (var in=Files.newBufferedReader(root.resolve("request.properties"), StandardCharsets.UTF_8)) { request.load(in); }
		if (args[0].equals("compile")) {
			var compiler = new EclipseCompiler(); var diagnostics = new DiagnosticCollector<JavaFileObject>();
			Files.createDirectories(root.resolve("classes"));
			try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
				List<File> sources;
				try (var stream = Files.walk(root.resolve("sources"))) { sources=stream.filter(p->p.toString().endsWith(".java")).sorted().map(Path::toFile).toList(); }
                var options=List.of("-source","21","-target","21","-proc:none","-encoding","UTF-8","-classpath",request.getProperty("sdk")+File.pathSeparator+request.getProperty("authoring"),"-d",root.resolve("classes").toString());
				boolean success=compiler.getTask(new PrintWriter(System.err),manager,diagnostics,options,null,manager.getJavaFileObjectsFromFiles(sources)).call();
				var output=new Properties(); int i=0;
				for (var d:diagnostics.getDiagnostics()) {
					if(i>=100)break; String p="diagnostic."+(i++)+".";
					output.setProperty(p+"file",d.getSource()==null?"":Path.of(d.getSource().toUri()).getFileName().toString());
					output.setProperty(p+"line",Long.toString(d.getLineNumber())); output.setProperty(p+"column",Long.toString(d.getColumnNumber()));
					output.setProperty(p+"severity",d.getKind().name()); output.setProperty(p+"message",d.getMessage(Locale.ROOT));
				}
				output.setProperty("count",Integer.toString(i)); try(var writer=Files.newBufferedWriter(root.resolve("diagnostics.properties"),StandardCharsets.UTF_8)){output.store(writer,null);}
				if(!success)System.exit(2);
			}
		} else if(args[0].equals("generate")) {
			var params=new TreeMap<String,String>(); for(var key:request.stringPropertyNames())if(key.startsWith("param."))params.put(key.substring(6),request.getProperty(key));
			String[] seeds=request.getProperty("seeds").split(",");
			for(int i=0;i<seeds.length;i++) {
				try(var loader=new URLClassLoader(new java.net.URL[]{root.resolve("classes").toUri().toURL()},DrawProgram.class.getClassLoader())) {
                    var program=loader.loadClass(request.getProperty("entryClass")).getDeclaredConstructor().newInstance();
                    var context=new DrawContext(Long.parseLong(seeds[i]),params,DrawLimits.DEFAULT);DrawStructure drawing;
                    if(program instanceof StructureProgram authored) {
                        var result=Objects.requireNonNull(authored.generate(new AuthoringContext(context)),"Program returned null");drawing=result.drawing();
                        Files.write(root.resolve("result-"+i+".authoring.json"),result.sidecar());
                    } else if(program instanceof DrawProgram legacy)drawing=Objects.requireNonNull(legacy.generate(context),"Program returned null");
                    else throw new IllegalArgumentException("Entry must implement DrawProgram or StructureProgram");
					Files.write(root.resolve("result-"+i+".wsdraw"),DrawSnapshotCodec.encode(drawing));
				}
			}
			Files.writeString(root.resolve("done"),Integer.toString(seeds.length),StandardCharsets.UTF_8);
		} else throw new IllegalArgumentException("Unknown worker phase");
	}
}
