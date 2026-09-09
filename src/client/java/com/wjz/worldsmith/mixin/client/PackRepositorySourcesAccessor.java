package com.wjz.worldsmith.mixin.client;

import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.RepositorySource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.Set;

/** Used exclusively on Minecraft's client resource repository, never the server data repository. */
@Mixin(PackRepository.class)
public interface PackRepositorySourcesAccessor {
    @Accessor("sources") Set<RepositorySource> worldsmith$getSources();
    @Mutable @Accessor("sources") void worldsmith$setSources(Set<RepositorySource> sources);
}
