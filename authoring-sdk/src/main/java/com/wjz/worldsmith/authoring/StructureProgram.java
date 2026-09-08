package com.wjz.worldsmith.authoring;

/** Optional authoring entry point; world placement and assembly are not part of it. */
public interface StructureProgram {
    AuthoredStructure generate(AuthoringContext context) throws Exception;
}
