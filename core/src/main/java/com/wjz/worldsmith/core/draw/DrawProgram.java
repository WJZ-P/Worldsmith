package com.wjz.worldsmith.core.draw;

/** Authoring entry point. Loaded only by the independent drawing worker, never world generation. */
public interface DrawProgram {
	DrawStructure generate(DrawContext context) throws Exception;
}
