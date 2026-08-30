package com.wjz.worldsmith.mixin.client;

import com.mojang.datafixers.util.Pair;
import java.nio.file.Path;
import java.util.function.Consumer;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.WorldDataConfiguration;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Narrow access to Minecraft's own temporary data-pack workflow. */
@Mixin(CreateWorldScreen.class)
public interface CreateWorldScreenAccessor {
	@Accessor("uiState")
	WorldCreationUiState worldsmith$getUiState();

	@Accessor("tempDataPackRepository")
	@Nullable PackRepository worldsmith$getTempDataPackRepository();

	@Invoker("getOrCreateTempDataPackDir")
	@Nullable Path worldsmith$getOrCreateTempDataPackDir();

	@Invoker("getDataPackSelectionSettings")
	@Nullable Pair<Path, PackRepository> worldsmith$getDataPackSelectionSettings(WorldDataConfiguration configuration);

	@Invoker("tryApplyNewDataPacks")
	void worldsmith$tryApplyNewDataPacks(
		PackRepository repository,
		boolean fromDataPackScreen,
		Consumer<WorldDataConfiguration> onAbort
	);
}
